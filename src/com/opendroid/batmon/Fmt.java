package com.opendroid.batmon;

import android.content.Context;
import android.os.BatteryManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Display formatting shared by the screens and the notification. */
public final class Fmt {
    public static final String MINUS = "−";
    public static final String DASH = "—";

    private Fmt() {}

    public static String signedMa(int ma) {
        if (ma == BatterySnapshot.NONE) return DASH;
        String s = String.format(Locale.getDefault(), "%,d", Math.abs(ma));
        return ma > 0 ? "+" + s : ma < 0 ? MINUS + s : s;
    }

    public static String ma(double ma) {
        return String.format(Locale.getDefault(), "%,d", Math.round(ma));
    }

    public static String watts(float w) {
        if (Float.isNaN(w)) return DASH;
        String s = String.format(Locale.getDefault(), "%.2f", Math.abs(w));
        return (w < -0.005f ? MINUS : "") + s;
    }

    public static String temp(float c, boolean fahrenheit) {
        if (Float.isNaN(c)) return DASH;
        if (fahrenheit) return String.format(Locale.getDefault(), "%.1f °F", c * 9f / 5f + 32f);
        return String.format(Locale.getDefault(), "%.1f °C", c);
    }

    public static String tempUnit(boolean fahrenheit) { return fahrenheit ? "°F" : "°C"; }

    public static float tempValue(float c, boolean fahrenheit) {
        return fahrenheit ? c * 9f / 5f + 32f : c;
    }

    public static String volts(int mv) {
        if (mv <= 0) return DASH;
        return String.format(Locale.getDefault(), "%.3f V", mv / 1000f);
    }

    public static String mah(double mah) {
        if (Double.isNaN(mah) || mah < 0) return DASH;
        return String.format(Locale.getDefault(), "%,d mAh", Math.round(mah));
    }

    public static String percent(double p) {
        if (Double.isNaN(p)) return DASH;
        return String.format(Locale.getDefault(), "%.0f %%", p);
    }

    public static String duration(long ms) {
        if (ms < 0) return DASH;
        long totalMin = ms / 60_000;
        if (totalMin < 1) return (ms / 1000) + " s";
        long h = totalMin / 60, m = totalMin % 60;
        if (h == 0) return m + " m";
        if (h < 24) return h + " h " + m + " m";
        return (h / 24) + " d " + (h % 24) + " h";
    }

    public static String time(Context c, long ts) {
        boolean is24 = android.text.format.DateFormat.is24HourFormat(c);
        return new SimpleDateFormat(is24 ? "HH:mm" : "h:mm a", Locale.getDefault()).format(new Date(ts));
    }

    public static String timeSec(Context c, long ts) {
        boolean is24 = android.text.format.DateFormat.is24HourFormat(c);
        return new SimpleDateFormat(is24 ? "HH:mm:ss" : "h:mm:ss a", Locale.getDefault()).format(new Date(ts));
    }

    public static String dayTime(Context c, long ts) {
        boolean is24 = android.text.format.DateFormat.is24HourFormat(c);
        return new SimpleDateFormat(is24 ? "EEE d MMM, HH:mm" : "EEE d MMM, h:mm a", Locale.getDefault())
                .format(new Date(ts));
    }

    public static String status(int status) {
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING: return "Charging";
            case BatteryManager.BATTERY_STATUS_DISCHARGING: return "Discharging";
            case BatteryManager.BATTERY_STATUS_FULL: return "Full";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "Not charging";
            default: return "Unknown";
        }
    }

    public static String health(int health) {
        switch (health) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return "Good";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "Overheat";
            case BatteryManager.BATTERY_HEALTH_DEAD: return "Dead";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "Over voltage";
            case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE: return "Failure";
            case BatteryManager.BATTERY_HEALTH_COLD: return "Cold";
            default: return "Unknown";
        }
    }

    public static String plug(int plugged) {
        switch (plugged) {
            case BatteryManager.BATTERY_PLUGGED_AC: return "AC charger";
            case BatteryManager.BATTERY_PLUGGED_USB: return "USB";
            case BatteryManager.BATTERY_PLUGGED_WIRELESS: return "Wireless";
            case 8: return "Dock"; // BATTERY_PLUGGED_DOCK, API 33
            case 0: return "Battery";
            default: return "Plugged";
        }
    }
}
