package com.dsh.batteryalarm;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import java.util.Timer;
import java.util.TimerTask;

/**
 * سرویس پس‌زمینهٔ پایش شارژ. حتی اگر برنامه کامل بسته شود، این سرویس
 * زنده می‌ماند و با صدای آلارم خود اندروید هشدار می‌دهد. زنگ تنها با
 * لمس دکمهٔ «توقف» قطع می‌شود.
 */
public class BatteryWatchService extends Service {

    private static final String TAG = "BatteryWatch";
    private static final String CH_WATCH = "battery_watch_channel";
    private static final String CH_ALARM = "battery_alarm_channel";
    private static final int NOTIF_WATCH = 777;
    private static final int NOTIF_ALARM = 778;
    private static final int TARGET_PERCENT = 85;

    private PowerManager.WakeLock wakeLock;
    private MediaPlayer mediaPlayer;
    private Vibrator vibrator;
    boolean alarmFired = false;
    boolean stopRequested = false;
    long htmlActiveUntil = 0;
    private Timer timer;

    private final BatteryReceiver batteryReceiver = new BatteryReceiver();

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        startForeground(NOTIF_WATCH, buildWatchNotification("پایش شارژ فعال است"));

        // WakeLock دائمی برای زنده ماندن سرویس
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dsh:batterywatch:watch");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(); // بدون انقضا
        }
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        // تایمر یادآور برای زنده نگه‌داشتن سرویس در حالت Doze
        timer = new Timer("dsh-watchdog", true);
        timer.scheduleAtFixedRate(new WatchdogTask(this), 60_000, 60_000);

        Log.i(TAG, "service started, watchdog armed");
    }

    void triggerAlarm(int percent) {
        Log.i(TAG, "ALARM at " + percent + "%");
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ALARM, buildAlarmNotification(percent));
        }
        startVibration();
        playSystemAlarm();
    }

    private void playSystemAlarm() {
        try {
            stopMediaPlayer();
            Uri uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM);
            if (uri == null) {
                uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE);
            }
            if (uri == null) {
                uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_NOTIFICATION);
            }
            if (uri == null) return;

            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, uri);
            mediaPlayer.setLooping(true);
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            // بدون anonymous class: از Runnable در دستورات استفاده می‌کنیم
            mediaPlayer.setOnPreparedListener(new PreparedListener());
            mediaPlayer.prepareAsync();
            Log.i(TAG, "playing system alarm sound");
        } catch (Exception e) {
            Log.e(TAG, "playAlarm failed", e);
        }
    }

    private void startVibration() {
        try {
            if (vibrator == null || !vibrator.hasVibrator()) return;
            long[] pattern = {0, 500, 300, 500, 300, 500};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
            } else {
                vibrator.vibrate(pattern, 0);
            }
        } catch (Exception e) {
            Log.e(TAG, "vibrate failed", e);
        }
    }

    private void stopMediaPlayer() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
    }

    /** توقف کامل زنگ — تنها از طریق این متد صدای آلارم قطع می‌شود */
    void stopAlarm() {
        stopMediaPlayer();
        if (vibrator != null) {
            try { vibrator.cancel(); } catch (Exception ignored) {}
        }
        alarmFired = false;
        stopRequested = true;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIF_ALARM);
        Log.i(TAG, "alarm stopped by user");
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel watch = new NotificationChannel(
                CH_WATCH, "پایش پس‌زمینه", NotificationManager.IMPORTANCE_LOW);
        watch.setDescription("اعلان دائمی برای زنده ماندن پایش شارژ");
        nm.createNotificationChannel(watch);

        NotificationChannel alarm = new NotificationChannel(
                CH_ALARM, "زنگ هشدار شارژ", NotificationManager.IMPORTANCE_HIGH);
        alarm.setDescription("زنگ هشدار هنگام رسیدن به ۸۵٪");
        alarm.setSound(null, null);
        nm.createNotificationChannel(alarm);
    }

    private Notification buildWatchNotification(String text) {
        return new Notification.Builder(this, CH_WATCH)
                .setContentTitle("هشدار شارژ باتری")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .build();
    }

    private Notification buildAlarmNotification(int percent) {
        Intent stopIntent = new Intent(this, BatteryWatchService.class);
        stopIntent.putExtra("stop_alarm", true);
        PendingIntent stopPending = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action stopAction = new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                "توقف زنگ", stopPending).build();

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(this, 2, openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this, CH_ALARM)
                .setContentTitle("⚡ شارژ به " + percent + "٪ رسید!")
                .setContentText("گوشی را از شارژ بکشید — برای توقف زنگ بزنید")
                .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_MAX)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(stopAction)
                .setContentIntent(openPending)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (intent.getBooleanExtra("stop_alarm", false)) {
                stopAlarm();
            } else if (intent.getBooleanExtra("html_heartbeat", false)) {
                // برنامه در پیش‌زمینه است و خودش صدا را پخش می‌کند
                htmlActiveUntil = System.currentTimeMillis() + 8000;
                if (alarmFired) {
                    stopMediaPlayer();
                    if (vibrator != null) { try { vibrator.cancel(); } catch (Exception ignored) {} }
                }
            } else if (intent.getBooleanExtra("html_stopped", false)) {
                // کاربر در برنامه دکمهٔ توقف را زد
                stopAlarm();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // ری‌استارت سرویس وقتی برنامه از لیست اخیرها پاک شد
        Intent restart = new Intent(this, BatteryWatchService.class);
        PendingIntent pi = PendingIntent.getService(this, 3, restart,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am != null) {
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 1000, pi);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopMediaPlayer();
        if (vibrator != null) { try { vibrator.cancel(); } catch (Exception ignored) {} }
        if (timer != null) { timer.cancel(); timer = null; }
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
        }
        try { unregisterReceiver(batteryReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    static class PreparedListener implements MediaPlayer.OnPreparedListener {
        @Override
        public void onPrepared(MediaPlayer mp) {
            try { mp.start(); } catch (Exception e) { Log.e("BatteryWatch", "start failed", e); }
        }
    }


    static class WatchdogTask extends java.util.TimerTask {
        private final BatteryWatchService svc;
        WatchdogTask(BatteryWatchService svc) { this.svc = svc; }
        @Override
        public void run() {
            if (svc.wakeLock != null && !svc.wakeLock.isHeld()) {
                try { svc.wakeLock.acquire(); } catch (Exception ignored) {}
            }
        }
    }

}
