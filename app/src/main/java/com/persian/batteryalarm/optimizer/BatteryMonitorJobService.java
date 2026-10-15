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

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
