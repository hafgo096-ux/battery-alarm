package com.dsh.batteryalarm;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class WebBridge {
    private static final String TAG = "WebBridge";
    private final Context ctx;

    public WebBridge(Context ctx) {
        this.ctx = ctx;
    }

    public void alarmHeartbeat() {
        try {
            Intent i = new Intent(ctx, BatteryWatchService.class);
            i.putExtra("html_heartbeat", true);
            ctx.startService(i);
        } catch (Exception e) {
            Log.w(TAG, "heartbeat failed", e);
        }
    }

    public void alarmStopped() {
        try {
            Intent i = new Intent(ctx, BatteryWatchService.class);
            i.putExtra("html_stopped", true);
            ctx.startService(i);
        } catch (Exception e) {
            Log.w(TAG, "stop failed", e);
        }
    }

    public void alarmTriggeredNative(int percent) {
        try {
            Intent i = new Intent(ctx, BatteryWatchService.class);
            i.putExtra("native_alarm", true);
            i.putExtra("percent", percent);
            ctx.startService(i);
        } catch (Exception e) {
            Log.w(TAG, "native trigger failed", e);
        }
    }

    /** زنگ هشدار سفارشی برای رویدادهای غیر باتری (مثل مصرف بالای منابع) */
    public void resourceAlarm(String title, String sub) {
        try {
            Intent i = new Intent(ctx, BatteryWatchService.class);
            i.putExtra("native_alarm", true);
            i.putExtra("alarm_title", title);
            i.putExtra("alarm_sub", sub == null ? "" : sub);
            ctx.startService(i);
        } catch (Exception e) {
            Log.w(TAG, "resource alarm failed", e);
        }
    }
}
