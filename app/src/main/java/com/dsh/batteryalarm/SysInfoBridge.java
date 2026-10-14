package com.dsh.batteryalarm;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * پل اطلاعاتی سخت‌افزار بین کد نیتیو اندروید و صفحهٔ HTML.
 * همهٔ متدها رشتهٔ JSON برمی‌گردانند.
 */
public class SysInfoBridge {
    private static final String TAG = "SysInfo";
    private final Context ctx;

    public SysInfoBridge(Context ctx) {
        this.ctx = ctx;
    }

    private String err(Exception e) {
        Log.e(TAG, "sysinfo error", e);
        try { return new JSONObject().put("error", e.getMessage()).toString(); }
        catch (Exception ignored) { return "{}"; }
    }

    private String readFile(String path) {
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new FileReader(path));
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            br.close();
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    private String readFirstLine(String path) {
        try { return readFile(path).trim(); }
        catch (Exception e) { return ""; }
    }

    private String readProp(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method m = c.getMethod("get", String.class);
            Object v = m.invoke(null, key);
            return v == null ? "" : v.toString();
        } catch (Exception e) { return ""; }
    }

    private long parseKb(String s) {
        try {
            String t = s.trim().replaceAll("[^0-9]", "");
            return t.isEmpty() ? 0 : Long.parseLong(t);
        } catch (Exception e) { return 0; }
    }

    private String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes / 1024.0;
        String[] u = {"KB", "MB", "GB", "TB"};
        int i = 0;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return String.format("%.1f %s", v, u[i]);
    }

    // ================= GPU =================
    @android.webkit.JavascriptInterface
    public String getGpuInfo() {
        try {
            JSONObject o = new JSONObject();
            final String[] gl = new String[3];
            try {
                final CountDownLatch latch = new CountDownLatch(1);
                new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            android.opengl.EGLDisplay d = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY);
                            int[] ver = new int[2];
                            android.opengl.EGL14.eglInitialize(d, ver, 0, ver, 1);
                            int[] cfgAttr = {
                                android.opengl.EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGL14.EGL_OPENGL_ES2_BIT,
                                android.opengl.EGL14.EGL_RED_SIZE, 8,
                                android.opengl.EGL14.EGL_GREEN_SIZE, 8,
                                android.opengl.EGL14.EGL_BLUE_SIZE, 8,
                                android.opengl.EGL14.EGL_NONE
                            };
                            android.opengl.EGLConfig[] cfgs = new android.opengl.EGLConfig[1];
                            int[] n = new int[1];
                            android.opengl.EGL14.eglChooseConfig(d, cfgAttr, 0, cfgs, 0, 1, n, 0);
                            if (n[0] > 0) {
                                int[] ctxAttr = { android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, android.opengl.EGL14.EGL_NONE };
                                android.opengl.EGLContext c = android.opengl.EGL14.eglCreateContext(d, cfgs[0], android.opengl.EGL14.EGL_NO_CONTEXT, ctxAttr, 0);
                                int[] pbAttr = { android.opengl.EGL14.EGL_WIDTH, 1, android.opengl.EGL14.EGL_HEIGHT, 1, android.opengl.EGL14.EGL_NONE };
                                android.opengl.EGLSurface s = android.opengl.EGL14.eglCreatePbufferSurface(d, cfgs[0], pbAttr, 0);
                                android.opengl.EGL14.eglMakeCurrent(d, s, s, c);
                                gl[0] = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_RENDERER);
                                gl[1] = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_VENDOR);
                                gl[2] = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_VERSION);
                                int[] tex = new int[1];
                                android.opengl.GLES20.glGetIntegerv(android.opengl.GLES20.GL_MAX_TEXTURE_SIZE, tex, 0);
                                android.opengl.EGL14.eglMakeCurrent(d, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_CONTEXT);
                                android.opengl.EGL14.eglDestroySurface(d, s);
                                android.opengl.EGL14.eglDestroyContext(d, c);
                                android.opengl.EGL14.eglTerminate(d);
                                String mt = (gl[0] == null) ? "" : gl[0];
                                o.put("maxTexture", tex[0]);
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "EGL query failed: " + e.getMessage());
                        } finally {
                            latch.countDown();
                        }
                    }
                });
                latch.await(3, TimeUnit.SECONDS);
            } catch (Exception e) { Log.w(TAG, "gl thread failed", e); }

            String renderer = gl[0];
            if (renderer == null || renderer.isEmpty()) renderer = readProp("ro.hardware.egl");
            String vendor = gl[1];
            if (vendor == null || vendor.isEmpty()) vendor = readProp("ro.board.platform");
            String gles = gl[2];
            if (gles == null || gles.isEmpty()) {
                int v = 0;
                try { v = Integer.parseInt(readProp("ro.opengles.version")); } catch (Exception ignored) {}
                gles = "OpenGL ES " + (v / 65536) + "." + ((v % 65536) / 256);
            }
            o.put("renderer", renderer);
            o.put("vendor", vendor);
            o.put("gles", gles);
            o.put("clock", readGpuClock());
            return o.toString();
        } catch (Exception e) { return err(e); }
    }

    private String readGpuClock() {
        String[] paths = {
            "/sys/class/kgsl/kgsl-3d0/gpuclk",
            "/sys/class/kgsl/kgsl-3d0/max_gpuclk",
            "/sys/class/kgsl/kgsl-2d0/gpuclk",
            "/sys/kernel/gpu/gpu_clock",
            "/sys/devices/platform/mali.0/clock"
        };
        for (String p : paths) {
            String v = readFirstLine(p);
            if (!v.isEmpty()) {
                try {
                    long hz = Long.parseLong(v.replaceAll("[^0-9]", ""));
                    if (hz > 1000000) return (hz / 1000000) + " MHz";
                    if (hz > 1000) return (hz / 1000) + " MHz";
                    return v + " (raw)";
                } catch (Exception ignored) {}
            }
        }
        return "نامشخص";
    }

    // ================= CPU =================
    @android.webkit.JavascriptInterface
    public String getCpuInfo() {
        try {
            JSONObject o = new JSONObject();
            int cores = Runtime.getRuntime().availableProcessors();
            o.put("cores", cores);

            String cpuinfo = readFile("/proc/cpuinfo");
            String model = "نامشخص";
            for (String line : cpuinfo.split("\n")) {
                String l = line.toLowerCase();
                if (l.startsWith("model name") || l.startsWith("hardware") || l.startsWith("processor")) {
                    String[] kv = line.split(":");
                    if (kv.length > 1 && !kv[1].trim().isEmpty()) { model = kv[1].trim(); if (!l.startsWith("processor")) break; }
                }
            }
            o.put("model", model);

            long[][] s1 = readCpuJiffies();
            try { Thread.sleep(200); } catch (Exception ignored) {}
            long[][] s2 = readCpuJiffies();

            String loadavg = readFirstLine("/proc/loadavg");
            o.put("loadavg", loadavg);
            String[] la = loadavg.split("\\s+");
            if (la.length > 0) o.put("load1", la[0]);

            JSONArray coresArr = new JSONArray();
            double totalUsage = 0;
            int valid = 0;
            for (int i = 0; i < Math.min(s1.length, s2.length); i++) {
                JSONObject c = new JSONObject();
                c.put("core", i);
                long[] a = s1[i], b = s2[i];
                long idleA = 0, idleB = 0, totA = 0, totB = 0;
                for (int j = 0; j < a.length; j++) {
                    totA += a[j];
                    if (j == 3 || j == 4) idleA += a[j];
                }
                for (int j = 0; j < b.length; j++) {
                    totB += b[j];
                    if (j == 3 || j == 4) idleB += b[j];
                }
                long totD = totB - totA, idleD = idleB - idleA;
                double usage = totD > 0 ? (1.0 - (double) idleD / totD) * 100 : 0;
                if (usage < 0) usage = 0;
                if (usage > 100) usage = 100;
                c.put("usage", Math.round(usage));
                totalUsage += usage; valid++;
                c.put("curFreq", readCpuFreq(i, "scaling_cur_freq"));
                c.put("maxFreq", readCpuFreq(i, "cpuinfo_max_freq"));
                coresArr.put(c);
            }
            o.put("avgUsage", valid > 0 ? Math.round(totalUsage / valid) : 0);
            o.put("perCore", coresArr);
            o.put("topProcs", topProcesses(true));
            return o.toString();
        } catch (Exception e) { return err(e); }
    }

    private long[][] readCpuJiffies() {
        try {
            List<long[]> list = new ArrayList<>();
            for (String line : readFile("/proc/stat").split("\n")) {
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
            long[][] arr = new long[Math.max(0, list.size() - 1)][];
            for (int i = 1; i < list.size(); i++) arr[i - 1] = list.get(i);
            return arr;
        } catch (Exception e) { return new long[0][]; }
    }

    private long readCpuFreq(int core, String key) {
        try {
            String v = readFirstLine("/sys/devices/system/cpu/cpu" + core + "/cpufreq/" + key);
            if (v.isEmpty()) return 0;
            return Long.parseLong(v.trim()) / 1000; // kHz -> MHz
        } catch (Exception e) { return 0; }
    }

    private JSONArray topProcesses(final boolean byCpu) {
        JSONArray arr = new JSONArray();
        try {
            File proc = new File("/proc");
            File[] dirs = proc.listFiles();
            if (dirs == null) return arr;
            final PackageManager pm = ctx.getPackageManager();
            List<Object[]> rows = new ArrayList<>();
            for (File f : dirs) {
                if (!f.isDirectory()) continue;
                String name = f.getName();
                if (!name.matches("\\d+")) continue;
                try {
                    String stat = readFile(f.getAbsolutePath() + "/stat");
                    int open = stat.indexOf('('), close = stat.lastIndexOf(')');
                    if (open < 0 || close < 0) continue;
                    String pname = stat.substring(open + 1, close);
                    String rest = stat.substring(close + 2).trim();
                    String[] parts = rest.split("\\s+");
                    if (parts.length < 13) continue;
                    long cpu = Long.parseLong(parts[11]) + Long.parseLong(parts[12]);
                    long rss = 0;
                    for (String line : readFile(f.getAbsolutePath() + "/status").split("\n")) {
                        if (line.startsWith("VmRSS:")) { rss = parseKb(line.substring(5)) * 1024L; break; }
                    }
                    rows.add(new Object[]{ name, pname, cpu, rss });
                } catch (Exception ignored) {}
            }
            Collections.sort(rows, new Comparator<Object[]>() {
                public int compare(Object[] a, Object[] b) {
                    if (byCpu) return Long.valueOf((Long) b[2]).compareTo((Long) a[2]);
                    return Long.valueOf((Long) b[3]).compareTo((Long) a[3]);
                }
            });
            int limit = Math.min(12, rows.size());
            for (int i = 0; i < limit; i++) {
                Object[] r = rows.get(i);
                JSONObject p = new JSONObject();
                p.put("pid", r[0]);
                p.put("name", r[1]);
                p.put("cpuTime", r[2]);
                p.put("rss", r[3]);
                try {
                    ApplicationInfo ai = pm.getApplicationInfo((String) r[1], 0);
                    p.put("label", pm.getApplicationLabel(ai));
                } catch (Exception e) { p.put("label", r[1]); }
                arr.put(p);
            }
        } catch (Exception e) { Log.w(TAG, "topProcs failed", e); }
        return arr;
    }

    // ================= Memory =================
    @android.webkit.JavascriptInterface
    public String getMemoryInfo() {
        try {
            JSONObject o = new JSONObject();
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            o.put("total", mi.totalMem);
            o.put("avail", mi.availMem);
            o.put("used", mi.totalMem - mi.availMem);
            o.put("lowMemory", mi.lowMemory);
            o.put("threshold", mi.threshold);
            o.put("usedPct", mi.totalMem > 0 ? Math.round((double) (mi.totalMem - mi.availMem) / mi.totalMem * 100) : 0);

            JSONObject detail = new JSONObject();
            for (String line : readFile("/proc/meminfo").split("\n")) {
                String[] kv = line.split(":");
                if (kv.length == 2) detail.put(kv[0].trim(), parseKb(kv[1]) * 1024L);
            }
            o.put("detail", detail);
            o.put("topProcs", topProcesses(false));
            return o.toString();
        } catch (Exception e) { return err(e); }
    }

    // ================= Storage =================
    @android.webkit.JavascriptInterface
    public String getStorageInfo() {
        try {
            JSONObject o = new JSONObject();
            File data = Environment.getDataDirectory();
            StatFs sf = new StatFs(data.getAbsolutePath());
            long total = sf.getTotalBytes();
            long avail = sf.getAvailableBytes();
            o.put("total", total);
            o.put("avail", avail);
            o.put("used", total - avail);
            o.put("usedPct", total > 0 ? Math.round((double) (total - avail) / total * 100) : 0);

            try {
                File ext = Environment.getExternalStorageDirectory();
                StatFs sf2 = new StatFs(ext.getAbsolutePath());
                JSONObject e = new JSONObject();
                e.put("total", sf2.getTotalBytes());
                e.put("avail", sf2.getAvailableBytes());
                e.put("used", sf2.getTotalBytes() - sf2.getAvailableBytes());
                o.put("external", e);
            } catch (Exception ignored) {}

            // حجم پوشه‌های رسانه‌ای (قابل خواندن)
            JSONArray cats = new JSONArray();
            String[][] medias = {
                {"عکس‌ها و دوربین", "DCIM"}, {"عکس‌ها", "Pictures"},
                {"ویدیوها", "Movies"}, {"موسیقی", "Music"},
                {"دانلودها", "Download"}, {"اسناد", "Documents"}
            };
            File base = Environment.getExternalStorageDirectory();
            for (String[] m : medias) {
                File dir = new File(base, m[1]);
                if (dir.exists()) {
                    JSONObject c = new JSONObject();
                    c.put("name", m[0]);
                    c.put("size", dirSize(dir, 3000));
                    cats.put(c);
                }
            }
            // پوشه‌های رسانهٔ برنامه‌های رایج
            String[] appMedias = {"WhatsApp/Media", "Telegram", "Instagram", "Snapchat", "Viber"};
            for (String am : appMedias) {
                File dir = new File(base, am);
                if (dir.exists()) {
                    JSONObject c = new JSONObject();
                    c.put("name", am.split("/")[0]);
                    c.put("size", dirSize(dir, 3000));
                    cats.put(c);
                }
            }
            o.put("categories", cats);

            // حافظهٔ کش این برنامه
            try { o.put("appCache", dirSize(ctx.getCacheDir(), 99999)); }
            catch (Exception ignored) {}
            return o.toString();
        } catch (Exception e) { return err(e); }
    }

    private long dirSize(File dir, int maxFiles) {
        long size = 0;
        int[] count = new int[1];
        try { size = walk(dir, count, maxFiles); } catch (Exception ignored) {}
        return size;
    }

    private long walk(File dir, int[] count, int max) {
        if (count[0] > max) return 0;
        long size = 0;
        File[] files = dir.listFiles();
        if (files == null) return dir.exists() ? dir.length() : 0;
        for (File f : files) {
            if (count[0] > max) break;
            if (f.isDirectory()) size += walk(f, count, max);
            else { size += f.length(); count[0]++; }
        }
        return size;
    }

    // ================= Battery / Thermal =================
    @android.webkit.JavascriptInterface
    public String getBatteryInfo() {
        try {
            JSONObject o = new JSONObject();
            IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent i = ctx.registerReceiver(null, f);
            if (i != null) {
                int level = i.getIntExtra("level", -1);
                int scale = i.getIntExtra("scale", -1);
                o.put("percent", scale > 0 ? (level * 100) / scale : 0);
                o.put("temperature", i.getIntExtra("temperature", -1) / 10.0);
                o.put("voltage", i.getIntExtra("voltage", -1));
                o.put("plugged", i.getIntExtra("plugged", 0) > 0);
                int status = i.getIntExtra("status", -1);
                o.put("status", status);
                int health = i.getIntExtra("health", -1);
                String hs = "نامشخص";
                if (health == 2) hs = "خوب";
                else if (health == 3) hs = "گرم";
                else if (health == 4) hs = "مرده";
                else if (health == 5) hs = "ولتاژ بیش از حد";
                else if (health == 6) hs = "نامشخص";
                else if (health == 7) hs = "سرد";
                o.put("health", hs);
                o.put("technology", i.getStringExtra("technology"));
            }
            // حرارت CPU
            JSONArray zones = new JSONArray();
            for (int z = 0; z < 12; z++) {
                String t = readFirstLine("/sys/class/thermal/thermal_zone" + z + "/temp");
                String type = readFirstLine("/sys/class/thermal/thermal_zone" + z + "/type");
                if (!t.isEmpty()) {
                    try {
                        double temp = Double.parseDouble(t.trim());
                        if (temp > 1000) temp /= 1000;
                        JSONObject zj = new JSONObject();
                        zj.put("type", type.isEmpty() ? ("zone" + z) : type);
                        zj.put("temp", Math.round(temp * 10) / 10.0);
                        zones.put(zj);
                    } catch (Exception ignored) {}
                }
            }
            o.put("thermal", zones);
            return o.toString();
        } catch (Exception e) { return err(e); }
    }

    // ================= Device =================
    @android.webkit.JavascriptInterface
    public String getDeviceInfo() {
        try {
            JSONObject o = new JSONObject();
            o.put("model", Build.MODEL);
            o.put("manufacturer", Build.MANUFACTURER);
            o.put("brand", Build.BRAND);
            o.put("device", Build.DEVICE);
            o.put("product", Build.PRODUCT);
            o.put("board", Build.BOARD);
            o.put("android", Build.VERSION.RELEASE);
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("security", Build.VERSION.SECURITY_PATCH);
            o.put("bootloader", Build.BOOTLOADER);
            o.put("display", Build.DISPLAY);
            o.put("abi", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "");
            o.put("kernel", readFirstLine("/proc/version").trim());
            o.put("uptime", android.os.SystemClock.elapsedRealtime() / 1000L);
            return o.toString();
        } catch (Exception e) { return err(e); }
    }

    // ================= Thresholds =================
    @android.webkit.JavascriptInterface
    public void setThresholds(String json) {
        try {
            JSONObject o = new JSONObject(json);
            SharedPreferences sp = ctx.getSharedPreferences("dsh_thresholds", Context.MODE_PRIVATE);
            SharedPreferences.Editor e = sp.edit();
            if (o.has("resEnabled")) e.putBoolean("resEnabled", o.optBoolean("resEnabled", false));
            if (o.has("memThreshold")) e.putInt("memThreshold", o.optInt("memThreshold", 90));
            if (o.has("storageThreshold")) e.putInt("storageThreshold", o.optInt("storageThreshold", 95));
            if (o.has("cpuThreshold")) e.putInt("cpuThreshold", o.optInt("cpuThreshold", 95));
            if (o.has("tempThreshold")) e.putInt("tempThreshold", o.optInt("tempThreshold", 45));
            e.apply();
            Log.i(TAG, "thresholds saved");
        } catch (Exception ex) { Log.w(TAG, "setThresholds failed", ex); }
    }
}
