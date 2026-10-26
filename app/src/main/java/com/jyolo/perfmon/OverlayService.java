package com.jyolo.perfmon;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * 普通悬浮窗宿主（TYPE_APPLICATION_OVERLAY）。
 *
 * 三条设计底线：
 *  1) 采样全部在 HandlerThread 上做（每秒 13+ 次 sysfs 读，绝不占主线程）；
 *  2) 前台服务 + 常驻通知，否则国产 ROM 几分钟就把进程收掉；
 *  3) 拖动与点击交给 PanelDragHelper，用位移阈值区分，避免「想拖动却把面板折叠了」。
 *
 * 注意：前台上层应用如果调用了 hideOverlayWindows（部分跑分/安全类 App 会这么做），
 * 系统会隐藏所有 TYPE_APPLICATION_OVERLAY 窗口 —— 那种场景下请开启「无障碍浮层」，
 * 由 PerfMonA11yService 用 TYPE_ACCESSIBILITY_OVERLAY 显示同一块面板。
 * 两边不会同时显示：a11y 模式生效时本服务只保留前台服务与通知。
 */
public class OverlayService extends Service {

    private static final String TAG = "PerfMon";

    public static final String ACTION_START = "com.jyolo.perfmon.START";
    public static final String ACTION_STOP = "com.jyolo.perfmon.STOP";
    public static final String ACTION_RELOAD = "com.jyolo.perfmon.RELOAD";
    public static final String ACTION_RESET = "com.jyolo.perfmon.RESET";
    /** 只改透明度：不重建面板（拖动滑块时每 tick 调用，重建会很卡）。 */
    public static final String ACTION_ALPHA = "com.jyolo.perfmon.ALPHA";

    private static final String CHANNEL_ID = "perfmon_overlay";
    private static final int NOTIF_ID = 0x504D;   // 'PM'

    /** 供 Activity 显示服务状态。 */
    private static volatile boolean sRunning = false;

    public static boolean isRunning() {
        return sRunning;
    }

    private WindowManager wm;
    private SharedPreferences prefs;
    private CpuReader reader;

    private HandlerThread bgThread;
    private Handler bgHandler;
    private Handler mainHandler;

    private PanelView panel;
    private WindowManager.LayoutParams lp;

    private int intervalMs = Prefs.DEFAULT_INTERVAL_MS;
    private float textSp = 11.5f;
    private boolean showMem = true;
    private boolean showBat = true;
    private boolean collapsed = false;
    private int alphaPct = Prefs.DEFAULT_ALPHA_PCT;

    // ------------------------------------------------------------------ 生命周期

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = Prefs.get(this);
        mainHandler = new Handler(Looper.getMainLooper());
        reader = new CpuReader(this);

        bgThread = new HandlerThread("perfmon-sampler");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());

        createChannel();
        startForeground(NOTIF_ID, buildNotification());

        registerBatteryReceiver();
        loadSettings();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            Log.i(TAG, "stop requested by action");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_ALPHA.equals(action)) {
            loadSettings();
            if (panel != null) {
                panel.setAlphaPct(alphaPct);
            } else if (PerfMonA11yService.isConnected()) {
                PerfMonA11yService.applyAlpha(alphaPct);
            }
            return sRunning ? START_STICKY : START_NOT_STICKY;
        }

        if (ACTION_RELOAD.equals(action) && sRunning) {
            Log.i(TAG, "reload settings");
            rebuildPanel();
            return START_STICKY;
        }

        if (ACTION_RESET.equals(action)) {
            prefs.edit()
                    .remove(Prefs.KEY_X)
                    .remove(Prefs.KEY_Y)
                    .putBoolean(Prefs.KEY_COLLAPSED, false)
                    .apply();
            Log.i(TAG, "reset position + collapsed state");
            removePanel();
        }

        // 以下统一走「启动」路径：START、RESET、服务未运行时的 RELOAD、以及系统重启的空 intent
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted, giving up");
            toast("没有悬浮窗权限，请先在 PerfMon 里授权");
            stopSelf();
            return START_NOT_STICKY;
        }

        loadSettings();
        if (a11yHosted()) {
            // 无障碍浮层模式：面板由 PerfMonA11yService 负责，这里只保住前台服务与通知
            Log.i(TAG, "a11y overlay active; keeping foreground service, panel owned by a11y service");
            removePanel();
        } else if (panel == null) {
            showPanel();
        }
        sRunning = true;
        startLoop();
        Log.i(TAG, "overlay started; interval=" + intervalMs + "ms cpus=" + reader.cpuCount());
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        sRunning = false;
        if (bgHandler != null) {
            bgHandler.removeCallbacksAndMessages(null);
        }
        if (bgThread != null) {
            bgThread.quitSafely();
        }
        removePanel();
        try {
            unregisterReceiver(batteryReceiver);
        } catch (Exception ignored) {
        }
        try {
            stopForeground(true);
        } catch (Exception ignored) {
        }
        Log.i(TAG, "overlay destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ 采样循环

    private final Runnable sampleTask = new Runnable() {
        @Override
        public void run() {
            if (!sRunning) {
                return;
            }
            final CpuReader.Sample s;
            try {
                s = reader.sample();
            } catch (Throwable t) {
                Log.e(TAG, "sample failed", t);
                bgHandler.postDelayed(this, intervalMs);
                return;
            }
            mainHandler.post(() -> {
                if (panel != null) {
                    panel.render(s);
                }
            });
            bgHandler.postDelayed(this, intervalMs);
        }
    };

    private boolean a11yHosted() {
        return PerfMonA11yService.isConnected() && Prefs.a11yOverlay(this);
    }

    private void startLoop() {
        if (bgHandler == null) {
            return;
        }
        bgHandler.removeCallbacks(sampleTask);
        bgHandler.post(sampleTask);
    }

    // ------------------------------------------------------------------ 悬浮面板

    private void rebuildPanel() {
        loadSettings();
        removePanel();
        if (a11yHosted()) {
            PerfMonA11yService.reloadIfConnected();
            return;
        }
        if (Settings.canDrawOverlays(this)) {
            showPanel();
        }
    }

    private void removePanel() {
        if (panel != null && wm != null) {
            try {
                wm.removeView(panel);
            } catch (Exception ignored) {
            }
        }
        panel = null;
    }

    private void showPanel() {
        final int screenW = screenMetricsW();
        final int screenH = screenMetricsH();

        final PanelView pv = new PanelView(this, reader, textSp, showMem, showBat, collapsed);
        pv.setOnCloseListener(() -> {
            Log.i(TAG, "close tapped");
            stopSelf();
        });
        pv.setAlphaPct(alphaPct);
        panel = pv;

        lp = new WindowManager.LayoutParams(
                dp(panelWidthDp()),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;

        final int savedX = prefs.getInt(Prefs.KEY_X, Integer.MIN_VALUE);
        lp.x = savedX == Integer.MIN_VALUE ? 0 : savedX;
        lp.y = prefs.getInt(Prefs.KEY_Y, dp(140));

        try {
            wm.addView(panel, lp);
        } catch (Exception e) {
            Log.e(TAG, "addView failed: " + e, e);
            toast("悬浮窗创建失败：" + e.getClass().getSimpleName());
            panel = null;
            stopSelf();
            return;
        }

        new PanelDragHelper(wm, panel, lp, prefs, dp(5), () -> {
            if (panel != null) {
                panel.post(this::clampPosition);
            }
        }).attach();

        panel.post(() -> {
            if (panel == null) {
                return;
            }
            Log.i(TAG, "panel " + panel.getWidth() + "x" + panel.getHeight()
                    + "px screen=" + screenW + "x" + screenH
                    + " textSp=" + textSp + " alpha=" + alphaPct + "%"
                    + " targetW=" + dp(panelWidthDp()));
            if (savedX == Integer.MIN_VALUE) {
                lp.x = Math.max(0, screenW - panel.getWidth() - dp(6));
                lp.y = Math.max(0, Math.min(lp.y, screenH - panel.getHeight()));
                wm.updateViewLayout(panel, lp);
            } else {
                clampPosition();
            }
        });
    }

    /** 面板宽度按字号档位固定，避免 wrap_content 在不同设备上被字体测量带偏。 */
    private int panelWidthDp() {
        if (textSp <= 10f) {
            return 150;
        }
        if (textSp >= 13f) {
            return 184;
        }
        return 166;
    }

    private void clampPosition() {
        if (panel == null || lp == null) {
            return;
        }
        int w = panel.getWidth();
        int h = panel.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        int nx = Math.min(Math.max(lp.x, 0), Math.max(0, screenMetricsW() - w));
        int ny = Math.min(Math.max(lp.y, 0), Math.max(0, screenMetricsH() - h));
        if (nx != lp.x || ny != lp.y) {
            lp.x = nx;
            lp.y = ny;
            wm.updateViewLayout(panel, lp);
        }
    }

    // ------------------------------------------------------------------ 杂项

    private void loadSettings() {
        intervalMs = Prefs.intervalMs(this);
        textSp = Prefs.textSizeSp(this);
        showMem = prefs.getBoolean(Prefs.KEY_SHOW_MEM, true);
        showBat = prefs.getBoolean(Prefs.KEY_SHOW_BATTERY, true);
        collapsed = prefs.getBoolean(Prefs.KEY_COLLAPSED, false);
        alphaPct = Prefs.alphaPct(this);
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "CPU 悬浮监控", NotificationManager.IMPORTANCE_MIN);
        ch.setDescription("保持 PerfMon 悬浮窗运行");
        ch.setShowBadge(false);
        ch.enableLights(false);
        ch.enableVibration(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("PerfMon 运行中")
                .setContentText("CPU 频率与温度悬浮窗 · 点此打开设置")
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (reader != null) {
                reader.setBatteryTemperature(tenths);
            }
        }
    };

    private void registerBatteryReceiver() {
        IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(batteryReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(batteryReceiver, f);
            }
        } catch (Exception e) {
            Log.w(TAG, "battery receiver: " + e.getMessage());
        }
    }

    private int screenMetricsW() {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return dm.widthPixels;
    }

    private int screenMetricsH() {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return dm.heightPixels;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
