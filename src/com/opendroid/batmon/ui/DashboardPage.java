package com.opendroid.batmon.ui;

import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.opendroid.batmon.BatteryReader;
import com.opendroid.batmon.BatterySnapshot;
import com.opendroid.batmon.Fmt;
import com.opendroid.batmon.LiveStats;
import com.opendroid.batmon.MainActivity;
import com.opendroid.batmon.Prefs;

import java.util.Locale;

/** Live readout: headline current, session min/avg/max, a 3-minute chart and every battery value. */
public final class DashboardPage extends Page {
    private static final long TICK_MS = 1000;
    private static final long LIVE_WINDOW_MS = 3 * 60_000;
    private static final int LIVE_CAP = 200;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::tick;
    private final BatteryReader reader;
    private final Prefs prefs;
    private final LiveStats stats = new LiveStats(64);
    private final long[] liveT = new long[LIVE_CAP];
    private final float[] liveV = new float[LIVE_CAP];
    private int liveN;
    private int lastPlugged = -1;

    private LinearLayout heroCard, pill;
    private View pillDot;
    private TextView pillText, hero, heroCaption, power, avg, min, max, footnote;
    private ChartView chart;
    private Ui.Tile tLevel, tTemp, tVolt, tHealth, tSource, tTech, tCounter, tCycles, tCapacity, tTime;
    private int stateColor;

    public DashboardPage(MainActivity act) {
        super(act);
        reader = new BatteryReader(act);
        prefs = Prefs.get(act);
    }

    @Override
    protected View build() {
        ScrollView[] scroll = new ScrollView[1];
        LinearLayout col = scrollColumn(scroll);

        LinearLayout titleRow = ui.horizontal();
        TextView title = ui.pageTitle("BatMon");
        titleRow.addView(title, Ui.weighted());
        TextView reset = ui.medium("Reset stats", 14, ui.accent);
        reset.setPadding(ui.dp(12), ui.dp(10), ui.dp(4), ui.dp(10));
        reset.setOnClickListener(v -> {
            stats.reset();
            liveN = 0;
            tick();
        });
        titleRow.addView(reset);
        col.addView(titleRow);

        col.addView(buildHero());

        LinearLayout chartCard = ui.card();
        chartCard.addView(ui.cardHeader("Current", "Last 3 minutes · above zero is charging"));
        chart = new ChartView(ui)
                .signedColors(ui.good, ui.serious)
                .minSpan(100)
                .relativeTime(true)
                .gap(5000)
                .axisFormat(v -> axisMa(v))
                .tipFormat(v -> Fmt.signedMa(Math.round(v)) + " mA")
                .tipTime(t -> Fmt.timeSec(act, t))
                .emptyText("Waiting for readings…");
        chart.setContentDescription("Chart of battery current over the last 3 minutes");
        chartCard.addView(chart, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(180)));
        col.addView(chartCard);

        tLevel = ui.new Tile("Level");
        tTemp = ui.new Tile("Temperature");
        tVolt = ui.new Tile("Voltage");
        tHealth = ui.new Tile("Health");
        tSource = ui.new Tile("Power source");
        tTech = ui.new Tile("Technology");
        tCounter = ui.new Tile("Remaining charge");
        tCycles = ui.new Tile("Charge cycles");
        tCapacity = ui.new Tile("Full capacity");
        tTime = ui.new Tile("Time left");
        col.addView(ui.grid(2, tLevel.view, tTemp.view, tVolt.view, tHealth.view, tSource.view,
                tTech.view, tCounter.view, tCycles.view, tCapacity.view, tTime.view));

        footnote = ui.text("", 12, ui.muted);
        footnote.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(8));
        col.addView(footnote);
        return scroll[0];
    }

    private View buildHero() {
        heroCard = ui.card();
        heroCard.setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(18));

        pill = ui.horizontal();
        pill.setPadding(ui.dp(10), ui.dp(6), ui.dp(12), ui.dp(6));
        pillDot = ui.dot(ui.muted, 8);
        pill.addView(pillDot);
        pillText = ui.medium("Reading…", 13, ui.ink);
        pillText.setPadding(ui.dp(8), 0, 0, 0);
        pill.addView(pillText);
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        heroCard.addView(pill, pillLp);

        LinearLayout heroRow = ui.horizontal();
        heroRow.setGravity(Gravity.BOTTOM);
        heroRow.setPadding(0, ui.dp(14), 0, 0);
        hero = ui.medium(Fmt.DASH, 60, ui.ink);
        hero.setFontFeatureSettings("tnum"); // the value updates every second; keep its width steady
        heroRow.addView(hero);
        TextView unit = ui.text("mA", 22, ui.ink2);
        unit.setPadding(ui.dp(8), 0, 0, ui.dp(8));
        heroRow.addView(unit);
        heroCard.addView(heroRow);

        heroCaption = ui.text("", 13, ui.muted);
        heroCaption.setPadding(0, ui.dp(6), 0, ui.dp(16));
        heroCard.addView(heroCaption);

        LinearLayout row = ui.horizontal();
        row.setBaselineAligned(false);
        power = miniStat(row, "Power");
        avg = miniStat(row, "Average");
        min = miniStat(row, "Min");
        max = miniStat(row, "Max");
        heroCard.addView(row);
        return heroCard;
    }

    private TextView miniStat(LinearLayout row, String label) {
        LinearLayout l = ui.vertical();
        l.addView(ui.text(label, 12, ui.muted));
        TextView v = ui.medium(Fmt.DASH, 15, ui.ink);
        v.setPadding(0, ui.dp(4), 0, 0);
        v.setFontFeatureSettings("tnum");
        l.addView(v);
        row.addView(l, Ui.weighted());
        return v;
    }

    @Override
    public void onShow() {
        handler.removeCallbacks(tick);
        tick();
    }

    @Override
    public void onHide() {
        handler.removeCallbacks(tick);
    }

    private void tick() {
        handler.removeCallbacks(tick);
        BatterySnapshot s = reader.read();
        if (s.plugged != lastPlugged) {
            if (lastPlugged != -1) stats.reset(); // min/max/avg describe the current plug state only
            lastPlugged = s.plugged;
        }
        if (s.hasCurrent()) {
            stats.add(s.currentMa);
            pushLive(s.time, s.currentMa);
        }
        render(s);
        handler.postDelayed(tick, TICK_MS);
    }

    private void pushLive(long t, float v) {
        if (liveN == LIVE_CAP) {
            System.arraycopy(liveT, 1, liveT, 0, LIVE_CAP - 1);
            System.arraycopy(liveV, 1, liveV, 0, LIVE_CAP - 1);
            liveN--;
        }
        liveT[liveN] = t;
        liveV[liveN] = v;
        liveN++;
        chart.setRange(t - LIVE_WINDOW_MS, t);
        chart.setData(liveT, liveV, liveN);
    }

    private void render(BatterySnapshot s) {
        boolean f = prefs.bool(Prefs.FAHRENHEIT);
        int smoothing = prefs.integer(Prefs.SMOOTHING);
        int window = smoothing == 0 ? 1 : smoothing == 1 ? 10 : 30;
        int shown = s.hasCurrent() ? (stats.isEmpty() ? s.currentMa : stats.smoothed(window)) : BatterySnapshot.NONE;

        // State pill and card tint
        String state;
        int color;
        if (!s.isPlugged()) {
            state = "Discharging";
            color = ui.serious;
        } else if (s.isFull()) {
            state = "Full · " + Fmt.plug(s.plugged);
            color = ui.good;
        } else if (shown != BatterySnapshot.NONE && shown < 0) {
            state = "Plugged in, still draining · " + Fmt.plug(s.plugged);
            color = ui.warning;
        } else {
            state = "Charging · " + Fmt.plug(s.plugged);
            color = ui.good;
        }
        pillText.setText(state);
        if (color != stateColor) {
            stateColor = color;
            ((GradientDrawable) pillDot.getBackground()).setColor(color);
            pill.setBackground(ui.rounded(Ui.alpha(color, 0.16f), 999, 0));
            heroCard.setBackground(ui.rounded(Ui.mix(ui.surface, color, 0.07f), 16, Ui.alpha(color, 0.35f)));
        }

        if (shown == BatterySnapshot.NONE) {
            hero.setText(Fmt.DASH);
            heroCaption.setText("This device does not report battery current");
        } else {
            hero.setText(Fmt.signedMa(shown));
            String how = window == 1 ? "Live reading" : "Averaged over " + window + " s, outliers trimmed";
            heroCaption.setText(how + (window > 1 ? " · now " + Fmt.signedMa(s.currentMa) : ""));
        }
        power.setText(Fmt.watts(s.powerW(shown)) + " W");
        if (stats.isEmpty()) {
            avg.setText(Fmt.DASH);
            min.setText(Fmt.DASH);
            max.setText(Fmt.DASH);
        } else {
            avg.setText(Fmt.signedMa(stats.mean()));
            min.setText(Fmt.signedMa(stats.min()));
            max.setText(Fmt.signedMa(stats.max()));
        }

        // Tiles
        tLevel.set(s.level >= 0 ? s.level + " %" : Fmt.DASH, Fmt.status(s.status));
        tTemp.set(Fmt.temp(s.tempC, f), tempNote(s.tempC));
        tVolt.set(Fmt.volts(s.voltageMv), null);
        int design = BatteryReader.designCapacityMah(act);
        double full = gaugeFullMah(s);
        String wear = !Double.isNaN(full) && design > 0
                ? Fmt.percent(Math.min(100, full * 100 / design)) + " of design capacity" : null;
        tHealth.set(Fmt.health(s.health), wear);
        tSource.set(Fmt.plug(s.plugged), s.isPlugged() ? Fmt.status(s.status) : "Not plugged in");
        tTech.set(s.technology != null && !s.technology.isEmpty() ? s.technology : Fmt.DASH, null);
        tCounter.set(s.chargeCounterUah > 0 ? Fmt.mah(s.chargeCounterUah / 1000.0) : Fmt.DASH,
                s.chargeCounterUah > 0 ? "From the fuel gauge" : "Not reported");
        // Kernels without a cycle counter often report 0 rather than nothing.
        tCycles.set(s.cycleCount >= 0 ? String.valueOf(s.cycleCount) : Fmt.DASH,
                s.cycleCount > 0 ? "Reported by Android"
                        : s.cycleCount == 0 ? "Reported as 0; likely unsupported" : "Not reported by this phone");
        tCapacity.set(Double.isNaN(full) ? Fmt.DASH : Fmt.mah(full),
                design > 0 ? "Design " + Fmt.mah(design) : "Design capacity unknown");
        renderTime(s, full, design);

        String unit = prefs.bool(Prefs.LEARNED_MICROAMPS) ? "µA" : "mA";
        String sign = prefs.bool(Prefs.LEARNED_INVERTED) ? "inverted" : "normal";
        footnote.setText(String.format(Locale.US,
                "Raw current %s, sign %s (auto-detected; override in Settings). %d samples since reset.",
                unit, sign, stats.samples()));
    }

    private String tempNote(float c) {
        if (Float.isNaN(c)) return null;
        if (c >= 45) return "Hot";
        if (c >= 40) return "Warm";
        if (c < 5) return "Cold";
        return "Normal";
    }

    /**
     * Full capacity right now: the gauge's learned value when Android reports it, otherwise
     * charge counter ÷ level (unreliable below 10 %).
     */
    static double gaugeFullMah(BatterySnapshot s) {
        if (s.maxCapacityMah > 0) return s.maxCapacityMah;
        if (s.chargeCounterUah <= 0 || s.level < 10) return Double.NaN;
        return s.chargeCounterUah / 1000.0 * 100.0 / s.level;
    }

    private void renderTime(BatterySnapshot s, double full, int design) {
        double cap = !Double.isNaN(full) ? full : design;
        if (s.isPlugged()) {
            if (s.isFull()) {
                tTime.set("Full", null);
            } else if (s.chargeTimeRemainingMs > 0) {
                tTime.set(Fmt.duration(s.chargeTimeRemainingMs), "Until full · Android estimate");
            } else if (!stats.isEmpty() && stats.mean() > 50 && cap > 0 && s.level >= 0) {
                double hours = cap * (100 - s.level) / 100.0 / stats.mean();
                tTime.set(Fmt.duration((long) (hours * 3_600_000)), "Until full · at current rate");
            } else {
                tTime.set(Fmt.DASH, "Until full");
            }
        } else {
            double remaining = s.chargeCounterUah > 0 ? s.chargeCounterUah / 1000.0
                    : cap > 0 && s.level >= 0 ? cap * s.level / 100.0 : Double.NaN;
            int drain = stats.isEmpty() ? 0 : -stats.mean();
            if (!Double.isNaN(remaining) && drain > 10) {
                tTime.set(Fmt.duration((long) (remaining / drain * 3_600_000)), "Until empty · at current rate");
            } else {
                tTime.set(Fmt.DASH, "Until empty");
            }
        }
    }

    private static String axisMa(float v) {
        if (Math.abs(v) < 0.5f) return "0";
        String s = String.format(Locale.getDefault(), "%,.0f", Math.abs(v));
        return v < 0 ? Fmt.MINUS + s : s;
    }
}
