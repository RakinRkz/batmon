package com.opendroid.batmon;

import java.util.Arrays;

/**
 * Min / max / mean of current since the last reset, plus a trimmed mean over a short window for
 * the headline value (like Ampere: drop the outliers at both ends, average the rest).
 */
public final class LiveStats {
    private final int[] window;
    private int count, pos;
    private int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
    private long sum;
    private int n;

    public LiveStats(int windowSize) {
        window = new int[windowSize];
    }

    public void add(int v) {
        window[pos] = v;
        pos = (pos + 1) % window.length;
        if (count < window.length) count++;
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

    /** Trimmed mean of the newest {@code size} samples, dropping {@code size/5} at each end. */
    public int smoothed(int size) {
        int k = Math.min(size, count);
        if (k == 0) return 0;
        int[] vals = new int[k];
        for (int i = 0; i < k; i++) {
            vals[i] = window[(pos - 1 - i + window.length) % window.length];
        }
        Arrays.sort(vals);
        int trim = k >= 5 ? k / 5 : 0;
        long s = 0;
        for (int i = trim; i < k - trim; i++) s += vals[i];
        return (int) Math.round((double) s / (k - 2 * trim));
    }
}
