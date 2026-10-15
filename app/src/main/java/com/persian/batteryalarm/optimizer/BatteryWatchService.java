package com.persian.batteryalarm.optimizer;

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
import android.content.res.AssetFileDescriptor;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
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
 * سرویس پس‌زمینه پایش هوشمند شارژ، سخت‌افزار و مدیریت بهینه‌سازی
 * دارای موتور چندلایه صوتی ضد سکوت با صدای فارسی و هشدارهای مطمئن
 */
public class BatteryWatchService extends Service {

    private static final String TAG = "BatteryWatchService";
    public static final String CH_WATCH = "persian_battery_watch";
    public static final String CH_ALARM = "persian_battery_alarm";
    public static final int NOTIF_WATCH = 777;
    public static final int NOTIF_ALARM = 778;

    private PowerManager.WakeLock wakeLock;
    private MediaPlayer mediaPlayer;
    private ToneGenerator toneGenerator;
    private Vibrator vibrator;
    public boolean alarmFired = false;
    public boolean stopRequested = false;
    private Timer timer;
    private boolean receiverRegistered = false;

    private final BatteryReceiver batteryReceiver = new BatteryReceiver();

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();

        Notification watchNotif = buildWatchNotification("پایش هوشمند باتری و سخت‌افزار فعال است");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_WATCH, watchNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_WATCH, watchNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_WATCH, watchNotif);
        }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "persian:batteryalarm:watch");
            wakeLock.setReferenceCounted(false);
            try { wakeLock.acquire(); } catch (Exception ignored) {}
        }
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            }
            receiverRegistered = true;
        } catch (Exception e) {
            Log.w(TAG, "failed to register receiver", e);
        }

        timer = new Timer("persian-watchdog", true);
        timer.scheduleAtFixedRate(new WatchdogTask(this), 60_000, 60_000);
        Log.i(TAG, "BatteryWatchService created and active");
    }

    public void triggerAlarm(int percent, String soundType) {
        Log.i(TAG, "Triggering alarm at " + percent + "% with soundType=" + soundType);
        alarmFired = true;
        stopRequested = false;

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ALARM, buildAlarmNotification(percent, null, null));
        }

        startVibration();
        playReliableAlarmSound(soundType != null ? soundType : "fa_battery_85.mp3");
    }

    public void triggerAlarmCustom(String title, String sub, String soundAsset) {
        Log.i(TAG, "Custom alarm: " + title);
        alarmFired = true;
        stopRequested = false;

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ALARM, buildAlarmNotification(0, title, sub));
        }

        startVibration();
        playReliableAlarmSound(soundAsset != null ? soundAsset : "fa_battery_low.mp3");
    }

    /**
     * پخش تضمینی صدا با ۳ لایه پشتیبان بدون قطعی یا بی‌صدا شدن
     */
    public synchronized void playReliableAlarmSound(String assetName) {
        stopMediaPlayer();

        // افزایش بلندی صدای آلارم
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int maxVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                int curVol = am.getStreamVolume(AudioManager.STREAM_ALARM);
                if (curVol < maxVol * 0.7) {
                    am.setStreamVolume(AudioManager.STREAM_ALARM, (int)(maxVol * 0.85), 0);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "audioManager volume adjust failed", e);
        }

        // لایه ۱: فایل صوتی فارسی نیتیو
        boolean played = false;
        if (assetName != null && !assetName.isEmpty()) {
            try {
                String path = "audio/" + assetName;
                AssetFileDescriptor afd = getAssets().openFd(path);
                mediaPlayer = new MediaPlayer();
                mediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                afd.close();

                mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
                mediaPlayer.setLooping(false);
                mediaPlayer.setOnCompletionListener(mp -> {
                    // پس از اتمام پیام صوتی، آژیر هشدار به صورت حلقه پخش شود
                    playSirenLoop();
                });
                mediaPlayer.prepare();
                mediaPlayer.start();
                played = true;
                Log.i(TAG, "Playing Persian voice asset: " + path);
            } catch (Exception e) {
                Log.w(TAG, "Asset playback failed: " + assetName + ", falling back", e);
            }
        }

        // لایه ۲: صدای زنگ سیستم
        if (!played) {
            try {
                Uri uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM);
                if (uri == null) uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE);
                if (uri == null) uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_NOTIFICATION);

                if (uri != null) {
                    mediaPlayer = new MediaPlayer();
                    mediaPlayer.setDataSource(this, uri);
                    mediaPlayer.setLooping(true);
                    mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build());
                    mediaPlayer.prepare();
                    mediaPlayer.start();
                    played = true;
                    Log.i(TAG, "Playing system ringtone fallback");
                }
            } catch (Exception e) {
                Log.w(TAG, "System ringtone failed, using tone generator fallback", e);
            }
        }

        // لایه ۳: بوق بیزر ToneGenerator (غیرقابل شکست حتی در بی‌صداترین شرایط)
        if (!played) {
            try {
                toneGenerator = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
                toneGenerator.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 15000);
                Log.i(TAG, "ToneGenerator fallback active");
            } catch (Exception e) {
                Log.e(TAG, "ToneGenerator failed", e);
            }
        }
    }

    private void playSirenLoop() {
        if (!alarmFired || stopRequested) return;
        try {
            stopMediaPlayer();
            AssetFileDescriptor afd = getAssets().openFd("audio/fa_siren.mp3");
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mediaPlayer.setLooping(true);
            mediaPlayer.prepare();
            mediaPlayer.start();
        } catch (Exception e) {
            Log.w(TAG, "siren loop failed, starting tone beep", e);
            try {
                if (toneGenerator == null) {
                    toneGenerator = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
                }
                toneGenerator.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 10000);
            } catch (Exception ignored) {}
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
        if (toneGenerator != null) {
            try {
                toneGenerator.stopTone();
                toneGenerator.release();
            } catch (Exception ignored) {}
            toneGenerator = null;
        }
    }

    public synchronized void stopAlarm() {
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
                CH_WATCH, "پایش مداوم باتری و سیستم", NotificationManager.IMPORTANCE_LOW);
        watch.setDescription("اعلان زنده ماندن سرویس پایش هوشمند باتری");
        nm.createNotificationChannel(watch);

        NotificationChannel alarm = new NotificationChannel(
                CH_ALARM, "زنگ هشدار باتری و بهینه‌ساز", NotificationManager.IMPORTANCE_HIGH);
        alarm.setDescription("زنگ هشدار صوتی هنگام رسیدن به شارژ هدف یا دمای بحرانی");
        alarm.enableVibration(true);
        nm.createNotificationChannel(alarm);
    }

    private Notification buildWatchNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this, CH_WATCH)
                .setContentTitle("زنگ هوشمند باتری و بهینه‌ساز")
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
            } else if (intent.getBooleanExtra("native_alarm", false)) {
                int pct = intent.getIntExtra("percent", 85);
                String sound = intent.getStringExtra("sound_asset");
                triggerAlarm(pct, sound);
            } else if (intent.hasExtra("custom_title")) {
                String title = intent.getStringExtra("custom_title");
                String sub = intent.getStringExtra("custom_sub");
                String sound = intent.getStringExtra("sound_asset");
                triggerAlarmCustom(title, sub, sound);
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
