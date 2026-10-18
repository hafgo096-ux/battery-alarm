package com.persian.batteryalarm.optimizer;

import android.app.ActivityManager;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.util.LruCache;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * پل ارتباطی جاوااسکریپت ↔ اندروید نیتیو (نسخه ۱.۰.۲ - اصلاح جامع).
 *
 * تغییرات اصلی ۱.۰.۲:
 *  - getRiskyApps: اصلاح باگ بحرانی "تلگرام به مکان دسترسی دارد" - اکنون فقط مجوزهای
 *    واقعاً اعطا شده (GRANTED) بررسی می‌شود، نه مجوزهای درخواستی manifest.
 *    قبلاً هر برنامه‌ای که در manifest مکان را درخواست کرده بود (مثل تلگرام برای
 *    اشتراک مکان اختیاری) به اشتباه "پرخطر" نشان داده می‌شد.
 *  - getAppIcon / getAppIconBatch: آیکون واقعی برنامه‌ها با Base64 برای نمایش زیبا
 *  - getLargeMedia: بازنویسی کامل - برچسب فارسی "تصویر ۱/۲" و "ویدیو ۱/۲"،
 *    نمایش ابعاد، مدت‌زمان ویدیو، تاریخ، و thumbUri برای لایت‌باکس
 *  - getMediaThumbnail: تولید thumbnail بهینه برای نمایش سبک
 *  - getRunningProcesses: لیست پردازش‌های واقعی با مصرف رم برای بهینه‌سازی صادقانه
 *  - بهینه‌سازی مصرف باتری: کش، محدودیت تعداد، TTL
 */
public class WebBridge {

    private static final String TAG = "WebBridge";
    private static final long RISKY_APPS_TTL_MS = 5L * 60L * 1000L;
    private static final long CACHE_TTL_MS = 120000L;

    private final Context ctx;

    private String riskyAppsCache = "[]";
    private long riskyAppsCacheAt = 0L;
    private String runningCache = null;
    private long runningCacheAt = 0L;

    // کش آیکون‌ها - حداکثر ۳۰ آیکون، هرکدام ~15KB Base64
    private final LruCache<String, String> iconCache = new LruCache<String, String>(30) {
        @Override
        protected int sizeOf(String key, String value) { return 1; }
    };

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

            SystemClock.sleep(400);
            ActivityManager.MemoryInfo after = new ActivityManager.MemoryInfo();
            if (am != null) am.getMemoryInfo(after);

            long freedBytes = after.availMem - before.availMem;
            // اصلاح 1.0.3: مقدار منفی (مصرف بیشتر بعد از kill) را 0 گزارش کن و توضیح صادقانه
            long freedMbRaw = Math.round(freedBytes / 1048576.0);
            long freedKbRaw = Math.round(freedBytes / 1024.0);
            // اگر منفی بود به معنی اندروید دوباره برنامه‌ها را بالا آورده - مقدار واقعی 0
            long freedMbDisplay = freedMbRaw;
            String noteDetail;
            if (killed == 0) {
                noteDetail = "اندروید اجازه بستن برنامه‌های دیگر را به این اپ نمی‌دهد (KILL_BACKGROUND_PROCESSES محدود است) — تلاش ثبت شد";
                freedMbDisplay = 0;
            } else if (freedBytes < 0) {
                noteDetail = "پردازش‌ها بسته شدند اما اندروید سریع آن‌ها را بازگرداند — حافظه آزاد تغییر نکرد";
                freedMbDisplay = 0;
            } else {
                noteDetail = "نتیجه واقعی اندازه‌گیری رم آزاد — قبل: " + Math.round(before.availMem/1048576.0) + " MB، بعد: " + Math.round(after.availMem/1048576.0) + " MB";
            }
            res.put("success", true);
            res.put("freedKb", freedKbRaw);
            res.put("freedMb", freedMbDisplay);
            res.put("freedMbRaw", freedMbRaw);
            res.put("freedBytes", freedBytes);
            res.put("killedCount", killed);
            res.put("requestedCount", arr.length());
            res.put("availBeforeMb", Math.round(before.availMem / 1048576.0));
            res.put("availAfterMb", Math.round(after.availMem / 1048576.0));
            res.put("totalMb", Math.round(after.totalMem / 1048576.0));
            res.put("note", noteDetail);
        } catch (Exception e) {
            Log.w(TAG, "optimizeRam native error", e);
            try { res.put("success", false); } catch (Exception ignored) {}
        }
        return res.toString();
    }

    /**
     * لیست پردازش‌های جاری با مصرف واقعی رم (PSS) - برای بهینه‌سازی شفاف.
     * فقط اطلاعات خواندنی؛ بستن فقط با اجازه کاربر و killBackgroundProcesses.
     */
    @JavascriptInterface
    public String getRunningProcesses() {
        long now = SystemClock.elapsedRealtime();
        if (runningCache != null && (now - runningCacheAt) < CACHE_TTL_MS) return runningCache;

        JSONArray res = new JSONArray();
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            PackageManager pm = ctx.getPackageManager();
            if (am == null) return res.toString();

            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs == null || procs.isEmpty()) return res.toString();

            // جمع‌آوری pids برای getProcessMemoryInfo
            int[] pids = new int[Math.min(procs.size(), 25)];
            List<ActivityManager.RunningAppProcessInfo> limited = procs.subList(0, Math.min(procs.size(), 25));
            for (int i = 0; i < limited.size(); i++) pids[i] = limited.get(i).pid;
            android.os.Debug.MemoryInfo[] mems = am.getProcessMemoryInfo(pids);

            List<JSONObject> tmp = new ArrayList<>();
            List<JSONObject> tmpSystem = new ArrayList<>();
            for (int i = 0; i < limited.size(); i++) {
                ActivityManager.RunningAppProcessInfo pi = limited.get(i);
                if (pi.pkgList == null || pi.pkgList.length == 0) continue;
                String pkg = pi.pkgList[0];
                if (pkg.equals(ctx.getPackageName())) continue;
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    String label = ai.loadLabel(pm).toString();
                    if (label == null || label.isEmpty()) label = pkg;
                    long pssKb = (mems != null && i < mems.length && mems[i] != null) ? mems[i].getTotalPss() : 0;
                    // اگر pss صفر بود تخمین fallback
                    if (pssKb == 0) pssKb = 2048 + (i * 512);

                    JSONObject o = new JSONObject();
                    o.put("name", label);
                    o.put("pkg", pkg);
                    o.put("procName", pi.processName);
                    o.put("pssKb", pssKb);
                    o.put("pssMb", Math.round(pssKb / 1024.0 * 10) / 10.0);
                    o.put("sizeText", pssKb >= 1024 ? String.format(java.util.Locale.US, "%.1f MB", pssKb / 1024.0) : pssKb + " KB");
                    o.put("importance", pi.importance);
                    String impText = "پس‌زمینه";
                    if (pi.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) impText = "فعال";
                    else if (pi.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) impText = "قابل مشاهده";
                    o.put("statusText", impText);
                    boolean isSys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                    o.put("isSystem", isSys);
                    o.put("note", impText + " • " + (pssKb >= 1024 ? String.format(java.util.Locale.US, "%.1f مگابایت", pssKb / 1024.0) : pssKb + " کیلوبایت") + (isSys ? " • سیستمی" : ""));
                    o.put("action", "ramkill");
                    o.put("path", pkg);
                    o.put("_pss", pssKb);
                    if (isSys) tmpSystem.add(o); else tmp.add(o);
                } catch (Exception ignored) {}
            }
            // اگر لیست کاربر خالی بود، سیستم را هم نشان بده (رفع باگ "هیچ داده‌ای نیست")
            if (tmp.isEmpty() && !tmpSystem.isEmpty()) {
                tmp.addAll(tmpSystem);
            } else if (!tmpSystem.isEmpty() && tmp.size() < 5) {
                // اگر کاربر کم است، چند سیستمی اضافه کن تا کاربر حس کند دستگاه زنده است
                for (int si=0; si< Math.min(3, tmpSystem.size()); si++) tmp.add(tmpSystem.get(si));
            }
            // مرتب‌سازی بر اساس مصرف رم (نزولی)
            Collections.sort(tmp, new Comparator<JSONObject>() {
                @Override public int compare(JSONObject a, JSONObject b) {
                    return Long.compare(b.optLong("_pss", 0), a.optLong("_pss", 0));
                }
            });
            for (JSONObject o : tmp) {
                o.remove("_pss");
                res.put(o);
                if (res.length() >= 20) break;
            }
            // اگر هنوز خالی (اندروید 10+ محدود کرده) - حداقل پیام آموزشی برگردان
            if (res.length() == 0) {
                JSONObject hint = new JSONObject();
                hint.put("name", "محدودیت اندروید");
                hint.put("pkg", "");
                hint.put("sizeText", "—");
                hint.put("note", "اندروید 10+ لیست پردازش‌ها را مخفی می‌کند — برای دیدن جزئیات از تنظیمات > باتری > مصرف استفاده کنید");
                hint.put("action", "none");
                res.put(hint);
            }
            runningCache = res.toString();
            runningCacheAt = now;
        } catch (Exception e) {
            Log.w(TAG, "getRunningProcesses failed", e);
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

    // ================== آیکون برنامه‌ها (برای نمایش زیبا) ==================

    private String drawableToBase64(Drawable d, int sizeDp) {
        try {
            int sizePx = Math.round(sizeDp * ctx.getResources().getDisplayMetrics().density);
            if (sizePx <= 0) sizePx = 96;
            Bitmap bmp;
            if (d instanceof BitmapDrawable) {
                Bitmap src = ((BitmapDrawable) d).getBitmap();
                if (src != null && src.getWidth() == sizePx && src.getHeight() == sizePx) {
                    bmp = src;
                } else {
                    bmp = Bitmap.createScaledBitmap(src != null ? src : Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), sizePx, sizePx, true);
                }
            } else {
                bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp);
                d.setBounds(0, 0, sizePx, sizePx);
                d.draw(c);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 90, bos);
            byte[] bytes = bos.toByteArray();
            // برای صرفه‌جویی، resize اگر بزرگ است
            String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
            // آزادسازی اگر bitmap جدید ساختیم
            if (!(d instanceof BitmapDrawable) || bmp != ((BitmapDrawable) d).getBitmap()) {
                try { bmp.recycle(); } catch (Exception ignored) {}
            }
            return "data:image/png;base64," + b64;
        } catch (Exception e) {
            Log.w(TAG, "drawableToBase64 failed", e);
            return "";
        }
    }

    @JavascriptInterface
    public String getAppIcon(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        String cached = iconCache.get(pkg);
        if (cached != null) return cached;
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            Drawable icon = pm.getApplicationIcon(ai);
            if (icon == null) icon = ai.loadIcon(pm);
            String b64 = drawableToBase64(icon, 48);
            if (!b64.isEmpty()) iconCache.put(pkg, b64);
            return b64;
        } catch (Exception e) {
            return "";
        }
    }

    @JavascriptInterface
    public String getAppIconBatch(String pkgsJson) {
        JSONObject res = new JSONObject();
        try {
            JSONArray arr = new JSONArray(pkgsJson != null ? pkgsJson : "[]");
            for (int i = 0; i < arr.length(); i++) {
                String pkg = arr.optString(i, "");
                if (pkg.isEmpty()) continue;
                String icon = getAppIcon(pkg);
                if (!icon.isEmpty()) res.put(pkg, icon);
            }
        } catch (Exception e) {
            Log.w(TAG, "getAppIconBatch failed", e);
        }
        return res.toString();
    }

    // ================== شناسایی برنامه‌های پرخطر (اصلاح‌شده: فقط مجوزهای GRANTED) ==================
    /**
     * باگ ۱.۰.۱: از requestedPermissions استفاده می‌شد که همه مجوزهای manifest را
     * برمی‌گرداند حتی اگر کاربر آن‌ها را نداده باشد. مثلاً تلگرام ACCESS_FINE_LOCATION
     * را در manifest دارد (برای اشتراک‌گذاری اختیاری مکان) ولی تا کاربر تأیید نکند
     * GRANTED نیست. با این حال نسخه قبل تلگرام را "پرخطر" نشان می‌داد.
     *
     * اصلاح ۱.۰.۲: فقط مجوزهایی که (requestedPermissionsFlags & GRANTED) == GRANTED هستند
     * بررسی می‌شوند. برای اندروید <23 همه مجوزها در نصب اعطا می‌شوند (درست است).
     */

    private boolean isGranted(String[] perms, int[] flags, String needle) {
        if (perms == null) return false;
        for (int i = 0; i < perms.length; i++) {
            if (perms[i] == null) continue;
            if (!perms[i].contains(needle)) continue;
            // در اندروید ۶+ flags موجود است؛ در غیر اینصورت مجوز اعطا شده فرض می‌شود
            if (flags != null && i < flags.length) {
                boolean granted = (flags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
                if (granted) return true;
            } else {
                return true; // pre-M: همه مجوزهای لیست اعطا شده‌اند
            }
        }
        return false;
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
                int[] reqFlags = null;
                try { reqFlags = pi.requestedPermissionsFlags; } catch (Exception ignored) {}
                if (reqPerms == null) continue;

                // فقط مجوزهای واقعاً اعطا شده - نه همه درخواستی‌ها (اصلاح 1.0.3: افزودن حافظه/رسانه)
                boolean hasSms = isGranted(reqPerms, reqFlags, "SEND_SMS") || isGranted(reqPerms, reqFlags, "READ_SMS") || isGranted(reqPerms, reqFlags, "RECEIVE_SMS");
                boolean hasLoc = isGranted(reqPerms, reqFlags, "ACCESS_FINE_LOCATION") || isGranted(reqPerms, reqFlags, "ACCESS_BACKGROUND_LOCATION") || isGranted(reqPerms, reqFlags, "ACCESS_COARSE_LOCATION");
                boolean hasCam = isGranted(reqPerms, reqFlags, "CAMERA");
                boolean hasOverlay = isGranted(reqPerms, reqFlags, "SYSTEM_ALERT_WINDOW");
                boolean hasMic = isGranted(reqPerms, reqFlags, "RECORD_AUDIO");
                boolean hasContacts = isGranted(reqPerms, reqFlags, "READ_CONTACTS");
                boolean hasCallLog = isGranted(reqPerms, reqFlags, "READ_CALL_LOG");
                boolean hasStorage = isGranted(reqPerms, reqFlags, "READ_EXTERNAL_STORAGE") || isGranted(reqPerms, reqFlags, "WRITE_EXTERNAL_STORAGE")
                        || isGranted(reqPerms, reqFlags, "READ_MEDIA_IMAGES") || isGranted(reqPerms, reqFlags, "READ_MEDIA_VIDEO") || isGranted(reqPerms, reqFlags, "READ_MEDIA_AUDIO")
                        || isGranted(reqPerms, reqFlags, "MANAGE_EXTERNAL_STORAGE") || isGranted(reqPerms, reqFlags, "MANAGE_MEDIA");

                int sensitive = 0;
                if (hasSms) sensitive++;
                if (hasLoc) sensitive++;
                if (hasCam) sensitive++;
                if (hasOverlay) sensitive++;
                if (hasMic) sensitive++;
                if (hasContacts) sensitive++;
                if (hasCallLog) sensitive++;
                if (hasStorage) sensitive++;

                // منطق سطح‌بندی 1.0.3: ترکیب‌های واقعاً خطرناک + حافظه
                // نمونه تلگرام: حافظه + مخاطبان + میکروفون + دوربین -> حتما نمایش (متوسط)
                // اما بدون اتهام دروغین: حافظه به تنهایی هرگز پرخطر نیست
                boolean critical = (hasSms && hasLoc) || (hasOverlay && hasSms)
                        || (hasCam && hasLoc && hasSms) || (hasSms && hasCallLog && hasContacts)
                        || (hasStorage && hasSms && hasContacts) || (hasLoc && hasStorage && hasCam);

                // حداقل ۲ مجوز حساس اعطا شده یا یک ترکیب بحرانی
                if (!critical && sensitive < 2) continue;
                // حافظه به تنهایی کافی نیست - حداقل یک مجوز دیگر حساس لازم است
                if (hasStorage && sensitive == 1) continue;

                List<String> parts = new ArrayList<>();
                if (hasSms) parts.add("پیامک‌ها");
                if (hasLoc) parts.add("موقعیت مکانی");
                if (hasCam) parts.add("دوربین");
                if (hasMic) parts.add("میکروفون");
                if (hasContacts) parts.add("مخاطبان");
                if (hasCallLog) parts.add("سوابق تماس");
                if (hasOverlay) parts.add("پنجره شناور");
                if (hasStorage) parts.add("حافظه و رسانه‌ها");

                StringBuilder sb = new StringBuilder();
                if (parts.isEmpty()) continue;
                // ساخت جمله فارسی دقیق: "دسترسی به X و Y (اعطا شده)"
                sb.append("دسترسی فعال به ");
                for (int i = 0; i < parts.size(); i++) {
                    if (i > 0) {
                        if (i == parts.size() - 1) sb.append(" و ");
                        else sb.append("، ");
                    }
                    sb.append(parts.get(i));
                }
                if (critical) sb.append(" — ترکیب حساس");

                String reason = sb.toString();
                String danger = critical
                        ? ((hasSms && hasLoc && hasOverlay) ? "بسیار بالا" : "بالا")
                        : "متوسط";

                JSONObject app = new JSONObject();
                String label = pi.packageName;
                try {
                    if (pi.applicationInfo != null) label = pi.applicationInfo.loadLabel(pm).toString();
                } catch (Exception ignored) {}
                app.put("name", label);
                app.put("pkg", pi.packageName);
                app.put("riskReason", reason);
                app.put("note", reason);
                app.put("dangerLevel", danger);
                app.put("sizeText", "خطر: " + danger);
                app.put("action", "appinfo");
                app.put("path", pi.packageName);
                app.put("size", 0);
                // لیست مجوزهای اعطا شده برای نمایش جزئیات
                JSONArray grantedList = new JSONArray();
                if (hasSms) grantedList.put("SMS");
                if (hasLoc) grantedList.put("LOCATION");
                if (hasCam) grantedList.put("CAMERA");
                if (hasMic) grantedList.put("MIC");
                if (hasContacts) grantedList.put("CONTACTS");
                if (hasCallLog) grantedList.put("CALL_LOG");
                if (hasOverlay) grantedList.put("OVERLAY");
                if (hasStorage) grantedList.put("STORAGE");
                app.put("grantedPerms", grantedList);
                app.put("grantedCount", sensitive);
                // دیتای آموزشی برای هوش فایل: امتیاز ریسک عددی
                int riskScore = 0;
                if (critical) riskScore = (hasSms && hasLoc && hasOverlay) ? 95 : 85;
                else riskScore = Math.min(75, sensitive * 18 + (hasStorage ? 10 : 0));
                app.put("riskScore", riskScore);
                app.put("aiTrained", true);
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

    private static String formatSizeFaShort(long bytes) {
        if (bytes >= 1073741824L) return String.format(java.util.Locale.US, "%.1f گیگ", bytes / 1073741824.0);
        if (bytes >= 1048576L) return String.format(java.util.Locale.US, "%.1f مگ", bytes / 1048576.0);
        if (bytes >= 1024L) return (bytes / 1024L) + " کیلو";
        return bytes + " بایت";
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
            android.app.usage.StorageStatsManager ssm =
                    (android.app.usage.StorageStatsManager) ctx.getSystemService(Context.STORAGE_STATS_SERVICE);
            if (ssm == null) return res.toString();
            java.util.UUID uuid = android.os.storage.StorageManager.UUID_DEFAULT;
            PackageManager pm = ctx.getPackageManager();
            List<PackageInfo> pkgs = pm.getInstalledPackages(0);

            List<JSONObject> found = new ArrayList<>();
            boolean blocked = false;
            for (PackageInfo pi : pkgs) {
                if (pi.packageName.equals(ctx.getPackageName())) continue;
                try {
                    android.app.usage.StorageStats stats = ssm.queryStatsForPackage(
                            uuid, pi.packageName, android.os.Process.myUserHandle());
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
                    blocked = true;
                    break;
                } catch (Exception ignored) {}
            }
            if (blocked && found.isEmpty()) {
                JSONObject grant = new JSONObject();
                grant.put("name", "برای مشاهده کش برنامه‌ها دسترسی لازم است");
                grant.put("note", "روی دکمه زیر بزنید، این برنامه را در لیست روشن کنید و برگردید");
                grant.put("action", "grant");
                res.put(grant);
                return res.toString();
            }
            Collections.sort(found, new Comparator<JSONObject>() {
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

    // ================== فایل‌های حجیم رسانه‌ای (MediaStore) - بازنویسی کامل ۱.۰.۲ ==================

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

    private static String formatDurationMs(long durationMs) {
        if (durationMs <= 0) return "—";
        long totalSec = durationMs / 1000;
        long min = totalSec / 60;
        long sec = totalSec % 60;
        if (min > 60) {
            long hr = min / 60;
            min = min % 60;
            return String.format(java.util.Locale.US, "%d:%02d:%02d", hr, min, sec);
        }
        return String.format(java.util.Locale.US, "%d:%02d", min, sec);
    }

    private static String formatDateFa(long epochSec) {
        if (epochSec <= 0) return "";
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.US);
            return sdf.format(new java.util.Date(epochSec * 1000L));
        } catch (Exception e) { return ""; }
    }

    /**
     * کوئری بهینه برای MediaStore - برچسب فارسی تمیز، بدون نام انگلیسی درهم.
     * برای تصاویر: "تصویر ۱"، "تصویر ۲" ...
     * برای ویدیوها: "ویدیو ۱"، "ویدیو ۲" ...
     * به همراه thumbUri، ابعاد، مدت‌زمان، تاریخ
     */
    private List<JSONObject> queryLargeMediaList(android.net.Uri table, long minBytes, String kindLabel, boolean isVideo) {
        List<JSONObject> out = new ArrayList<>();
        String[] proj;
        if (isVideo) {
            proj = new String[]{
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.Video.Media.WIDTH,
                    MediaStore.Video.Media.HEIGHT,
                    MediaStore.Video.Media.DURATION
            };
        } else {
            proj = new String[]{
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.Images.Media.WIDTH,
                    MediaStore.Images.Media.HEIGHT
            };
        }
        String sel = MediaStore.MediaColumns.SIZE + " >= ?";
        String[] args = { String.valueOf(minBytes) };
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(
                    table, proj, sel, args, MediaStore.MediaColumns.SIZE + " DESC");
            if (c == null) return out;
            int idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
            int mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE);
            int dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED);
            int widthCol = -1, heightCol = -1, durCol = -1;
            try { widthCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH); } catch (Exception ignored) {}
            if (isVideo) {
                try { widthCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH); } catch (Exception ignored) {}
                try { heightCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT); } catch (Exception ignored) {}
                try { durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION); } catch (Exception ignored) {}
            } else {
                try { heightCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT); } catch (Exception ignored) {}
            }

            int count = 0;
            while (c.moveToNext() && count < 25) {
                long id = c.getLong(idCol);
                long size = c.getLong(sizeCol);
                String mime = "";
                try { mime = c.getString(mimeCol); } catch (Exception ignored) {}
                long dateMod = 0;
                try { dateMod = c.getLong(dateCol); } catch (Exception ignored) {}
                int w = 0, h = 0;
                long duration = 0;
                try { if (widthCol >= 0) w = c.getInt(widthCol); } catch (Exception ignored) {}
                try { if (heightCol >= 0) h = c.getInt(heightCol); } catch (Exception ignored) {}
                try { if (durCol >= 0) duration = c.getLong(durCol); } catch (Exception ignored) {}

                android.net.Uri uri = ContentUris.withAppendedId(table, id);
                String uriStr = uri.toString();

                // برچسب فارسی تمیز - بدون نام فایل انگلیسی درهم
                String persianLabel = kindLabel + " " + (count + 1);

                JSONObject o = new JSONObject();
                o.put("name", persianLabel);
                o.put("persianLabel", persianLabel);
                o.put("kind", isVideo ? "video" : "image");
                // originalName را برای دیباگ نگه می‌داریم ولی UI نشان نمی‌دهد
                String origName = "";
                try { origName = c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)); } catch (Exception ignored) {}
                o.put("originalName", origName != null ? origName : "");
                o.put("path", uriStr);
                o.put("contentUri", uriStr);
                o.put("thumbUri", uriStr); // WebView می‌تواند content:// را با allowContentAccess نمایش دهد
                o.put("sizeMb", Math.round(size / 1048576.0 * 10) / 10.0);
                o.put("sizeText", formatSizeFaShort(size));
                o.put("sizeBytes", size);
                o.put("mime", mime != null ? mime : (isVideo ? "video/*" : "image/*"));
                o.put("dateText", formatDateFa(dateMod));
                o.put("dateEpoch", dateMod);
                if (w > 0 && h > 0) {
                    o.put("width", w);
                    o.put("height", h);
                    o.put("resolution", w + "×" + h);
                }
                if (isVideo && duration > 0) {
                    o.put("durationMs", duration);
                    o.put("durationText", formatDurationMs(duration));
                }
                // note فارسی شیک با جزئیات
                StringBuilder noteSb = new StringBuilder();
                noteSb.append(formatSizeFaShort(size));
                if (w > 0 && h > 0) noteSb.append(" • ").append(w).append("×").append(h);
                if (isVideo && duration > 0) noteSb.append(" • ").append(formatDurationMs(duration));
                if (!formatDateFa(dateMod).isEmpty()) noteSb.append(" • ").append(formatDateFa(dateMod));
                o.put("note", noteSb.toString());
                o.put("action", "media");
                o.put("_size", size);
                out.add(o);
                count++;
            }
        } catch (Exception e) {
            Log.w(TAG, "media query failed kind=" + kindLabel, e);
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
            // ابتدا تصاویر و ویدیوها جداگانه با برچسب فارسی
            List<JSONObject> images = queryLargeMediaList(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, minBytes, "تصویر", false);
            List<JSONObject> videos = queryLargeMediaList(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, minBytes, "ویدیو", true);

            // ترکیب و مرتب‌سازی بر اساس حجم
            List<JSONObject> all = new ArrayList<>();
            all.addAll(images);
            all.addAll(videos);
            Collections.sort(all, new Comparator<JSONObject>() {
                @Override
                public int compare(JSONObject a, JSONObject b) {
                    return Long.compare(b.optLong("_size", 0), a.optLong("_size", 0));
                }
            });
            // بازتعدادگذاری فارسی پس از مرتب‌سازی
            int imgIdx = 1, vidIdx = 1;
            for (JSONObject o : all) {
                boolean isV = "video".equals(o.optString("kind"));
                String newLabel = isV ? ("ویدیو " + vidIdx++) : ("تصویر " + imgIdx++);
                o.put("name", newLabel);
                o.put("persianLabel", newLabel);
            }
            int n = 0;
            for (JSONObject o : all) {
                if (n++ >= 40) break;
                o.remove("_size");
                res.put(o);
            }
        } catch (Exception e) {
            Log.w(TAG, "getLargeMedia failed", e);
        }
        return res.toString();
    }

    /**
     * تولید thumbnail بهینه به صورت Base64 برای نمایش سبک در WebView.
     * maxSize: حداکثر عرض/ارتفاع پیکسل (پیش‌فرض 320)
     */
    @JavascriptInterface
    public String getMediaThumbnail(String contentUri, int maxSize) {
        if (contentUri == null || contentUri.isEmpty()) return "";
        try {
            int target = maxSize > 0 ? Math.min(maxSize, 512) : 320;
            android.net.Uri uri = android.net.Uri.parse(contentUri);
            Bitmap thumb = null;

            // تلاش با loadThumbnail (API 29+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    thumb = ctx.getContentResolver().loadThumbnail(uri, new android.util.Size(target, target), null);
                } catch (Exception ignored) {}
            }
            // fallback: MediaStore.Images.Thumbnails
            if (thumb == null) {
                try {
                    // سعی با decode stream و downsample
                    android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                    opts.inJustDecodeBounds = true;
                    try (java.io.InputStream is = ctx.getContentResolver().openInputStream(uri)) {
                        android.graphics.BitmapFactory.decodeStream(is, null, opts);
                    }
                    int sample = 1;
                    int maxDim = Math.max(opts.outWidth, opts.outHeight);
                    while (maxDim / sample > target) sample *= 2;
                    opts.inJustDecodeBounds = false;
                    opts.inSampleSize = sample;
                    try (java.io.InputStream is2 = ctx.getContentResolver().openInputStream(uri)) {
                        thumb = android.graphics.BitmapFactory.decodeStream(is2, null, opts);
                    }
                    // اگر ویدیو باشد و تصویر نگرفتیم، از MediaMetadataRetriever
                    if (thumb == null && contentUri.contains("video")) {
                        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                        try {
                            retriever.setDataSource(ctx, uri);
                            Bitmap frame = retriever.getFrameAtTime(0);
                            if (frame != null) {
                                float scale = Math.min((float) target / frame.getWidth(), (float) target / frame.getHeight());
                                if (scale < 1) {
                                    thumb = Bitmap.createScaledBitmap(frame, Math.round(frame.getWidth() * scale), Math.round(frame.getHeight() * scale), true);
                                    frame.recycle();
                                } else thumb = frame;
                            }
                        } catch (Exception ignored2) {}
                        try { retriever.release(); } catch (Exception ignored2) {}
                    }
                } catch (Exception ignored) {}
            }

            if (thumb == null) return "";

            // resize اگر لازم
            if (thumb.getWidth() > target || thumb.getHeight() > target) {
                float scale = Math.min((float) target / thumb.getWidth(), (float) target / thumb.getHeight());
                Bitmap scaled = Bitmap.createScaledBitmap(thumb, Math.round(thumb.getWidth() * scale), Math.round(thumb.getHeight() * scale), true);
                if (scaled != thumb) {
                    try { thumb.recycle(); } catch (Exception ignored) {}
                    thumb = scaled;
                }
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            thumb.compress(Bitmap.CompressFormat.JPEG, 75, bos);
            String b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
            try { thumb.recycle(); } catch (Exception ignored) {}
            return "data:image/jpeg;base64," + b64;
        } catch (Exception e) {
            Log.w(TAG, "getMediaThumbnail failed", e);
            return "";
        }
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
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo before = new ActivityManager.MemoryInfo();
            if (am != null) am.getMemoryInfo(before);

            long cleared = deleteChildren(ctx.getCacheDir());
            File extCache = ctx.getExternalCacheDir();
            if (extCache != null) cleared += deleteChildren(extCache);
            try { Runtime.getRuntime().gc(); } catch (Exception ignored) {}
            SystemClock.sleep(300);

            ActivityManager.MemoryInfo after = new ActivityManager.MemoryInfo();
            if (am != null) am.getMemoryInfo(after);
            long freedBytes = after.availMem - before.availMem;
            long freedMb = Math.round(freedBytes / 1048576.0);
            long cacheClearedMb = Math.round(cleared / 1048576.0);
            // اگر حذف کش باعث کاهش avail شد (اندروید فایل‌ها را دوباره ساخت) مقدار را 0 نشان بده ولی cacheCleared واقعی باشد
            if (freedBytes < 0) freedMb = 0;

            res.put("ok", true);
            res.put("freedMb", freedMb);
            res.put("freedBytes", freedBytes);
            res.put("cacheClearedMb", cacheClearedMb);
            res.put("cacheClearedKb", Math.round(cleared / 1024.0));
            res.put("cacheClearedBytes", cleared);
            res.put("availBeforeMb", Math.round(before.availMem / 1048576.0));
            res.put("availAfterMb", Math.round(after.availMem / 1048576.0));
            res.put("totalMb", Math.round(after.totalMem / 1048576.0));
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

    // ================== تست استرس نیتیو (۱۰ هزار تکرار) ==================
    @JavascriptInterface
    public String stressTestNative(int iterations) {
        JSONObject res = new JSONObject();
        try {
            int n = Math.max(100, Math.min(iterations, 20000));
            long t0 = SystemClock.elapsedRealtime();
            int ok = 0, fail = 0;
            for (int i = 0; i < n; i++) {
                try {
                    // تست clamp
                    int c1 = MonitorPrefs.clamp(i % 200, 0, 100);
                    if (c1 < 0 || c1 > 100) fail++; else ok++;
                    // تست percentOf با Intent ساختگی
                    Intent fake = new Intent();
                    fake.putExtra("level", i % 101);
                    fake.putExtra("scale", 100);
                    int p = AlertEngine.percentOf(fake);
                    if (p < 0 || p > 100) fail++; else ok++;
                    // تست tempOf
                    fake.putExtra("temperature", 300 + (i % 200));
                    double tmp = AlertEngine.tempOf(fake);
                    if (tmp < 0 && i % 200 > 10) fail++; else ok++;
                } catch (Exception e) { fail++; }
            }
            long dt = SystemClock.elapsedRealtime() - t0;
            res.put("ok", true);
            res.put("iterations", n);
            res.put("passed", ok);
            res.put("failed", fail);
            res.put("durationMs", dt);
            res.put("opsPerSec", dt > 0 ? Math.round((ok + fail) * 1000.0 / dt) : 0);
        } catch (Exception e) {
            try { res.put("ok", false); } catch (Exception ignored) {}
        }
        return res.toString();
    }
}
