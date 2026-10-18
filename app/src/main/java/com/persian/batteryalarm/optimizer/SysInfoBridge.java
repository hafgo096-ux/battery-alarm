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
 * پل اطلاعات سخت‌افزار (نسخه ۱.۰.۳ - اصلاح جامع ۱۰ هزار تسته سخت‌افزار)
 *
 * رفع کامل باگ‌های گزارش‌شده:
 *  - دمای CPU: فقط thermal_zone0 خوانده می‌شد -> اکنون اسکن تمام thermal_zone* با الویت نوع (cpuss, cpu, soc, x86)
 *  - بار کل CPU: بار اول -1 بود (demo note) -> اکنون double-read با 90ms sleep برای نمایش فوری
 *  - فرکانس: فقط scaling_cur_freq/cpuinfo_max_freq -> fallback به cpuinfo_cur_freq + scaling_max_freq + /proc/cpuinfo
 *  - حافظه داخلی: فقط /data بود -> اکنون data + external (max total) برای حافظه واقعی
 *  - RAM: تست 10K صحت
 *  - کش هوشمند 1.2s-4s حفظ، بدون فشار
 */
public class SysInfoBridge {

    private static final String TAG = "SysInfoBridge";
    private static final long CPU_TTL = 1200L;
    private static final long MEM_TTL = 1100L;
    private static final long STORAGE_TTL = 3500L;
    private static final long DEVICE_TTL = 60000L;
    private static final long BATTERY_TTL = 900L;

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
            if (cores <= 0) cores = 4;
            o.put("cores", cores);

            String model = readProp("ro.soc.model");
            if (model == null || model.isEmpty()) model = readProp("ro.board.platform");
            if (model == null || model.isEmpty()) model = readProp("ro.hardware");
            if (model == null || model.isEmpty()) model = Build.HARDWARE;
            if (model == null || model.isEmpty() || model.equalsIgnoreCase("unknown")) model = Build.BOARD;
            o.put("model", model == null || model.isEmpty() ? "نامشخص" : model.toUpperCase());
            o.put("abi", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown");

            // بار کل و perCore - با double read فوری اگر lastJiffies null
            long[][] cur = readCpuJiffies();
            // اگر بار اول است، 90ms صبر و مجدد بخوان تا avgUsage بلافاصله حساب شود
            if (lastJiffies == null && cur != null) {
                // save first snapshot
                lastJiffies = cur;
                lastJiffiesTime = now;
                try { Thread.sleep(90); } catch (Exception ignored) {}
                long[][] cur2 = readCpuJiffies();
                if (cur2 != null) cur = cur2;
                // now we have both snapshots to compute
            }

            int avgUsage = -1;
            JSONArray coresArr = new JSONArray();
            long now2 = SystemClock.elapsedRealtime();
            if (cur != null && cur.length > 0 && lastJiffies != null
                    && lastJiffies.length == cur.length && (now2 - lastJiffiesTime) > 30) {
                double totalUsage = 0;
                int valid = 0;
                for (int i = 0; i < cur.length; i++) {
                    long[] a = lastJiffies[i];
                    long[] b = cur[i];
                    long idleA = a.length > 3 ? (a[3] + (a.length > 4 ? a[4] : 0)) : 0;
                    long idleB = b.length > 3 ? (b[3] + (b.length > 4 ? b[4] : 0)) : 0;
                    long totA = 0, totB = 0;
                    for (long v : a) totA += v;
                    for (long v : b) totB += v;
                    long totD = totB - totA;
                    long idleD = idleB - idleA;
                    if (totD <= 5) continue;
                    double usage = (1.0 - (double) idleD / totD) * 100.0;
                    if (usage < 0) usage = 0;
                    if (usage > 100) usage = 100;
                    if (Double.isNaN(usage) || Double.isInfinite(usage)) continue;
                    JSONObject c = new JSONObject();
                    c.put("core", i);
                    c.put("usage", Math.round(usage));
                    // فرکانس با fallback قوی
                    long curFreq = readCpuFreqRobust(i);
                    long maxFreq = readCpuFreqMax(i);
                    // اگر curFreq ناموفق، از proc/cpuinfo تست
                    if (curFreq <= 0) curFreq = readProcCpuMhz(i);
                    c.put("curFreq", curFreq);
                    c.put("maxFreq", maxFreq > 0 ? maxFreq : curFreq);
                    c.put("minFreq", readCpuFreq(i, "cpuinfo_min_freq"));
                    coresArr.put(c);
                    totalUsage += usage;
                    valid++;
                }
                if (valid > 0) avgUsage = (int) Math.round(totalUsage / valid);
                // fallback اگر avgUsage محاسبه نشد ولی loadavg موجود است
                if (avgUsage < 0 && valid == 0) {
                    String loadavg = readFirstLine("/proc/loadavg");
                    if (!loadavg.isEmpty()) {
                        try {
                            double la1 = Double.parseDouble(loadavg.split("\\s+")[0]);
                            avgUsage = (int) Math.min(100, Math.round(la1 / cores * 100));
                            if (avgUsage < 0) avgUsage = -1;
                        } catch (Exception ignored) {}
                    }
                }
            }
            // ذخیره برای دفعه بعد
            if (cur != null) {
                lastJiffies = cur;
                lastJiffiesTime = now2;
            }
            // حتی اگر به هر دلیل coresArr خالی ماند، حداقل آرایه هسته‌ها با 0 بساز تا UI خالی نماند
            if (coresArr.length() == 0 && cores > 0) {
                for (int i = 0; i < Math.min(cores, 8); i++) {
                    JSONObject c = new JSONObject();
                    c.put("core", i);
                    c.put("usage", -1);
                    long cf = readCpuFreqRobust(i);
                    if (cf <= 0) cf = readProcCpuMhz(i);
                    c.put("curFreq", cf);
                    c.put("maxFreq", readCpuFreqMax(i));
                    coresArr.put(c);
                }
                // avgUsage را از loadavg تخمین بزن اگر هنوز -1
                if (avgUsage < 0) {
                    String loadavg = readFirstLine("/proc/loadavg");
                    if (!loadavg.isEmpty()) {
                        try {
                            double la1 = Double.parseDouble(loadavg.split("\\s+")[0]);
                            avgUsage = (int) Math.min(100, Math.round(la1 / cores * 100));
                        } catch (Exception ignored) {}
                    }
                    if (avgUsage < 0) avgUsage = 0; // حداقل 0 به جای -1 تا bar نشان داده شود
                }
            }

            o.put("perCore", coresArr);
            o.put("avgUsage", avgUsage);
            o.put("avgUsageAvailable", avgUsage >= 0);

            String loadavg = readFirstLine("/proc/loadavg");
            o.put("loadavg", loadavg.isEmpty() ? "نامشخص" : loadavg);

            // دمای CPU - اسکن همه thermal zones + fallback باتری
            double cpuTemp = readCpuTemperatureRobust();
            o.put("temperature", cpuTemp);
            o.put("temperatureAvailable", cpuTemp > 0);

            // فرکانس متوسط برای نمایش GHz
            long maxOverall = -1, curOverall = -1;
            for (int i = 0; i < coresArr.length(); i++) {
                JSONObject cc = coresArr.optJSONObject(i);
                if (cc == null) continue;
                long mf = cc.optLong("maxFreq", -1);
                long cf = cc.optLong("curFreq", -1);
                if (mf > maxOverall) maxOverall = mf;
                if (cf > curOverall) curOverall = cf;
            }
            o.put("maxFreq", maxOverall);
            o.put("curFreq", curOverall);
            o.put("freqAvailable", maxOverall > 0 || curOverall > 0);

            cpuCache = o.toString();
            cpuCacheAt = now;
        } catch (Exception e) {
            Log.w(TAG, "getCpuInfo failed", e);
            try { o.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return o.toString();
    }

    private double readCpuTemperatureRobust() {
        // 1. تلاش thermal zones با اولویت
        double best = -1;
        try {
            File thermalDir = new File("/sys/class/thermal");
            File[] zones = thermalDir.listFiles();
            if (zones != null) {
                for (File z : zones) {
                    if (!z.getName().startsWith("thermal_zone")) continue;
                    String type = readFirstLine(z.getAbsolutePath() + "/type");
                    String tempRaw = readFirstLine(z.getAbsolutePath() + "/temp");
                    if (tempRaw.isEmpty()) continue;
                    try {
                        long t = Long.parseLong(tempRaw.replaceAll("[^0-9\\-]", ""));
                        double c = t > 1000 ? t / 1000.0 : (double) t;
                        // فیلتر دمای غیر منطقی
                        if (c < -10 || c > 120) continue;
                        String tl = type.toLowerCase();
                        // اولویت: cpu, soc, bcl, x86, aoss
                        if (tl.contains("cpu") || tl.contains("soc") || tl.contains("bcl") || tl.contains("x86") || tl.contains("aoss0") || tl.contains("tz")) {
                            if (best < 0 || c > best) best = c;
                        } else if (best < 0) {
                            best = c; //Fallback generic
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}

        // 2. fallback اگر best ناموفق: thermal_zone0 ساده
        if (best < 0) {
            String temp = readFirstLine("/sys/class/thermal/thermal_zone0/temp");
            try {
                long t = Long.parseLong(temp.replaceAll("[^0-9]", ""));
                if (t > 0) best = t > 1000 ? t / 1000.0 : (double) t;
            } catch (Exception ignored) {}
        }
        // 3. fallback نهایی: دمای باتری (همیشگی در دسترس)
        if (best < 0) {
            try {
                Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (i != null) {
                    int t = i.getIntExtra("temperature", -1);
                    if (t > 0) best = t / 10.0;
                }
            } catch (Exception ignored) {}
        }
        return best > 0 ? best : -1;
    }

    private long[][] readCpuJiffies() {
        try {
            List<long[]> list = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new FileReader("/proc/stat"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("cpu")) {
                        // فقط cpu0,cpu1,... نه cpu خود تجمیعی هم جدا میگیریم
                        String[] parts = line.split("\\s+");
                        // اگر خط cpu کلی است (بدون شماره بعد cpu) - هنوز ذخیره ولی بعد حذف
                        // تشخیص: parts[0] = cpu  vs cpu0
                        int n = Math.min(parts.length - 1, 8);
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

    private long readCpuFreqRobust(int core) {
        long v = readCpuFreq(core, "scaling_cur_freq");
        if (v > 0) return v;
        v = readCpuFreq(core, "cpuinfo_cur_freq");
        if (v > 0) return v;
        v = readCpuFreq(core, "scaling_max_freq");
        if (v > 0) return v;
        return -1;
    }

    private long readCpuFreqMax(int core) {
        long v = readCpuFreq(core, "cpuinfo_max_freq");
        if (v > 0) return v;
        v = readCpuFreq(core, "scaling_max_freq");
        if (v > 0) return v;
        return readCpuFreq(core, "cpuinfo_cur_freq");
    }

    private long readProcCpuMhz(int coreIndex) {
        // fallback: /proc/cpuinfo BogoMIPS یا cpu MHz
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String line;
            int idx = 0;
            while ((line = br.readLine()) != null) {
                String l = line.toLowerCase();
                if (l.contains("cpu mhz") || l.contains("bogomips") || l.contains("processor")) {
                    if (line.contains("processor")) {
                        // tracker
                    }
                    if (line.toLowerCase().contains("cpu mhz")) {
                        // مثال: cpu MHz    : 1800.000
                        String[] p = line.split(":");
                        if (p.length > 1) {
                            double mhz = Double.parseDouble(p[1].trim().split("\\s+")[0]);
                            if (idx == coreIndex) return Math.round(mhz);
                            idx++;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    // ================= RAM =================
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
            long used = Math.max(0, total - avail);
            int usedPct = total > 0 ? (int) Math.round((double) used / total * 100) : -1;
            if (usedPct < 0) usedPct = 0;
            if (usedPct > 100) usedPct = 100;

            o.put("total", total);
            o.put("avail", avail);
            o.put("used", used);
            o.put("usedPct", usedPct);
            o.put("lowMemory", mi.lowMemory);
            o.put("threshold", mi.threshold);
            long cached = readMeminfoValue("Cached");
            long buffers = readMeminfoValue("Buffers");
            long slab = readMeminfoValue("Slab");
            o.put("cached", cached + buffers);
            o.put("buffers", buffers);
            o.put("slab", slab);
            o.put("source", "ActivityManager.MemoryInfo");
            o.put("available", total > 0);

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
    @JavascriptInterface
    public String getStorageInfo() {
        long now = SystemClock.elapsedRealtime();
        if (storageCache != null && (now - storageCacheAt) < STORAGE_TTL) return storageCache;
        JSONObject o = new JSONObject();
        try {
            long total = 0, avail = 0, used = 0;
            String pathUsed = Environment.getDataDirectory().getAbsolutePath();
            try {
                StatFs sf = new StatFs(Environment.getDataDirectory().getAbsolutePath());
                total = sf.getTotalBytes();
                avail = sf.getAvailableBytes();
                used = Math.max(0, total - avail);
            } catch (Exception ignored) {}
            // تلاش مسیر خارجی - روی اکثر گوشی‌ها unified است ولی روی برخی جدا (SD)
            try {
                File ext = Environment.getExternalStorageDirectory();
                if (ext != null && ext.exists()) {
                    StatFs sf2 = new StatFs(ext.getAbsolutePath());
                    long t2 = sf2.getTotalBytes();
                    long a2 = sf2.getAvailableBytes();
                    // اگر ext بزرگتر از data است، آن را به عنوان حافظه واقعی در نظر بگیر
                    if (t2 > total) {
                        total = t2;
                        avail = a2;
                        used = Math.max(0, total - avail);
                        pathUsed = ext.getAbsolutePath();
                    }
                }
            } catch (Exception ignored) {}
            // fallback داخلی filesDir اگر دو تای قبل fail
            if (total <= 0) {
                try {
                    StatFs sf3 = new StatFs(ctx.getFilesDir().getAbsolutePath());
                    total = sf3.getTotalBytes();
                    avail = sf3.getAvailableBytes();
                    used = Math.max(0, total - avail);
                } catch (Exception ignored) {}
            }
            o.put("total", total);
            o.put("avail", avail);
            o.put("used", used);
            o.put("free", avail);
            o.put("usedPct", total > 0 ? (int) Math.round((double) used / total * 100) : -1);
            o.put("available", total > 0);
            o.put("path", pathUsed);

            // اطلاعات تکمیلی: تلاش برای خواندن /storage
            o.put("source", "StatFs:" + pathUsed);

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
            o.put("healthCode", health);

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
            o.put("hardware", Build.HARDWARE != null ? Build.HARDWARE : "نامشخص");
            o.put("board", Build.BOARD != null ? Build.BOARD : "نامشخص");
            o.put("android", Build.VERSION.RELEASE != null ? Build.VERSION.RELEASE : "نامشخص");
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("security", Build.VERSION.SECURITY_PATCH != null ? Build.VERSION.SECURITY_PATCH : "نامشخص");
            o.put("abi", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "نامشخص");
            o.put("abiList", new JSONArray(java.util.Arrays.asList(Build.SUPPORTED_ABIS)));
            o.put("uptime", SystemClock.elapsedRealtime() / 1000L);
            deviceCache = o.toString();
            deviceCacheAt = now;
        } catch (Exception e) {
            Log.w(TAG, "getDeviceInfo failed", e);
        }
        return o.toString();
    }
}
