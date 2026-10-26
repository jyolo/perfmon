package com.jyolo.perfmon;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.SystemClock;

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

    public CpuReader(Context ctx) {
        Context app = ctx.getApplicationContext();
        this.appCtx = app;
        this.am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
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
