package com.persian.batteryalarm.optimizer;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * مخزن مرکزی تنظیمات برنامه (v1.0.1 - رفع باگ ذخیره‌سازی).
 *
 * پیش از این نسخه، رابط کاربری تنظیمات را فقط در localStorage مرورگر وب‌ویو ذخیره می‌کرد.
 * چون صفحه با اسکیم file:// بارگذاری می‌شد، دسترسی به localStorage در وب‌ویو اندروید
 * ممنوع است و مقدارها دور ریخته می‌شدند؛ همچنین هیچ مسیر «بازخوانی» تنظیمات وجود نداشت.
 * اکنون همه تنظیمات (سقف شارژ، صدا، ویبره، حجم زنگ، حالت پایش...) اینجا ذخیره و
 * در هر بار اجرا بازخوانی می‌شوند.
 */
public final class MonitorPrefs {

    private MonitorPrefs() {}

    public static final String FILE = "persian_battery_prefs";

    // ---------- کلیدهای تنظیمات کاربر ----------
    public static final String TARGET_PERCENT = "target_percent";
    public static final String LOW_PERCENT = "low_percent";
    public static final String OVERHEAT_THRESHOLD = "overheat_threshold";
    public static final String ALARM_VOLUME = "alarm_volume";
    public static final String ALARM_AUTO_STOP_SEC = "alarm_auto_stop_sec";
    public static final String ALARM_COOLDOWN_SEC = "alarm_cooldown_sec";
    public static final String VOICE_ENABLED = "voice_enabled";
    public static final String LOW_ENABLED = "low_enabled";
    public static final String OVERHEAT_ENABLED = "overheat_enabled";
    public static final String UNPLUG_ENABLED = "unplug_alarm_enabled";
    public static final String VIBRATE_ENABLED = "vibrate_enabled";
    public static final String SIREN_LOOP_ENABLED = "siren_loop_enabled";
    public static final String FORCE_SYSTEM_VOLUME = "force_system_volume";
    public static final String MONITORING_ENABLED = "monitoring_enabled";
    public static final String LOW_POWER_MODE = "low_power_mode";

    // ---------- کلیدهای وضعیت (در UI نمایش داده نمی‌شوند) ----------
    public static final String S_TARGET_ARMED = "state_target_armed";
    public static final String S_LOW_FIRED = "state_low_fired";
    public static final String S_OVERHEAT_AT = "state_overheat_at";
    public static final String S_UNPLUG_AT = "state_unplug_at";
    public static final String S_LAST_CHARGING = "state_last_charging";
    public static final String S_LAST_ALARM_AT = "state_last_alarm_at";
    public static final String S_LAST_ALARM_TYPE = "state_last_alarm_type";
    public static final String S_ALARM_COUNT = "state_alarm_count";
    public static final String S_BATTERY_PROMPT_DONE = "state_battery_prompt_done";
    public static final String S_FIRST_RUN_DONE = "state_first_run_done";

    private static final Map<String, Object> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put(TARGET_PERCENT, 85);
        DEFAULTS.put(LOW_PERCENT, 20);
        DEFAULTS.put(OVERHEAT_THRESHOLD, 42);
        DEFAULTS.put(ALARM_VOLUME, 100);
        DEFAULTS.put(ALARM_AUTO_STOP_SEC, 45);
        DEFAULTS.put(ALARM_COOLDOWN_SEC, 60);
        DEFAULTS.put(VOICE_ENABLED, Boolean.TRUE);
        DEFAULTS.put(LOW_ENABLED, Boolean.TRUE);
        DEFAULTS.put(OVERHEAT_ENABLED, Boolean.TRUE);
        DEFAULTS.put(UNPLUG_ENABLED, Boolean.TRUE);
        DEFAULTS.put(VIBRATE_ENABLED, Boolean.TRUE);
        DEFAULTS.put(SIREN_LOOP_ENABLED, Boolean.TRUE);
        DEFAULTS.put(FORCE_SYSTEM_VOLUME, Boolean.FALSE);
        DEFAULTS.put(MONITORING_ENABLED, Boolean.TRUE);
        DEFAULTS.put(LOW_POWER_MODE, Boolean.FALSE);
    }

    public static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean isKnownKey(String key) {
        return key != null && DEFAULTS.containsKey(key);
    }

    public static boolean isBooleanKey(String key) {
        Object d = DEFAULTS.get(key);
        return d instanceof Boolean;
    }

    private static int clampFor(String key, int value) {
        if (TARGET_PERCENT.equals(key)) return clamp(value, 50, 100);
        if (LOW_PERCENT.equals(key)) return clamp(value, 5, 50);
        if (OVERHEAT_THRESHOLD.equals(key)) return clamp(value, 35, 60);
        if (ALARM_VOLUME.equals(key)) return clamp(value, 0, 100);
        if (ALARM_AUTO_STOP_SEC.equals(key)) return clamp(value, 0, 120);
        if (ALARM_COOLDOWN_SEC.equals(key)) return clamp(value, 10, 300);
        return value;
    }

    public static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    public static int getInt(Context ctx, String key, int fallback) {
        Object def = DEFAULTS.get(key);
        int d = (def instanceof Integer) ? (Integer) def : fallback;
        try {
            return sp(ctx).getInt(key, d);
        } catch (Exception e) {
            return d;
        }
    }

    public static boolean getBool(Context ctx, String key, boolean fallback) {
        Object def = DEFAULTS.get(key);
        boolean d = (def instanceof Boolean) ? (Boolean) def : fallback;
        try {
            return sp(ctx).getBoolean(key, d);
        } catch (Exception e) {
            return d;
        }
    }

    public static void putInt(Context ctx, String key, int value) {
        try {
            sp(ctx).edit().putInt(key, clampFor(key, value)).apply();
        } catch (Exception ignored) {}
    }

    public static void putBool(Context ctx, String key, boolean value) {
        try {
            sp(ctx).edit().putBoolean(key, value).apply();
        } catch (Exception ignored) {}
    }

    /** نوشتن مقدار از سمت جاوااسکریپت با حفظ نوع (bool / int). */
    public static boolean applyValue(Context ctx, String key, String jsonValue) {
        if (!isKnownKey(key) || jsonValue == null) return false;
        String v = jsonValue.trim();
        Object def = DEFAULTS.get(key);
        try {
            if (def instanceof Boolean) {
                boolean b = "true".equalsIgnoreCase(v) || "1".equals(v) || "\"true\"".equalsIgnoreCase(v);
                putBool(ctx, key, b);
                return true;
            }
            if (v.startsWith("\"") && v.endsWith("\"") && v.length() > 1) {
                v = v.substring(1, v.length() - 1);
            }
            int i = (int) Math.round(Double.parseDouble(v));
            putInt(ctx, key, i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** همه تنظیمات به صورت JSON برای بازخوانی در رابط کاربری. */
    public static JSONObject asJson(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            SharedPreferences p = sp(ctx);
            for (Map.Entry<String, Object> e : DEFAULTS.entrySet()) {
                String k = e.getKey();
                Object d = e.getValue();
                if (d instanceof Boolean) o.put(k, p.getBoolean(k, (Boolean) d));
                else if (d instanceof Integer) {
                    int dv = (Integer) d;
                    o.put(k, clampFor(k, p.getInt(k, dv)));
                } else o.put(k, p.getString(k, String.valueOf(d)));
            }
        } catch (Exception ignored) {}
        return o;
    }

    public static void resetAll(Context ctx) {
        try {
            SharedPreferences.Editor e = sp(ctx).edit();
            for (Map.Entry<String, Object> entry : DEFAULTS.entrySet()) {
                Object d = entry.getValue();
                if (d instanceof Boolean) e.putBoolean(entry.getKey(), (Boolean) d);
                else if (d instanceof Integer) e.putInt(entry.getKey(), (Integer) d);
            }
            e.apply();
        } catch (Exception ignored) {}
    }

    // ---------- دسترسی‌های تایپ‌دار ----------
    public static int targetPercent(Context c) { return clampFor(TARGET_PERCENT, getInt(c, TARGET_PERCENT, 85)); }
    public static int lowPercent(Context c) { return clampFor(LOW_PERCENT, getInt(c, LOW_PERCENT, 20)); }
    public static int overheatThreshold(Context c) { return clampFor(OVERHEAT_THRESHOLD, getInt(c, OVERHEAT_THRESHOLD, 42)); }
    public static int alarmVolume(Context c) { return clampFor(ALARM_VOLUME, getInt(c, ALARM_VOLUME, 100)); }
    public static int autoStopSec(Context c) { return clampFor(ALARM_AUTO_STOP_SEC, getInt(c, ALARM_AUTO_STOP_SEC, 45)); }
    public static int cooldownSec(Context c) { return clampFor(ALARM_COOLDOWN_SEC, getInt(c, ALARM_COOLDOWN_SEC, 60)); }
    public static boolean voiceEnabled(Context c) { return getBool(c, VOICE_ENABLED, true); }
    public static boolean lowEnabled(Context c) { return getBool(c, LOW_ENABLED, true); }
    public static boolean overheatEnabled(Context c) { return getBool(c, OVERHEAT_ENABLED, true); }
    public static boolean unplugEnabled(Context c) { return getBool(c, UNPLUG_ENABLED, true); }
    public static boolean vibrateEnabled(Context c) { return getBool(c, VIBRATE_ENABLED, true); }
    public static boolean sirenLoop(Context c) { return getBool(c, SIREN_LOOP_ENABLED, true); }
    public static boolean forceSystemVolume(Context c) { return getBool(c, FORCE_SYSTEM_VOLUME, false); }
    public static boolean monitoringEnabled(Context c) { return getBool(c, MONITORING_ENABLED, true); }
    public static boolean lowPowerMode(Context c) { return getBool(c, LOW_POWER_MODE, false); }

    public static long lastAlarmAt(Context c) {
        try { return sp(c).getLong(S_LAST_ALARM_AT, 0L); } catch (Exception e) { return 0L; }
    }

    public static void markAlarm(Context c, String type) {
        try {
            SharedPreferences p = sp(c);
            p.edit()
                    .putLong(S_LAST_ALARM_AT, System.currentTimeMillis())
                    .putString(S_LAST_ALARM_TYPE, type)
                    .putInt(S_ALARM_COUNT, p.getInt(S_ALARM_COUNT, 0) + 1)
                    .apply();
        } catch (Exception ignored) {}
    }
}
