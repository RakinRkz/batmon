package com.opendroid.batmon;

import java.util.function.IntSupplier;

/**
 * Splits the sample stream into charge / discharge sessions and adds up the charge moved,
 * attributing each interval to the screen state at its start.
 */
public final class SessionTracker {
    /** Current is integrated only between samples this close; two readings say little about longer spans. */
    private static final long MAX_TRAPEZOID_MS = 90_000;
    /** Without a charge counter, integrate up to this long and count it as unmeasured. */
    private static final long MAX_INTEGRATION_GAP_MS = 10 * 60_000;
    /** A session left open by an earlier run continues only after a pause shorter than this. */
    private static final long MAX_RESUME_GAP_MS = 30 * 60_000;
    /** Sessions shorter than this with no level change are noise (cable wiggles). */
    private static final long MIN_SESSION_MS = 2 * 60_000;
    /** Added to gapMs to keep a session out of capacity estimates. */
    private static final long TAINT_MS = 365 * 86_400_000L;

    private final HistoryDb db;
    private final IntSupplier capacityMah;
    private Session cur;
    private BatterySnapshot last;
    private boolean lastScreenOn;

    public SessionTracker(HistoryDb db, IntSupplier capacityMah) {
        this.db = db;
        this.capacityMah = capacityMah;
        cur = db.openSession();
    }

    public void onSample(BatterySnapshot s, boolean screenOn) {
        if (s.level < 0) return;
        boolean charge = s.isPlugged();
        if (cur != null && (cur.charge != charge || (last == null && !resumable(cur, s)))) {
            close(cur);
            cur = null;
            last = null;
        }
        if (cur == null) {
            cur = start(s);
        } else if (last == null) {
            cur.gapMs += Math.max(0, s.time - cur.endTs); // resuming after a short pause
        } else {
            integrate(last, s, lastScreenOn);
        }

        cur.endTs = s.time;
        cur.endLevel = s.level;
        cur.endCounterUah = s.chargeCounterUah;
        if (s.plugged != 0) cur.plug = s.plugged;
        if (!Float.isNaN(s.tempC) && (Float.isNaN(cur.maxTemp) || s.tempC > cur.maxTemp)) cur.maxTemp = s.tempC;
        if (s.hasCurrent() && Math.abs(s.currentMa) > Math.abs(cur.peakMa)) cur.peakMa = s.currentMa;
        if (!db.updateSession(cur)) {
            // The row is gone (Clear history), so what was measured before should be forgotten too.
            cur = start(s);
        }

        last = s;
        lastScreenOn = screenOn;
    }

    /** Closes the session in progress, for when monitoring stops. */
    public void closeOpen() {
        if (cur != null) close(cur);
        cur = null;
        last = null;
    }

    /**
     * A session left open by an earlier run (process killed, phone died) continues only if nothing
     * can have happened in between: a short pause, and the level still moving the session's way.
     * Otherwise a plug cycle may have gone unseen.
     */
    private static boolean resumable(Session c, BatterySnapshot s) {
        long pause = s.time - c.endTs;
        if (pause < 0 || pause > MAX_RESUME_GAP_MS) return false;
        return c.charge ? s.level >= c.endLevel - 1 : s.level <= c.endLevel + 1;
    }

    private Session start(BatterySnapshot s) {
        Session n = new Session();
        n.charge = s.isPlugged();
        n.startTs = n.endTs = s.time;
        n.startLevel = n.endLevel = s.level;
        n.startCounterUah = n.endCounterUah = s.chargeCounterUah;
        n.plug = s.plugged;
        db.insertSession(n);
        return n;
    }

    private void integrate(BatterySnapshot a, BatterySnapshot b, boolean screenOn) {
        long dt = b.elapsed - a.elapsed;
        if (dt <= 0) return;
        if (a.hasCurrent() && b.hasCurrent() && a.convention != b.convention) {
            cur.gapMs += TAINT_MS; // unit or sign changed: earlier mAh used the other convention
        }
        if (dt <= MAX_INTEGRATION_GAP_MS && implausibleLevelChange(a, b, dt)) {
            cur.gapMs += TAINT_MS; // faked battery state (dumpsys battery) or a gauge reset
        }

        double mah;
        boolean currents = a.hasCurrent() && b.hasCurrent();
        if (dt <= MAX_TRAPEZOID_MS && currents) {
            double avg = (a.currentMa + b.currentMa) / 2.0;
            mah = (cur.charge ? avg : -avg) * dt / 3_600_000.0;
        } else if (a.chargeCounterUah > 0 && b.chargeCounterUah > 0) {
            // The CPU likely slept in between; the gauge's coulomb counter saw what we didn't.
            long d = b.chargeCounterUah - a.chargeCounterUah;
            mah = (cur.charge ? d : -d) / 1000.0;
            cur.gapMs += dt;
        } else if (dt <= MAX_INTEGRATION_GAP_MS && currents) {
            double avg = (a.currentMa + b.currentMa) / 2.0;
            mah = (cur.charge ? avg : -avg) * dt / 3_600_000.0;
            cur.gapMs += dt;
        } else {
            cur.gapMs += dt;
            return;
        }
        cur.mah += mah;
        if (screenOn) {
            cur.msScreenOn += dt;
            cur.mahScreenOn += mah;
        } else {
            cur.msScreenOff += dt;
            cur.mahScreenOff += mah;
        }
    }

    /**
     * True when the level moved far more than the measured current allows (twice the charge the
     * larger of the two currents could move, plus 3 points for rounding). Fast chargers stay well
     * inside that; a faked 18 → 85 % jump does not.
     */
    private boolean implausibleLevelChange(BatterySnapshot a, BatterySnapshot b, long dt) {
        int points = Math.abs(b.level - a.level);
        if (points <= 3) return false;
        int capacity = capacityMah.getAsInt();
        if (capacity <= 0 || !a.hasCurrent() || !b.hasCurrent()) return points > 10 && dt < 60_000;
        double maxMa = Math.max(Math.abs(a.currentMa), Math.abs(b.currentMa));
        double possible = maxMa * dt / 3_600_000.0 / capacity * 100.0;
        return points > possible * 2 + 3;
    }

    private void close(Session s) {
        s.open = false;
        if (s.durationMs() < MIN_SESSION_MS && s.deltaLevel() == 0) {
            db.deleteSession(s.id);
        } else {
            db.updateSession(s);
        }
    }
}
