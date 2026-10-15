package com.persian.batteryalarm.optimizer;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.List;

/**
 * پل ارتباطی جاوااسکریپت ↔ اندروید نیتیو (نسخه ۱.۰.۱).
 *
 * قابلیت‌های جدید:
 *  - settingsJson() / setSetting(): ذخیره‌سازی دائمی همه تنظیمات در SharedPreferences
 *    (رفع کامل باگ «پاک شدن تنظیمات پس از بستن برنامه»).
 *  - setAlarmVolume()/getAlarmVolume(): کنترل بلندی صدای هشدار از داخل برنامه.
 *  - setMonitoringEnabled()/setLowPowerMode(): مدیریت مصرف باتری توسط کاربر.
 *  - monitorState(): وضعیت زنده برای پنل «مصرف باتری» و اجرای تست‌های واقعی.
 *  - optimizeRam(): مقدار آزادسازی واقعی رم (بدون داده‌سازی).
 *  - deleteFile(): نتیجه واقعی حذف (بدون ادعای دروغین موفقیت).
 */
public class WebBridge {

    private static final String TAG = "WebBridge";
    private static final long RISKY_APPS_TTL_MS = 5L * 60L * 1000L;

    private final Context ctx;

    private String riskyAppsCache = "[]";
    private long riskyAppsCacheAt = 0L;

    public WebBridge(Context ctx) {
        this.ctx = ctx;
    }

    private void startServiceSafely(Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent);
            else ctx.startService(intent);
        } catch (Exception e) {
            Log.w(TAG, "startServiceSafely failed", e);
        }
    }

    private Intent serviceIntent() {
        return new Intent(ctx, BatteryWatchService.class);
    }

    @JavascriptInterface
    public boolean isNative() {
        return true;
    }

    @JavascriptInterface
    public void alarmHeartbeat() {
        // کانال ارتباطی فعال است
    }

    // ================== ذخیره‌سازی دائمی تنظیمات ==================

    @JavascriptInterface
    public String settingsJson() {
        return MonitorPrefs.asJson(ctx).toString();
    }

    @JavascriptInterface
    public String setSetting(String key, String jsonValue) {
        JSONObject res = new JSONObject();
        try {
            boolean ok = MonitorPrefs.applyValue(ctx, key, jsonValue);
            res.put("ok", ok);
            res.put("key", key == null ? "" : key);
            Object applied = MonitorPrefs.asJson(ctx).opt(key);
            res.put("value", applied == null ? JSONObject.NULL : applied);
            Log.i(TAG, "setSetting " + key + " = " + jsonValue + " -> " + ok);
        } catch (Exception e) {
            Log.w(TAG, "setSetting failed", e);
        }
        return res.toString();
    }

    @JavascriptInterface
    public String resetSettings() {
        MonitorPrefs.resetAll(ctx);
        return MonitorPrefs.asJson(ctx).toString();
    }

    @JavascriptInterface
    public int getTargetThreshold() {
        return MonitorPrefs.targetPercent(ctx);
    }

    @JavascriptInterface
    public void setTargetThreshold(int percent) {
        MonitorPrefs.putInt(ctx, MonitorPrefs.TARGET_PERCENT, percent);
    }

    @JavascriptInterface
    public boolean getBoolSetting(String key, boolean defaultVal) {
        if (!MonitorPrefs.isKnownKey(key)) return defaultVal;
        return MonitorPrefs.getBool(ctx, key, defaultVal);
    }

    @JavascriptInterface
    public void setBoolSetting(String key, boolean val) {
        if (MonitorPrefs.isKnownKey(key) && MonitorPrefs.isBooleanKey(key)) MonitorPrefs.putBool(ctx, key, val);
    }

    // ================== کنترل بلندی صدای هشدار ==================

    @JavascriptInterface
    public int getAlarmVolume() {
        return MonitorPrefs.alarmVolume(ctx);
    }

    @JavascriptInterface
    public String setAlarmVolume(int percent, boolean applyToSystem) {
        JSONObject res = new JSONObject();
        try {
            int v = MonitorPrefs.clamp(percent, 0, 100);
            MonitorPrefs.putInt(ctx, MonitorPrefs.ALARM_VOLUME, v);
            boolean systemApplied = false;
            if (applyToSystem && v > 0) {
                try {
                    android.media.AudioManager am =
                            (android.media.AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) {
                        int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM);
                        am.setStreamVolume(android.media.AudioManager.STREAM_ALARM,
                                Math.max(1, Math.round(max * (v / 100f))), 0);
                        systemApplied = true;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "cannot set system alarm volume", e);
                }
            }
            res.put("ok", true);
            res.put("volume", v);
            res.put("systemApplied", systemApplied);
        } catch (Exception e) {
            Log.w(TAG, "setAlarmVolume failed", e);
        }
        return res.toString();
    }

    @JavascriptInterface
    public String testAlarm() {
        JSONObject res = new JSONObject();
        try {
            Intent i = serviceIntent();
            i.putExtra("test_alarm", true);
            startServiceSafely(i);
            res.put("ok", true);
            res.put("volume", MonitorPrefs.alarmVolume(ctx));
        } catch (Exception e) {
                try { res.put("ok", false); } catch (Exception ignored) {}
        }
        return res.toString();
    }

    @JavascriptInterface
    public void stopAlarm() {
        try {
            Intent i = serviceIntent();
            i.putExtra("stop_alarm", true);
            startServiceSafely(i);
        } catch (Exception e) {
            Log.w(TAG, "stop failed", e);
        }
    }

    @JavascriptInterface
    public void playPersianVoice(String alertType) {
        try {
            String soundAsset = "fa_battery_85.mp3";
            String title = "زنگ هوشمند باتری";
            String sub = "شارژ به میزان تعیین‌شده رسید";

            if ("target_85".equals(alertType) || "target".equals(alertType)) {
                soundAsset = MonitorPrefs.voiceEnabled(ctx) ? "fa_battery_85.mp3" : "fa_siren.mp3";
                title = "⚡ شارژ به سقف هدف رسید";
                sub = "جهت طول عمر باتری شارژر را جدا کنید";
            } else if ("full_100".equals(alertType) || "full".equals(alertType)) {
                soundAsset = MonitorPrefs.voiceEnabled(ctx) ? "fa_battery_100.mp3" : "fa_siren.mp3";
                title = "🔋 باتری ۱۰۰٪ شارژ شد";
                sub = "شارژ کامل شد، شارژر را بکشید";
            } else if ("low_20".equals(alertType) || "low".equals(alertType)) {
                soundAsset = MonitorPrefs.voiceEnabled(ctx) ? "fa_battery_low.mp3" : "fa_siren.mp3";
                title = "⚠️ شارژ کم است";
                sub = "لطفاً گوشی را به شارژر وصل کنید";
            } else if ("overheat".equals(alertType)) {
                soundAsset = "fa_battery_hot.mp3";
                title = "🔥 هشدار دمای بالای باتری";
                sub = "دستگاه داغ است، کابل را جدا کنید";
            } else if ("ram_clean".equals(alertType)) {
                soundAsset = "fa_ram_clean.mp3";
                title = "⚡ بهینه‌سازی رم انجام شد";
                sub = "حافظه رم آزاد و سبک شد";
            } else if ("files_clean".equals(alertType)) {
                soundAsset = "fa_files_clean.mp3";
                title = "🗑️ پاکسازی انجام شد";
                sub = "فایل‌های انتخاب‌شده پاک شدند";
            } else if ("siren".equals(alertType) || "unplug".equals(alertType)) {
                soundAsset = "fa_siren.mp3";
                title = "🚨 آژیر هشدار";
                sub = "هشدار بحرانی";
            }

            Intent i = serviceIntent();
            i.putExtra("custom_title", title);
            i.putExtra("custom_sub", sub);
            i.putExtra("sound_asset", soundAsset);
            startServiceSafely(i);
        } catch (Exception e) {
            Log.w(TAG, "playPersianVoice native failed", e);
        }
    }

    // ================== حالت پایش و مصرف باتری ==================

    @JavascriptInterface
    public String monitorState() {
        JSONObject o = new JSONObject();
        try {
            Intent battery = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            boolean enabled = MonitorPrefs.monitoringEnabled(ctx);
            boolean lowPower = MonitorPrefs.lowPowerMode(ctx);
            o.put("enabled", enabled);
            o.put("lowPower", lowPower);
            o.put("serviceRunning", BatteryWatchService.isRunning);
            o.put("mode", !enabled ? "off" : (lowPower ? "powersave" : "foreground"));
            o.put("percent", AlertEngine.percentOf(battery));
            o.put("charging", AlertEngine.chargingOf(battery));
            o.put("temperature", AlertEngine.tempOf(battery));
            o.put("alarmVolume", MonitorPrefs.alarmVolume(ctx));
            o.put("lastAlarmAt", MonitorPrefs.lastAlarmAt(ctx));
            o.put("lastAlarmType", MonitorPrefs.sp(ctx).getString(MonitorPrefs.S_LAST_ALARM_TYPE, ""));
            o.put("alarmCount", MonitorPrefs.sp(ctx).getInt(MonitorPrefs.S_ALARM_COUNT, 0));
            o.put("wakeLocks", 0);
            o.put("pollsPerMinute", lowPower ? 0.07 : 3.0);
            o.put("batteryStateArmed", MonitorPrefs.sp(ctx).getBoolean(MonitorPrefs.S_TARGET_ARMED, true));
            try {
                PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
                o.put("versionName", pi.versionName);
                o.put("versionCode", pi.versionCode);
            } catch (Exception ignored) {}
            o.put("sdk", Build.VERSION.SDK_INT);
        } catch (Exception e) {
            Log.w(TAG, "monitorState failed", e);
        }
        return o.toString();
    }

    @JavascriptInterface
    public String setMonitoringEnabled(boolean enabled) {
        JSONObject res = new JSONObject();
        try {
            MonitorPrefs.putBool(ctx, MonitorPrefs.MONITORING_ENABLED, enabled);
            if (enabled) {
                if (MonitorPrefs.lowPowerMode(ctx)) {
                    BatteryMonitorJobService.schedule(ctx);
                } else {
                    startServiceSafely(serviceIntent());
                }
            } else {
                Intent stop = serviceIntent();
                stop.putExtra("stop_alarm", true);
                startServiceSafely(stop);
                Intent i = new Intent(ctx, BatteryWatchService.class);
                i.putExtra("stop_alarm", true);
                ctx.stopService(i);
                BatteryMonitorJobService.cancel(ctx);
            }
            res.put("ok", true);
            res.put("enabled", MonitorPrefs.monitoringEnabled(ctx));
        } catch (Exception e) {
            Log.w(TAG, "setMonitoringEnabled failed", e);
        }
        return res.toString();
    }

    @JavascriptInterface
    public String setLowPowerMode(boolean lowPower) {
        JSONObject res = new JSONObject();
        try {
            MonitorPrefs.putBool(ctx, MonitorPrefs.LOW_POWER_MODE, lowPower);
            if (lowPower) {
                Intent stop = serviceIntent();
                stop.putExtra("stop_alarm", true);
                startServiceSafely(stop);
                Intent i = new Intent(ctx, BatteryWatchService.class);
                i.putExtra("stop_alarm", true);
                ctx.stopService(i);
                BatteryMonitorJobService.schedule(ctx);
            } else {
                BatteryMonitorJobService.schedule(ctx);
                if (MonitorPrefs.monitoringEnabled(ctx)) startServiceSafely(serviceIntent());
            }
            res.put("ok", true);
            res.put("lowPower", MonitorPrefs.lowPowerMode(ctx));
        } catch (Exception e) {
            Log.w(TAG, "setLowPowerMode failed", e);
        }
        return res.toString();
    }

    // ================== بهینه‌سازی رم (با سنجش واقعی) ==================

    @JavascriptInterface
    public String optimizeRam(String selectedPackagesJson) {
        JSONObject res = new JSONObject();
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo before = new ActivityManager.MemoryInfo();
            if (am != null) am.getMemoryInfo(before);

            int killed = 0;
            JSONArray arr = new JSONArray(selectedPackagesJson != null && !selectedPackagesJson.isEmpty()
                    ? selectedPackagesJson : "[]");
            if (am != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String pkg = arr.optString(i, "");
                    if (!pkg.isEmpty() && !pkg.equals(ctx.getPackageName())) {
                        try {
                            am.killBackgroundProcesses(pkg);
                            killed++;
                        } catch (Exception ignored) {}
                    }
                }
            }

            // اندازه‌گیری واقعی تغییر رم آزاد (بدون هیچ عدد ساختگی)
            SystemClock.sleep(400);
            ActivityManager.MemoryInfo after = new ActivityManager.MemoryInfo();
            if (am != null) am.getMemoryInfo(after);

            long freedBytes = after.availMem - before.availMem; // می‌تواند ۰ یا منفی باشد
            res.put("success", true);
            res.put("freedKb", Math.round(freedBytes / 1024.0));
            res.put("freedMb", Math.round(freedBytes / 1048576.0));
            res.put("killedCount", killed);
            res.put("requestedCount", arr.length());
            res.put("availBeforeMb", Math.round(before.availMem / 1048576.0));
            res.put("availAfterMb", Math.round(after.availMem / 1048576.0));
            res.put("totalMb", Math.round(after.totalMem / 1048576.0));
            res.put("note", killed == 0
                    ? "اندروید اجازه بستن برنامه‌های دیگر را به این اپ نمی‌دهد (KILL_BACKGROUND_PROCESSES محدود است)"
                    : "نتیجه واقعی اندازه‌گیری رم آزاد");
        } catch (Exception e) {
            Log.w(TAG, "optimizeRam native error", e);
            try { res.put("success", false); } catch (Exception ignored) {}
        }
        return res.toString();
    }

    // ================== حذف فایل با گزارش صادقانه ==================

    @JavascriptInterface
    public String deleteFile(String path) {
        JSONObject res = new JSONObject();
        try {
            res.put("path", path == null ? "" : path);
            if (path == null || path.isEmpty()) {
                res.put("ok", false);
                res.put("reason", "empty_path");
                return res.toString();
            }
            File f = new File(path);
            if (!f.exists()) {
                res.put("ok", false);
                res.put("exists", false);
                res.put("reason", "not_found");
                return res.toString();
            }
            boolean deleted = f.delete();
            res.put("ok", deleted);
            res.put("exists", true);
            res.put("reason", deleted ? "deleted" : "permission_denied_or_scoped_storage");
            Log.i(TAG, "deleteFile result=" + deleted + " path=" + path);
        } catch (Exception e) {
            try {
                res.put("ok", false);
                res.put("reason", "exception");
            } catch (Exception ignored) {}
        }
        return res.toString();
    }

    // ================== شناسایی برنامه‌های پرخطر (با کش ۵ دقیقه‌ای) ==================

    @JavascriptInterface
    public String getRiskyApps() {
        long now = System.currentTimeMillis();
        if ((now - riskyAppsCacheAt) < RISKY_APPS_TTL_MS && !"[]".equals(riskyAppsCache)) {
            return riskyAppsCache;
        }
        JSONArray res = new JSONArray();
        try {
            PackageManager pm = ctx.getPackageManager();
            List<PackageInfo> pkgs = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS);
            for (PackageInfo pi : pkgs) {
                if (pi.applicationInfo == null) continue;
                if ((pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                String[] reqPerms = pi.requestedPermissions;
                if (reqPerms == null) continue;
                boolean hasSms = false, hasLoc = false, hasCam = false, hasOverlay = false;
                for (String p : reqPerms) {
                    if (p == null) continue;
                    if (p.contains("SEND_SMS") || p.contains("READ_SMS")) hasSms = true;
                    if (p.contains("ACCESS_FINE_LOCATION") || p.contains("ACCESS_BACKGROUND_LOCATION")) hasLoc = true;
                    if (p.contains("CAMERA")) hasCam = true;
                    if (p.contains("SYSTEM_ALERT_WINDOW")) hasOverlay = true;
                }
                if ((hasSms && hasLoc) || (hasOverlay && hasSms) || (hasCam && hasLoc && hasSms)) {
                    JSONObject app = new JSONObject();
                    app.put("name", pi.applicationInfo.loadLabel(pm).toString());
                    app.put("pkg", pi.packageName);
                    app.put("riskReason", "دسترسی همزمان به پیامک‌ها و موقعیت مکانی / پنجره شناور");
                    app.put("dangerLevel", (hasSms && hasLoc && hasOverlay) ? "بسیار بالا" : "بالا");
                    app.put("size", 0);
                    res.put(app);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "risky apps scan error", e);
        }
        riskyAppsCache = res.toString();
        riskyAppsCacheAt = now;
        return riskyAppsCache;
    }

    @JavascriptInterface
    public String appInfo() {
        JSONObject o = new JSONObject();
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            o.put("versionName", pi.versionName);
            o.put("versionCode", pi.versionCode);
            o.put("package", ctx.getPackageName());
            o.put("sdk", Build.VERSION.SDK_INT);
        } catch (Exception ignored) {}
        return o.toString();
    }
}
