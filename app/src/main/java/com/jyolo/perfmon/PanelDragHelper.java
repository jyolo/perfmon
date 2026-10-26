package com.jyolo.perfmon;

import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * 面板的拖动 / 单击折叠逻辑。两个宿主（普通悬浮窗、无障碍浮层）共用，
 * 差别只在窗口类型，交互必须完全一致，所以抽出来而不是各写一份。
 *
 * 约定：位移 > 5dp 判为拖动（只存位置），否则 < 450ms 松手判为单击（折叠/展开）。
 */
class PanelDragHelper implements View.OnTouchListener {

    private final WindowManager wm;
    private final PanelView panel;
    private final WindowManager.LayoutParams lp;
    private final SharedPreferences prefs;
    private final int touchSlopPx;
    private final Runnable onCollapsedChanged;

    private float downRawX, downRawY;
    private int startX, startY;
    private long downAtMs;
    private boolean dragging;

    PanelDragHelper(WindowManager wm, PanelView panel, WindowManager.LayoutParams lp,
                    SharedPreferences prefs, int touchSlopPx, Runnable onCollapsedChanged) {
        this.wm = wm;
        this.panel = panel;
        this.lp = lp;
        this.prefs = prefs;
        this.touchSlopPx = touchSlopPx;
        this.onCollapsedChanged = onCollapsedChanged;
    }

    void attach() {
        panel.setOnTouchListener(this);
    }

    @Override
    public boolean onTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                startX = lp.x;
                startY = lp.y;
                downAtMs = SystemClock.uptimeMillis();
                dragging = false;
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - downRawX;
                float dy = e.getRawY() - downRawY;
                if (!dragging && Math.hypot(dx, dy) > touchSlopPx) {
                    dragging = true;
                }
                if (dragging) {
                    int[] screen = screenSize();
                    int maxX = Math.max(0, screen[0] - panel.getWidth());
                    int maxY = Math.max(0, screen[1] - panel.getHeight());
                    lp.x = Math.min(Math.max(startX + (int) dx, 0), maxX);
                    lp.y = Math.min(Math.max(startY + (int) dy, 0), maxY);
                    wm.updateViewLayout(panel, lp);
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
                if (dragging) {
                    persistPosition();
                } else if (SystemClock.uptimeMillis() - downAtMs < 450) {
                    panel.setCollapsed(!panel.isCollapsed());
                    prefs.edit().putBoolean(Prefs.KEY_COLLAPSED, panel.isCollapsed()).apply();
                    if (onCollapsedChanged != null) {
                        onCollapsedChanged.run();
                    }
                }
                dragging = false;
                return true;

            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    persistPosition();
                }
                dragging = false;
                return true;

            default:
                return false;
        }
    }

    private void persistPosition() {
        prefs.edit().putInt(Prefs.KEY_X, lp.x).putInt(Prefs.KEY_Y, lp.y).apply();
    }

    private int[] screenSize() {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return new int[]{dm.widthPixels, dm.heightPixels};
    }
}
