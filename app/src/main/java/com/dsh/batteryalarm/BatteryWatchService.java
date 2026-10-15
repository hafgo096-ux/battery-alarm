package com.dsh.batteryalarm;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
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
 * سرویس پس‌زمینهٔ پایش هوشمند شارژ باتری و وضعیت سیستم.
 * سازگار با اندروید ۸ تا ۱۵ (API 26 تا 35).
 */
public class BatteryWatchService extends Service {

    private static final String TAG = "BatteryWatch";
    private static final String CH_WATCH = "battery_watch_channel";
    private static final String CH_ALARM = "battery_alarm_channel";
    private static final int NOTIF_WATCH = 777;
    private static final int NOTIF_ALARM = 778;

    private PowerManager.WakeLock wakeLock;
    private MediaPlayer mediaPlayer;
    private Vibrator vibrator;
    boolean alarmFired = false;
    boolean stopRequested = false;
    long htmlActiveUntil = 0;
    private Timer timer;
    private boolean receiverRegistered = false;

    private final BatteryReceiver batteryReceiver = new BatteryReceiver();

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();

        // شروع Foreground Service سازگار با اندروید ۱۴ و ۱۵ (API 34+)
        Notification watchNotif = buildWatchNotification("پایش هوشمند شارژ و سخت‌افزار فعال است");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_WATCH, watchNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_WATCH, watchNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_WATCH, watchNotif);
        }

        // WakeLock برای اجرای پایدار پایش در حالت قفل صفحه
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dsh:batterywatch:watch");
            wakeLock.setReferenceCounted(false);
            try {
                wakeLock.acquire();
            } catch (Exception ignored) {}
        }
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        // ثبت دریافت‌کننده تغییرات باتری
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            }
            receiverRegistered = true;
        } catch (Exception e) {
            Log.w(TAG, "failed to register battery receiver", e);
        }

        // تایمر واچ‌داگ برای زنده نگه‌داشتن سرویس در حالت Doze
        timer = new Timer("dsh-watchdog", true);
        timer.scheduleAtFixedRate(new WatchdogTask(this), 60_000, 60_000);

        Log.i(TAG, "BatteryWatchService started successfully");
    }

    void triggerAlarm(int percent) {
        Log.i(TAG, "ALARM at " + percent + "%");
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ALARM, buildAlarmNotification(percent, null, null));
        }
        startVibration();
        playSystemAlarm();
    }

    void triggerAlarmCustom(String title, String sub) {
        Log.i(TAG, "ALARM custom: " + title);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ALARM, buildAlarmNotification(0, title, sub));
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
            long[] pattern = {0, 600, 250, 600, 250, 800};
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
                CH_WATCH, "پایش مداوم باتری", NotificationManager.IMPORTANCE_LOW);
        watch.setDescription("اعلان برای زنده ماندن سرویس پایش هوشمند");
        nm.createNotificationChannel(watch);

        NotificationChannel alarm = new NotificationChannel(
                CH_ALARM, "زنگ هشدار باتری و سخت‌افزار", NotificationManager.IMPORTANCE_HIGH);
        alarm.setDescription("زنگ هشدار هنگام رسیدن به شارژ هدف یا مصرف بحرانی");
        alarm.setSound(null, null);
        alarm.enableVibration(true);
        nm.createNotificationChannel(alarm);
    }

    private Notification buildWatchNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this, CH_WATCH)
                .setContentTitle("هشدار هوشمند باتری")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                .setOngoing(true)
                .setContentIntent(openPending)
                .setPriority(Notification.PRIORITY_LOW)
                .build();
    }

    private Notification buildAlarmNotification(int percent, String title, String sub) {
        String t = (title != null && !title.isEmpty()) ? title : "⚡ شارژ به " + percent + "٪ رسید!";
        String s = (sub != null && !sub.isEmpty()) ? sub : "گوشی را از شارژ بکشید — برای توقف زنگ بزنید";
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
                .setContentTitle(t)
                .setContentText(s)
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
                htmlActiveUntil = System.currentTimeMillis() + 8000;
                if (alarmFired) {
                    stopMediaPlayer();
                    if (vibrator != null) { try { vibrator.cancel(); } catch (Exception ignored) {} }
                }
            } else if (intent.getBooleanExtra("html_stopped", false)) {
                stopAlarm();
            } else if (intent.getBooleanExtra("native_alarm", false)) {
                SharedPreferences sp = getSharedPreferences("dsh_settings", Context.MODE_PRIVATE);
                int target = sp.getInt("target_percent", 85);
                int percent = intent.getIntExtra("percent", target);
                String title = intent.getStringExtra("alarm_title");
                String sub = intent.getStringExtra("alarm_sub");
                if (!alarmFired && !stopRequested) {
                    alarmFired = true;
                    if (title != null && !title.isEmpty()) {
                        triggerAlarmCustom(title, sub);
                    } else {
                        triggerAlarm(percent);
                    }
                }
            }
        }
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
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
        if (receiverRegistered) {
            try {
                unregisterReceiver(batteryReceiver);
                receiverRegistered = false;
            } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    static class PreparedListener implements MediaPlayer.OnPreparedListener {
        @Override
        public void onPrepared(MediaPlayer mp) {
            try { mp.start(); } catch (Exception e) { Log.e("BatteryWatch", "start failed", e); }
        }
    }

    static class WatchdogTask extends TimerTask {
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
