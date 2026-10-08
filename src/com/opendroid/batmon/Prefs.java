package com.opendroid.batmon;

import android.content.Context;
import android.content.SharedPreferences;

/** Typed access to the app's settings and the few values it learns about the device. */
public final class Prefs {
    // Settings
    public static final String MONITOR = "monitor";
    public static final String INTERVAL = "interval_s";
    public static final String KEEP_AWAKE_CHARGING = "keep_awake_charging";
    public static final String NOTIF_ICON = "notif_icon";          // current | level | temp
    public static final String FAHRENHEIT = "fahrenheit";
    public static final String CURRENT_UNIT = "current_unit";      // auto | ua | ma
    public static final String CURRENT_SIGN = "current_sign";      // auto | normal | inverted
    public static final String SMOOTHING = "smoothing";            // 0 off, 1 normal, 2 heavy
    public static final String DESIGN_CAPACITY = "design_capacity"; // mAh, 0 = from system
    public static final String ALERT_CHARGE = "alert_charge";
    public static final String ALERT_CHARGE_LEVEL = "alert_charge_level";
    public static final String ALERT_LOW = "alert_low";
    public static final String ALERT_LOW_LEVEL = "alert_low_level";
    public static final String ALERT_TEMP = "alert_temp";
    public static final String ALERT_TEMP_C = "alert_temp_c";
    public static final String HISTORY_DAYS = "history_days";
    // Learned / internal state
    public static final String LEARNED_MICROAMPS = "learned_ua";
    public static final String LEARNED_INVERTED = "learned_inverted";
    /** Android 8 reports an unsupported CURRENT_NOW as 0; a 0 only counts once a non-zero was seen. */
    public static final String CURRENT_NONZERO_SEEN = "current_nonzero_seen";
    /** Set once the level is below the charge limit; the alert fires when charging reaches it. */
    public static final String CHARGE_ALERT_ARMED = "charge_alert_armed";
    /** Set once the level is above the low-battery level; the alert fires when it falls to it. */
    public static final String LOW_ALERT_ARMED = "low_alert_armed";
    public static final String TEMP_ALERT_ACTIVE = "temp_alert_active";
    public static final String ASKED_NOTIF_PERMISSION = "asked_notif_permission";

    private static Prefs instance;
    private final SharedPreferences sp;

    private Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("batmon", Context.MODE_PRIVATE);
    }

    public static synchronized Prefs get(Context c) {
        if (instance == null) instance = new Prefs(c);
        return instance;
    }

    public boolean bool(String key) {
        switch (key) {
            case MONITOR:
            case KEEP_AWAKE_CHARGING:
            case ALERT_CHARGE:
            case ALERT_LOW:
            case ALERT_TEMP:
            case LOW_ALERT_ARMED:
                return sp.getBoolean(key, true);
            default:
                return sp.getBoolean(key, false);
        }
    }

    public int integer(String key) {
        switch (key) {
            case INTERVAL: return sp.getInt(key, 5);
            case SMOOTHING: return sp.getInt(key, 1);
            case ALERT_CHARGE_LEVEL: return sp.getInt(key, 80);
            case ALERT_LOW_LEVEL: return sp.getInt(key, 20);
            case ALERT_TEMP_C: return sp.getInt(key, 42);
            case HISTORY_DAYS: return sp.getInt(key, 14);
            default: return sp.getInt(key, 0);
        }
    }

    public String string(String key) {
        switch (key) {
            case NOTIF_ICON: return sp.getString(key, "current");
            case CURRENT_UNIT:
            case CURRENT_SIGN:
                return sp.getString(key, "auto");
            default: return sp.getString(key, "");
        }
    }

    public void put(String key, boolean v) { sp.edit().putBoolean(key, v).apply(); }
    public void put(String key, int v) { sp.edit().putInt(key, v).apply(); }
    public void put(String key, String v) { sp.edit().putString(key, v).apply(); }

    /** Writes only when the value changes, for flags checked on every sample. */
    public void set(String key, boolean v) {
        if (bool(key) != v) put(key, v);
    }
}
