package com.persian.batteryalarm.optimizer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * سرویس پایش هوشمند باتری (نسخه ۱.۰.۱ - بهینه‌سازی جدی مصرف باتری).
 *
 * اصلاحات کلیدی نسبت به نسخه قبلی:
 *  ۱. حذف کامل WakeLock دائمی و Timer نگهبان (بزرگ‌ترین عامل مصرف باتری در نسخه قبل).
 *  ۲. پایش فقط رویدادمحور با ACTION_BATTERY_CHANGED (بدون Polling).
 *  ۳. کنترل کامل بلندی صدای هشدار از داخل برنامه (alarm_volume).
 *  ۴. توقف خودکار زنگ پس از مدت تعیین‌شده توسط کاربر (جلوگیری از پخش بی‌پایان).
 *  ۵. احترام به «حالت کم‌مصرف» و «غیرفعال بودن پایش».
 */
public class BatteryWatchService extends Service {

    private static final String TAG = "BatteryWatchService";
    public static final String CH_WATCH = "persian_battery_watch";
    public static final String CH_ALARM = "persian_battery_alarm";
    public static final int NOTIF_WATCH = 777;
    public static final int NOTIF_ALARM = 778;

    /** برای اینکه Job نگهبان بداند سرویس زنده است (بدون هیچ Polling). */
    public static volatile boolean isRunning = false;

    private MediaPlayer mediaPlayer;
    private ToneGenerator toneGenerator;
    private Vibrator vibrator;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable autoStopRunnable;
    private boolean receiverRegistered = false;
    private boolean alarmActive = false;
    private int lastNotifiedPercent = -1;
    private boolean lastNotifiedCharging = false;
    private long lastNotificationAt = 0L;

    private final BatteryReceiver batteryReceiver = new BatteryReceiver();

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        createChannels();

        Notification watchNotif = buildWatchNotification("پایش هوشمند باتری فعال است", -1, false);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_WATCH, watchNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_WATCH, watchNotif);
            }
        } catch (Exception e) {
            Log.w(TAG, "startForeground failed", e);
        }

        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        try {
            Intent stickyIntent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                stickyIntent = registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED);
            } else {
                stickyIntent = registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            }
            receiverRegistered = true;
            if (stickyIntent != null) {
                handleBatteryIntent(stickyIntent);
            }
        } catch (Exception e) {
            Log.w(TAG, "failed to register receiver", e);
        }

        // Job نگهبان (هیچ WakeLock یا Timer در این نسخه وجود ندارد).
        BatteryMonitorJobService.schedule(this);
        Log.i(TAG, "BatteryWatchService created (event-driven, no wakelock)");
    }

    /** تنها مسیر پردازش داده باتری؛ توسط BroadcastReceiver صدا زده می‌شود. */
    public void handleBatteryIntent(Intent intent) {
        try {
            int percent = AlertEngine.percentOf(intent);
            if (percent < 0) return;
            boolean charging = AlertEngine.chargingOf(intent);
            updateNotificationIfNeeded(percent, charging);

            if (MonitorPrefs.monitoringEnabled(this)) {
                AlertEngine.Decision decision = AlertEngine.evaluate(this, intent);
                if (decision != null) startAlarm(decision, false);
            }
        } catch (Exception e) {
            Log.w(TAG, "handleBatteryIntent failed", e);
        }
    }

    private void updateNotificationIfNeeded(int percent, boolean charging) {
        long now = System.currentTimeMillis();
        boolean changed = percent != lastNotifiedPercent || charging != lastNotifiedCharging;
        if (!changed && (now - lastNotificationAt) < 30_000L) return;
        if (!changed && alarmActive) return;
        lastNotifiedPercent = percent;
        lastNotifiedCharging = charging;
        lastNotificationAt = now;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_WATCH, buildWatchNotification(
                    (charging ? "در حال شارژ - " : "مصرف باتری - ") + percent + "٪", percent, charging));
        } catch (Exception ignored) {}
    }

    public void startAlarm(AlertEngine.Decision d, boolean testMode) {
        if (d == null) return;
        long now = System.currentTimeMillis();
        if (!testMode) {
            long cooldown = MonitorPrefs.cooldownSec(this) * 1000L;
            boolean safety = "overheat".equals(d.type);
            if (!safety && (now - MonitorPrefs.lastAlarmAt(this)) < cooldown) {
                Log.i(TAG, "alarm suppressed by cooldown: " + d.type);
                return;
            }
        }

        cancelAutoStop();
        alarmActive = true;
        MonitorPrefs.markAlarm(this, d.type);
        AlertEngine.postAlarmNotification(this, d);
        if (d.vibrate && MonitorPrefs.vibrateEnabled(this)) startVibration();

        int volume = MonitorPrefs.alarmVolume(this);
        if (volume <= 0) {
            Log.i(TAG, "alarm volume is 0 (muted by user) - notification only");
        } else {
            playReliableAlarmSound(d.soundAsset, testMode);
        }

        int autoStop = testMode ? 6 : MonitorPrefs.autoStopSec(this);
        if (autoStop > 0) {
            final int seconds = autoStop;
            autoStopRunnable = new Runnable() {
                @Override
                public void run() {
                    Log.i(TAG, "auto stopping alarm after " + seconds + "s");
                    stopAlarm();
                    // اگر پایش خاموش است، سرویس فقط برای تست روشن شده بود؛ پس خودش را ببندد
                    if (!MonitorPrefs.monitoringEnabled(BatteryWatchService.this)) {
                        stopForeground(true);
                        stopSelf();
                    }
                }
            };
            handler.postDelayed(autoStopRunnable, autoStop * 1000L);
        }
    }

    private void cancelAutoStop() {
        if (autoStopRunnable != null) {
            handler.removeCallbacks(autoStopRunnable);
            autoStopRunnable = null;
        }
    }

    /** پخش صدا با احترام کامل به «بلندی صدای تعیین‌شده توسط کاربر». */
    public synchronized void playReliableAlarmSound(String assetName, boolean testMode) {
        stopMediaPlayer();

        int volumePercent = MonitorPrefs.alarmVolume(this);
        final float fraction = MonitorPrefs.clamp(volumePercent, 0, 100) / 100f;

        if (MonitorPrefs.forceSystemVolume(this)) {
            try {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    int maxVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                    am.setStreamVolume(AudioManager.STREAM_ALARM, Math.round(maxVol * fraction), 0);
                }
            } catch (Exception e) {
                Log.w(TAG, "setting system alarm volume failed (needs MODIFY_AUDIO_SETTINGS)", e);
            }
        }

        boolean played = false;
        if (assetName != null && !assetName.isEmpty()) {
            try {
                String path = "audio/" + assetName;
                mediaPlayer = new MediaPlayer();
                try {
                    AssetFileDescriptor afd = getAssets().openFd(path);
                    mediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                    afd.close();
                } catch (Exception fdEx) {
                    File tempSound = new File(getCacheDir(), "cache_" + assetName);
                    if (!tempSound.exists()) {
                        try (InputStream in = getAssets().open(path);
                             OutputStream out = new FileOutputStream(tempSound)) {
                            byte[] buf = new byte[8192];
                            int r;
                            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                        }
                    }
                    mediaPlayer.setDataSource(tempSound.getAbsolutePath());
                }

                mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
                mediaPlayer.setVolume(fraction, fraction);
                mediaPlayer.setLooping(false);
                final boolean allowSiren = MonitorPrefs.sirenLoop(this);
                mediaPlayer.setOnCompletionListener(mp -> {
                    if (!testMode && allowSiren && alarmActive) playSirenLoop(fraction);
                });
                mediaPlayer.prepare();
                mediaPlayer.start();
                played = true;
            } catch (Exception e) {
                Log.w(TAG, "Asset playback failed: " + assetName + ", falling back", e);
            }
        }

        if (!played) {
            try {
                Uri uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM);
                if (uri == null) uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE);
                if (uri == null) uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_NOTIFICATION);
                if (uri != null) {
                    mediaPlayer = new MediaPlayer();
                    mediaPlayer.setDataSource(this, uri);
                    mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build());
                    mediaPlayer.setVolume(fraction, fraction);
                    mediaPlayer.setLooping(true);
                    mediaPlayer.prepare();
                    mediaPlayer.start();
                    played = true;
                }
            } catch (Exception e) {
                Log.w(TAG, "System ringtone failed, using tone generator", e);
            }
        }

        if (!played) {
            try {
                toneGenerator = new ToneGenerator(AudioManager.STREAM_ALARM,
                        MonitorPrefs.clamp((int) (fraction * 100f), 1, 100));
                toneGenerator.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 15000);
            } catch (Exception e) {
                Log.e(TAG, "ToneGenerator failed", e);
            }
        }
    }

    private void playSirenLoop(float fraction) {
        if (!alarmActive) return;
        try {
            stopMediaPlayer();
            mediaPlayer = new MediaPlayer();
            try {
                AssetFileDescriptor afd = getAssets().openFd("audio/fa_siren.mp3");
                mediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                afd.close();
            } catch (Exception fdEx) {
                File tempSound = new File(getCacheDir(), "cache_fa_siren.mp3");
                if (!tempSound.exists()) {
                    try (InputStream in = getAssets().open("audio/fa_siren.mp3");
                         OutputStream out = new FileOutputStream(tempSound)) {
                        byte[] buf = new byte[8192];
                        int r;
                        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                    }
                }
                mediaPlayer.setDataSource(tempSound.getAbsolutePath());
            }
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mediaPlayer.setVolume(fraction, fraction);
            mediaPlayer.setLooping(true);
            mediaPlayer.prepare();
            mediaPlayer.start();
        } catch (Exception e) {
            Log.w(TAG, "siren loop failed", e);
        }
    }

    private void startVibration() {
        try {
            if (vibrator == null || !vibrator.hasVibrator()) return;
            long[] pattern = {0, 500, 250, 500, 250, 700};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                vibrator.vibrate(pattern, -1);
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
        cancelAutoStop();
        stopMediaPlayer();
        alarmActive = false;
        if (vibrator != null) {
            try { vibrator.cancel(); } catch (Exception ignored) {}
        }
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIF_ALARM);
        } catch (Exception ignored) {}
        Log.i(TAG, "alarm stopped");
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            NotificationChannel watch = new NotificationChannel(
                    CH_WATCH, "پایش مداوم باتری و سیستم", NotificationManager.IMPORTANCE_MIN);
            watch.setDescription("اعلان زنده ماندن سرویس پایش هوشمند باتری (بدون صدا)");
            watch.setShowBadge(false);
            nm.createNotificationChannel(watch);

            NotificationChannel alarm = new NotificationChannel(
                    CH_ALARM, "زنگ هشدار باتری", NotificationManager.IMPORTANCE_HIGH);
            alarm.setDescription("زنگ هشدار صوتی هنگام رسیدن به شارژ هدف یا دمای بحرانی");
            alarm.enableVibration(true);
            nm.createNotificationChannel(alarm);
        } catch (Exception e) {
            Log.w(TAG, "createChannels failed", e);
        }
    }

    private Notification buildWatchNotification(String text, int percent, boolean charging) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CH_WATCH)
                : new Notification.Builder(this);

        builder.setContentTitle("زنگ هوشمند باتری")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(openPending)
                .setPriority(Notification.PRIORITY_MIN);

        if (percent >= 0) {
            builder.setSubText(charging ? "شارژ" : "مصرف");
        }
        return builder.build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            boolean wantsSound = false;
            if (intent != null) {
                if (intent.getBooleanExtra("stop_alarm", false)) {
                    stopAlarm();
                } else if (intent.getBooleanExtra("test_alarm", false)) {
                    wantsSound = true;
                    AlertEngine.Decision d = new AlertEngine.Decision();
                    d.type = "test";
                    d.title = "🔔 تست زنگ هشدار";
                    d.sub = "بلندی صدا: " + MonitorPrefs.alarmVolume(this) + "٪";
                    d.soundAsset = "fa_battery_85.mp3";
                    d.vibrate = false;
                    startAlarm(d, true);
                } else if (intent.getBooleanExtra("native_alarm", false)) {
                    wantsSound = true;
                    AlertEngine.Decision d = new AlertEngine.Decision();
                    d.type = "manual";
                    d.percent = intent.getIntExtra("percent", MonitorPrefs.targetPercent(this));
                    String sound = intent.getStringExtra("sound_asset");
                    d.soundAsset = sound != null ? sound : "fa_battery_85.mp3";
                    d.title = "⚡ هشدار شارژ باتری";
                    d.sub = "هشدار دستی از داخل برنامه";
                    startAlarm(d, false);
                } else if (intent.hasExtra("custom_title")) {
                    wantsSound = true;
                    AlertEngine.Decision d = new AlertEngine.Decision();
                    d.type = "custom";
                    d.title = intent.getStringExtra("custom_title");
                    d.sub = intent.getStringExtra("custom_sub");
                    String sound = intent.getStringExtra("sound_asset");
                    d.soundAsset = sound != null ? sound : "fa_battery_low.mp3";
                    startAlarm(d, false);
                }
            }

            // اگر کاربر صراحتاً درخواست پخش صدا (تست/صدای فارسی) کرده، حتی با پایش
            // خاموش هم صدا پخش شود و سرویس بلافاصله خودکشی نکند (توقف خودکار،
            // سرویس را هم بعداً می‌بندد). در غیر این صورت، سرویس متوقف می‌شود.
            if (!MonitorPrefs.monitoringEnabled(this) && !wantsSound) {
                Log.i(TAG, "monitoring disabled -> stopping service");
                stopAlarm();
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            }
        } catch (Exception e) {
            Log.w(TAG, "onStartCommand failed", e);
        }
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // بدون WakeLock و بدون ری‌استارت تهاجمی: Job نگهبان (هر ۱۵ دقیقه) در صورت نیاز سرویس را برمی‌گرداند.
        if (MonitorPrefs.monitoringEnabled(this)) BatteryMonitorJobService.schedule(this);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        stopAlarm();
        if (receiverRegistered) {
            try {
                unregisterReceiver(batteryReceiver);
            } catch (Exception ignored) {}
            receiverRegistered = false;
        }
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIF_WATCH);
        } catch (Exception ignored) {}
        super.onDestroy();
    }
}
