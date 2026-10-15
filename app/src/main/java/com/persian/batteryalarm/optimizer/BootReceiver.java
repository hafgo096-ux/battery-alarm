package com.persian.batteryalarm.optimizer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * بازگرداندن پایش پس از روشن شدن گوشی.
 * در حالت کم‌مصرف، فقط Job زمان‌بندی می‌شود (بدون سرویس دائمی).
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        try {
            if (!MonitorPrefs.monitoringEnabled(context)) {
                Log.i(TAG, "monitoring disabled by user, skipping boot start");
                return;
            }
            if (MonitorPrefs.lowPowerMode(context)) {
                BatteryMonitorJobService.schedule(context);
                Log.i(TAG, "low power mode: periodic job scheduled");
            } else {
                Intent svc = new Intent(context, BatteryWatchService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(svc);
                else context.startService(svc);
            }
        } catch (Exception e) {
            Log.w(TAG, "boot restart failed", e);
        }
    }
}
