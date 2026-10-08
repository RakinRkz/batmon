package com.opendroid.batmon;

import java.util.Arrays;

/**
 * Min / max / mean of current since the last reset, plus a trimmed mean over a recent time window
 * for the headline value (like Ampere: drop the outliers at both ends, average the rest).
 */
public final class LiveStats {
    private final int[] values;
    private final long[] times;
    private int count, pos;
    private int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
    private long sum;
    private int n;

    public LiveStats(int capacity) {
        values = new int[capacity];
        times = new long[capacity];
    }

    /** {@code elapsedMs} is SystemClock.elapsedRealtime() at the reading. */
    public void add(int v, long elapsedMs) {
        values[pos] = v;
        times[pos] = elapsedMs;
        pos = (pos + 1) % values.length;
        if (count < values.length) count++;
        if (v < min) min = v;
        if (v > max) max = v;
        sum += v;
        n++;
    }

    public void reset() {
        count = pos = n = 0;
        sum = 0;
        min = Integer.MAX_VALUE;
        max = Integer.MIN_VALUE;
    }

    public boolean isEmpty() { return n == 0; }
    public int min() { return min; }
    public int max() { return max; }
    public int mean() { return n == 0 ? 0 : (int) Math.round((double) sum / n); }
    public int samples() { return n; }

    /**
     * Trimmed mean of the readings from the last {@code windowMs} (dropping a fifth at each end), so
     * readings from before a pause never count. Falls back to the newest reading.
     */
    public int smoothed(long windowMs, long nowElapsed) {
        if (count == 0) return 0;
        int[] vals = new int[count];
        int k = 0;
        for (int i = 0; i < count; i++) {
            int idx = (pos - 1 - i + values.length) % values.length;
            if (i > 0 && nowElapsed - times[idx] > windowMs) break;
            vals[k++] = values[idx];
        }
        Arrays.sort(vals, 0, k);
        int trim = k >= 5 ? k / 5 : 0;
        long s = 0;
        for (int i = trim; i < k - trim; i++) s += vals[i];
        return (int) Math.round((double) s / (k - 2 * trim));
    }
}
