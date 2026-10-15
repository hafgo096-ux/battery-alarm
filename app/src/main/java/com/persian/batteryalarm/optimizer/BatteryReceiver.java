package com.persian.batteryalarm.optimizer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

public class BatteryReceiver extends BroadcastReceiver {
    private static final String TAG = "PersianBatteryReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!(context instanceof BatteryWatchService)) return;
        BatteryWatchService svc = (BatteryWatchService) context;
        int level = intent.getIntExtra("level", -1);
        int scale = intent.getIntExtra("scale", -1);
        int plugged = intent.getIntExtra("plugged", 0);
        int temp = intent.getIntExtra("temperature", 0); // tenth of celsius
        if (level < 0 || scale <= 0) return;
        int percent = (level * 100) / scale;
        boolean charging = plugged > 0;
        double tempC = temp / 10.0;

        SharedPreferences sp = context.getSharedPreferences("persian_battery_prefs", Context.MODE_PRIVATE);
        int targetPercent = sp.getInt("target_percent", 85);
        boolean lowAlertEnabled = sp.getBoolean("low_enabled", true);
        int lowPercent = sp.getInt("low_percent", 20);
        boolean overheatAlertEnabled = sp.getBoolean("overheat_enabled", true);
        int overheatThreshold = sp.getInt("overheat_threshold", 38);

        Log.d(TAG, "Battery level=" + percent + "% charging=" + charging + " temp=" + tempC + "°C");

        // ۱. هشدار شارژ به درصد هدف (مثلا ۸۵٪ یا ۱۰۰٪)
        if (charging && percent >= targetPercent && !svc.alarmFired && !svc.stopRequested) {
            String soundAsset = percent >= 100 ? "fa_battery_100.mp3" : "fa_battery_85.mp3";
            svc.triggerAlarm(percent, soundAsset);
        }

        // ۲. هشدار دمای بیش از حد مجاز باتری حین شارژ یا مصرف
        if (overheatAlertEnabled && tempC >= overheatThreshold && !svc.alarmFired && !svc.stopRequested) {
            svc.triggerAlarmCustom("🔥 هشدار حرارت بالای باتری (" + tempC + "°C)",
                    "دستگاه داغ شده است، لطفاً شارژر را جدا کنید",
                    "fa_battery_hot.mp3");
        }

        // ۳. هشدار افت شدید باتری (شارژ کم)
        if (!charging && lowAlertEnabled && percent <= lowPercent && !svc.alarmFired && !svc.stopRequested) {
            svc.triggerAlarmCustom("⚠️ باتری ضعیف است (" + percent + "٪)",
                    "لطفاً گوشی را به شارژر متصل نمایید",
                    "fa_battery_low.mp3");
        }

        // ریست وضعیت هنگامی که شارژ جدا شد یا درصد کاهش یافت
        if (percent <= targetPercent - 2 || !charging) {
            svc.stopRequested = false;
        }
    }
}
