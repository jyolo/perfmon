package com.jyolo.perfmon;

import android.content.Context;
import android.content.SharedPreferences;

/** 设置项集中在一处，键名不再散落各个类。 */
public final class Prefs {

    private static final String FILE = "perfmon";

    public static final String KEY_INTERVAL_MS = "interval_ms";     // 刷新间隔
    public static final String KEY_TEXT_SCALE = "text_scale";       // 0 小 / 1 中 / 2 大
    public static final String KEY_SHOW_MEM = "show_mem";
    public static final String KEY_SHOW_BATTERY = "show_battery";
    public static final String KEY_COLLAPSED = "collapsed";
    public static final String KEY_ALPHA_PCT = "alpha_pct";         // 悬浮窗不透明度 20..100
    public static final String KEY_A11Y_OVERLAY = "a11y_overlay";   // 用无障碍浮层显示（对抗 hideOverlayWindows）
    public static final String KEY_X = "win_x";
    public static final String KEY_Y = "win_y";

    public static final int DEFAULT_INTERVAL_MS = 1000;
    public static final int DEFAULT_ALPHA_PCT = 95;
    public static final int MIN_ALPHA_PCT = 20;

    private Prefs() {
    }

    public static SharedPreferences get(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static int intervalMs(Context ctx) {
        int v = get(ctx).getInt(KEY_INTERVAL_MS, DEFAULT_INTERVAL_MS);
        return v < 200 ? DEFAULT_INTERVAL_MS : v;
    }

    public static float textSizeSp(Context ctx) {
        switch (get(ctx).getInt(KEY_TEXT_SCALE, 1)) {
            case 0:
                return 10f;
            case 2:
                return 13f;
            default:
                return 11.5f;
        }
    }

    /** 悬浮窗不透明度百分比（20..100），越界值一律夹回区间，避免滑块写出 0 之后面板“消失”。 */
    public static int alphaPct(Context ctx) {
        int v = get(ctx).getInt(KEY_ALPHA_PCT, DEFAULT_ALPHA_PCT);
        if (v < MIN_ALPHA_PCT) {
            return MIN_ALPHA_PCT;
        }
        return Math.min(v, 100);
    }

    /** 是否用无障碍浮层（TYPE_ACCESSIBILITY_OVERLAY）显示面板，默认关。 */
    public static boolean a11yOverlay(Context ctx) {
        return get(ctx).getBoolean(KEY_A11Y_OVERLAY, false);
    }
}
