package com.dsh.batteryalarm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Vibrator;
import android.util.Log;

/**
 * سرویس پس‌زمینه که شارژ باتری را مستقل از رابط گرافیکی پایش می‌کند.
 * حتی اگر برنامه بسته شود، این سرویس فعال می‌ماند و در ۸۵٪ زنگ می‌زند.
 */
public class BatteryWatchService extends Service {

    private static final String TAG = "BatteryWatch";
    private static final String CHANNEL_ID = "battery_watch_channel";
    private static final int NOTIF_ID = 777;
    private static final int TARGET_PERCENT = 85;

    private PowerManager.WakeLock wakeLock;
    private MediaPlayer mediaPlayer;
    private boolean alarmFired = false;

    static class BatteryReceiver extends BroadcastReceiver {
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
            if (charging && percent >= TARGET_PERCENT && !svc.alarmFired) {
                svc.alarmFired = true;
                svc.triggerAlarm(percent);
            }
            if (!charging) {
                svc.alarmFired = false;
                svc.stopAlarm();
            }
        }
    }

    private final BatteryReceiver batteryReceiver = new BatteryReceiver();

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("پایش شارژ فعال است"));

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dsh:batterywatch");
            wakeLock.acquire(10 * 60 * 1000L);
        }
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        Log.i(TAG, "service started");
    }

    void triggerAlarm(int percent) {
        Log.i(TAG, "ALARM at " + percent + "%");
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID + 1, buildNotification("⚡ شارژ به " + percent + "٪ رسید!"));
        }
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            long[] pattern = {0, 400, 200, 400, 200, 400};
            v.vibrate(pattern, 0);
        }
        playAlarmSound();
    }

    private void playAlarmSound() {
        try {
            if (mediaPlayer != null) {
                mediaPlayer.release();
                mediaPlayer = null;
            }
            android.net.Uri uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM);
            if (uri == null) {
                uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_NOTIFICATION);
            }
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
            }
        } catch (Exception e) {
            Log.e(TAG, "play failed", e);
        }
    }

    void stopAlarm() {
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null) v.cancel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "پایش شارژ باتری", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("اعلان‌های هشدار شارژ ۸۵٪");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("هشدار شارژ باتری")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                .setOngoing(true);
        return b.build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopAlarm();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        unregisterReceiver(batteryReceiver);
        super.onDestroy();
    }
}
