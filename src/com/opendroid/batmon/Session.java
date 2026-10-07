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
    /** Time not covered by samples (the CPU slept or the service wasn't running). */
    public long gapMs;
    public float maxTemp = Float.NaN;
    public int peakMa;
    public int plug;
    public boolean open = true;

    public long durationMs() { return Math.max(0, endTs - startTs); }

    public int deltaLevel() { return Math.abs(endLevel - startLevel); }

    public double avgMa() {
        long ms = msScreenOn + msScreenOff;
        return ms > 0 ? mah / (ms / 3_600_000.0) : Double.NaN;
    }

    public double avgMaScreenOn() { return msScreenOn > 60_000 ? mahScreenOn / (msScreenOn / 3_600_000.0) : Double.NaN; }

    public double avgMaScreenOff() { return msScreenOff > 60_000 ? mahScreenOff / (msScreenOff / 3_600_000.0) : Double.NaN; }

    /** Full capacity implied by this charge session, or NaN when it is too short or patchy. */
    public double estimatedCapacityMah() {
        if (!charge || endLevel - startLevel < MIN_ESTIMATE_DELTA || mah <= 0) return Double.NaN;
        if (gapMs > durationMs() / 10) return Double.NaN;
        return mah * 100.0 / (endLevel - startLevel);
    }
}
