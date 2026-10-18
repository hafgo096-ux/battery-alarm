package com.persian.batteryalarm.optimizer;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * پل اطلاعات سخت‌افزار (نسخه ۱.۰.۱ - سبک و صادق).
 *
 * اصلاحات:
 *  - کش‌گذاری نتایج (CPU ۱.۵ ثانیه، رم ۱.۲ ثانیه، حافظه ۴ ثانیه) تا هر فراخوانی
 *    سبب خواندن مکرر فایل‌های /proc و مصرف بی‌دلیل CPU/باتری نشود.
 *  - حذف کامل داده‌های ساختگی (اعداد تصادفی برای لود هسته‌ها و مقادیر پیش‌فرض دروغین).
 *    در صورت عدم دسترسی، مقدار -1 و «unknown» برگردانده می‌شود تا UI «—» نشان دهد.
 */
public class SysInfoBridge {

    private static final String TAG = "SysInfoBridge";
    private static final long CPU_TTL = 1500L;
    private static final long MEM_TTL = 1200L;
    private static final long STORAGE_TTL = 4000L;
    private static final long DEVICE_TTL = 60000L;
    private static final long BATTERY_TTL = 1000L;

    private final Context ctx;

    private long[][] lastJiffies = null;
    private long lastJiffiesTime = 0;

    private String cpuCache = null;
    private long cpuCacheAt = 0;
    private String memCache = null;
    private long memCacheAt = 0;
    private String storageCache = null;
    private long storageCacheAt = 0;
    private String deviceCache = null;
    private long deviceCacheAt = 0;
    private String batteryCache = null;
    private long batteryCacheAt = 0;

    public SysInfoBridge(Context ctx) {
        this.ctx = ctx;
    }

    private String readFirstLine(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line = br.readLine();
            return line != null ? line.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String readProp(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method m = c.getMethod("get", String.class);
            Object v = m.invoke(null, key);
            return v == null ? "" : v.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private long parseKb(String s) {
        try {
            String t = s.trim().replaceAll("[^0-9]", "");
            return t.isEmpty() ? 0 : Long.parseLong(t);
        } catch (Exception e) {
            return 0;
        }
    }

    // ================= CPU =================
    @JavascriptInterface
    public String getCpuInfo() {
        long now = SystemClock.elapsedRealtime();
        if (cpuCache != null && (now - cpuCacheAt) < CPU_TTL) return cpuCache;

        JSONObject o = new JSONObject();
        try {
            int cores = Runtime.getRuntime().availableProcessors();
            o.put("cores", cores);

            String model = readProp("ro.soc.model");
            if (model == null || model.isEmpty()) model = readProp("ro.board.platform");
            if (model == null || model.isEmpty()) model = Build.HARDWARE;
            if (model == null || model.isEmpty() || model.equalsIgnoreCase("unknown")) model = Build.BOARD;
            o.put("model", model == null || model.isEmpty() ? "نامشخص" : model.toUpperCase());
            o.put("abi", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown");

            long[][] cur = readCpuJiffies();
            int avgUsage = -1;
            JSONArray coresArr = new JSONArray();

            if (cur != null && cur.length > 0 && lastJiffies != null
                    && lastJiffies.length == cur.length && (now - lastJiffiesTime) > 400) {
                double totalUsage = 0;
                int valid = 0;
                for (int i = 0; i < cur.length; i++) {
                    long[] a = lastJiffies[i];
                    long[] b = cur[i];
                    long idleA = a.length > 4 ? a[3] + a[4] : 0;
                    long idleB = b.length > 4 ? b[3] + b[4] : 0;
                    long totA = 0, totB = 0;
                    for (long v : a) totA += v;
                    for (long v : b) totB += v;
                    long totD = totB - totA;
                    long idleD = idleB - idleA;
                    if (totD <= 0) continue;
                    double usage = (1.0 - (double) idleD / totD) * 100.0;
                    if (usage < 0) usage = 0;
                    if (usage > 100) usage = 100;
                    JSONObject c = new JSONObject();
                    c.put("core", i);
                    c.put("usage", Math.round(usage));
                    c.put("curFreq", readCpuFreq(i, "scaling_cur_freq"));
                    c.put("maxFreq", readCpuFreq(i, "cpuinfo_max_freq"));
                    coresArr.put(c);
                    totalUsage += usage;
                    valid++;
                }
                if (valid > 0) avgUsage = (int) Math.round(totalUsage / valid);
            }
            if (cur != null) {
                lastJiffies = cur;
                lastJiffiesTime = now;
            }

            o.put("perCore", coresArr);
            o.put("avgUsage", avgUsage);
            o.put("avgUsageAvailable", avgUsage >= 0);

            String loadavg = readFirstLine("/proc/loadavg");
            o.put("loadavg", loadavg.isEmpty() ? "نامشخص" : loadavg);

            String temp = readFirstLine("/sys/class/thermal/thermal_zone0/temp");
            long t = 0;
            try { t = Long.parseLong(temp.replaceAll("[^0-9]", "")); } catch (Exception ignored) {}
            o.put("temperature", t > 0 ? (t > 1000 ? t / 1000.0 : t) : -1);

            cpuCache = o.toString();
            cpuCacheAt = now;
        } catch (Exception e) {
            Log.w(TAG, "getCpuInfo failed", e);
        }
        return o.toString();
    }

    private long[][] readCpuJiffies() {
        try {
            List<long[]> list = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new FileReader("/proc/stat"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("cpu")) {
                        String[] parts = line.trim().split("\\s+");
                        int n = Math.min(parts.length - 1, 7);
                        long[] vals = new long[n];
                        for (int i = 0; i < n; i++) {
                            try { vals[i] = Long.parseLong(parts[i + 1]); }
                            catch (Exception e) { vals[i] = 0; }
                        }
                        list.add(vals);
                    }
                }
            }
            if (list.size() <= 1) return null;
            long[][] arr = new long[list.size() - 1][];
            for (int i = 1; i < list.size(); i++) arr[i - 1] = list.get(i);
            return arr;
        } catch (Exception e) {
            return null;
        }
    }

    private long readCpuFreq(int core, String key) {
        try {
            String v = readFirstLine("/sys/devices/system/cpu/cpu" + core + "/cpufreq/" + key);
            if (v.isEmpty()) return -1;
            return Long.parseLong(v.trim()) / 1000;
        } catch (Exception e) {
            return -1;
        }
    }

    // ================= RAM =================
    // باگ‌فیکس ۱.۰.۱ → ۱: availMem شامل reclaimable cache هم هست.
    // برای نمایش دقیق‌تر، از /proc/meminfo هم مقادیر MemFree و MemAvailable رو می‌خونیم.
    @JavascriptInterface
    public String getMemoryInfo() {
        long now = SystemClock.elapsedRealtime();
        if (memCache != null && (now - memCacheAt) < MEM_TTL) return memCache;
        JSONObject o = new JSONObject();
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            if (am != null) am.getMemoryInfo(mi);

            long total = mi.totalMem;
            long avail = mi.availMem; // شامل reclaimable cache
            long used = Math.max(0, total - avail);
            int usedPct = total > 0 ? (int) Math.round((double) used / total * 100) : -1;

            // خواندن مقادیر دقیق‌تر از /proc/meminfo
            long memFree = readMeminfoValue("MemFree");
            long memAvailable = readMeminfoValue("MemAvailable");
            long cached = readMeminfoValue("Cached");
            long buffers = readMeminfoValue("Buffers");

            o.put("total", total);
            o.put("avail", avail);
            o.put("used", used);
            o.put("usedPct", usedPct);
            o.put("lowMemory", mi.lowMemory);
            o.put("threshold", mi.threshold);
            o.put("cached", cached + buffers);
            o.put("memFree", memFree);
            o.put("memAvailable", memAvailable > 0 ? memAvailable : avail);
            o.put("source", "ActivityManager.MemoryInfo + /proc/meminfo");

            memCache = o.toString();
            memCacheAt = now;
        } catch (Exception e) {
            Log.w(TAG, "getMemoryInfo failed", e);
        }
        return o.toString();
    }

    private long readMeminfoValue(String key) {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith(key + ":")) {
                    String[] parts = line.split(":");
                    return parseKb(parts[1]) * 1024L;
                }
            }
        } catch (Exception ignored) {}
        return 0L;
    }

    // ================= Storage =================
    // باگ‌فیکس ۱.۰.۱ → ۱: قبلاً فقط Environment.getDataDirectory() استفاده می‌شد
    // که فقط پارتیشن /data رو نشون می‌داد. الان هم حافظه داخلی و هم فضای خارجی (در صورت وجود)
    // با استفاده از getExternalFilesDir گزارش داده می‌شود تا آمار دقیق‌تر باشد.
    @JavascriptInterface
    public String getStorageInfo() {
        long now = SystemClock.elapsedRealtime();
        if (storageCache != null && (now - storageCacheAt) < STORAGE_TTL) return storageCache;
        JSONObject o = new JSONObject();
        try {
            // فضای اصلی حافظه داخلی (data partition)
            StatFs sfData = new StatFs(Environment.getDataDirectory().getAbsolutePath());
            long dataTotal = sfData.getTotalBytes();
            long dataAvail = sfData.getAvailableBytes();

            // فضای حافظه خارجی (اگر وجود دارد و internal emulated است)
            // در اکثر دستگاه‌ها /sdcard یک symlink به /data/media است
            // پس معمولاً همان پارتیشن را نشان می‌دهد
            long total = dataTotal;
            long avail = dataAvail;

            // بررسی فضای storage خارجی (اگر internal emulated نباشد)
            try {
                File extFiles = ctx.getExternalFilesDir(null);
                if (extFiles != null) {
                    StatFs sfExt = new StatFs(extFiles.getAbsolutePath());
                    long extTotal = sfExt.getTotalBytes();
                    long extAvail = sfExt.getAvailableBytes();
                    // فقط اگر پارتیشن متفاوتی است (نه emulated internal)
                    if (extTotal > 0 && Math.abs(extTotal - dataTotal) > 1073741824L) {
                        // پارتیشن خارجی متفاوت - جمع کن
                        total = dataTotal + extTotal;
                        avail = dataAvail + extAvail;
                    }
                    // اگر همون پارتیشن است، همان مقادیر dataTotal/dataAvail را استفاده کن
                }
            } catch (Exception ignored) {}

            long used = Math.max(0, total - avail);
            o.put("total", total);
            o.put("avail", avail);
            o.put("used", used);
            o.put("usedPct", total > 0 ? (int) Math.round((double) used / total * 100) : -1);
            o.put("source", "StatFs");
            storageCache = o.toString();
            storageCacheAt = now;
        } catch (Exception e) {
            Log.w(TAG, "getStorageInfo failed", e);
        }
        return o.toString();
    }

    // ================= Battery =================
    @JavascriptInterface
    public String getBatteryInfo() {
        long nowB = SystemClock.elapsedRealtime();
        if (batteryCache != null && (nowB - batteryCacheAt) < BATTERY_TTL) return batteryCache;
        JSONObject o = new JSONObject();
        try {
            Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int percent = AlertEngine.percentOf(i);
            double temp = AlertEngine.tempOf(i);
            o.put("available", i != null && percent >= 0);
            o.put("percent", percent);
            o.put("temperature", temp);
            o.put("voltage", i != null ? i.getIntExtra("voltage", -1) : -1);
            o.put("plugged", AlertEngine.chargingOf(i));
            o.put("status", i != null ? i.getIntExtra("status", -1) : -1);
            o.put("level", i != null ? i.getIntExtra("level", -1) : -1);
            o.put("scale", i != null ? i.getIntExtra("scale", -1) : -1);

            int health = i != null ? i.getIntExtra("health", -1) : -1;
            String hs = "نامشخص";
            if (health == 2) hs = "سالم و عالی (Good)";
            else if (health == 3) hs = "داغ (Overheat)";
            else if (health == 4) hs = "فرسوده (Dead)";
            else if (health == 5) hs = "ولتاژ غیرمجاز (Over Voltage)";
            else if (health == 6) hs = "خرابی (Unspecified Failure)";
            else if (health == 7) hs = "سرد (Cold)";
            o.put("health", hs);

            String tech = i != null ? i.getStringExtra("technology") : null;
            o.put("technology", tech != null && !tech.isEmpty() ? tech : "نامشخص");
            batteryCache = o.toString();
            batteryCacheAt = nowB;
        } catch (Exception e) {
            Log.w(TAG, "getBatteryInfo failed", e);
        }
        return o.toString();
    }

    // ================= Device =================
    @JavascriptInterface
    public String getDeviceInfo() {
        long now = SystemClock.elapsedRealtime();
        if (deviceCache != null && (now - deviceCacheAt) < DEVICE_TTL) return deviceCache;
        JSONObject o = new JSONObject();
        try {
            o.put("model", Build.MODEL != null ? Build.MODEL : "نامشخص");
            o.put("manufacturer", Build.MANUFACTURER != null ? Build.MANUFACTURER : "نامشخص");
            o.put("brand", Build.BRAND != null ? Build.BRAND : "نامشخص");
            o.put("device", Build.DEVICE != null ? Build.DEVICE : "نامشخص");
            o.put("android", Build.VERSION.RELEASE != null ? Build.VERSION.RELEASE : "نامشخص");
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("security", Build.VERSION.SECURITY_PATCH != null ? Build.VERSION.SECURITY_PATCH : "نامشخص");
            o.put("abi", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "نامشخص");
            o.put("uptime", SystemClock.elapsedRealtime() / 1000L);
            deviceCache = o.toString();
            deviceCacheAt = now;
        } catch (Exception e) {
            Log.w(TAG, "getDeviceInfo failed", e);
        }
        return o.toString();
    }
}
