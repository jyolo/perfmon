package com.jyolo.perfmon;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * 无障碍浮层宿主（TYPE_ACCESSIBILITY_OVERLAY）。
 *
 * 为什么需要它：部分前台上层应用（跑分、部分安全/游戏类 App）会调用
 * Window.setHideOverlayWindows(true)，系统随即把该窗口之上的所有
 * TYPE_APPLICATION_OVERLAY 窗口隐藏 —— 表现为「打开安兔兔后悬浮窗不见了」。
 * 这类隐藏对无障碍浮层无效，所以同一块面板换这个窗口类型就能继续显示。
 *
 * 代价：用户必须手动在系统「无障碍」里勾选 PerfMon，设置页里有直达入口与说明。
 * 开关由 Prefs.KEY_A11Y_OVERLAY 控制；关闭时本服务即使连着也不显示面板。
 */
public class PerfMonA11yService extends AccessibilityService {

    private static final String TAG = "PerfMonA11y";

    private static volatile PerfMonA11yService sInstance;
    private static volatile boolean sPanelShown;

    public static boolean isConnected() {
        return sInstance != null;
    }

    public static boolean isPanelShown() {
        return sPanelShown;
    }

    /** 设置页改透明度时直接作用到正在显示的面板（不用重启无障碍服务）。 */
    static void applyAlpha(int pct) {
        PerfMonA11yService s = sInstance;
        if (s != null && s.panel != null) {
            s.mainHandler.post(() -> {
                if (s.panel != null) {
                    s.panel.setAlphaPct(pct);
                }
            });
        }
    }

    /** 设置项（字号/显示项/折叠）变化后重建面板。 */
    static void reloadIfConnected() {
        PerfMonA11yService s = sInstance;
        if (s != null) {
            s.mainHandler.post(s::rebuild);
        }
    }

    private WindowManager wm;
    private SharedPreferences prefs;
    private CpuReader reader;

    private android.os.HandlerThread bgThread;
    private android.os.Handler bgHandler;
    private final android.os.Handler mainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private PanelView panel;
    private WindowManager.LayoutParams lp;
    private PanelDragHelper drag;

    private int intervalMs = Prefs.DEFAULT_INTERVAL_MS;
    private float textSp = 11.5f;
    private boolean showMem = true;
    private boolean showBat = true;
    private boolean collapsed = false;
    private int alphaPct = Prefs.DEFAULT_ALPHA_PCT;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = Prefs.get(this);
        reader = new CpuReader(this);

        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            // 只要「能拿到窗口变化」这一件事，不读取任何界面内容
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.notificationTimeout = 0;
            setServiceInfo(info);
        }

        bgThread = new android.os.HandlerThread("perfmon-a11y-sampler");
        bgThread.start();
        bgHandler = new android.os.Handler(bgThread.getLooper());

        Log.i(TAG, "connected");
        rebuild();
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        Log.i(TAG, "unbind");
        sInstance = null;
        teardown();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        teardown();
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不做界面内容处理：本服务只为获得无障碍浮层能力
    }

    @Override
    public void onInterrupt() {
    }

    // ------------------------------------------------------------------ 面板

    private void loadSettings() {
        intervalMs = Prefs.intervalMs(this);
        textSp = Prefs.textSizeSp(this);
        showMem = prefs.getBoolean(Prefs.KEY_SHOW_MEM, true);
        showBat = prefs.getBoolean(Prefs.KEY_SHOW_BATTERY, true);
        collapsed = prefs.getBoolean(Prefs.KEY_COLLAPSED, false);
        alphaPct = Prefs.alphaPct(this);
    }

    private void rebuild() {
        loadSettings();
        removePanel();
        if (!Prefs.a11yOverlay(this)) {
            Log.i(TAG, "a11y overlay switch is off; no panel");
            stopLoop();
            return;
        }
        showPanel();
        startLoop();
    }

    private void teardown() {
        stopLoop();
        removePanel();
        if (bgThread != null) {
            bgThread.quitSafely();
            bgThread = null;
        }
    }

    private void showPanel() {
        if (wm == null) {
            return;
        }
        final int screenW = screenMetricsW();
        final int screenH = screenMetricsH();

        PanelView pv = new PanelView(this, reader, textSp, showMem, showBat, collapsed);
        pv.setOnCloseListener(() -> {
            Log.i(TAG, "close tapped -> 关闭无障碍浮层开关");
            prefs.edit().putBoolean(Prefs.KEY_A11Y_OVERLAY, false).apply();
            removePanel();
            stopLoop();
        });
        pv.setAlphaPct(alphaPct);
        panel = pv;

        lp = new WindowManager.LayoutParams(
                dp(panelWidthDp()),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
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
            panel = null;
            return;
        }
        sPanelShown = true;

        drag = new PanelDragHelper(wm, panel, lp, prefs, dp(5), () -> {
            if (panel != null) {
                panel.post(this::clampPosition);
            }
        });
        drag.attach();

        panel.post(() -> {
            if (panel == null) {
                return;
            }
            Log.i(TAG, "a11y panel " + panel.getWidth() + "x" + panel.getHeight()
                    + " type=ACCESSIBILITY_OVERLAY alpha=" + alphaPct + "%");
            if (savedX == Integer.MIN_VALUE) {
                lp.x = Math.max(0, screenW - panel.getWidth() - dp(6));
                lp.y = Math.max(0, Math.min(lp.y, screenH - panel.getHeight()));
                wm.updateViewLayout(panel, lp);
            } else {
                clampPosition();
            }
        });
    }

    private void removePanel() {
        sPanelShown = false;
        if (panel != null && wm != null) {
            try {
                wm.removeView(panel);
            } catch (Exception ignored) {
            }
        }
        panel = null;
        drag = null;
    }

    // ------------------------------------------------------------------ 采样

    private final Runnable sampleTask = new Runnable() {
        @Override
        public void run() {
            final CpuReader.Sample s;
            try {
                s = reader.sample();
            } catch (Throwable t) {
                if (bgHandler != null) {
                    bgHandler.postDelayed(this, intervalMs);
                }
                return;
            }
            mainHandler.post(() -> {
                if (panel != null) {
                    panel.render(s);
                }
            });
            if (bgHandler != null) {
                bgHandler.postDelayed(this, intervalMs);
            }
        }
    };

    private void startLoop() {
        if (bgHandler == null) {
            return;
        }
        bgHandler.removeCallbacks(sampleTask);
        bgHandler.post(sampleTask);
    }

    private void stopLoop() {
        if (bgHandler != null) {
            bgHandler.removeCallbacks(sampleTask);
        }
    }

    // ------------------------------------------------------------------ 杂项

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

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
