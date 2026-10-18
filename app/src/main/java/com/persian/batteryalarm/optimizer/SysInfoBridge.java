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
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * پل اطلاعات سخت‌افزار (نسخه ۱.۰.۳ - داده واقعی و کامل).
 *
 * اصلاحات ۱.۰.۳ (باگ‌های گزارش‌شده «هیچ داده‌ای نشان داده نمی‌شود»):
 *  - دمای پردازنده: اسکن همه thermal zones و انتخاب zone مربوط به CPU/SOC بر اساس
 *    field type (قبلاً فقط zone0 که در بسیاری از دستگاه‌ها باتری/بدنه است).
 *  - فرکانس: fallback به scaling_max_freq + گزارش فرکانس برای همه هسته‌ها حتی
 *    وقتی دلتای /proc/stat هنوز در دسترس نیست (قبل از این فرکانس تا ابد «—» می‌ماند).
 *  - بار کل: fallback به /proc/loadavg برای اولین نمونه‌برداری (به‌جای «—»).
 *  - حافظه داخلی: بررسی چند کاندید (دیتا/ایموله/فایل‌های خارجی) و گزارش
 *    بزرگ‌ترین ظرفیت = حافظه واقعی کاربر + حجم‌های ثانویه (SD/USB).
 *  - رم: خواندن MemFree/MemAvailable از /proc/meminfo + فیل‌بک MemTotal.
 *
 * اصول قدیمی:
 *  - کش‌گذاری نتایج (CPU ۱.۵ ثانیه، رم ۱.۲ ثانیه، حافظه ۴ ثانیه).
 *  - بدون داده ساختگی؛ در صورت عدم دسترسی -1 و «unknown» (UI: «—»).
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
            int[] usagePerCore = new int[Math.max(cores, cur != null ? cur.length : 0)];
            for (int i = 0; i < usagePerCore.length; i++) usagePerCore[i] = -1;
            int avgUsage = -1;
            boolean jiffiesValid = false;

            if (cur != null && cur.length > 0 && lastJiffies != null
                    && lastJiffies.length == cur.length && (now - lastJiffiesTime) > 400) {
                double totalUsage = 0;
                int valid = 0;
                for (int i = 0; i < cur.length && i < usagePerCore.length; i++) {
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
                    usagePerCore[i] = (int) Math.round(usage);
                    totalUsage += usage;
                    valid++;
                }
                if (valid > 0) {
                    avgUsage = (int) Math.round(totalUsage / valid);
                    jiffiesValid = true;
                }
            }
            if (cur != null) {
                lastJiffies = cur;
                lastJiffiesTime = now;
            }

            // باگ‌فیکس ۱.۰.۳: فرکانس برای همه هسته‌ها همیشه گزارش می‌شود (حتی بدون
            // دلتای jiffies) تا «فرکانس کاری» در اولین خواندن هم خالی نماند.
            JSONArray coresArr = new JSONArray();
            long curFreqMax = -1;
            long maxFreqMax = -1;
            for (int i = 0; i < usagePerCore.length; i++) {
                long f = readCpuFreq(i, "scaling_cur_freq");
                long mx = readCpuFreq(i, "cpuinfo_max_freq", "scaling_max_freq");
                if (f > curFreqMax) curFreqMax = f;
                if (mx > maxFreqMax) maxFreqMax = mx;
                JSONObject c = new JSONObject();
                c.put("core", i);
                c.put("usage", usagePerCore[i]);
                c.put("curFreq", f);
                c.put("maxFreq", mx);
                coresArr.put(c);
            }

            // باگ‌فیکس ۱.۰.۳: تا زمانی که دلتای /proc/stat آماده نیست (نمونه اول)،
            // از /proc/loadavg تخمین صادقانه می‌گیریم تا «بار کل» خالی نماند.
            String usageSource = "jiffies";
            String loadavg = readFirstLine("/proc/loadavg");
            if (avgUsage < 0) {
                try {
                    double load1 = Double.parseDouble(loadavg.split("\\s+")[0]);
                    if (load1 > 0 && cores > 0) {
                        double est = load1 / cores * 100.0;
                        avgUsage = (int) Math.round(Math.min(100.0, Math.max(0.0, est)));
                        usageSource = "loadavg-estimate";
                    }
                } catch (Exception ignored) {}
            }

            o.put("perCore", coresArr);
            o.put("avgUsage", avgUsage);
            o.put("avgUsageAvailable", avgUsage >= 0);
            o.put("usageSource", usageSource);
            o.put("loadavg", loadavg.isEmpty() ? "نامشخص" : loadavg);
            o.put("curFreqMHz", curFreqMax);
            o.put("maxFreqMHz", maxFreqMax);

            // باگ‌فیکس ۱.۰.۳: دمای واقعی CPU از اسکن thermal zones (نه فقط zone0)
            double cpuTemp = readCpuTemperature();
            o.put("temperature", cpuTemp);
            o.put("temperatureAvailable", cpuTemp > 0);

            cpuCache = o.toString();
            cpuCacheAt = now;
        } catch (Exception e) {
            Log.w(TAG, "getCpuInfo failed", e);
        }
        return o.toString();
    }

    /**
     * دمای واقعی واحد مرکزی پردازنده.
     * در بسیاری از دستگاه‌ها thermal_zone0 مربوط به باتری یا بدنه است؛
     * بنابراین همه zoneها را می‌خوانیم و از field نوع (type) هر zone،
     * zone مربوط به CPU/SOC را انتخاب می‌کنیم (با امتیازدهی).
     * مقدار برگشتی: درجه سانتی‌گراد (double) یا -1 در صورت عدم دسترسی.
     */
    private long readCpuTemperature() {
        long bestVal = -1;
        int bestScore = -1;
        long fallback0 = -1;
        for (int z = 0; z <= 15; z++) {
            String raw = readFirstLine("/sys/class/thermal/thermal_zone" + z + "/temp");
            long v = 0;
            try { v = Long.parseLong(raw.replaceAll("[^0-9]", "")); } catch (Exception ignored) {}
            if (v <= 0) continue;
            if (z == 0) fallback0 = v;
            String type = readFirstLine("/sys/class/thermal/thermal_zone" + z + "/type");
            int score = thermalTypeScore(type);
            if (score > bestScore) {
                bestScore = score;
                bestVal = v;
            }
        }
        if (bestScore < 0) bestVal = fallback0;
        if (bestVal <= 0) return -1;
        // سنسورها معمولاً میلی‌درجه گزارش می‌دهند (42500 = 42.5°)؛ برخی درجه (42).
        return bestVal > 1000 ? bestVal / 1000 : bestVal;
    }

    private int thermalTypeScore(String type) {
        if (type == null) return -1;
        String t = type.trim().toLowerCase(java.util.Locale.US);
        if (t.isEmpty()) return -1;
        if (t.contains("cpu-thermal") || t.contains("cpu_thermal")) return 100;
        if (t.contains("soc") || t.contains("xusr")) return 90;
        if (t.contains("cpu")) return 80;
        if (t.contains("tsens")) return 60;
        // سنسورهایی که ربطی به CPU ندارند - امتیاز منفی
        if (t.contains("battery") || t.contains("batt") || t.contains("skin")
                || t.contains("gpu") || t.contains("charge") || t.contains("nv")) return -1;
        return 40; // type ناشناخته ولی مقدار معتبر - پایین‌ترین اولویت
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

    /**
     * فرکانس هسته به مگاهرتز (مقدار raw در sysfs بر حسب کیلوهرتز است).
     * باگ‌فیکس ۱.۰.۳: پشتیبانی از چند کلید با fallback (در برخی دستگاه‌ها
     * cpuinfo_max_freq وجود ندارد و فقط scaling_max_freq هست).
     */
    private long readCpuFreq(int core, String... keys) {
        for (String key : keys) {
            try {
                String v = readFirstLine("/sys/devices/system/cpu/cpu" + core + "/cpufreq/" + key);
                if (v == null || v.isEmpty()) continue;
                long hz = Long.parseLong(v.trim());
                if (hz > 0) return hz / 1000; // kHz -> MHz
            } catch (Exception ignored) {}
        }
        return -1;
    }

    // ================= RAM =================
    // باگ‌فیکس ۱.۰.۳:
    //  - فیل‌بک به /proc/meminfo در صورتی که ActivityManager عدد صفر برگرداند
    //  - گزارش دقیق MemFree و MemAvailable جدا از هم (قبل‌تر فقط cached بود)
    //  - افزونه usedReal = مصرف واقعی بدون کش (برای نوار رنگی و افسانه‌ها)
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
            long avail = mi.availMem;
            if (total <= 0) total = readMeminfoValue("MemTotal");
            if (avail <= 0) avail = readMeminfoValue("MemAvailable");
            if (avail <= 0) avail = readMeminfoValue("MemFree");

            long used = Math.max(0, total - avail);
            int usedPct = total > 0 ? (int) Math.round((double) used / total * 100) : -1;

            long cached = readMeminfoValue("Cached") + readMeminfoValue("Buffers");
            long memFree = readMeminfoValue("MemFree");
            long memAvailable = readMeminfoValue("MemAvailable");
            if (memAvailable <= 0) memAvailable = avail;
            long usedReal = Math.max(0, used - cached);

            o.put("total", total);
            o.put("avail", avail);
            o.put("used", used);
            o.put("usedReal", usedReal);
            o.put("usedPct", usedPct);
            o.put("lowMemory", mi.lowMemory);
            o.put("threshold", mi.threshold);
            o.put("cached", cached);
            o.put("memFree", memFree);
            o.put("memAvailable", memAvailable);
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
    // باگ‌فیکس ۱.۰.۳ (گزارش: «حافظه واقعی رو نشون نمیده»):
    // قبلاً فقط پارتیشن /data اندازه‌گیری می‌شد که در برخی دستگاه‌ها با ظرفیتی
    // که کاربر در تنظیمات اندروید می‌بیند یکی نیست. حالا چند کاندید بررسی می‌شود:
    //   /data ، /storage/emulated/0 ، Directory خارجی کاربر ، فایل‌های خارجی اپ
    // و بزرگ‌ترین ظرفیت به‌عنوان «حافظه داخلی واقعی» گزارش می‌شود. هر پارتیشن
    // کاملاً مجزا (مثلاً SD کارت/USB، تفاوت بیش از ۱ گیگ) هم به‌عنوان حجم
    // ثانویه در آرایه volumes جداگانه اعلام می‌شود.
    @JavascriptInterface
    public String getStorageInfo() {
        long now = SystemClock.elapsedRealtime();
        if (storageCache != null && (now - storageCacheAt) < STORAGE_TTL) return storageCache;
        JSONObject o = new JSONObject();
        try {
            List<String[]> paths = new ArrayList<>();
            try { paths.add(new String[]{Environment.getDataDirectory().getAbsolutePath(), "data"}); } catch (Exception ignored) {}
            paths.add(new String[]{"/storage/emulated/0", "emulated"});
            try { paths.add(new String[]{Environment.getExternalStorageDirectory().getAbsolutePath(), "external"}); } catch (Exception ignored) {}
            try {
                File ext = ctx.getExternalFilesDir(null);
                if (ext != null) paths.add(new String[]{ext.getAbsolutePath(), "app-external"});
            } catch (Exception ignored) {}

            JSONArray vols = new JSONArray();
            long bestTotal = -1;
            long bestAvail = -1;
            String bestPath = "";
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String[] p : paths) {
                try {
                    File file = new File(p[0]);
                    if (!file.exists()) continue;
                    String canonical = file.getCanonicalPath();
                    if (!seen.add(canonical)) continue;
                    StatFs sf = new StatFs(canonical);
                    long total = sf.getTotalBytes();
                    if (total <= 0) continue;
                    long avail = sf.getAvailableBytes();
                    if (total > bestTotal) {
                        bestTotal = total;
                        bestAvail = avail;
                        bestPath = p[1];
                    }
                } catch (Exception ignored) {}
            }

            if (bestTotal <= 0) {
                o.put("total", 0);
                o.put("avail", 0);
                o.put("used", 0);
                o.put("usedPct", -1);
                o.put("source", "unknown");
                storageCache = o.toString();
                storageCacheAt = now;
                return storageCache;
            }

            long used = Math.max(0, bestTotal - bestAvail);
            o.put("total", bestTotal);
            o.put("avail", bestAvail);
            o.put("used", used);
            o.put("usedPct", (int) Math.round((double) used / bestTotal * 100));
            o.put("source", bestPath);

            // حجم‌های ثانویه (SD/USB یا پارتیشن مجزا) — فقط اگر واقعاً متفاوت باشند
            try {
                File ext = ctx.getExternalFilesDir(null);
                if (ext != null) {
                    String canonical = ext.getCanonicalPath();
                    StatFs sf = new StatFs(canonical);
                    long t = sf.getTotalBytes();
                    if (t > 0 && Math.abs(t - bestTotal) > 1073741824L) {
                        JSONObject v = new JSONObject();
                        v.put("total", t);
                        v.put("avail", sf.getAvailableBytes());
                        v.put("used", Math.max(0, t - sf.getAvailableBytes()));
                        v.put("usedPct", (int) Math.round((double) v.optLong("used") / t * 100));
                        v.put("label", "حافظه خارجی");
                        vols.put(v);
                    }
                }
            } catch (Exception ignored) {}
            o.put("volumes", vols);

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
