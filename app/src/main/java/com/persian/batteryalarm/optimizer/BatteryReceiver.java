package com.persian.batteryalarm.optimizer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * گیرنده رویداد باتری (نسخه ۱.۰.۱).
 * فقط رویدادهای ACTION_BATTERY_CHANGED را به سرویس پایش تحویل می‌دهد.
 * هیچ پایش دوره‌ای یا WakeLock در این بخش وجود ندارد ⇒ مصرف باتری تقریباً صفر.
 */
public class BatteryReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context instanceof BatteryWatchService) {
            ((BatteryWatchService) context).handleBatteryIntent(intent);
        }
    }
}
