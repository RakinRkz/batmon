package com.opendroid.batmon;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Samples and sessions, shared by the service (writer) and the screens (readers). */
public final class HistoryDb extends SQLiteOpenHelper {
    /** Background thread for screen queries. */
    public static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private static HistoryDb instance;

    public static synchronized HistoryDb get(Context c) {
        if (instance == null) instance = new HistoryDb(c.getApplicationContext());
        return instance;
    }

    private HistoryDb(Context c) {
        super(c, "batmon.db", null, 1);
        setWriteAheadLoggingEnabled(true); // the service writes while screens read
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE samples (ts INTEGER PRIMARY KEY, current_ma INTEGER, voltage_mv INTEGER,"
                + " temp_dc INTEGER, level INTEGER, status INTEGER, plugged INTEGER, screen INTEGER,"
                + " counter_uah INTEGER)");
        db.execSQL("CREATE TABLE sessions (_id INTEGER PRIMARY KEY AUTOINCREMENT, charge INTEGER,"
                + " start_ts INTEGER, end_ts INTEGER, start_level INTEGER, end_level INTEGER,"
                + " start_counter INTEGER, end_counter INTEGER, mah REAL, mah_on REAL, mah_off REAL,"
                + " ms_on INTEGER, ms_off INTEGER, gap_ms INTEGER, max_temp REAL, peak_ma INTEGER,"
                + " plug INTEGER, open INTEGER)");
        db.execSQL("CREATE INDEX sessions_start ON sessions(start_ts)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    // ---- samples ----

    public void insertSample(BatterySnapshot s, boolean screenOn) {
        ContentValues v = new ContentValues();
        v.put("ts", s.time);
        if (s.hasCurrent()) v.put("current_ma", s.currentMa);
        v.put("voltage_mv", s.voltageMv);
        if (!Float.isNaN(s.tempC)) v.put("temp_dc", Math.round(s.tempC * 10));
        v.put("level", s.level);
        v.put("status", s.status);
        v.put("plugged", s.plugged);
        v.put("screen", screenOn ? 1 : 0);
        if (s.chargeCounterUah > 0) v.put("counter_uah", s.chargeCounterUah);
        getWritableDatabase().insertWithOnConflict("samples", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /**
     * Time series averaged into buckets so that at most {@code maxPoints} rows come back. Each point
     * sits at the mean time of its samples, so it never falls outside [from, to].
     */
    public static final class Series {
        public long[] t;
        public float[] current, level, temp;
        public int n;
        public long bucketMs;
    }

    public Series series(long from, long to, int maxPoints) {
        long bucket = Math.max(1000, (to - from) / maxPoints);
        Series s = new Series();
        s.bucketMs = bucket;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT AVG(ts) AS t, AVG(current_ma), AVG(level), AVG(temp_dc) FROM samples"
                        + " WHERE ts BETWEEN ? AND ? GROUP BY ts / ? ORDER BY t",
                new String[] {str(from), str(to), str(bucket)})) {
            int n = c.getCount();
            s.t = new long[n];
            s.current = new float[n];
            s.level = new float[n];
            s.temp = new float[n];
            int i = 0;
            while (c.moveToNext()) {
                s.t[i] = (long) c.getDouble(0);
                s.current[i] = c.isNull(1) ? Float.NaN : c.getFloat(1);
                s.level[i] = c.isNull(2) ? Float.NaN : c.getFloat(2);
                s.temp[i] = c.isNull(3) ? Float.NaN : c.getFloat(3) / 10f;
                i++;
            }
            s.n = i;
        }
        return s;
    }

    /**
     * Median fuel-gauge capacity estimate (charge counter ÷ level) from samples at ≥30 % since
     * {@code from}, ignoring values outside [minMah, maxMah] (faked battery states, gauge glitches).
     */
    public double gaugeCapacityMedianMah(long from, double minMah, double maxMah) {
        List<Double> vals = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT counter_uah, level FROM samples WHERE ts >= ? AND level >= 30 AND counter_uah > 0",
                new String[] {str(from)})) {
            while (c.moveToNext()) {
                double mah = c.getLong(0) / 1000.0 * 100.0 / c.getInt(1);
                if (mah >= minMah && mah <= maxMah) vals.add(mah);
            }
        }
        if (vals.isEmpty()) return Double.NaN;
        Double[] arr = vals.toArray(new Double[0]);
        Arrays.sort(arr);
        return arr[arr.length / 2];
    }

    // ---- sessions ----

    public long insertSession(Session s) {
        s.id = getWritableDatabase().insert("sessions", null, values(s));
        return s.id;
    }

    /** False when the row no longer exists (Clear history ran). */
    public boolean updateSession(Session s) {
        return getWritableDatabase().update("sessions", values(s), "_id = ?", new String[] {str(s.id)}) > 0;
    }

    public void deleteSession(long id) {
        getWritableDatabase().delete("sessions", "_id = ?", new String[] {str(id)});
    }

    public Session openSession() {
        List<Session> l = sessions("open = 1", "1");
        return l.isEmpty() ? null : l.get(0);
    }

    public List<Session> recentSessions(int limit) {
        return sessions(null, String.valueOf(limit));
    }

    public List<Session> chargeSessions() {
        return sessions("charge = 1 AND end_level - start_level >= " + Session.MIN_ESTIMATE_DELTA, null);
    }

    /** Total mAh pushed into the battery across all recorded charge sessions (net drains count as 0). */
    public double totalChargedMah() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT SUM(MAX(mah, 0)) FROM sessions WHERE charge = 1",
                null)) {
            return c.moveToFirst() && !c.isNull(0) ? c.getDouble(0) : 0;
        }
    }

    private List<Session> sessions(String where, String limit) {
        List<Session> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("sessions", null, where, null, null, null,
                "start_ts DESC", limit)) {
            while (c.moveToNext()) out.add(fromCursor(c));
        }
        return out;
    }

    private static ContentValues values(Session s) {
        ContentValues v = new ContentValues();
        v.put("charge", s.charge ? 1 : 0);
        v.put("start_ts", s.startTs);
        v.put("end_ts", s.endTs);
        v.put("start_level", s.startLevel);
        v.put("end_level", s.endLevel);
        v.put("start_counter", s.startCounterUah);
        v.put("end_counter", s.endCounterUah);
        v.put("mah", s.mah);
        v.put("mah_on", s.mahScreenOn);
        v.put("mah_off", s.mahScreenOff);
        v.put("ms_on", s.msScreenOn);
        v.put("ms_off", s.msScreenOff);
        v.put("gap_ms", s.gapMs);
        if (!Float.isNaN(s.maxTemp)) v.put("max_temp", s.maxTemp);
        v.put("peak_ma", s.peakMa);
        v.put("plug", s.plug);
        v.put("open", s.open ? 1 : 0);
        return v;
    }

    private static Session fromCursor(Cursor c) {
        Session s = new Session();
        s.id = c.getLong(c.getColumnIndexOrThrow("_id"));
        s.charge = c.getInt(c.getColumnIndexOrThrow("charge")) == 1;
        s.startTs = c.getLong(c.getColumnIndexOrThrow("start_ts"));
        s.endTs = c.getLong(c.getColumnIndexOrThrow("end_ts"));
        s.startLevel = c.getInt(c.getColumnIndexOrThrow("start_level"));
        s.endLevel = c.getInt(c.getColumnIndexOrThrow("end_level"));
        s.startCounterUah = c.getLong(c.getColumnIndexOrThrow("start_counter"));
        s.endCounterUah = c.getLong(c.getColumnIndexOrThrow("end_counter"));
        s.mah = c.getDouble(c.getColumnIndexOrThrow("mah"));
        s.mahScreenOn = c.getDouble(c.getColumnIndexOrThrow("mah_on"));
        s.mahScreenOff = c.getDouble(c.getColumnIndexOrThrow("mah_off"));
        s.msScreenOn = c.getLong(c.getColumnIndexOrThrow("ms_on"));
        s.msScreenOff = c.getLong(c.getColumnIndexOrThrow("ms_off"));
        s.gapMs = c.getLong(c.getColumnIndexOrThrow("gap_ms"));
        int t = c.getColumnIndexOrThrow("max_temp");
        s.maxTemp = c.isNull(t) ? Float.NaN : c.getFloat(t);
        s.peakMa = c.getInt(c.getColumnIndexOrThrow("peak_ma"));
        s.plug = c.getInt(c.getColumnIndexOrThrow("plug"));
        s.open = c.getInt(c.getColumnIndexOrThrow("open")) == 1;
        return s;
    }

    // ---- maintenance ----

    public void prune(long samplesBefore, long sessionsBefore) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("samples", "ts < ?", new String[] {str(samplesBefore)});
        db.delete("sessions", "end_ts < ? AND open = 0", new String[] {str(sessionsBefore)});
    }

    public void clear() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("samples", null, null);
        db.delete("sessions", null, null);
    }

    private static String str(long v) { return Long.toString(v); }
}
