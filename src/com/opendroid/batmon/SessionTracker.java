package com.opendroid.batmon;

/**
 * Splits the sample stream into charge / discharge sessions and integrates current over time,
 * attributing each interval to the screen state at its start.
 */
public final class SessionTracker {
    /** Intervals longer than this are not integrated from current (the CPU was likely asleep). */
    private static final long MAX_INTEGRATION_GAP_MS = 10 * 60_000;
    /** Sessions shorter than this with no level change are noise (cable wiggles). */
    private static final long MIN_SESSION_MS = 2 * 60_000;
    /** A real battery can't move more than this many points within a minute. */
    private static final int MAX_LEVEL_STEP = 3;
    private static final long TAINT_MS = 365 * 86_400_000L;

    private final HistoryDb db;
    private Session cur;
    private BatterySnapshot last;
    private boolean lastScreenOn;

    public SessionTracker(HistoryDb db) {
        this.db = db;
        cur = db.openSession();
    }

    public Session current() { return cur; }

    public void onSample(BatterySnapshot s, boolean screenOn) {
        if (s.level < 0) return;
        boolean charge = s.isPlugged();
        if (cur != null && cur.charge != charge) {
            close(cur);
            cur = null;
            last = null;
        }
        if (cur == null) {
            cur = new Session();
            cur.charge = charge;
            cur.startTs = cur.endTs = s.time;
            cur.startLevel = cur.endLevel = s.level;
            cur.startCounterUah = s.chargeCounterUah;
            cur.plug = s.plugged;
            db.insertSession(cur);
        } else if (last == null) {
            // Resuming a session left open by an earlier run of the service.
            cur.gapMs += Math.max(0, s.time - cur.endTs);
        } else {
            integrate(last, s, lastScreenOn);
        }

        cur.endTs = s.time;
        cur.endLevel = s.level;
        cur.endCounterUah = s.chargeCounterUah;
        if (s.plugged != 0) cur.plug = s.plugged;
        if (!Float.isNaN(s.tempC) && (Float.isNaN(cur.maxTemp) || s.tempC > cur.maxTemp)) cur.maxTemp = s.tempC;
        if (s.hasCurrent() && Math.abs(s.currentMa) > Math.abs(cur.peakMa)) cur.peakMa = s.currentMa;
        db.updateSession(cur);

        last = s;
        lastScreenOn = screenOn;
    }

    private void integrate(BatterySnapshot a, BatterySnapshot b, boolean screenOn) {
        long dt = b.time - a.time;
        if (dt <= 0) return;
        if (Math.abs(b.level - a.level) > MAX_LEVEL_STEP && dt < 60_000) {
            // Faked battery state (dumpsys battery) or a gauge reset; keep it out of capacity estimates.
            cur.gapMs += TAINT_MS;
        }
        double mah;
        if (dt <= MAX_INTEGRATION_GAP_MS && a.hasCurrent() && b.hasCurrent()) {
            double avg = (a.currentMa + b.currentMa) / 2.0;
            mah = (cur.charge ? avg : -avg) * dt / 3_600_000.0;
        } else if (a.chargeCounterUah > 0 && b.chargeCounterUah > 0) {
            long d = b.chargeCounterUah - a.chargeCounterUah;
            mah = (cur.charge ? d : -d) / 1000.0;
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

    private void close(Session s) {
        s.open = false;
        if (s.durationMs() < MIN_SESSION_MS && s.deltaLevel() == 0) {
            db.deleteSession(s.id);
        } else {
            db.updateSession(s);
        }
    }
}
