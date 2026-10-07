package com.opendroid.batmon;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Reads the battery and normalizes what devices disagree on: CURRENT_NOW comes in µA on most
 * phones but mA on some (older Samsung), and a few report the sign inverted. Both are learned
 * from the readings themselves unless the user pins them in Settings.
 */
public final class BatteryReader {
    private static final IntentFilter BATTERY_CHANGED = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
    /** No phone draws 15 A, so any |reading| above this must be µA. */
    private static final int MICROAMP_THRESHOLD = 15_000;
    /** Consecutive unplugged readings needed before flipping the learned sign. */
    private static final int SIGN_VOTES = 5;

    // Battery-changed extras added in Android 16, in µAh. Not in the public SDK, so read by name.
    private static final String EXTRA_MAXIMUM_CAPACITY = "android.os.extra.MAXIMUM_CAPACITY";
    private static final String EXTRA_DESIGN_CAPACITY = "android.os.extra.DESIGN_CAPACITY";

    private static int designCapacityCache = -1;

    private final Context ctx;
    private final BatteryManager bm;
    private final Prefs prefs;
    private int signVotes;

    public BatteryReader(Context c) {
        ctx = c.getApplicationContext();
        bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
        prefs = Prefs.get(ctx);
    }

    public Intent stickyIntent() {
        return ctx.registerReceiver(null, BATTERY_CHANGED);
    }

    public BatterySnapshot read() {
        return read(stickyIntent());
    }

    public BatterySnapshot read(Intent i) {
        BatterySnapshot s = new BatterySnapshot();
        s.time = System.currentTimeMillis();
        if (i != null) {
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (level >= 0 && scale > 0) s.level = Math.round(level * 100f / scale);
            s.status = i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
            s.plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            s.health = i.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN);
            s.technology = i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
            s.present = i.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true);
            s.voltageMv = normalizeVoltage(i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0));
            int t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (t != Integer.MIN_VALUE) s.tempC = t / 10f;
            if (Build.VERSION.SDK_INT >= 34) {
                int cycles = i.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1);
                if (cycles >= 0) s.cycleCount = cycles;
            }
            s.maxCapacityMah = capacityExtraMah(i, EXTRA_MAXIMUM_CAPACITY);
            s.designCapacityMah = capacityExtraMah(i, EXTRA_DESIGN_CAPACITY);
        }
        if (bm != null) {
            int raw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            s.rawCurrent = raw;
            if (raw != Integer.MIN_VALUE) s.currentMa = normalizeCurrent(raw, s, true);
            int avg = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE);
            if (avg != Integer.MIN_VALUE && avg != 0) s.currentAvgMa = normalizeCurrent(avg, s, false);
            int counter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            if (counter > 0 && counter != Integer.MIN_VALUE) {
                // A handful of devices report mAh instead of µAh.
                s.chargeCounterUah = counter < 20_000 && s.level > 5 ? counter * 1000L : counter;
            }
            if (Build.VERSION.SDK_INT >= 28) s.chargeTimeRemainingMs = bm.computeChargeTimeRemaining();
        }
        return s;
    }

    private int normalizeCurrent(int raw, BatterySnapshot s, boolean learn) {
        boolean microAmps;
        String unit = prefs.string(Prefs.CURRENT_UNIT);
        if ("ua".equals(unit)) {
            microAmps = true;
        } else if ("ma".equals(unit)) {
            microAmps = false;
        } else {
            if (learn && Math.abs(raw) >= MICROAMP_THRESHOLD && !prefs.bool(Prefs.LEARNED_MICROAMPS)) {
                prefs.put(Prefs.LEARNED_MICROAMPS, true);
            }
            microAmps = prefs.bool(Prefs.LEARNED_MICROAMPS);
        }
        int ma = microAmps ? raw / 1000 : raw;

        boolean inverted;
        String sign = prefs.string(Prefs.CURRENT_SIGN);
        if ("normal".equals(sign)) {
            inverted = false;
        } else if ("inverted".equals(sign)) {
            inverted = true;
        } else {
            boolean learned = prefs.bool(Prefs.LEARNED_INVERTED);
            // Unplugged, the battery can only discharge, so the reading's sign tells us the convention.
            if (learn && !s.isPlugged() && s.status == BatteryManager.BATTERY_STATUS_DISCHARGING
                    && Math.abs(ma) >= 20) {
                boolean looksInverted = ma > 0;
                if (looksInverted != learned) {
                    if (++signVotes >= SIGN_VOTES) {
                        prefs.put(Prefs.LEARNED_INVERTED, looksInverted);
                        learned = looksInverted;
                        signVotes = 0;
                    }
                } else {
                    signVotes = 0;
                }
            }
            inverted = learned;
        }
        return inverted ? -ma : ma;
    }

    private static int capacityExtraMah(Intent i, String key) {
        int v = i.getIntExtra(key, -1);
        int mah = v >= 100_000 ? v / 1000 : v; // µAh normally; tolerate mAh
        return mah >= 500 && mah <= 50_000 ? mah : -1;
    }

    private static int normalizeVoltage(int v) {
        if (v > 0 && v < 100) return v * 1000;  // reported in V
        if (v > 100_000) return v / 1000;       // reported in µV
        return v;
    }

    /** Design capacity in mAh: the user's override, else what the system reports, else 0. */
    public static int designCapacityMah(Context c) {
        int override = Prefs.get(c).integer(Prefs.DESIGN_CAPACITY);
        if (override > 0) return override;
        return systemDesignCapacityMah(c);
    }

    /** From the battery broadcast (Android 16+), else the framework power profile, else 0. */
    public static synchronized int systemDesignCapacityMah(Context c) {
        if (designCapacityCache < 0) {
            designCapacityCache = 0;
            Intent sticky = c.getApplicationContext().registerReceiver(null, BATTERY_CHANGED);
            if (sticky != null) {
                int fromBroadcast = capacityExtraMah(sticky, EXTRA_DESIGN_CAPACITY);
                if (fromBroadcast > 0) {
                    designCapacityCache = fromBroadcast;
                    return designCapacityCache;
                }
            }
            try {
                Class<?> cls = Class.forName("com.android.internal.os.PowerProfile");
                Object profile = cls.getConstructor(Context.class).newInstance(c.getApplicationContext());
                double mah = (Double) cls.getMethod("getBatteryCapacity").invoke(profile);
                if (mah > 500 && mah < 50_000) designCapacityCache = (int) Math.round(mah);
            } catch (Throwable ignored) {
                // Hidden API unavailable on this build; Settings lets the user enter it.
            }
        }
        return designCapacityCache;
    }

    /** Raw values for the diagnostics screen. */
    public List<String[]> diagnostics() {
        List<String[]> rows = new ArrayList<>();
        if (bm != null) {
            rows.add(row("CURRENT_NOW (raw)", prop(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)));
            rows.add(row("CURRENT_AVERAGE (raw)", prop(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)));
            rows.add(row("CHARGE_COUNTER (raw)", prop(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)));
            rows.add(row("ENERGY_COUNTER (raw)", longProp(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)));
            rows.add(row("CAPACITY (raw)", prop(BatteryManager.BATTERY_PROPERTY_CAPACITY)));
            if (Build.VERSION.SDK_INT >= 28) {
                rows.add(row("computeChargeTimeRemaining", String.valueOf(bm.computeChargeTimeRemaining())));
            }
        }
        rows.add(row("Learned unit", prefs.bool(Prefs.LEARNED_MICROAMPS) ? "µA" : "mA"));
        rows.add(row("Learned sign", prefs.bool(Prefs.LEARNED_INVERTED) ? "inverted" : "normal"));
        rows.add(row("PowerProfile capacity", systemDesignCapacityMah(ctx) + " mAh"));
        Intent i = stickyIntent();
        Bundle extras = i != null ? i.getExtras() : null;
        if (extras != null) {
            for (String key : new TreeSet<>(extras.keySet())) {
                rows.add(row("extra: " + key, String.valueOf(extras.get(key))));
            }
        }
        return rows;
    }

    private String prop(int id) {
        int v = bm.getIntProperty(id);
        return v == Integer.MIN_VALUE ? "unsupported" : String.valueOf(v);
    }

    private String longProp(int id) {
        long v = bm.getLongProperty(id);
        return v == Long.MIN_VALUE ? "unsupported" : String.valueOf(v);
    }

    private static String[] row(String k, String v) { return new String[] {k, v}; }
}
