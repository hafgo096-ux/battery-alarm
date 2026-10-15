package com.persian.batteryalarm.optimizer;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.util.Log;

/**
 * موتور تصمیم‌گیری هشدارها (نسخه ۱.۰.۱).
 *
 * این کلاس منطق هشدار را از سرویس جدا می‌کند تا هم حالت «سرویس دائمی» و هم
 * حالت «کم‌مصرف با Job» بتوانند از یک منطق واحد استفاده کنند.
 * منطق جدید: بدون WakeLock، بدون حلقه پایش، فقط رویدادمحور (ACTION_BATTERY_CHANGED).
 */
public final class AlertEngine {

    private static final String TAG = "AlertEngine";
    public static final String STATE_LAST_PERCENT = "state_last_percent";

    private AlertEngine() {}

    public static class Decision {
        public String type = "none";
        public String title = "";
        public String sub = "";
        public String soundAsset = "fa_battery_85.mp3";
        public int percent = 0;
        public double tempC = 0;
        public boolean vibrate = true;
    }

    public static int percentOf(Intent battery) {
        if (battery == null) return -1;
        int level = battery.getIntExtra("level", -1);
        int scale = battery.getIntExtra("scale", -1);
        if (level < 0 || scale <= 0) return -1;
        int p = Math.round(level * 100f / scale);
        return MonitorPrefs.clamp(p, 0, 100);
    }

    public static double tempOf(Intent battery) {
        if (battery == null) return -1;
        int t = battery.getIntExtra("temperature", Integer.MIN_VALUE);
        if (t == Integer.MIN_VALUE || t <= 0) return -1;
        return t / 10.0;
    }

    public static boolean chargingOf(Intent battery) {
        return battery != null && battery.getIntExtra("plugged", 0) > 0;
    }

    /**
     * ارزیابی وضعیت فعلی و برگرداندن هشدار در صورت نیاز (null = بدون هشدار).
     * این تابع «وضعیت» را در SharedPreferences نگه می‌دارد تا هر هشدار فقط یک‌بار
     * در هر چرخه شارژ/دشارژ پخش شود (جلوگیری از پخش مکرر و مصرف باتری).
     */
    public static Decision evaluate(Context ctx, Intent battery) {
        try {
            if (battery == null) return null;
            int percent = percentOf(battery);
            if (percent < 0) return null;

            boolean charging = chargingOf(battery);
            double tempC = tempOf(battery);

            SharedPreferences p = MonitorPrefs.sp(ctx);
            boolean wasCharging = p.getBoolean(MonitorPrefs.S_LAST_CHARGING, charging);
            boolean armed = p.getBoolean(MonitorPrefs.S_TARGET_ARMED, true);
            boolean lowFired = p.getBoolean(MonitorPrefs.S_LOW_FIRED, false);
            long overheatAt = p.getLong(MonitorPrefs.S_OVERHEAT_AT, 0L);
            long unplugAt = p.getLong(MonitorPrefs.S_UNPLUG_AT, 0L);
            int lastPercent = p.getInt(STATE_LAST_PERCENT, percent);
            long now = System.currentTimeMillis();

            int target = MonitorPrefs.targetPercent(ctx);
            int lowPercent = MonitorPrefs.lowPercent(ctx);
            int overheatThreshold = MonitorPrefs.overheatThreshold(ctx);
            long overheatRewarn = 10L * 60L * 1000L;

            SharedPreferences.Editor ed = p.edit();
            boolean dirty = false;

            // ---------- بازآماده‌سازی (re-arm) ----------
            if (!charging && !armed) { ed.putBoolean(MonitorPrefs.S_TARGET_ARMED, true); armed = true; dirty = true; }
            if (charging && !armed && percent <= target - 2) { ed.putBoolean(MonitorPrefs.S_TARGET_ARMED, true); armed = true; dirty = true; }
            if (lowFired && (charging || percent > lowPercent + 3)) { ed.putBoolean(MonitorPrefs.S_LOW_FIRED, false); lowFired = false; dirty = true; }
            if (overheatAt > 0 && tempC > 0 && tempC < overheatThreshold - 2) { ed.putLong(MonitorPrefs.S_OVERHEAT_AT, 0L); overheatAt = 0; dirty = true; }
            if (charging && unplugAt > 0) { ed.putLong(MonitorPrefs.S_UNPLUG_AT, 0L); unplugAt = 0; dirty = true; }
            if (wasCharging != charging) { ed.putBoolean(MonitorPrefs.S_LAST_CHARGING, charging); dirty = true; }
            if (lastPercent != percent) { ed.putInt(STATE_LAST_PERCENT, percent); dirty = true; }
            if (dirty) ed.apply();

            boolean voice = MonitorPrefs.voiceEnabled(ctx);

            // ---------- ۱. حفاظت حرارتی (اولویت امنیتی) ----------
            if (MonitorPrefs.overheatEnabled(ctx) && tempC > 0 && tempC >= overheatThreshold
                    && (now - overheatAt) > overheatRewarn) {
                p.edit().putLong(MonitorPrefs.S_OVERHEAT_AT, now).apply();
                Decision d = new Decision();
                d.type = "overheat";
                d.title = "🔥 هشدار حرارت بالای باتری (" + String.format(java.util.Locale.US, "%.1f", tempC) + "°C)";
                d.sub = "دستگاه داغ شده است، لطفاً شارژر را جدا کنید";
                d.soundAsset = "fa_battery_hot.mp3";
                d.percent = percent;
                d.tempC = tempC;
                d.vibrate = true;
                return d;
            }

            // ---------- ۲. رسیدن به سقف شارژ هدف ----------
            if (charging && armed && percent >= target) {
                p.edit().putBoolean(MonitorPrefs.S_TARGET_ARMED, false).apply();
                Decision d = new Decision();
                boolean full = percent >= 100;
                d.type = full ? "full_100" : "target";
                d.title = full ? "🔋 باتری ۱۰۰٪ شارژ شد" : "⚡ شارژ به " + percent + "٪ رسید";
                d.sub = full ? "شارژ کامل شد، شارژر را بکشید" : "جهت طول عمر باتری شارژر را جدا کنید";
                d.soundAsset = full ? "fa_battery_100.mp3" : "fa_battery_85.mp3";
                if (!voice) d.soundAsset = "fa_siren.mp3";
                d.percent = percent;
                d.tempC = tempC;
                return d;
            }

            // ---------- ۳. افت شارژ (Low Battery) ----------
            if (!charging && MonitorPrefs.lowEnabled(ctx) && percent <= lowPercent && !lowFired) {
                p.edit().putBoolean(MonitorPrefs.S_LOW_FIRED, true).apply();
                Decision d = new Decision();
                d.type = "low_20";
                d.title = "⚠️ باتری ضعیف است (" + percent + "٪)";
                d.sub = "لطفاً گوشی را به شارژر متصل نمایید";
                d.soundAsset = voice ? "fa_battery_low.mp3" : "fa_siren.mp3";
                d.vibrate = false;
                d.percent = percent;
                d.tempC = tempC;
                return d;
            }

            // ---------- ۴. آژیر قطع ناگهانی کابل (ضدسرقت) ----------
            long cooldown = MonitorPrefs.cooldownSec(ctx) * 1000L;
            if (!charging && wasCharging && MonitorPrefs.unplugEnabled(ctx)
                    && lastPercent > 0 && (now - unplugAt) > Math.max(cooldown, 30000L)) {
                p.edit().putLong(MonitorPrefs.S_UNPLUG_AT, now).apply();
                Decision d = new Decision();
                d.type = "unplug";
                d.title = "🚨 شارژر جدا شد!";
                d.sub = "آژیر ضدسرقت قطع ناگهانی کابل فعال شد";
                d.soundAsset = "fa_siren.mp3";
                d.percent = percent;
                d.tempC = tempC;
                return d;
            }

            return null;
        } catch (Exception e) {
            Log.w(TAG, "evaluate failed", e);
            return null;
        }
    }

    /** اعلان هشدار با دکمه توقف؛ در حالت کم‌مصرف هم قابل استفاده است. */
    public static void postAlarmNotification(Context ctx, Decision d) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            Intent stopIntent = new Intent(ctx, BatteryWatchService.class);
            stopIntent.putExtra("stop_alarm", true);
            PendingIntent stopPending = PendingIntent.getService(ctx, 1, stopIntent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Action stopAction = new Notification.Action.Builder(
                    Icon.createWithResource(ctx, android.R.drawable.ic_media_pause), "توقف زنگ", stopPending).build();

            Intent openIntent = new Intent(ctx, MainActivity.class);
            openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent openPending = PendingIntent.getActivity(ctx, 2, openIntent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(ctx, BatteryWatchService.CH_ALARM)
                    : new Notification.Builder(ctx);

            nm.notify(BatteryWatchService.NOTIF_ALARM, b
                    .setContentTitle(d.title)
                    .setContentText(d.sub)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setPriority(Notification.PRIORITY_MAX)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setShowWhen(true)
                    .addAction(stopAction)
                    .setContentIntent(openPending)
                    .build());
        } catch (Exception e) {
            Log.w(TAG, "postAlarmNotification failed", e);
        }
    }
}
