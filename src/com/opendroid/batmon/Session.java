package com.opendroid.batmon;

/** One continuous charge (plugged) or discharge (unplugged) period. */
public final class Session {
    /** Smallest level change that gives a usable capacity estimate. */
    public static final int MIN_ESTIMATE_DELTA = 15;

    public long id = -1;
    public boolean charge;
    public long startTs, endTs;
    public int startLevel, endLevel;
    public long startCounterUah = -1, endCounterUah = -1;
    /** Charge moved in mAh (into the battery for charge sessions, out of it otherwise). */
    public double mah;
    public double mahScreenOn, mahScreenOff;
    public long msScreenOn, msScreenOff;
    /**
     * Time not measured by current integration (the CPU slept, the service wasn't running), plus a
     * year for every reason to distrust the whole session (faked battery state, unit/sign change).
     */
    public long gapMs;
    public float maxTemp = Float.NaN;
    public int peakMa;
    public int plug;
    public boolean open = true;

    public long durationMs() { return Math.max(0, endTs - startTs); }

    public int deltaLevel() { return Math.abs(endLevel - startLevel); }

    /** Net charge into the battery in mAh; negative when it lost charge. */
    public double netMah() { return charge ? mah : -mah; }

    public double avgMa() {
        long ms = msScreenOn + msScreenOff;
        return ms > 0 ? mah / (ms / 3_600_000.0) : Double.NaN;
    }

    public double avgMaScreenOn() { return msScreenOn > 60_000 ? mahScreenOn / (msScreenOn / 3_600_000.0) : Double.NaN; }

    public double avgMaScreenOff() { return msScreenOff > 60_000 ? mahScreenOff / (msScreenOff / 3_600_000.0) : Double.NaN; }

    /** Why this charge session gives no capacity estimate, or null when it gives one. */
    public String capacityIssue() {
        if (!charge) return "Discharge sessions don't measure capacity";
        if (mah <= 0) return "The charger didn't keep up, so no capacity estimate";
        if (endLevel - startLevel < MIN_ESTIMATE_DELTA) {
            return "Too short to estimate capacity (needs " + MIN_ESTIMATE_DELTA + " points)";
        }
        if (gapMs > durationMs() / 10) return "Not fully measured, so not used for capacity";
        return null;
    }

    /** Full capacity implied by this charge session, or NaN (see {@link #capacityIssue()}). */
    public double estimatedCapacityMah() {
        return capacityIssue() == null ? mah * 100.0 / (endLevel - startLevel) : Double.NaN;
    }
}
