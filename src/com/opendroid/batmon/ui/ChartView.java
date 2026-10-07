package com.opendroid.batmon.ui;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Single-series time chart: 2dp line over a 12% wash, hairline grid, clean y ticks, time ticks,
 * an end dot with a surface ring, and a drag crosshair with a tooltip. In signed mode the part
 * above zero and the part below zero take different colors.
 */
public final class ChartView extends View {
    public interface ValueFormat { String format(float v); }
    public interface TimeFormat { String format(long t); }

    private static final long SEC = 1000, MIN = 60 * SEC, HOUR = 60 * MIN, DAY = 24 * HOUR;
    private static final long[] TIME_STEPS = {10 * SEC, 15 * SEC, 30 * SEC, MIN, 2 * MIN, 5 * MIN,
            10 * MIN, 15 * MIN, 30 * MIN, HOUR, 2 * HOUR, 3 * HOUR, 6 * HOUR, 12 * HOUR, DAY, 2 * DAY};

    private final Ui ui;
    private long[] xs = new long[0];
    private float[] ys = new float[0];
    private int n;

    private boolean signed;
    private int color, posColor, negColor;
    private boolean includeZero;
    private float fixedMin = Float.NaN, fixedMax = Float.NaN;
    private long rangeFrom, rangeTo;
    private boolean relativeTime;
    private long gapMs = Long.MAX_VALUE;
    private float minSpan;
    /** Null: format ticks with as many decimals as the tick step needs. */
    private ValueFormat axisFormat;
    private ValueFormat tipFormat = v -> String.format(Locale.getDefault(), "%,.1f", v);
    private TimeFormat tipTime;
    private String emptyText = "No data yet";

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint();
    private final Paint basePaint = new Paint();
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crossPaint = new Paint();
    private final Paint tipBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipValue = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipSub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path line = new Path(), area = new Path();
    private final RectF rect = new RectF();
    private final int touchSlop;

    private float pl, pr, pt, pb, lo, hi;
    private long x0, x1;
    private long touchT = -1;
    private float downX, downY;
    private boolean dragging;
    private final Runnable clearTouch = () -> {
        touchT = -1;
        invalidate();
    };

    public ChartView(Ui ui) {
        super(ui.c);
        this.ui = ui;
        color = posColor = negColor = ui.accent;
        touchSlop = ViewConfiguration.get(ui.c).getScaledTouchSlop();

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(ui.dpf(2));
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
        gridPaint.setColor(ui.grid);
        gridPaint.setStrokeWidth(1);
        basePaint.setColor(ui.baseline);
        basePaint.setStrokeWidth(Math.max(1, ui.dp(1)));
        labelPaint.setColor(ui.muted);
        labelPaint.setTextSize(ui.sp(11));
        labelPaint.setFontFeatureSettings("tnum");
        ringPaint.setColor(ui.surface);
        crossPaint.setColor(ui.ink2);
        crossPaint.setStrokeWidth(Math.max(1, ui.dp(1)));
        tipBg.setColor(ui.surface);
        tipBorder.setStyle(Paint.Style.STROKE);
        tipBorder.setStrokeWidth(Math.max(1, ui.dp(1)));
        tipBorder.setColor(ui.baseline);
        tipValue.setColor(ui.ink);
        tipValue.setTextSize(ui.sp(14));
        tipValue.setTypeface(Ui.MEDIUM);
        tipSub.setColor(ui.ink2);
        tipSub.setTextSize(ui.sp(12));
    }

    // ---- configuration ----

    public ChartView color(int c) { color = c; signed = false; return this; }

    public ChartView signedColors(int positive, int negative) {
        posColor = positive;
        negColor = negative;
        signed = true;
        includeZero = true;
        return this;
    }

    public ChartView includeZero(boolean z) { includeZero = z; return this; }

    public ChartView fixedRange(float min, float max) { fixedMin = min; fixedMax = max; return this; }

    /** Smallest value range the y axis shows, so small wiggles don't fill the whole height. */
    public ChartView minSpan(float span) { minSpan = span; return this; }

    public ChartView relativeTime(boolean r) { relativeTime = r; return this; }

    public ChartView gap(long ms) { gapMs = ms; return this; }

    public ChartView axisFormat(ValueFormat f) { axisFormat = f; return this; }

    public ChartView tipFormat(ValueFormat f) { tipFormat = f; return this; }

    public ChartView tipTime(TimeFormat f) { tipTime = f; return this; }

    public ChartView emptyText(String s) { emptyText = s; return this; }

    /** Visible time window; pass 0, 0 to fit the data. */
    public void setRange(long from, long to) {
        rangeFrom = from;
        rangeTo = to;
        invalidate();
    }

    /** Points must be in time order. The arrays are used directly, not copied. */
    public void setData(long[] xs, float[] ys, int n) {
        this.xs = xs;
        this.ys = ys;
        this.n = n;
        invalidate();
    }

    // ---- drawing ----

    private float yOf(float v) { return pb - (v - lo) / (hi - lo) * (pb - pt); }

    private float xOf(long t) { return pl + (t - x0) / (float) (x1 - x0) * (pr - pl); }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (rangeTo > rangeFrom) {
            x0 = rangeFrom;
            x1 = rangeTo;
        } else if (n > 0) {
            x0 = xs[0];
            x1 = xs[n - 1];
        } else {
            x0 = 0;
            x1 = 1;
        }
        if (x1 <= x0) {
            x0 -= MIN;
            x1 += MIN;
        }

        float dmin = Float.POSITIVE_INFINITY, dmax = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            if (xs[i] < x0 || xs[i] > x1 || Float.isNaN(ys[i])) continue;
            dmin = Math.min(dmin, ys[i]);
            dmax = Math.max(dmax, ys[i]);
        }
        boolean empty = dmin > dmax;
        if (empty) {
            dmin = 0;
            dmax = 1;
        }
        if (dmax - dmin < minSpan) {
            float mid = (dmin + dmax) / 2f;
            dmin = mid - minSpan / 2f;
            dmax = mid + minSpan / 2f;
        }
        if (!Float.isNaN(fixedMin)) dmin = Math.min(dmin, fixedMin);
        if (!Float.isNaN(fixedMax)) dmax = Math.max(dmax, fixedMax);
        if (includeZero) {
            dmin = Math.min(dmin, 0);
            dmax = Math.max(dmax, 0);
        }
        float[] ticks = niceTicks(dmin, dmax, 4);
        lo = ticks[0];
        hi = ticks[ticks.length - 1];
        float step = ticks.length > 1 ? ticks[1] - ticks[0] : 1f;

        float labelW = 0;
        for (float t : ticks) labelW = Math.max(labelW, labelPaint.measureText(tickLabel(t, step)));
        pl = labelW + ui.dpf(8);
        pr = w - ui.dpf(6);
        pt = ui.dpf(8);
        pb = h - ui.dpf(22);

        labelPaint.setTextAlign(Paint.Align.RIGHT);
        float textMid = -(labelPaint.ascent() + labelPaint.descent()) / 2f;
        for (float t : ticks) {
            float y = yOf(t);
            boolean zero = includeZero && Math.abs(t) < 1e-6;
            c.drawLine(pl, y, pr, y, zero ? basePaint : gridPaint);
            c.drawText(tickLabel(t, step), pl - ui.dpf(6), y + textMid, labelPaint);
        }
        drawTimeTicks(c, h);

        if (empty) {
            labelPaint.setTextAlign(Paint.Align.CENTER);
            c.drawText(emptyText, (pl + pr) / 2f, (pt + pb) / 2f + textMid, labelPaint);
            return;
        }

        float baseY = includeZero ? yOf(0) : pb;
        buildPaths(baseY);
        if (signed) {
            c.save();
            c.clipRect(0, 0, w, baseY);
            drawSeries(c, posColor);
            c.restore();
            c.save();
            c.clipRect(0, baseY, w, h);
            drawSeries(c, negColor);
            c.restore();
        } else {
            drawSeries(c, color);
        }

        int last = lastVisible();
        if (last >= 0 && touchT < 0) drawDot(c, last);
        if (touchT >= 0) drawTooltip(c, w, h);
    }

    private void buildPaths(float baseY) {
        line.reset();
        area.reset();
        boolean down = false;
        float lastX = 0;
        long prevT = 0;
        for (int i = 0; i < n; i++) {
            if (xs[i] < x0 || xs[i] > x1 || Float.isNaN(ys[i])) {
                if (down) {
                    area.lineTo(lastX, baseY);
                    area.close();
                    down = false;
                }
                continue;
            }
            float x = xOf(xs[i]), y = yOf(ys[i]);
            if (down && xs[i] - prevT > gapMs) {
                area.lineTo(lastX, baseY);
                area.close();
                down = false;
            }
            if (!down) {
                line.moveTo(x, y);
                area.moveTo(x, baseY);
                area.lineTo(x, y);
                down = true;
            } else {
                line.lineTo(x, y);
                area.lineTo(x, y);
            }
            lastX = x;
            prevT = xs[i];
        }
        if (down) {
            area.lineTo(lastX, baseY);
            area.close();
        }
    }

    private void drawSeries(Canvas c, int col) {
        fillPaint.setColor(Ui.alpha(col, 0.12f));
        c.drawPath(area, fillPaint);
        linePaint.setColor(col);
        c.drawPath(line, linePaint);
    }

    private int colorAt(int i) {
        if (!signed) return color;
        return ys[i] >= 0 ? posColor : negColor;
    }

    private void drawDot(Canvas c, int i) {
        float cx = xOf(xs[i]), cy = yOf(ys[i]);
        c.drawCircle(cx, cy, ui.dpf(6), ringPaint);
        dotPaint.setColor(colorAt(i));
        c.drawCircle(cx, cy, ui.dpf(4), dotPaint);
    }

    private int lastVisible() {
        for (int i = n - 1; i >= 0; i--) {
            if (xs[i] >= x0 && xs[i] <= x1 && !Float.isNaN(ys[i])) return i;
        }
        return -1;
    }

    private void drawTimeTicks(Canvas c, float h) {
        long span = x1 - x0;
        long step = TIME_STEPS[TIME_STEPS.length - 1];
        for (long s : TIME_STEPS) {
            if (span / s <= 4) {
                step = s;
                break;
            }
        }
        labelPaint.setTextAlign(Paint.Align.CENTER);
        float y = h - ui.dpf(6);
        if (relativeTime) {
            for (long t = x1; t >= x0; t -= step) {
                drawTick(c, t, t == x1 ? "now" : "−" + relative(x1 - t), y);
            }
            return;
        }
        boolean is24 = android.text.format.DateFormat.is24HourFormat(getContext());
        SimpleDateFormat f = new SimpleDateFormat(step >= DAY ? "EEE d" : is24 ? "HH:mm" : "h:mm a",
                Locale.getDefault());
        long off = TimeZone.getDefault().getOffset(x0);
        long first = ((x0 + off) / step + 1) * step - off;
        for (long t = first; t <= x1; t += step) drawTick(c, t, f.format(new Date(t)), y);
    }

    private void drawTick(Canvas c, long t, String label, float y) {
        float half = labelPaint.measureText(label) / 2f;
        float x = Math.max(pl + half, Math.min(pr - half, xOf(t)));
        c.drawText(label, x, y, labelPaint);
    }

    private static String relative(long ms) {
        if (ms < MIN) return (ms / SEC) + "s";
        if (ms < HOUR) return (ms / MIN) + "m";
        return (ms / HOUR) + "h";
    }

    private void drawTooltip(Canvas c, float w, float h) {
        int idx = -1;
        long best = Long.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            if (xs[i] < x0 || xs[i] > x1 || Float.isNaN(ys[i])) continue;
            long d = Math.abs(xs[i] - touchT);
            if (d < best) {
                best = d;
                idx = i;
            }
        }
        if (idx < 0) return;
        float cx = xOf(xs[idx]), cy = yOf(ys[idx]);
        c.drawLine(cx, pt, cx, pb, crossPaint);
        drawDot(c, idx);

        String v = tipFormat.format(ys[idx]);
        String t = tipTime != null ? tipTime.format(xs[idx]) : "";
        float pad = ui.dpf(8);
        float vh = tipValue.descent() - tipValue.ascent();
        float th = t.isEmpty() ? 0 : tipSub.descent() - tipSub.ascent() + ui.dpf(2);
        float bw = Math.max(tipValue.measureText(v), tipSub.measureText(t)) + 2 * pad;
        float bh = vh + th + 2 * pad;
        float bx = cx + ui.dpf(12);
        if (bx + bw > w) bx = cx - ui.dpf(12) - bw;
        bx = Math.max(0, bx);
        float by = Math.max(0, Math.min(h - bh, cy - bh / 2f));
        rect.set(bx, by, bx + bw, by + bh);
        c.drawRoundRect(rect, ui.dpf(8), ui.dpf(8), tipBg);
        c.drawRoundRect(rect, ui.dpf(8), ui.dpf(8), tipBorder);
        tipValue.setTextAlign(Paint.Align.LEFT);
        tipSub.setTextAlign(Paint.Align.LEFT);
        c.drawText(v, bx + pad, by + pad - tipValue.ascent(), tipValue);
        if (!t.isEmpty()) c.drawText(t, bx + pad, by + pad + vh + ui.dpf(2) - tipSub.ascent(), tipSub);
    }

    private String tickLabel(float v, float step) {
        if (Math.abs(v) < step * 1e-3f) v = 0; // no "-0"
        if (axisFormat != null) return axisFormat.format(v);
        int decimals = step >= 0.999f ? 0 : step >= 0.0999f ? 1 : 2;
        return String.format(Locale.getDefault(), "%,." + decimals + "f", v);
    }

    /** Evenly spaced round ticks (1, 2 or 5 × 10^k) covering [lo, hi]. */
    static float[] niceTicks(float lo, float hi, int target) {
        if (hi - lo < 1e-6f) {
            float pad = Math.abs(hi) > 0 ? Math.abs(hi) * 0.1f : 1f;
            lo -= pad;
            hi += pad;
        }
        double rough = (hi - lo) / target;
        double mag = Math.pow(10, Math.floor(Math.log10(rough)));
        double norm = rough / mag;
        double step = (norm < 1.5 ? 1 : norm < 3 ? 2 : norm < 7 ? 5 : 10) * mag;
        double start = Math.floor(lo / step) * step;
        double end = Math.ceil(hi / step) * step;
        int count = (int) Math.round((end - start) / step) + 1;
        float[] t = new float[count];
        for (int i = 0; i < count; i++) t[i] = (float) (start + i * step);
        return t;
    }

    // ---- touch: drag horizontally to inspect, vertical drags still scroll the page ----

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (n == 0) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                dragging = false;
                removeCallbacks(clearTouch);
                setTouch(e.getX());
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (!dragging && Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                    dragging = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (dragging) setTouch(e.getX());
                return true;
            case MotionEvent.ACTION_UP:
                getParent().requestDisallowInterceptTouchEvent(false);
                postDelayed(clearTouch, 2500);
                return true;
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                clearTouch.run();
                return true;
            default:
                return true;
        }
    }

    private void setTouch(float x) {
        float f = Math.max(0, Math.min(1, (x - pl) / (pr - pl)));
        touchT = x0 + (long) (f * (x1 - x0));
        invalidate();
    }
}
