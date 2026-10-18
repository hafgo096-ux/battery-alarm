package com.persian.batteryalarm.optimizer;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

/**
 * حالت کم‌مصرف (Ultra Saver):
 * در این حالت سرویس دائمی (Foreground Service) خاموش می‌شود و تنها یک Job زمان‌بندی‌شده
 * هر ۱۵ دقیقه بیدار می‌شود. این کار مصرف باتری پس‌زمینه را عملاً به صفر می‌رساند
 * (بدون WakeLock، بدون پایش لحظه‌ای) و در عین حال هشدارهای مهم از دست نمی‌روند.
 */
public class BatteryMonitorJobService extends JobService {

    private static final String TAG = "BatteryMonitorJob";
    public static final int JOB_ID = 4201;
    public static final long PERIOD_MS = 15L * 60L * 1000L;

    public static void schedule(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, BatteryMonitorJobService.class))
                    .setPeriodic(PERIOD_MS)
                    .setPersisted(true)
                    .setRequiresDeviceIdle(false)
                    .setRequiresCharging(false)
                    .build();
            js.schedule(job);
        } catch (Exception e) {
            Log.w(TAG, "schedule failed", e);
        }
    }

    public static void cancel(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js != null) js.cancel(JOB_ID);
        } catch (Exception ignored) {}
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        try {
            final Context ctx = getApplicationContext();

            // در حالت سرویس دائمی، این Job فقط نقش «نگهبان» دارد و سرویس را زنده می‌کند.
            if (!MonitorPrefs.monitoringEnabled(ctx)) {
                cancel(ctx);
                jobFinished(params, false);
                return false;
            }

            if (MonitorPrefs.lowPowerMode(ctx)) {
                Intent battery = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                AlertEngine.Decision d = AlertEngine.evaluate(ctx, battery);
                if (d != null && !BatteryWatchService.isRunning) {
                    AlertEngine.postAlarmNotification(ctx, d);
                    MonitorPrefs.markAlarm(ctx, d.type);
                    playLowPowerSound(ctx, d);
                    Log.i(TAG, "low-power alarm notification: " + d.type);
                }
            } else if (!BatteryWatchService.isRunning) {
                try {
                    Intent svc = new Intent(ctx, BatteryWatchService.class);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(svc);
                    else ctx.startService(svc);
                } catch (Exception e) {
                    Log.w(TAG, "cannot restore watch service from job", e);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "onStartJob failed", e);
        }
        jobFinished(params, false);
        return false;
    }

    /**
     * پخش یک‌باره صدای هشدار در حالت کم‌مصرف (نسخه ۱.۰.۱).
     * بدون حلقه و بدون سرویس دائمی؛ فقط همان پیام فارسی مربوط به هشدار،
     * با احترام به بلندی صدای تعیین‌شده توسط کاربر.
     */
    private void playLowPowerSound(Context ctx, AlertEngine.Decision d) {
        try {
            int volume = MonitorPrefs.alarmVolume(ctx);
            if (volume <= 0 || d == null || d.soundAsset == null || d.soundAsset.isEmpty()) return;
            final float fraction = MonitorPrefs.clamp(volume, 0, 100) / 100f;
            final android.media.MediaPlayer mp = new android.media.MediaPlayer();
            android.content.res.AssetFileDescriptor afd = ctx.getAssets().openFd("audio/" + d.soundAsset);
            try {
                mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            } finally {
                try { afd.close(); } catch (Exception ignored) {}
            }
            mp.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mp.setVolume(fraction, fraction);
            mp.setLooping(false);
            mp.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(android.media.MediaPlayer player) {
                    try { player.release(); } catch (Exception ignored) {}
                }
            });
            mp.setOnErrorListener(new android.media.MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(android.media.MediaPlayer player, int what, int extra) {
                    try { player.release(); } catch (Exception ignored) {}
                    return true;
                }
            });
            mp.prepare();
            mp.start();
        } catch (Exception e) {
            Log.w(TAG, "low-power sound failed", e);
        }
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
