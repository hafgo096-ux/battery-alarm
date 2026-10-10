package com.dsh.batteryalarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BatteryReceiver extends BroadcastReceiver {
    private static final String TAG = "BatteryWatch";
    private static final int TARGET_PERCENT = 85;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!(context instanceof BatteryWatchService)) return;
        BatteryWatchService svc = (BatteryWatchService) context;
        int level = intent.getIntExtra("level", -1);
        int scale = intent.getIntExtra("scale", -1);
        int plugged = intent.getIntExtra("plugged", 0);
        if (level < 0 || scale <= 0) return;
        int percent = (level * 100) / scale;
        boolean charging = plugged > 0;
        Log.d(TAG, "battery: " + percent + "% charging=" + charging);

        if (charging && percent >= TARGET_PERCENT && !svc.alarmFired && !svc.stopRequested) {
            svc.alarmFired = true;
            boolean htmlHandling = System.currentTimeMillis() < svc.htmlActiveUntil;
            if (htmlHandling) {
                Log.d(TAG, "html app is handling the alarm, native stays silent");
            } else {
                svc.triggerAlarm(percent);
            }
        }
        if (percent <= TARGET_PERCENT - 3 || !charging) {
            svc.stopRequested = false;
        }
    }
}
