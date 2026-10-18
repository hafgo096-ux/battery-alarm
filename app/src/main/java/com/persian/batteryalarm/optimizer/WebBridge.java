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
import java.util.ArrayList;
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
 *  - getAppCaches()/getLargeMedia()/getOwnStorage()/freeOwnMemory(): اسکن واقعی
 *    کش برنامه‌ها، فایل‌های حجیم و پاکسازی امن کش خود برنامه (نسخه ۱.۰.۱).
 *  - openAppInfo()/openInFilesApp()/openUsageAccessSettings()/requestMediaPermission():
 *    هدایت کاربر به صفحات سیستمی اندروید برای مدیریت امن فایل‌ها.
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
    // رفع باگ نسخه ۱.۰.۱: قبلاً فقط requestedPermissions (درخواست manifest) بررسی می‌شد.
    // در اندروید ۶+ (API 23+) دسترسی‌های خطرناک نیاز به runtime permission دارند.
    // الان با checkPermission بررسی می‌کنیم که آیا کاربر واقعاً دسترسی داده یا نه.

    private boolean hasGrantedPermission(PackageManager pm, String pkg, String perm) {
        try {
            return pm.checkPermission(perm, pkg) == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isRuntimePermission(String perm) {
        // دسترسی‌هایی که نیاز به تایید صریح کاربر دارند (dangerous permissions)
        if (perm == null) return false;
        return perm.contains("SEND_SMS") || perm.contains("READ_SMS") || perm.contains("RECEIVE_SMS")
                || perm.contains("ACCESS_FINE_LOCATION") || perm.contains("ACCESS_COARSE_LOCATION")
                || perm.contains("ACCESS_BACKGROUND_LOCATION")
                || perm.contains("CAMERA")
                || perm.contains("RECORD_AUDIO")
                || perm.contains("READ_CONTACTS") || perm.contains("WRITE_CONTACTS")
                || perm.contains("READ_CALL_LOG") || perm.contains("WRITE_CALL_LOG")
                || perm.contains("READ_PHONE_STATE") || perm.contains("READ_PHONE_NUMBERS")
                || perm.contains("READ_EXTERNAL_STORAGE") || perm.contains("WRITE_EXTERNAL_STORAGE")
                || perm.contains("READ_MEDIA_IMAGES") || perm.contains("READ_MEDIA_VIDEO")
                || perm.contains("BODY_SENSORS") || perm.contains("ACTIVITY_RECOGNITION");
    }

    @JavascriptInterface
    public String getRiskyApps() {
        long now = System.currentTimeMillis();
        if ((now - riskyAppsCacheAt) < RISKY_APPS_TTL_MS && !"[]".equals(riskyAppsCache)) {
            return riskyAppsCache;
        }
        JSONArray high = new JSONArray();
        JSONArray medium = new JSONArray();
        try {
            PackageManager pm = ctx.getPackageManager();
            List<PackageInfo> pkgs = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS);
            for (PackageInfo pi : pkgs) {
                if (pi.applicationInfo == null) continue;
                if ((pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                if (pi.packageName.equals(ctx.getPackageName())) continue;
                String[] reqPerms = pi.requestedPermissions;
                if (reqPerms == null) continue;

                // بررسی دسترسی‌هایی که کاربر واقعاً تایید کرده (runtime permission granted)
                boolean hasSms = false, hasLoc = false, hasCam = false, hasOverlay = false,
                        hasMic = false, hasContacts = false, hasCallLog = false;
                int grantedCount = 0;

                for (String p : reqPerms) {
                    if (p == null) continue;
                    boolean granted = false;
                    // برای دسترسی‌های runtime (dangerous)، بررسی grant واقعی
                    if (isRuntimePermission(p)) {
                        granted = hasGrantedPermission(pm, pi.packageName, p);
                    } else {
                        // دسترسی‌های normal/dangerous غیر-runtime (مثل SYSTEM_ALERT_WINDOW)
                        // این‌ها با checkPermission قابل بررسی‌اند
                        granted = hasGrantedPermission(pm, pi.packageName, p);
                    }

                    if (!granted) continue; // دسترسی تایید نشده ← نادیده بگیر

                    grantedCount++;
                    if (p.contains("SEND_SMS") || p.contains("READ_SMS") || p.contains("RECEIVE_SMS")) hasSms = true;
                    if (p.contains("ACCESS_FINE_LOCATION") || p.contains("ACCESS_COARSE_LOCATION")
                            || p.contains("ACCESS_BACKGROUND_LOCATION")) hasLoc = true;
                    if (p.contains("CAMERA")) hasCam = true;
                    if (p.contains("SYSTEM_ALERT_WINDOW")) hasOverlay = true;
                    if (p.contains("RECORD_AUDIO")) hasMic = true;
                    if (p.contains("READ_CONTACTS") || p.contains("WRITE_CONTACTS")) hasContacts = true;
                    if (p.contains("READ_CALL_LOG") || p.contains("WRITE_CALL_LOG")) hasCallLog = true;
                }

                int sensitive = 0;
                if (hasSms) sensitive++;
                if (hasLoc) sensitive++;
                if (hasCam) sensitive++;
                if (hasOverlay) sensitive++;
                if (hasMic) sensitive++;
                if (hasContacts) sensitive++;
                if (hasCallLog) sensitive++;

                // اگر هیچ دسترسی حساسی واقعاً تایید نشده، از لیست حذف کن
                if (sensitive == 0) continue;

                boolean critical = (hasSms && hasLoc) || (hasOverlay && hasSms)
                        || (hasCam && hasLoc && hasSms);
                if (!critical && sensitive < 2) continue;

                List<String> parts = new ArrayList<>();
                if (hasSms) parts.add("پیامک‌ها ✅تاییدشده");
                if (hasLoc) parts.add("موقعیت مکانی ✅تاییدشده");
                if (hasCam) parts.add("دوربین ✅تاییدشده");
                if (hasMic) parts.add("میکروفون ✅تاییدشده");
                if (hasContacts) parts.add("مخاطبان ✅تاییدشده");
                if (hasCallLog) parts.add("سوابق تماس ✅تاییدشده");
                if (hasOverlay) parts.add("پنجره شناور ✅تاییدشده");
                StringBuilder sb = new StringBuilder("دسترسی فعال و تاییدشده: ");
                for (int i = 0; i < parts.size(); i++) {
                    if (i > 0) sb.append("، ");
                    sb.append(parts.get(i));
                }
                String reason = sb.toString();
                String danger = critical
                        ? ((hasSms && hasLoc && hasOverlay) ? "بسیار بالا" : "بالا")
                        : "متوسط";

                JSONObject app = new JSONObject();
                app.put("name", pi.applicationInfo.loadLabel(pm).toString());
                app.put("pkg", pi.packageName);
                app.put("riskReason", reason);
                app.put("note", reason);
                app.put("dangerLevel", danger);
                app.put("sizeText", "خطر: " + danger);
                app.put("action", "appinfo");
                app.put("path", pi.packageName);
                app.put("size", 0);
                (critical ? high : medium).put(app);
            }
        } catch (Exception e) {
            Log.w(TAG, "risky apps scan error", e);
        }
        JSONArray res = new JSONArray();
        for (int i = 0; i < high.length() && res.length() < 40; i++) {
            try { res.put(high.get(i)); } catch (Exception ignored) {}
        }
        for (int i = 0; i < medium.length() && res.length() < 40; i++) {
            try { res.put(medium.get(i)); } catch (Exception ignored) {}
        }
        riskyAppsCache = res.toString();
        riskyAppsCacheAt = now;
        return riskyAppsCache;
    }

    // ================== کش واقعی برنامه‌ها (StorageStatsManager) ==================

    private static String formatSizeFa(long bytes) {
        if (bytes >= 1073741824L) {
            return String.format(java.util.Locale.US, "%.1f GB", bytes / 1073741824.0);
        }
        if (bytes >= 1048576L) {
            return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
        }
        if (bytes >= 1024L) return (bytes / 1024L) + " KB";
        return bytes + " B";
    }

    @JavascriptInterface
    public String getAppCaches() {
        JSONArray res = new JSONArray();
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                JSONObject info = new JSONObject();
                info.put("name", "نمایش کش برنامه‌ها نیاز به اندروید ۸ به بالا دارد");
                info.put("note", "نسخه اندروید این دستگاه قدیمی‌تر است");
                info.put("action", "none");
                res.put(info);
                return res.toString();
            }

            // باگ‌فیکس: بررسی دسترسی USAGE_ACCESS قبل از استفاده از StorageStatsManager
            // اگر کاربر این دسترسی را نداده، پیام راهنما نشان داده می‌شود
            android.app.AppOpsManager appOps = (android.app.AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            int mode = android.app.AppOpsManager.MODE_DEFAULT;
            try {
                mode = appOps.checkOpNoThrow(
                        android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(), ctx.getPackageName());
            } catch (Exception e) {
                Log.w(TAG, "appOps check failed", e);
            }

            if (mode != android.app.AppOpsManager.MODE_ALLOWED) {
                JSONObject grant = new JSONObject();
                grant.put("name", "برای مشاهده کش برنامه‌ها، دسترسی «آمار مصرف» لازم است");
                grant.put("note", "روی دکمه زیر بزنید، این برنامه را در لیست روشن کنید و برگردید");
                grant.put("action", "grant");
                res.put(grant);
                return res.toString();
            }

            android.app.usage.StorageStatsManager ssm =
                    (android.app.usage.StorageStatsManager) ctx.getSystemService(Context.STORAGE_STATS_SERVICE);
            if (ssm == null) {
                JSONObject info = new JSONObject();
                info.put("name", "سرویس آمار حافظه در دسترس نیست");
                info.put("note", "StorageStatsManager روی این دستگاه پشتیبانی نمی‌شود");
                info.put("action", "none");
                res.put(info);
                return res.toString();
            }

            java.util.UUID uuid = android.os.storage.StorageManager.UUID_DEFAULT;
            PackageManager pm = ctx.getPackageManager();
            List<PackageInfo> pkgs = pm.getInstalledPackages(0);

            List<JSONObject> found = new ArrayList<>();
            for (PackageInfo pi : pkgs) {
                if (pi.packageName.equals(ctx.getPackageName())) continue;
                try {
                    android.app.usage.StorageStats stats = ssm.queryStatsForPackage(
                            uuid, pi.packageName, android.os.Process.myUserHandle());
                    if (stats == null) continue;
                    long cache = stats.getCacheBytes();
                    if (cache >= 512L * 1024L) {
                        String label = pi.packageName;
                        try {
                            if (pi.applicationInfo != null) label = pi.applicationInfo.loadLabel(pm).toString();
                        } catch (Exception ignored) {}
                        JSONObject o = new JSONObject();
                        o.put("name", label);
                        o.put("pkg", pi.packageName);
                        o.put("sizeMb", Math.round(cache / 1048576.0));
                        o.put("sizeText", formatSizeFa(cache));
                        o.put("note", "کش موقت — برای پاکسازی از تنظیمات اندروید اقدام کنید");
                        o.put("action", "appinfo");
                        o.put("path", pi.packageName);
                        o.put("_cache", cache);
                        found.add(o);
                    }
                } catch (SecurityException se) {
                    // باگ‌فیکس: SecurityException یعنی دسترسی نداریم → پیام راهنما
                    Log.w(TAG, "StorageStats SecurityException for " + pi.packageName, se);
                    JSONObject grant = new JSONObject();
                    grant.put("name", "دسترسی آمار مصرف لازم است");
                    grant.put("note", "برای مشاهده کش واقعی برنامه‌ها، دسترسی «آمار مصرف» را فعال کنید");
                    grant.put("action", "grant");
                    res.put(grant);
                    return res.toString();
                } catch (Exception ignored) {}
            }
            java.util.Collections.sort(found, new java.util.Comparator<JSONObject>() {
                @Override
                public int compare(JSONObject a, JSONObject b) {
                    return Long.compare(b.optLong("_cache", 0), a.optLong("_cache", 0));
                }
            });
            int n = 0;
            for (JSONObject o : found) {
                if (n++ >= 25) break;
                o.remove("_cache");
                res.put(o);
            }
        } catch (Exception e) {
            Log.w(TAG, "getAppCaches failed", e);
        }
        return res.toString();
    }

    // ================== فایل‌های حجیم رسانه‌ای (MediaStore) ==================

    private boolean hasMediaPermission() {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                return ctx.checkSelfPermission("android.permission.READ_MEDIA_IMAGES")
                                == PackageManager.PERMISSION_GRANTED
                        || ctx.checkSelfPermission("android.permission.READ_MEDIA_VIDEO")
                                == PackageManager.PERMISSION_GRANTED;
            }
            return ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    private List<JSONObject> queryLargeMediaList(android.net.Uri table, long minBytes) {
        List<JSONObject> out = new ArrayList<>();
        String[] proj = {
                android.provider.MediaStore.MediaColumns._ID,
                android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                android.provider.MediaStore.MediaColumns.SIZE
        };
        String sel = android.provider.MediaStore.MediaColumns.SIZE + " >= ?";
        String[] args = { String.valueOf(minBytes) };
        android.database.Cursor c = null;
        try {
            c = ctx.getContentResolver().query(
                    table, proj, sel, args, android.provider.MediaStore.MediaColumns.SIZE + " DESC");
            if (c == null) return out;
            int idCol = c.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns._ID);
            int nameCol = c.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.DISPLAY_NAME);
            int sizeCol = c.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.SIZE);
            int count = 0;
            while (c.moveToNext() && count < 30) {
                long id = c.getLong(idCol);
                String name = c.getString(nameCol);
                long size = c.getLong(sizeCol);
                android.net.Uri uri = android.content.ContentUris.withAppendedId(table, id);
                JSONObject o = new JSONObject();
                o.put("name", (name != null && !name.isEmpty()) ? name : "فایل رسانه‌ای");
                o.put("path", uri.toString());
                o.put("sizeMb", Math.round(size / 1048576.0));
                o.put("sizeText", formatSizeFa(size));
                o.put("note", "برای حذف از برنامه مدیریت فایل خود اندروید استفاده کنید");
                o.put("action", "media");
                o.put("_size", size);
                out.add(o);
                count++;
            }
        } catch (Exception e) {
            Log.w(TAG, "media query failed", e);
        } finally {
            if (c != null) {
                try { c.close(); } catch (Exception ignored) {}
            }
        }
        return out;
    }

    @JavascriptInterface
    public String getLargeMedia(int minMb) {
        JSONArray res = new JSONArray();
        try {
            if (!hasMediaPermission()) {
                JSONObject g = new JSONObject();
                g.put("name", "برای مشاهده فایل‌های حجیم، دسترسی به رسانه‌ها لازم است");
                g.put("note", "اجازه دسترسی به عکس‌ها و ویدیوها را تأیید کنید و دوباره اسکن کنید");
                g.put("action", "grant_media");
                res.put(g);
                return res.toString();
            }
            long minBytes = Math.max(1, minMb) * 1024L * 1024L;
            List<JSONObject> all = new ArrayList<>();
            all.addAll(queryLargeMediaList(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, minBytes));
            all.addAll(queryLargeMediaList(
                    android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, minBytes));
            java.util.Collections.sort(all, new java.util.Comparator<JSONObject>() {
                @Override
                public int compare(JSONObject a, JSONObject b) {
                    return Long.compare(b.optLong("_size", 0), a.optLong("_size", 0));
                }
            });
            int n = 0;
            for (JSONObject o : all) {
                if (n++ >= 30) break;
                o.remove("_size");
                res.put(o);
            }
        } catch (Exception e) {
            Log.w(TAG, "getLargeMedia failed", e);
        }
        return res.toString();
    }

    // ================== کش خود برنامه + آزادسازی امن ==================

    private static long dirSize(File dir) {
        long total = 0;
        if (dir == null || !dir.exists()) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            try {
                if (f.isDirectory()) total += dirSize(f);
                else total += f.length();
            } catch (Exception ignored) {}
        }
        return total;
    }

    private static long deleteChildren(File dir) {
        long cleared = 0;
        if (dir == null || !dir.exists()) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            try {
                if (f.isDirectory()) {
                    cleared += deleteChildren(f);
                    f.delete();
                } else {
                    cleared += f.length();
                    f.delete();
                }
            } catch (Exception ignored) {}
        }
        return cleared;
    }

    @JavascriptInterface
    public String getOwnStorage() {
        JSONArray res = new JSONArray();
        try {
            long cacheBytes = dirSize(ctx.getCacheDir());
            File extCache = ctx.getExternalCacheDir();
            if (extCache != null) cacheBytes += dirSize(extCache);
            JSONObject o = new JSONObject();
            o.put("name", "کش موقت این برنامه");
            o.put("path", ctx.getCacheDir().getAbsolutePath());
            o.put("sizeMb", Math.round(cacheBytes / 104857.6) / 10.0);
            o.put("sizeText", formatSizeFa(cacheBytes));
            o.put("note", "پاکسازی امن: فقط فایل‌های موقت خود برنامه پاک می‌شود");
            o.put("action", "owncache");
            res.put(o);
        } catch (Exception e) {
            Log.w(TAG, "getOwnStorage failed", e);
        }
        return res.toString();
    }

    @JavascriptInterface
    public String freeOwnMemory() {
        JSONObject res = new JSONObject();
        try {
            // باگ‌فیکس ۱.۰.۱ → ۱: قبلاً freedBytes از availMem محاسبه می‌شد
            // که شامل reclaimable cache سیستم بود و عدد نادرست می‌داد.
            // الان فقط اندازه واقعی فایل‌های کش پاک‌شده گزارش می‌شود.

            // اندازه واقعی کش قبل از پاکسازی
            long cacheBytesBefore = dirSize(ctx.getCacheDir());
            File extCache = ctx.getExternalCacheDir();
            if (extCache != null) cacheBytesBefore += dirSize(extCache);

            // پاکسازی واقعی فایل‌های کش
            long cleared = deleteChildren(ctx.getCacheDir());
            if (extCache != null) cleared += deleteChildren(extCache);

            // gc برای آزادسازی حافظه JVM
            try { Runtime.getRuntime().gc(); } catch (Exception ignored) {}
            SystemClock.sleep(200);

            // اندازه واقعی کش بعد از پاکسازی (باید صفر یا نزدیک صفر باشد)
            long cacheBytesAfter = dirSize(ctx.getCacheDir());
            if (extCache != null) cacheBytesAfter += dirSize(extCache);

            // محاسبه رم آزاد واقعی سیستم (فقط جهت اطلاعات)
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
            if (am != null) am.getMemoryInfo(memInfo);

            // freedBytes = تفاوت واقعی فایل‌های پاک‌شده (نه availMem)
            long actualFreed = cacheBytesBefore - cacheBytesAfter;
            if (actualFreed < 0) actualFreed = 0;

            res.put("ok", true);
            res.put("freedMb", Math.round(actualFreed / 1048576.0));
            res.put("freedKb", Math.round(actualFreed / 1024.0));
            res.put("cacheClearedMb", Math.round(cleared / 1048576.0));
            res.put("cacheClearedKb", Math.round(cleared / 1024.0));
            res.put("cacheBeforeKb", Math.round(cacheBytesBefore / 1024.0));
            res.put("cacheAfterKb", Math.round(cacheBytesAfter / 1024.0));
            res.put("availAfterMb", Math.round(memInfo.availMem / 1048576.0));
            res.put("note", cleared > 0
                    ? "کش با موفقیت پاک شد"
                    : "کش قبلاً خالی بوده یا قابل پاکسازی نبود");
        } catch (Exception e) {
            Log.w(TAG, "freeOwnMemory failed", e);
            try { res.put("ok", false); } catch (Exception ignored) {}
        }
        return res.toString();
    }

    // ================== باز کردن صفحات سیستمی اندروید ==================

    @JavascriptInterface
    public void openAppInfo(String pkg) {
        try {
            if (pkg == null || pkg.isEmpty()) return;
            Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(android.net.Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "openAppInfo failed", e);
        }
    }

    @JavascriptInterface
    public void openInFilesApp(String uriStr) {
        try {
            if (uriStr == null || uriStr.isEmpty()) return;
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(android.net.Uri.parse(uriStr), "*/*");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "openInFilesApp failed", e);
        }
    }

    @JavascriptInterface
    public void openUsageAccessSettings() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "openUsageAccessSettings failed", e);
        }
    }

    @JavascriptInterface
    public String requestMediaPermission() {
        JSONObject res = new JSONObject();
        try {
            res.put("requested", false);
            if (!(ctx instanceof android.app.Activity)) {
                res.put("reason", "no_activity");
                return res.toString();
            }
            final android.app.Activity act = (android.app.Activity) ctx;
            final String[] perms;
            if (Build.VERSION.SDK_INT >= 33) {
                perms = new String[]{
                        "android.permission.READ_MEDIA_IMAGES",
                        "android.permission.READ_MEDIA_VIDEO"};
            } else {
                perms = new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE};
            }
            act.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try { act.requestPermissions(perms, 2001); } catch (Exception e) {
                        Log.w(TAG, "requestMediaPermission failed", e);
                    }
                }
            });
            res.put("requested", true);
        } catch (Exception e) {
            Log.w(TAG, "requestMediaPermission failed", e);
        }
        return res.toString();
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
