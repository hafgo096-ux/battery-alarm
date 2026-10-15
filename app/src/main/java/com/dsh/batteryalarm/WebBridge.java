package com.dsh.batteryalarm;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import android.webkit.JavascriptInterface;

/**
 * پل ارتباطی جاوااسکریپت WebView با کدهای نیتیو اندروید.
 * تمامی متدها دارای حاشیه‌نویسی @JavascriptInterface هستند.
 */
public class WebBridge {
    private static final String TAG = "WebBridge";
    private final Context ctx;

    public WebBridge(Context ctx) {
        this.ctx = ctx;
    }

    @JavascriptInterface
    public void alarmHeartbeat() {
        try {
            Intent i = new Intent(ctx, BatteryWatchService.class);
            i.putExtra("html_heartbeat", true);
            ctx.startService(i);
        } catch (Exception e) {
            Log.w(TAG, "heartbeat failed", e);
        }
    }

    @JavascriptInterface
    public void alarmStopped() {
        try {
            Intent i = new Intent(ctx, BatteryWatchService.class);
            i.putExtra("html_stopped", true);
            ctx.startService(i);
        } catch (Exception e) {
            Log.w(TAG, "stop failed", e);
        }
    }

    @JavascriptInterface
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

    @JavascriptInterface
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

    @JavascriptInterface
    public void setTargetThreshold(int percent) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences("dsh_settings", Context.MODE_PRIVATE);
            sp.edit().putInt("target_percent", percent).apply();
            Log.i(TAG, "Target percentage updated to: " + percent);
        } catch (Exception e) {
            Log.w(TAG, "failed to save target percent", e);
        }
    }

    @JavascriptInterface
    public int getTargetThreshold() {
        try {
            SharedPreferences sp = ctx.getSharedPreferences("dsh_settings", Context.MODE_PRIVATE);
            return sp.getInt("target_percent", 85);
        } catch (Exception e) {
            return 85;
        }
    }
}
