package com.jyolo.perfmon;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 只读采样器。
 *
 * 数据来源全部是内核 sysfs 与 BatteryManager，没有任何估算值：
 *   频率  /sys/devices/system/cpu/cpuN/cpufreq/scaling_cur_freq   (kHz)
 *   温度  /sys/class/thermal/thermal_zoneN/{type,temp}            (milli-°C)
 *   内存  ActivityManager.MemoryInfo
 *   电池  ACTION_BATTERY_CHANGED 的 EXTRA_TEMPERATURE             (0.1 °C)
 *
 * 读不到就返回哨兵值（FREQ_UNREADABLE / NaN），由界面显示成 "—"，
 * 不允许用「上一次的值」或「按架构猜的值」顶替，否则界面会撒谎。
 */
public final class CpuReader {

    /** CPU 下线（hotplug 关核）。 */
    public static final int FREQ_OFFLINE = 0;
    /** 节点存在但本进程无权/无法读取。 */
    public static final int FREQ_UNREADABLE = -1;

    private static final String CPU_ROOT = "/sys/devices/system/cpu";
    private static final String THERMAL_ROOT = "/sys/class/thermal";

    private final ActivityManager am;
    private final Context appCtx;
    private final int cpuCount;
    private final String[] freqPrimary;
    private final String[] freqFallback;
    private final String[] onlinePath;

    private String[] zonePath = new String[0];
    private String[] zoneType = new String[0];

    /** 由 OverlayService 的 sticky 广播接收器写入，单位 0.1 °C。 */
    private volatile int batteryTenthsC = Integer.MIN_VALUE;

    private final BatteryManager batteryManager;

    public CpuReader(Context ctx) {
        Context app = ctx.getApplicationContext();
        this.appCtx = app;
        this.am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
        this.batteryManager = (BatteryManager) app.getSystemService(Context.BATTERY_SERVICE);
        this.cpuCount = countCpus();
        this.freqPrimary = new String[cpuCount];
        this.freqFallback = new String[cpuCount];
        this.onlinePath = new String[cpuCount];
        for (int i = 0; i < cpuCount; i++) {
            String base = CPU_ROOT + "/cpu" + i;
            freqPrimary[i] = base + "/cpufreq/scaling_cur_freq";
            freqFallback[i] = base + "/cpufreq/cpuinfo_cur_freq";
            onlinePath[i] = base + "/online";
        }
        scanThermalZones();
    }

    public int cpuCount() {
        return cpuCount;
    }

    public void setBatteryTemperature(int tenthsC) {
        this.batteryTenthsC = tenthsC;
    }

    /** 一次完整采样。调用方负责放到后台线程。 */
    public Sample sample() {
        Sample s = new Sample();
        s.freqKhz = new int[cpuCount];
        for (int i = 0; i < cpuCount; i++) {
            s.freqKhz[i] = readFreqKhz(i);
        }
        s.cpuTempC = readCpuTempC();
        s.cpuTempZone = lastTempZone;
        s.batteryTempC = batteryTempC();
        readPower(s);
        fillMemory(s);
        s.atElapsedMs = SystemClock.elapsedRealtime();
        return s;
    }

    /** 优先用 Service 的广播接收器缓存；没有订阅者时回落到 sticky 广播。 */
    private float batteryTempC() {
        int tenths = batteryTenthsC;
        if (tenths == Integer.MIN_VALUE) {
            try {
                Intent i = appCtx.registerReceiver(null,
                        new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (i != null) {
                    tenths = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
                }
            } catch (Exception ignored) {
            }
        }
        return tenths == Integer.MIN_VALUE ? Float.NaN : tenths / 10f;
    }

    // ---------------------------------------------------------------- CPU 频率

    private int readFreqKhz(int idx) {
        String online = readLine(onlinePath[idx]);
        if (online != null && online.trim().equals("0")) {
            return FREQ_OFFLINE;
        }
        Integer v = readInt(freqPrimary[idx]);
        if (v != null && v > 0) {
            return v;
        }
        // 少数内核把 cpuinfo_cur_freq 设成 root-only，读不到就走哨兵值
        v = readInt(freqFallback[idx]);
        if (v != null && v > 0) {
            return v;
        }
        return FREQ_UNREADABLE;
    }

    // ---------------------------------------------------------------- 温度

    private String lastTempZone;

    private float readCpuTempC() {
        // 第一轮：严格匹配 cpu-<cluster>-<core>-usr（高通/联发科常见的 CPU 分区）
        float t = pickTemp(true);
        if (!Float.isNaN(t)) {
            return t;
        }
        // 第二轮：宽松匹配，只排除明显不是 CPU 的 zone
        return pickTemp(false);
    }

    private float pickTemp(boolean strict) {
        float best = Float.NaN;
        String bestName = null;
        for (int i = 0; i < zonePath.length; i++) {
            String type = zoneType[i];
            if (!strict && !isLooseCpuZone(type)) {
                continue;
            }
            if (strict && !isStrictCpuZone(type)) {
                continue;
            }
            float c = readTempC(zonePath[i]);
            if (Float.isNaN(c)) {
                continue;
            }
            // 合理性闸门：-20..150 °C 之外的一律当坏数据丢掉
            if (c < -20f || c > 150f) {
                continue;
            }
            if (Float.isNaN(best) || c > best) {
                best = c;
                bestName = type;
            }
        }
        if (bestName != null) {
            lastTempZone = bestName;
        }
        return best;
    }

    private static boolean isStrictCpuZone(String raw) {
        String t = raw.toLowerCase(Locale.US);
        if (t.contains("step") || t.contains("lowf") || t.contains("limit") || t.contains("max")) {
            return false;
        }
        return t.matches("cpu[-_].*-usr") || t.matches("cpu[-_]?\\d+") || t.equals("cpu-therm");
    }

    private static boolean isLooseCpuZone(String raw) {
        String t = raw.toLowerCase(Locale.US);
        if (t.contains("step") || t.contains("lowf") || t.contains("limit") || t.contains("max")) {
            return false;
        }
        if (t.contains("batt") || t.contains("charg") || t.contains("usb")
                || t.contains("pa-therm") || t.contains("modem") || t.contains("mdm")) {
            return false;
        }
        return t.contains("cpu") || t.contains("soc") || t.contains("ap-therm");
    }

    private void scanThermalZones() {
        File root = new File(THERMAL_ROOT);
        File[] dirs = root.listFiles((dir, name) -> name.startsWith("thermal_zone"));
        if (dirs == null) {
            return;
        }
        List<String> paths = new ArrayList<>();
        List<String> types = new ArrayList<>();
        for (File d : dirs) {
            String type = readLine(new File(d, "type").getAbsolutePath());
            if (type == null || type.trim().isEmpty()) {
                continue;
            }
            paths.add(new File(d, "temp").getAbsolutePath());
            types.add(type.trim());
        }
        zonePath = paths.toArray(new String[0]);
        zoneType = types.toArray(new String[0]);
    }

    // ---------------------------------------------------------------- 功耗

    private static final String BATTERY_SYSFS = "/sys/class/power_supply/battery";

    /**
     * 功耗 = |电流| × 电压。
     *
     * 取数顺序：BatteryManager 的 CURRENT_NOW(µA) + VOLTAGE(µV) → 回落 /sys/class/power_supply/battery 的
     * current_now + voltage_now → 再回落 sticky 广播的 EXTRA_VOLTAGE(mV)。
     *
     * 两个坑：
     *  1) 单位不统一：官方文档写 µA/µV，但部分厂商（三星等）给的是 mA/mV。按量级判定：
     *     电流 |v| ≤ 20000 视为 mA，电压 2000..6000 视为 mV。
     *  2) 充电时电流为正（电流灌进电池），放空时为负。数值取绝对值，另外用 charging 标记区分，
     *     避免把「充入功率」当成「整机功耗」看。
     *
     * 读不到就保持 NaN，界面显示 --，不做估算。
     */
    private void readPower(Sample s) {
        s.charging = isCharging();

        int curRaw = batteryInt(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        // 关键：0 也要当“可疑”处理。不少 ROM 对不支持的属性返回 0 而不是 MIN_VALUE，
        // 直接当 0 电流就会一直显示 0.00W。此时去 sysfs 再问一次。
        if (curRaw == Integer.MIN_VALUE || curRaw == 0) {
            Integer c = readInt(BATTERY_SYSFS + "/current_now");
            if (c != null && c != 0) {
                curRaw = c;
            } else if (curRaw == Integer.MIN_VALUE) {
                curRaw = Integer.MIN_VALUE;
            }
        }

        // BatteryManager 没有「电压」属性：电压只能从 sticky 广播（mV）或 sysfs（µV）拿
        int voltRaw = Integer.MIN_VALUE;
        int mV = batteryVoltageMilliVolt();
        if (mV > 0) {
            voltRaw = mV;
        } else {
            Integer v = readInt(BATTERY_SYSFS + "/voltage_now");
            voltRaw = v == null || v == 0 ? Integer.MIN_VALUE : v;
        }

        s.rawCurrent = curRaw;
        s.rawVoltage = voltRaw;

        if (curRaw == Integer.MIN_VALUE || voltRaw == Integer.MIN_VALUE) {
            logPowerOnce(s, "缺原始值");
            return;
        }

        double volts = voltsOf(voltRaw);
        if (Double.isNaN(volts)) {
            logPowerOnce(s, "电压量级不像 mV/µV");
            return;
        }

        long absCur = Math.abs((long) curRaw);
        if (absCur == 0) {
            // 手机不存在“0 瓦”的空闲状态；读到 0 说明这个 ROM 没给真实电流。
            // 宁可显示 --，也不要摆一个假的 0.00W。
            logPowerOnce(s, "电流为 0（ROM 不提供真实电流）→ 显示 --");
            return;
        }

        double watts = wattsFrom(absCur, volts);
        if (Double.isNaN(watts)) {
            logPowerOnce(s, "两种单位解释都超出 0.05..25W");
            return;
        }
        s.powerW = (float) watts;
        logPowerOnce(s, "按 " + (unitMode == UNIT_MA ? "mA" : "µA") + " 解释 → "
                + String.format(Locale.US, "%.2fW", watts));
    }

    // 电流单位一旦确认就锁定，避免逐样本在 µA / mA 之间来回跳：
    //   |raw| > 20000 只可能是 µA（换算成 mA 就是 20 A 以上，不可能）
    private static final int UNIT_UNKNOWN = 0;
    private static final int UNIT_UA = 1;
    private static final int UNIT_MA = 2;
    private static int unitMode = UNIT_UNKNOWN;

    /** 用两种口径各算一次功率，取落在 0.05..25 W 的那个；都不合理返回 NaN。 */
    private double wattsFrom(long absCur, double volts) {
        if (absCur > 20_000L) {
            unitMode = UNIT_UA;
        }
        if (unitMode == UNIT_UA) {
            double w = absCur / 1_000_000d * volts;
            return (w >= 0.05d && w <= 25d) ? w : Double.NaN;
        }
        if (unitMode == UNIT_MA) {
            double w = absCur / 1_000d * volts;
            return (w >= 0.05d && w <= 25d) ? w : Double.NaN;
        }
        // 还没定单位：先按 µA（AOSP 标准），不合理再按 mA
        double ua = absCur / 1_000_000d * volts;
        if (ua >= 0.05d && ua <= 25d) {
            return ua;
        }
        double ma = absCur / 1_000d * volts;
        if (ma >= 0.05d && ma <= 25d) {
            unitMode = UNIT_MA;      // 只有 mA 解释成立时才锁定为 mA
            return ma;
        }
        return Double.NaN;
    }

    /** 电压归一化到伏特：mV(2000..6000) 或 µV(2000000..6000000)，其它一律当不可用。 */
    private static double voltsOf(int raw) {
        long v = Math.abs((long) raw);
        if (v >= 2_000L && v <= 6_000L) {
            return v / 1000d;              // mV
        }
        if (v >= 2_000_000L && v <= 6_000_000L) {
            return v / 1_000_000d;         // µV
        }
        return Double.NaN;
    }

    /** 前 3 次采样打一遍原始值，方便在真机上核对 ROM 给的是什么单位。 */
    private int powerLogCount;

    private void logPowerOnce(Sample s, String how) {
        if (powerLogCount >= 3) {
            return;
        }
        powerLogCount++;
        Log.i("PerfMonPow", "raw current=" + s.rawCurrent + " voltage=" + s.rawVoltage
                + " charging=" + s.charging + " → " + how
                + " | power=" + (Float.isNaN(s.powerW) ? "NaN" : String.format(Locale.US, "%.2fW", s.powerW)));
    }

    private int batteryInt(int property) {
        if (batteryManager == null) {
            return Integer.MIN_VALUE;
        }
        try {
            int v = batteryManager.getIntProperty(property);
            // 不同 ROM 用 MIN_VALUE / 0 / -1 表示“不支持”，只有量级明显合理时才认
            if (v == Integer.MIN_VALUE || v == Integer.MAX_VALUE) {
                return Integer.MIN_VALUE;
            }
            return v;
        } catch (Exception e) {
            return Integer.MIN_VALUE;
        }
    }

    /** sticky 广播里的 EXTRA_VOLTAGE，单位 mV；拿不到返回 -1。 */
    private int batteryVoltageMilliVolt() {
        try {
            Intent i = appCtx.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) {
                return -1;
            }
            return i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
        } catch (Exception e) {
            return -1;
        }
    }

    private boolean isCharging() {
        try {
            Intent i = appCtx.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) {
                return false;
            }
            int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            return status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 内存

    private void fillMemory(Sample s) {
        if (am == null) {
            s.memTotalMb = -1;
            s.memUsedPct = -1;
            return;
        }
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        s.memTotalMb = mi.totalMem / (1024L * 1024L);
        s.memAvailMb = mi.availMem / (1024L * 1024L);
        s.memUsedPct = mi.totalMem <= 0 ? -1
                : Math.round(100f * (mi.totalMem - mi.availMem) / mi.totalMem);
    }

    // ---------------------------------------------------------------- 文件小工具

    private static int countCpus() {
        int n = 0;
        for (int i = 0; i < 32; i++) {
            if (new File(CPU_ROOT + "/cpu" + i).exists()) {
                n = i + 1;
            }
        }
        return Math.max(1, n);
    }

    private static String readLine(String path) {
        if (path == null) {
            return null;
        }
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8))) {
            String line = r.readLine();
            return line;
        } catch (Exception e) {
            return null;   // ENOENT / EACCES 都在这里，属于正常情况
        }
    }

    private static Integer readInt(String path) {
        String s = readLine(path);
        if (s == null) {
            return null;
        }
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static float readTempC(String path) {
        Integer v = readInt(path);
        if (v == null) {
            return Float.NaN;
        }
        // 绝大多数内核给 milli-°C；少数老设备直接给 °C
        return v >= 1000 ? v / 1000f : v;
    }

    // ---------------------------------------------------------------- 数据载体

    public static final class Sample {
        public int[] freqKhz = new int[0];
        public float cpuTempC = Float.NaN;
        public String cpuTempZone;
        public float batteryTempC = Float.NaN;
        public long memTotalMb = -1;
        public long memAvailMb = -1;
        public int memUsedPct = -1;
        /** 功耗（瓦）。NaN = 读不到。 */
        public float powerW = Float.NaN;
        /** 当前是否在充电（充电时电流方向相反，功率含义是“充入”而不是“整机耗电”）。 */
        public boolean charging;
        /** 原始读数，仅用于日志取证（µA/mA、µV/mV 由 ROM 决定）。 */
        public int rawCurrent = Integer.MIN_VALUE;
        public int rawVoltage = Integer.MIN_VALUE;
        public long atElapsedMs;

        /** 所有在线核心里的最高频率（kHz），没有可用值时返回 -1。 */
        public int maxFreqKhz() {
            int max = -1;
            for (int f : freqKhz) {
                if (f > max) {
                    max = f;
                }
            }
            return max;
        }
    }
}
