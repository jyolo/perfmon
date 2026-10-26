package com.jyolo.perfmon;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 外部控制入口（adb / Tasker / 快捷方式）。
 *
 * 只接受四个动作，不接受参数、不返回数据、不提供查询能力：
 *   adb shell am broadcast -a com.jyolo.perfmon.START  -n com.jyolo.perfmon/.ControlReceiver
 *   adb shell am broadcast -a com.jyolo.perfmon.STOP   -n com.jyolo.perfmon/.ControlReceiver
 *   adb shell am broadcast -a com.jyolo.perfmon.RELOAD -n com.jyolo.perfmon/.ControlReceiver
 *   adb shell am broadcast -a com.jyolo.perfmon.RESET  -n com.jyolo.perfmon/.ControlReceiver
 */
public class ControlReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) {
            return;
        }
        if (!OverlayService.ACTION_START.equals(action)
                && !OverlayService.ACTION_STOP.equals(action)
                && !OverlayService.ACTION_RELOAD.equals(action)
                && !OverlayService.ACTION_RESET.equals(action)) {
            return;
        }
        Intent svc = new Intent(context, OverlayService.class).setAction(action);
        try {
            if (OverlayService.ACTION_STOP.equals(action)) {
                // 停服务不需要先把它拉起来
                context.stopService(svc);
            } else if (Build.VERSION.SDK_INT >= 26) {
                // 广播接收时 App 通常处于后台背景，startService 会被系统拒绝
                // (IllegalStateException: Not allowed to start service)，
                // 必须用 startForegroundService 才能合法拉起。
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Exception e) {
            // 广播接收器里抛异常会直接杀掉进程，这里必须吞掉
            android.util.Log.w("PerfMon", "control action " + action + " failed: " + e);
        }
    }
}
