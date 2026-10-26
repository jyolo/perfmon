package com.jyolo.perfmon;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** 控制面板：授权、启停、刷新率、字号、以及一份实时预览（用来证明 sysfs 真的可读）。 */
public class MainActivity extends Activity {

    private static final int REQ_NOTIF = 1001;

    private SharedPreferences prefs;
    private CpuReader reader;

    private HandlerThread bgThread;
    private Handler bgHandler;
    private Handler ui;

    private TextView tvPerm;
    private TextView tvSvc;
    private TextView btnPerm;
    private TextView btnSvc;
    private TextView tvPreviewLeft;
    private TextView tvPreviewRight;
    private TextView tvPreviewInfo;
    private TextView tvAlphaVal;
    private TextView tvA11yState;
    private CheckBox cbA11y;

    private long lastAlphaPushAtMs;

    private boolean previewing;

    private final Runnable previewTask = new Runnable() {
        @Override
        public void run() {
            if (!previewing) {
                return;
            }
            final CpuReader.Sample s;
            try {
                s = reader.sample();
            } catch (Throwable t) {
                bgHandler.postDelayed(this, 1500);
                return;
            }
            ui.post(() -> renderPreview(s));
            bgHandler.postDelayed(this, 1000);
        }
    };

    // ------------------------------------------------------------------ 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.get(this);
        reader = new CpuReader(this);
        ui = new Handler(Looper.getMainLooper());
        bgThread = new HandlerThread("perfmon-preview");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());

        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
        startPreview();
    }

    @Override
    protected void onPause() {
        super.onPause();
        previewing = false;
        if (bgHandler != null) {
            bgHandler.removeCallbacks(previewTask);
        }
    }

    @Override
    protected void onDestroy() {
        previewing = false;
        if (bgThread != null) {
            bgThread.quitSafely();
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 界面

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF0E1116);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(28), dp(18), dp(28));
        // ScrollView 只有一个子 View：宽度必须显式撑满，否则内容按内容宽排版
        root.setLayoutParams(new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.addView(root);

        TextView h1 = label("PerfMon", 25f, 0xFFF2F5F8, true);
        root.addView(h1);
        TextView h2 = label("CPU 频率 / 温度悬浮监控", 13f, 0xFF8A97A4, false);
        LinearLayout.LayoutParams h2lp = fill(4);
        root.addView(h2, h2lp);

        // ---------- 状态卡
        LinearLayout statusCard = card();
        root.addView(statusCard, fill(18));

        statusCard.addView(section("状态"));

        tvPerm = label("未授权", 13f, 0xFF9AA6B2, false);
        btnPerm = button("授权");
        btnPerm.setOnClickListener(v -> requestOverlayPermission());
        statusCard.addView(row("悬浮窗权限", tvPerm, btnPerm));

        tvSvc = label("未运行", 13f, 0xFF9AA6B2, false);
        btnSvc = button("启动");
        btnSvc.setOnClickListener(v -> toggleService());
        statusCard.addView(row("监控服务", tvSvc, btnSvc), fill(10));

        TextView btnReset = button("重置");
        btnReset.setOnClickListener(v -> resetOverlay());
        statusCard.addView(row("悬浮窗位置", label("拖动后自动记住", 12f, 0xFF7A8794, false), btnReset),
                fill(10));

        // ---------- 预览卡
        LinearLayout previewCard = card();
        root.addView(previewCard, fill(14));
        previewCard.addView(section("实时读取（本进程实测值）"));

        // 两列用真实布局，不用字符串拼接 —— 拼字符串会因宽度不足自动换行错位
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        tvPreviewLeft = gridText();
        tvPreviewRight = gridText();
        grid.addView(tvPreviewLeft, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        grid.addView(tvPreviewRight, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        previewCard.addView(grid, fill(0));

        tvPreviewInfo = label("", 12.5f, 0xFFE8ECEF, false);
        tvPreviewInfo.setTypeface(Typeface.MONOSPACE);
        tvPreviewInfo.setLineSpacing(dp(2), 1f);
        previewCard.addView(tvPreviewInfo, fill(6));

        // ---------- 设置卡
        LinearLayout setCard = card();
        root.addView(setCard, fill(14));
        setCard.addView(section("设置"));

        setCard.addView(settingLabel("刷新间隔"));
        RadioGroup rgInterval = new RadioGroup(this);
        rgInterval.setOrientation(RadioGroup.HORIZONTAL);
        int curInterval = Prefs.intervalMs(this);
        String[] labels = {"0.5 秒", "1 秒", "2 秒", "3 秒"};
        int[] values = {500, 1000, 2000, 3000};
        for (int i = 0; i < values.length; i++) {
            RadioButton rb = radio(labels[i], values[i]);
            rb.setChecked(curInterval == values[i]);
            rgInterval.addView(rb, radioLp());
        }
        rgInterval.setOnCheckedChangeListener((g, id) -> {
            RadioButton rb = g.findViewById(g.getCheckedRadioButtonId());
            if (rb == null) {
                return;
            }
            prefs.edit().putInt(Prefs.KEY_INTERVAL_MS, (int) rb.getTag()).apply();
            reloadService();
        });
        setCard.addView(rgInterval, fill(0));

        TextView scaleLabel = settingLabel("字号");
        setCard.addView(scaleLabel, fill(14));

        RadioGroup rgScale = new RadioGroup(this);
        rgScale.setOrientation(RadioGroup.HORIZONTAL);
        String[] scaleNames = {"小", "中", "大"};
        int curScale = prefs.getInt(Prefs.KEY_TEXT_SCALE, 1);
        for (int i = 0; i < scaleNames.length; i++) {
            RadioButton rb = radio(scaleNames[i], i);
            rb.setChecked(curScale == i);
            rgScale.addView(rb, radioLp());
        }
        rgScale.setOnCheckedChangeListener((g, id) -> {
            RadioButton rb = g.findViewById(g.getCheckedRadioButtonId());
            if (rb == null) {
                return;
            }
            prefs.edit().putInt(Prefs.KEY_TEXT_SCALE, (int) rb.getTag()).apply();
            reloadService();
            PerfMonA11yService.reloadIfConnected();
        });
        setCard.addView(rgScale, fill(0));

        CheckBox cbMem = check("显示内存占用", prefs.getBoolean(Prefs.KEY_SHOW_MEM, true));
        cbMem.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(Prefs.KEY_SHOW_MEM, checked).apply();
            reloadService();
        });
        setCard.addView(cbMem, fill(12));

        CheckBox cbBat = check("显示电池温度", prefs.getBoolean(Prefs.KEY_SHOW_BATTERY, true));
        cbBat.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(Prefs.KEY_SHOW_BATTERY, checked).apply();
            reloadService();
        });
        setCard.addView(cbBat, fill(0));

        CheckBox cbPower = check("显示功耗", prefs.getBoolean(Prefs.KEY_SHOW_POWER, true));
        cbPower.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(Prefs.KEY_SHOW_POWER, checked).apply();
            reloadService();
            PerfMonA11yService.reloadIfConnected();
        });
        setCard.addView(cbPower, fill(0));

        // ---------- 外观卡（透明度）
        LinearLayout lookCard = card();
        root.addView(lookCard, fill(14));
        lookCard.addView(section("外观"));

        tvAlphaVal = label(Prefs.alphaPct(this) + "%", 13f, 0xFF4FC3F7, true);
        LinearLayout alphaHead = new LinearLayout(this);
        alphaHead.setOrientation(LinearLayout.HORIZONTAL);
        alphaHead.setGravity(Gravity.CENTER_VERTICAL);
        alphaHead.addView(label("悬浮窗透明度", 14.5f, 0xFFDCE3EA, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        alphaHead.addView(tvAlphaVal);
        lookCard.addView(alphaHead, fill(0));

        SeekBar sbAlpha = new SeekBar(this);
        sbAlpha.setMax(100 - Prefs.MIN_ALPHA_PCT);
        sbAlpha.setProgress(Prefs.alphaPct(this) - Prefs.MIN_ALPHA_PCT);
        sbAlpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int pct = Prefs.MIN_ALPHA_PCT + progress;
                tvAlphaVal.setText(pct + "%");
                prefs.edit().putInt(Prefs.KEY_ALPHA_PCT, pct).apply();
                if (fromUser) {
                    pushAlphaThrottled();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                pushAlphaNow();
            }
        });
        lookCard.addView(sbAlpha, fill(6));

        TextView alphaHint = label("左右拖动：越往左越透，可看到下层界面；最低 20%，不会拖到完全看不见。"
                , 12f, 0xFF7A8794, false);
        alphaHint.setLineSpacing(dp(2), 1f);
        lookCard.addView(alphaHint, fill(6));

        // ---------- 无障碍浮层（对抗 hideOverlayWindows）
        View divider = new View(this);
        divider.setBackgroundColor(0x1AFFFFFF);
        LinearLayout.LayoutParams divLp = fill(10);
        divLp.height = Math.max(1, dp(0.7f));
        lookCard.addView(divider, divLp);

        tvA11yState = label("", 12f, 0xFF7A8794, false);
        cbA11y = check("无障碍浮层（最上层显示）", Prefs.a11yOverlay(this));
        cbA11y.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(Prefs.KEY_A11Y_OVERLAY, checked).apply();
            PerfMonA11yService.reloadIfConnected();
            reloadService();
            refreshState();
            if (checked && !isA11yServiceEnabled()) {
                toast("还要在系统「无障碍」里勾选 PerfMon，已为你打开设置页");
                openA11ySettings();
            }
        });
        lookCard.addView(cbA11y, fill(12));

        TextView a11yHint = label("开启后改用无障碍浮层显示面板：安兔兔这类会调用 hideOverlayWindows 的应用，"
                + "会把普通悬浮窗整层隐藏，无障碍浮层不受影响。需要系统「无障碍」里勾选 PerfMon（本 App 不读取界面内容）。"
                , 12f, 0xFF7A8794, false);
        a11yHint.setLineSpacing(dp(2), 1f);
        lookCard.addView(a11yHint, fill(6));

        lookCard.addView(tvA11yState, fill(6));

        TextView btnA11y = button("打开无障碍设置");
        btnA11y.setOnClickListener(v -> openA11ySettings());
        LinearLayout a11yRow = new LinearLayout(this);
        a11yRow.setOrientation(LinearLayout.HORIZONTAL);
        a11yRow.addView(btnA11y);
        lookCard.addView(a11yRow, fill(10));

        // ---------- 说明卡
        LinearLayout helpCard = card();
        root.addView(helpCard, fill(14));
        helpCard.addView(section("使用说明"));
        TextView help = label(helpText(), 12.5f, 0xFFA7B3BF, false);
        help.setLineSpacing(dp(3), 1f);
        helpCard.addView(help, fill(0));

        return scroll;
    }

    private String helpText() {
        StringBuilder sb = new StringBuilder();
        sb.append("· 单击悬浮窗：展开 / 收起\n");
        sb.append("· 拖动：按住任意位置移动，松手位置会被记住\n");
        sb.append("· 右上角 ✕：停止监控\n");
        sb.append("· 温度取 thermal_zone 中 CPU 分区的最高值，读不到时显示 --，不会用猜测值填充\n");
        sb.append("· 全程不需要 root\n\n");
        sb.append("数据来源：\n");
        sb.append("/sys/devices/system/cpu/cpuN/cpufreq/scaling_cur_freq（频率）\n");
        sb.append("/sys/class/thermal/thermal_zone*/{type,temp}（温度）\n");
        sb.append("ActivityManager.MemoryInfo（内存）\n");
        sb.append("BatteryManager.EXTRA_TEMPERATURE（电池温度）\n");
        if (isMiui()) {
            sb.append("\nMIUI / HyperOS 额外两步：\n");
            sb.append("1) 设置 → 应用设置 → 应用管理 → PerfMon → 权限管理 → 显示悬浮窗\n");
            sb.append("2) 设置 → 电池与性能 → 应用智能省电 → PerfMon → 无限制（否则过一会儿悬浮窗会被收掉）\n");
        }
        return sb.toString();
    }

    private static boolean isMiui() {
        String m = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.US);
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco");
    }

    // ------------------------------------------------------------------ 行为

    private void requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            toast("已授权");
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (Exception e2) {
                toast("无法打开权限页，请到系统设置里手动授权「显示悬浮窗」");
            }
        }
        toast("请打开「显示悬浮窗 / 显示在其他应用上层」");
    }

    private void toggleService() {
        if (OverlayService.isRunning()) {
            stopService(new Intent(this, OverlayService.class));
            ui.postDelayed(this::refreshState, 400);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            return;
        }
        startForegroundServiceCompat(new Intent(this, OverlayService.class)
                .setAction(OverlayService.ACTION_START));
        ui.postDelayed(this::refreshState, 600);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIF) {
            // 通知权限被拒也照常启动：前台服务只需要通道存在，Android 13+ 只是不显示通知
            startForegroundServiceCompat(new Intent(this, OverlayService.class)
                    .setAction(OverlayService.ACTION_START));
            ui.postDelayed(this::refreshState, 600);
        }
    }

    private void startForegroundServiceCompat(Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            toast("启动失败：" + e.getMessage());
        }
    }

    private void reloadService() {
        if (!OverlayService.isRunning()) {
            return;
        }
        try {
            startService(new Intent(this, OverlayService.class)
                    .setAction(OverlayService.ACTION_RELOAD));
        } catch (Exception ignored) {
        }
    }

    /** 拖动滑块时把透明度推给已在运行的悬浮窗（节流 ~60ms，避免一次拖动打几十个 startService）。 */
    private void pushAlphaThrottled() {
        long now = SystemClock.uptimeMillis();
        if (now - lastAlphaPushAtMs < 60) {
            return;
        }
        lastAlphaPushAtMs = now;
        pushAlphaNow();
    }

    private void pushAlphaNow() {
        if (!OverlayService.isRunning()) {
            return;
        }
        try {
            startService(new Intent(this, OverlayService.class)
                    .setAction(OverlayService.ACTION_ALPHA));
        } catch (Exception ignored) {
        }
    }

    /** 面板被拖到屏幕外或误折叠时的一键找回。 */
    private void resetOverlay() {
        prefs.edit()
                .remove(Prefs.KEY_X)
                .remove(Prefs.KEY_Y)
                .putBoolean(Prefs.KEY_COLLAPSED, false)
                .apply();
        if (OverlayService.isRunning()) {
            try {
                startService(new Intent(this, OverlayService.class)
                        .setAction(OverlayService.ACTION_RESET));
                toast("已重置到右上角");
            } catch (Exception e) {
                toast("重置失败：" + e.getMessage());
            }
        } else {
            toast("已重置，下次启动时生效");
        }
    }

    private void refreshState() {
        if (tvPerm == null) {
            return;
        }
        boolean granted = Settings.canDrawOverlays(this);
        tvPerm.setText(granted ? "已授权" : "未授权");
        tvPerm.setTextColor(granted ? 0xFF81C784 : 0xFFFF8A65);
        btnPerm.setText(granted ? "重新授权" : "去授权");

        boolean running = OverlayService.isRunning();
        tvSvc.setText(running ? "运行中" : "未运行");
        tvSvc.setTextColor(running ? 0xFF81C784 : 0xFF9AA6B2);
        btnSvc.setText(running ? "停止" : "启动");

        boolean a11yOn = isA11yServiceEnabled();
        if (tvA11yState != null) {
            tvA11yState.setText(a11yOn
                    ? "无障碍服务：已开启" + (PerfMonA11yService.isPanelShown() ? "（浮层显示中）" : "")
                    : "无障碍服务：未开启（勾选后需在系统无障碍里允许 PerfMon）");
            tvA11yState.setTextColor(a11yOn ? 0xFF81C784 : 0xFFFF8A65);
        }
    }

    /** 系统「无障碍」里是否已勾选本 App 的服务（读的是 Secure 设置里的扁平列表）。 */
    private boolean isA11yServiceEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (flat == null || flat.isEmpty()) {
            return false;
        }
        String shortForm = getPackageName() + "/." + PerfMonA11yService.class.getSimpleName();
        String fullForm = getPackageName() + "/" + PerfMonA11yService.class.getName();
        for (String s : flat.split(":")) {
            if (s.equalsIgnoreCase(shortForm) || s.equalsIgnoreCase(fullForm)) {
                return true;
            }
        }
        return false;
    }

    private void openA11ySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            toast("请在「无障碍 → 已安装的应用」里找到 PerfMon 并打开");
        } catch (Exception e) {
            toast("打不开无障碍设置，请手动到 设置 → 无障碍 里勾选 PerfMon");
        }
    }

    private void startPreview() {
        if (previewing) {
            return;
        }
        previewing = true;
        if (bgHandler != null) {
            bgHandler.removeCallbacks(previewTask);
            bgHandler.post(previewTask);
        }
    }

    private void renderPreview(CpuReader.Sample s) {
        if (tvPreviewLeft == null) {
            return;
        }
        int n = s.freqKhz.length;
        int rows = (n + 1) / 2;
        StringBuilder left = new StringBuilder();
        StringBuilder right = new StringBuilder();
        for (int r = 0; r < rows; r++) {
            int li = r;
            int ri = r + rows;
            left.append(li < n ? core(li, s.freqKhz[li]) : "").append('\n');
            if (ri < n) {
                right.append(core(ri, s.freqKhz[ri]));
            }
            right.append('\n');
        }
        tvPreviewLeft.setText(left.toString().trim());
        tvPreviewRight.setText(right.toString().trim());

        StringBuilder info = new StringBuilder();
        if (Float.isNaN(s.cpuTempC)) {
            info.append("温度 --   （没有可读的 CPU 温度分区）\n");
        } else {
            info.append(String.format(Locale.US, "温度 %.1f°C   %s\n", s.cpuTempC,
                    s.cpuTempZone == null ? "" : s.cpuTempZone));
        }
        if (s.memUsedPct >= 0) {
            long usedMb = Math.max(0, s.memTotalMb - s.memAvailMb);
            info.append(String.format(Locale.US, "内存 %d%%   %.1f/%.1fG\n",
                    s.memUsedPct, usedMb / 1024f, s.memTotalMb / 1024f));
        }
        info.append(Float.isNaN(s.batteryTempC)
                ? "电池 --"
                : String.format(Locale.US, "电池 %.1f°C", s.batteryTempC));
        if (Float.isNaN(s.powerW)) {
            info.append("   功耗 --");
            if (s.rawCurrent != Integer.MIN_VALUE) {
                info.append(String.format(Locale.US, "\n原始电流 %d 电压 %d（单位由 ROM 决定，可据此核对）",
                        s.rawCurrent, s.rawVoltage));
            }
        } else {
            // 把原始读数一并显示：换机/换 ROM 时能立刻看出单位是 µA 还是 mA、电压是 mV 还是 µV
            info.append(String.format(Locale.US, "   功耗 %.2fW%s",
                    s.powerW, s.charging ? " (充电中)" : ""));
            long absCur = Math.abs((long) s.rawCurrent);
            long mA = absCur > 20_000L ? absCur / 1000L : absCur;
            double volts = s.rawVoltage >= 2_000 && s.rawVoltage <= 6_000
                    ? s.rawVoltage / 1000d : s.rawVoltage / 1_000_000d;
            info.append(String.format(Locale.US, "\n原始 %dmA @ %.2fV（ROM 原始值 %d / %d）",
                    mA, volts, s.rawCurrent, s.rawVoltage));
        }
        tvPreviewInfo.setText(info.toString().trim());
    }

    private TextView gridText() {
        TextView tv = label("", 12.5f, 0xFFE8ECEF, false);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setLineSpacing(dp(2), 1f);
        return tv;
    }

    private static String core(int idx, int khz) {
        String v = khz == CpuReader.FREQ_OFFLINE ? " off"
                : khz <= 0 ? "  --"
                : String.valueOf(Math.round(khz / 1000f));
        return String.format(Locale.US, "cpu%d %5s", idx, v);
    }

    // ------------------------------------------------------------------ 小部件

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF171D24);
        bg.setCornerRadius(dp(14));
        bg.setStroke(Math.max(1, dp(0.7f)), 0xFF232B34);
        c.setBackground(bg);
        return c;
    }

    private TextView section(String s) {
        TextView tv = label(s, 11.5f, 0xFF7A8794, true);
        tv.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams p = wrap();
        p.bottomMargin = dp(10);
        tv.setLayoutParams(p);
        return tv;
    }

    private TextView settingLabel(String s) {
        TextView tv = label(s, 13f, 0xFFB8C3CE, false);
        LinearLayout.LayoutParams p = wrap();
        p.topMargin = dp(2);
        p.bottomMargin = dp(2);
        tv.setLayoutParams(p);
        return tv;
    }

    private LinearLayout row(String name, TextView state, TextView btn) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView n = label(name, 14.5f, 0xFFDCE3EA, false);
        r.addView(n, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(state);
        LinearLayout.LayoutParams blp = wrap();
        blp.leftMargin = dp(10);
        r.addView(btn, blp);
        return r;
    }

    private RadioButton radio(String text, int tag) {
        RadioButton rb = new RadioButton(this);
        rb.setText(text);
        rb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        rb.setTextColor(0xFFDCE3EA);
        rb.setTag(tag);
        // RadioGroup 靠子 View 的 id 维护互斥与选中态；不设 id 时它无法取消其它项，
        // 表现就是“永远只有 1 秒被选中”，且 findViewById(checkedId) 返回 null → 设置存不下去。
        rb.setId(View.generateViewId());
        // 等分整行后单格宽度有限：去掉最小宽度限制，边距收紧，避免"0.5 秒"被截断
        rb.setMinWidth(0);
        rb.setMinimumWidth(0);
        rb.setPadding(dp(2), dp(2), dp(4), dp(2));
        return rb;
    }

    private CheckBox check(String text, boolean checked) {
        CheckBox cb = new CheckBox(this);
        cb.setText(text);
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        cb.setTextColor(0xFFDCE3EA);
        cb.setChecked(checked);
        cb.setPadding(dp(2), dp(4), dp(2), dp(4));
        return cb;
    }

    private TextView button(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tv.setTextColor(0xFF0E1116);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(16), dp(7), dp(16), dp(7));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF4FC3F7);
        bg.setCornerRadius(dp(20));
        tv.setBackground(bg);
        tv.setClickable(true);
        return tv;
    }

    private TextView label(String s, float sizeSp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        tv.setTextColor(color);
        tv.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        return tv;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** 宽度撑满父容器（卡片、分组、说明文字统一用这个，避免“卡片宽度随内容缩水”）。 */
    private LinearLayout.LayoutParams fill(int topMarginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (topMarginDp != 0) {
            p.topMargin = dp(topMarginDp);
        }
        return p;
    }

    /** 横向 RadioButton 等分整行；左右内边距收紧，窄屏（393dp）四个选项也不会被截字。 */
    private LinearLayout.LayoutParams radioLp() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
