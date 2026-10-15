package com.dsh.batteryalarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

public class BatteryReceiver extends BroadcastReceiver {
    private static final String TAG = "BatteryWatch";

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

        SharedPreferences sp = context.getSharedPreferences("dsh_settings", Context.MODE_PRIVATE);
        int targetPercent = sp.getInt("target_percent", 85);
        boolean lowAlertEnabled = sp.getBoolean("low_enabled", false);
        int lowPercent = sp.getInt("low_percent", 20);

        Log.d(TAG, "battery: " + percent + "% charging=" + charging + " target=" + targetPercent);

        // هشدار شارژ به درصد هدف
        if (charging && percent >= targetPercent && !svc.alarmFired && !svc.stopRequested) {
            svc.alarmFired = true;
            boolean htmlHandling = System.currentTimeMillis() < svc.htmlActiveUntil;
            if (htmlHandling) {
                Log.d(TAG, "html app is handling the alarm, native stays silent");
            } else {
                svc.triggerAlarm(percent);
            }
        }

        // هشدار شارژ کم در صورت فعال بودن
        if (!charging && lowAlertEnabled && percent <= lowPercent && !svc.alarmFired && !svc.stopRequested) {
            svc.alarmFired = true;
            boolean htmlHandling = System.currentTimeMillis() < svc.htmlActiveUntil;
            if (!htmlHandling) {
                svc.triggerAlarmCustom("⚠️ باتری ضعیف است (" + percent + "٪)", "لطفاً گوشی را به شارژر متصل نمایید");
            }
        }

        // ریست وضعیت وقتی شارژ قطع شد یا درصد افت کرد
        if (percent <= targetPercent - 2 || !charging) {
            svc.stopRequested = false;
        }
    }
}
