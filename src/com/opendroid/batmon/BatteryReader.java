package com.opendroid.batmon;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Reads the battery and normalizes what devices disagree on: CURRENT_NOW comes in µA on most
 * phones but mA on some (older Samsung), and a few report the sign inverted. Both are learned
 * from the readings themselves unless the user pins them in Settings.
 *
 * Instances keep sign-learning state, so each thread uses its own.
 */
public final class BatteryReader {
    private static final IntentFilter BATTERY_CHANGED = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
    /** No phone draws 15 A, so any |reading| above this must be µA. */
    private static final int MICROAMP_THRESHOLD = 15_000;
    /** Distinct unplugged readings that must agree before the learned sign flips. */
    private static final int SIGN_VOTES = 5;
    /** Ignore readings this soon after unplugging; some gauges lag behind the plug state. */
    private static final long UNPLUG_SETTLE_MS = 15_000;

    // Battery-changed extras added in Android 16, in µAh. Not in the public SDK, so read by name.
    private static final String EXTRA_MAXIMUM_CAPACITY = "android.os.extra.MAXIMUM_CAPACITY";
    private static final String EXTRA_DESIGN_CAPACITY = "android.os.extra.DESIGN_CAPACITY";

    private static int designCapacityCache = -1;

    private final Context ctx;
    private final BatteryManager bm;
    private final Prefs prefs;
    private int signVotes;
    private int lastVoteRaw = Integer.MIN_VALUE;
    private long lastPluggedElapsed = -1;

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
        s.elapsed = SystemClock.elapsedRealtime();
        if (i != null) {
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (level >= 0 && scale > 0) s.level = Math.round(level * 100f / scale);
            s.status = i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
            s.plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            s.health = i.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN);
            s.technology = i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
            s.voltageMv = normalizeVoltage(i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0));
            int t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (t != Integer.MIN_VALUE) s.tempC = t / 10f;
            if (Build.VERSION.SDK_INT >= 34) {
                int cycles = i.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1);
                if (cycles >= 0) s.cycleCount = cycles;
            }
            s.maxCapacityMah = capacityExtraMah(i, EXTRA_MAXIMUM_CAPACITY);
        }
        if (s.isPlugged()) lastPluggedElapsed = s.elapsed;
        if (bm != null) {
            int raw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            s.rawCurrent = raw;
            if (raw != Integer.MIN_VALUE && !zeroMeansUnsupported(raw)) {
                boolean microAmps = learnUnit(raw);
                int ma = microAmps ? raw / 1000 : raw;
                boolean inverted = learnSign(raw, ma, s);
                s.currentMa = inverted ? -ma : ma;
                s.convention = (microAmps ? 1 : 0) | (inverted ? 2 : 0);
            }
            int counter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            if (counter > 0 && counter != Integer.MIN_VALUE) s.chargeCounterUah = normalizeCounter(counter);
            if (Build.VERSION.SDK_INT >= 28) s.chargeTimeRemainingMs = bm.computeChargeTimeRemaining();
        }
        return s;
    }

    /**
     * Before Android 9, getIntProperty returned 0 rather than MIN_VALUE for an unsupported property,
     * so there a 0 only counts as a real reading once the phone has reported a non-zero current.
     */
    private boolean zeroMeansUnsupported(int raw) {
        if (Build.VERSION.SDK_INT >= 28) return false;
        if (raw != 0) {
            prefs.set(Prefs.CURRENT_NONZERO_SEEN, true);
            return false;
        }
        return !prefs.bool(Prefs.CURRENT_NONZERO_SEEN);
    }

    private boolean learnUnit(int raw) {
        if ("auto".equals(prefs.string(Prefs.CURRENT_UNIT)) && Math.abs(raw) >= MICROAMP_THRESHOLD) {
            prefs.set(Prefs.LEARNED_MICROAMPS, true);
        }
        return usesMicroAmps(prefs);
    }

    /**
     * Unplugged, the battery can only discharge, so the reading's sign tells us the convention. Only
     * readings that differ from the previous vote count, so a gauge stuck on its last charging value
     * can't outvote the real discharge.
     */
    private boolean learnSign(int raw, int ma, BatterySnapshot s) {
        if ("auto".equals(prefs.string(Prefs.CURRENT_SIGN)) && !s.isPlugged()
                && s.status == BatteryManager.BATTERY_STATUS_DISCHARGING && Math.abs(ma) >= 20
                && (lastPluggedElapsed < 0 || s.elapsed - lastPluggedElapsed > UNPLUG_SETTLE_MS)
                && raw != lastVoteRaw) {
            lastVoteRaw = raw;
            boolean looksInverted = ma > 0;
            if (looksInverted == prefs.bool(Prefs.LEARNED_INVERTED)) {
                signVotes = 0;
            } else if (++signVotes >= SIGN_VOTES) {
                prefs.set(Prefs.LEARNED_INVERTED, looksInverted);
                signVotes = 0;
            }
        }
        return isInverted(prefs);
    }

    /** Whether raw current is treated as µA: pinned in Settings, or learned. */
    public static boolean usesMicroAmps(Prefs p) {
        String unit = p.string(Prefs.CURRENT_UNIT);
        return "ua".equals(unit) || (!"ma".equals(unit) && p.bool(Prefs.LEARNED_MICROAMPS));
    }

    /** Whether the raw sign is flipped: pinned in Settings, or learned. */
    public static boolean isInverted(Prefs p) {
        String sign = p.string(Prefs.CURRENT_SIGN);
        return "inverted".equals(sign) || (!"normal".equals(sign) && p.bool(Prefs.LEARNED_INVERTED));
    }

    /** "µA (auto-detected), sign normal (set in Settings)" and the like. */
    public static String conventionSummary(Prefs p) {
        boolean unitAuto = "auto".equals(p.string(Prefs.CURRENT_UNIT));
        boolean signAuto = "auto".equals(p.string(Prefs.CURRENT_SIGN));
        return (usesMicroAmps(p) ? "µA" : "mA") + (unitAuto ? " (auto-detected)" : " (set in Settings)")
                + ", sign " + (isInverted(p) ? "inverted" : "normal")
                + (signAuto ? " (auto-detected)" : " (set in Settings)");
    }

    /**
     * CHARGE_COUNTER is µAh per the docs, but a few devices report mAh. A mAh value can't exceed the
     * battery's capacity, while a µAh value is that small only within a few mAh of empty.
     */
    private long normalizeCounter(int counter) {
        int design = designCapacityMah(ctx);
        long mahLimit = design > 0 ? design * 3L / 2 : 20_000;
        return counter <= mahLimit ? counter * 1000L : counter;
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
        rows.add(row("Current", conventionSummary(prefs)));
        rows.add(row("Design capacity (system)", systemDesignCapacityMah(ctx) + " mAh"));
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
        if (v == Integer.MIN_VALUE) return "unsupported";
        if (v == 0 && Build.VERSION.SDK_INT < 28) return "0 (Android 8 also reports unsupported as 0)";
        return String.valueOf(v);
    }

    private String longProp(int id) {
        long v = bm.getLongProperty(id);
        return v == Long.MIN_VALUE ? "unsupported" : String.valueOf(v);
    }

    private static String[] row(String k, String v) { return new String[] {k, v}; }
}
