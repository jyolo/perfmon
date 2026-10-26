package com.jyolo.perfmon;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * 悬浮面板本体：只负责「长什么样 + 怎么刷新」，不管挂在哪个窗口上。
 *
 * 这样同一块面板可以被两种宿主复用：
 *  - OverlayService      → TYPE_APPLICATION_OVERLAY（普通悬浮窗，需要 SYSTEM_ALERT_WINDOW）
 *  - PerfMonA11yService  → TYPE_ACCESSIBILITY_OVERLAY（无障碍浮层，前台上层应用调用 hideOverlayWindows
 *    时普通悬浮窗会被系统隐藏，无障碍浮层不受影响 —— 安兔兔这类跑分类 App 就属于前者）
 *
 * 构造完后再由宿主决定窗口类型、位置、拖动处理。
 */
class PanelView extends LinearLayout {

    private static final int COLOR_TEXT = 0xFFE8ECEF;
    private static final int COLOR_DIM = 0xFF9AA6B2;
    private static final int COLOR_FAINT = 0xFF7A8794;
    private static final int COLOR_COOL = 0xFF81C784;
    private static final int COLOR_WARM = 0xFFFFB74D;
    private static final int COLOR_HOT = 0xFFFF5252;

    private final CpuReader reader;
    private final float cellSp;

    private LinearLayout expandedBox;
    private LinearLayout collapsedBox;
    private TextView[] cellTv;
    private TextView tvCollapsed;
    private TextView tvTemp;
    private TextView tvMem;
    private TextView tvBat;
    private TextView tvPower;

    private boolean collapsed;
    private Runnable onClose;

    PanelView(Context ctx, CpuReader reader, float cellSp,
              boolean showMem, boolean showBat, boolean showPower, boolean collapsed) {
        super(ctx);
        this.reader = reader;
        this.cellSp = cellSp;
        this.collapsed = collapsed;
        build(showMem, showBat, showPower);
    }

    void setOnCloseListener(Runnable r) {
        this.onClose = r;
    }

    boolean isCollapsed() {
        return collapsed;
    }

    void setCollapsed(boolean c) {
        collapsed = c;
        if (collapsedBox != null) {
            collapsedBox.setVisibility(c ? View.VISIBLE : View.GONE);
        }
        if (expandedBox != null) {
            expandedBox.setVisibility(c ? View.GONE : View.VISIBLE);
        }
    }

    /** 整体不透明度（20..100）。一次 setAlpha 生效，不重建 View，滑块才能实时拖动。 */
    void setAlphaPct(int pct) {
        float a = Math.max(Prefs.MIN_ALPHA_PCT, Math.min(pct, 100)) / 100f;
        setAlpha(a);
    }

    private void build(boolean showMem, boolean showBat, boolean showPower) {
        setOrientation(VERTICAL);
        setPadding(dp(8), dp(6), dp(8), dp(9));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF121619);          // 底色不透明：整体透明度交给 setAlpha，滑块才线性
        bg.setCornerRadius(dp(9));
        bg.setStroke(Math.max(1, dp(0.8f)), 0x40FFFFFF);
        setBackground(bg);

        // ---- 标题行（✕ 关闭）
        LinearLayout head = new LinearLayout(getContext());
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("CPU", cellSp - 1f, COLOR_FAINT, true);
        head.addView(title, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        TextView close = text("✕", cellSp, COLOR_FAINT, false);
        close.setPadding(dp(8), 0, dp(2), 0);
        close.setOnClickListener(v -> {
            if (onClose != null) {
                onClose.run();
            }
        });
        head.addView(close);
        addView(head);

        // ---- 折叠态
        collapsedBox = new LinearLayout(getContext());
        collapsedBox.setOrientation(VERTICAL);
        tvCollapsed = text("CPU --", cellSp + 0.5f, COLOR_TEXT, false);
        tvCollapsed.setTypeface(Typeface.MONOSPACE);
        tvCollapsed.setSingleLine(true);
        collapsedBox.addView(tvCollapsed);
        addView(collapsedBox);

        // ---- 展开态：核心网格
        expandedBox = new LinearLayout(getContext());
        expandedBox.setOrientation(VERTICAL);
        addView(expandedBox);

        int n = reader.cpuCount();
        int rows = (n + 1) / 2;
        cellTv = new TextView[n];
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(HORIZONTAL);
            for (int c = 0; c < 2; c++) {
                int idx = r + c * rows;      // 左列 0..rows-1，右列 rows..n-1
                if (idx >= n) {
                    View filler = new View(getContext());
                    row.addView(filler, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
                    continue;
                }
                TextView tv = text("", cellSp, COLOR_TEXT, false);
                tv.setTypeface(Typeface.MONOSPACE);
                tv.setSingleLine(true);
                cellTv[idx] = tv;
                row.addView(tv, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            }
            expandedBox.addView(row);
        }

        View divider = new View(getContext());
        divider.setBackgroundColor(0x22FFFFFF);
        LayoutParams dlp = new LayoutParams(LayoutParams.MATCH_PARENT, Math.max(1, dp(0.7f)));
        dlp.topMargin = dp(3);
        dlp.bottomMargin = dp(3);
        expandedBox.addView(divider, dlp);

        tvTemp = text("温度 --", cellSp, COLOR_DIM, false);
        tvTemp.setTypeface(Typeface.MONOSPACE);
        tvTemp.setSingleLine(true);
        expandedBox.addView(tvTemp);

        tvMem = text("内存 --", cellSp, COLOR_DIM, false);
        tvMem.setTypeface(Typeface.MONOSPACE);
        tvMem.setSingleLine(true);
        tvMem.setVisibility(showMem ? VISIBLE : GONE);
        expandedBox.addView(tvMem);

        tvBat = text("电池 --", cellSp, COLOR_DIM, false);
        tvBat.setTypeface(Typeface.MONOSPACE);
        tvBat.setSingleLine(true);
        tvBat.setVisibility(showBat ? VISIBLE : GONE);
        expandedBox.addView(tvBat);

        tvPower = text("功耗 --", cellSp, COLOR_DIM, false);
        tvPower.setTypeface(Typeface.MONOSPACE);
        tvPower.setSingleLine(true);
        tvPower.setVisibility(showPower ? VISIBLE : GONE);
        expandedBox.addView(tvPower);

        setCollapsed(collapsed);

        // 子 View 不吃触摸，保证整块面板都能拖；✕ 有 OnClickListener，保持可点
        for (int i = 0; i < getChildCount(); i++) {
            makePassive(getChildAt(i));
        }
    }

    private void makePassive(View v) {
        if (v instanceof LinearLayout) {
            v.setClickable(false);
            v.setFocusable(false);
            LinearLayout g = (LinearLayout) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                makePassive(g.getChildAt(i));
            }
        } else if (v instanceof TextView) {
            v.setFocusable(false);
            if (!v.hasOnClickListeners()) {
                v.setClickable(false);
            }
        }
    }

    void render(CpuReader.Sample s) {
        if (cellTv == null) {
            return;
        }
        for (int i = 0; i < cellTv.length; i++) {
            TextView tv = cellTv[i];
            if (tv == null || i >= s.freqKhz.length) {
                continue;
            }
            tv.setText(formatCore(i, s.freqKhz[i]));
        }

        if (tvTemp != null) {
            if (Float.isNaN(s.cpuTempC)) {
                tvTemp.setText("温度 --");
                tvTemp.setTextColor(COLOR_FAINT);
            } else {
                tvTemp.setText(String.format(Locale.US, "温度 %.1f°C", s.cpuTempC));
                tvTemp.setTextColor(tempColor(s.cpuTempC));
            }
        }

        if (tvMem != null) {
            if (s.memUsedPct < 0) {
                tvMem.setText("内存 --");
            } else {
                long usedMb = Math.max(0, s.memTotalMb - s.memAvailMb);
                tvMem.setText(String.format(Locale.US, "内存 %d%% %.1f/%.1fG",
                        s.memUsedPct, usedMb / 1024f, s.memTotalMb / 1024f));
            }
        }

        if (tvBat != null) {
            tvBat.setText(Float.isNaN(s.batteryTempC)
                    ? "电池 --"
                    : String.format(Locale.US, "电池 %.1f°C", s.batteryTempC));
        }

        if (tvPower != null) {
            if (Float.isNaN(s.powerW)) {
                tvPower.setText("功耗 --");
            } else {
                // 充电时电流是灌进电池的，标一个 ⚡ 免得把充入功率当整机耗电看
                tvPower.setText(String.format(Locale.US, "功耗 %.2fW%s",
                        s.powerW, s.charging ? " ⚡" : ""));
            }
        }

        if (tvCollapsed != null) {
            String freq = s.maxFreqKhz() > 0
                    ? String.format(Locale.US, "%.2fG", s.maxFreqKhz() / 1_000_000f)
                    : "--";
            String temp = Float.isNaN(s.cpuTempC)
                    ? "--"
                    : String.format(Locale.US, "%.1f°", s.cpuTempC);
            tvCollapsed.setText(String.format(Locale.US, "CPU %s   %s", freq, temp));
            tvCollapsed.setTextColor(Float.isNaN(s.cpuTempC) ? COLOR_TEXT : tempColor(s.cpuTempC));
        }
    }

    static String formatCore(int idx, int khz) {
        String v;
        if (khz == CpuReader.FREQ_OFFLINE) {
            v = "off";
        } else if (khz <= 0) {
            v = "--";
        } else {
            v = String.valueOf(Math.round(khz / 1000f));
        }
        return String.format(Locale.US, "cpu%d %5s", idx, v);
    }

    static int tempColor(float c) {
        if (c >= 70f) {
            return COLOR_HOT;
        }
        if (c >= 60f) {
            return COLOR_WARM;
        }
        return COLOR_COOL;
    }

    private TextView text(CharSequence s, float sizeSp, int color, boolean bold) {
        TextView tv = new TextView(getContext());
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        tv.setTextColor(color);
        tv.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
