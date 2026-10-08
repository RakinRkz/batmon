package com.opendroid.batmon.ui;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.opendroid.batmon.Fmt;
import com.opendroid.batmon.HistoryDb;
import com.opendroid.batmon.MainActivity;
import com.opendroid.batmon.Prefs;
import com.opendroid.batmon.Session;

import java.util.List;
import java.util.Locale;

/** Logged level / current / temperature over a chosen window, plus the charge & discharge sessions. */
public final class HistoryPage extends Page {
    private static final long HOUR = 3_600_000L;
    private static final long[] RANGES = {6 * HOUR, 24 * HOUR, 72 * HOUR, 168 * HOUR};
    private static final String[] RANGE_LABELS = {"6 h", "24 h", "3 d", "7 d"};
    private static final int MAX_POINTS = 480;
    /**
     * Lines break where samples are further apart than this. A sleeping phone logs only on level
     * changes, so an hour or more without samples is normal and still drawn as a line.
     */
    private static final long MIN_GAP_MS = 2 * HOUR;

    private final TextView[] chips = new TextView[RANGES.length];
    private int range = 1;
    private ChartView levelChart, currentChart, tempChart;
    private TextView tempUnitLabel;
    private boolean fahrenheit;
    private LinearLayout sessionList;
    private TextView empty;
    private int loadToken;

    public HistoryPage(MainActivity act) {
        super(act);
    }

    @Override
    protected View build() {
        ScrollView[] scroll = new ScrollView[1];
        LinearLayout col = scrollColumn(scroll);
        col.addView(ui.pageTitle("History"));

        LinearLayout chipRow = ui.horizontal();
        chipRow.setPadding(0, 0, 0, ui.dp(12));
        for (int i = 0; i < RANGES.length; i++) {
            TextView chip = ui.medium(RANGE_LABELS[i], 14, ui.ink2);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8));
            final int idx = i;
            chip.setOnClickListener(v -> {
                range = idx;
                styleChips();
                load();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = ui.dp(8);
            chipRow.addView(chip, lp);
            chips[i] = chip;
        }
        styleChips();
        col.addView(chipRow);

        empty = ui.text("Nothing logged yet. Keep background monitoring on in Settings and history builds up here.",
                14, ui.ink2);
        empty.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(12));
        empty.setVisibility(View.GONE);
        col.addView(empty);

        levelChart = new ChartView(ui).color(ui.seriesLevel).fixedRange(0, 100)
                .tipFormat(v -> Math.round(v) + " %");
        chartCard(col, "Battery level", "Percent", levelChart);
        currentChart = new ChartView(ui).signedColors(ui.good, ui.serious).minSpan(100)
                .tipFormat(v -> Fmt.signedMa(Math.round(v)) + " mA");
        chartCard(col, "Current", "mA · above zero is charging", currentChart);
        // The unit is read on every load, since Settings can change it while this page is kept.
        tempChart = new ChartView(ui).color(ui.seriesTemp).minSpan(4)
                .tipFormat(v -> String.format(Locale.getDefault(), "%.1f %s", v, Fmt.tempUnit(fahrenheit)));
        tempUnitLabel = chartCard(col, "Temperature", Fmt.tempUnit(fahrenheit), tempChart);

        col.addView(ui.sectionLabel("Sessions"));
        sessionList = ui.vertical();
        col.addView(sessionList);
        return scroll[0];
    }

    /** Adds a titled card around {@code chart}; returns the subtitle view. */
    private TextView chartCard(LinearLayout col, String title, String sub, ChartView chart) {
        LinearLayout card = ui.card();
        LinearLayout header = ui.vertical();
        header.addView(ui.medium(title, 15, ui.ink));
        TextView subtitle = ui.text(sub, 13, ui.muted);
        subtitle.setPadding(0, ui.dp(4), 0, 0);
        header.addView(subtitle);
        header.setPadding(0, 0, 0, ui.dp(12));
        card.addView(header);
        chart.tipTime(t -> Fmt.dayTime(act, t));
        chart.setContentDescription(title + " chart");
        card.addView(chart, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(160)));
        col.addView(card);
        return subtitle;
    }

    private void styleChips() {
        for (int i = 0; i < chips.length; i++) {
            boolean sel = i == range;
            chips[i].setTextColor(sel ? ui.ink : ui.ink2);
            chips[i].setBackground(sel ? ui.rounded(Ui.alpha(ui.accent, 0.16f), 999, Ui.alpha(ui.accent, 0.5f))
                    : ui.rounded(ui.surface, 999, ui.border));
        }
    }

    @Override
    public void onShow() {
        load();
    }

    private void load() {
        final int token = ++loadToken;
        final long to = System.currentTimeMillis();
        final long from = to - RANGES[range];
        final boolean f = Prefs.get(act).bool(Prefs.FAHRENHEIT);
        HistoryDb.IO.execute(() -> {
            HistoryDb db = HistoryDb.get(act);
            HistoryDb.Series s = db.series(from, to, MAX_POINTS);
            List<Session> sessions = db.recentSessions(40);
            if (f) {
                for (int i = 0; i < s.n; i++) s.temp[i] = Fmt.tempValue(s.temp[i], true);
            }
            act.runOnUiThread(() -> {
                if (token != loadToken || act.isDestroyed()) return;
                fahrenheit = f;
                tempUnitLabel.setText(Fmt.tempUnit(f));
                empty.setVisibility(s.n == 0 && sessions.isEmpty() ? View.VISIBLE : View.GONE);
                // Wider ranges average samples into wider buckets; never break between neighbours.
                long gap = Math.max(MIN_GAP_MS, 3 * s.bucketMs);
                for (ChartView c : new ChartView[] {levelChart, currentChart, tempChart}) {
                    c.gap(gap);
                    c.setRange(from, to);
                }
                levelChart.setData(s.t, s.level, s.n);
                currentChart.setData(s.t, s.current, s.n);
                tempChart.setData(s.t, s.temp, s.n);
                renderSessions(sessions);
            });
        });
    }

    private void renderSessions(List<Session> sessions) {
        sessionList.removeAllViews();
        if (sessions.isEmpty()) {
            TextView t = ui.text("No sessions yet. A session starts each time you plug in or unplug.", 14, ui.ink2);
            t.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(12));
            sessionList.addView(t);
            return;
        }
        for (Session s : sessions) sessionList.addView(sessionCard(s));
    }

    private View sessionCard(Session s) {
        LinearLayout card = ui.card();
        card.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14));

        LinearLayout head = ui.horizontal();
        head.addView(ui.dot(s.charge ? ui.good : ui.serious, 8));
        TextView kind = ui.medium(s.charge ? "Charge" : "Discharge", 15, ui.ink);
        kind.setPadding(ui.dp(8), 0, 0, 0);
        head.addView(kind, Ui.weighted());
        TextView when = ui.text(s.open ? "In progress" : Fmt.duration(s.durationMs()), 13, ui.ink2);
        head.addView(when);
        card.addView(head);

        TextView range = ui.text(Fmt.dayTime(act, s.startTs) + "  →  "
                + (s.open ? "now" : Fmt.time(act, s.endTs)), 13, ui.muted);
        range.setPadding(0, ui.dp(6), 0, 0);
        card.addView(range);

        // Net charge and average current are signed as charge into the battery, like the Now page.
        double avg = s.avgMa();
        String avgText = Double.isNaN(avg) ? Fmt.DASH
                : Fmt.signedMa((int) Math.round(s.charge ? avg : -avg)) + " mA";
        String main = s.startLevel + " % → " + s.endLevel + " %   ·   "
                + Fmt.signedMah(s.netMah()) + "   ·   avg " + avgText;
        TextView mainT = ui.medium(main, 14, ui.ink);
        mainT.setPadding(0, ui.dp(10), 0, 0);
        card.addView(mainT);

        String detail;
        if (s.charge) {
            String issue = s.capacityIssue();
            detail = (Float.isNaN(s.maxTemp) ? "" : "Max " + Fmt.temp(s.maxTemp, fahrenheit) + "   ·   ")
                    + (issue != null ? issue : "Implies " + Fmt.mah(s.estimatedCapacityMah()) + " full capacity");
        } else {
            detail = "Drain with screen on " + maOrDash(s.avgMaScreenOn()) + " (" + Fmt.duration(s.msScreenOn)
                    + ")   ·   off " + maOrDash(s.avgMaScreenOff()) + " (" + Fmt.duration(s.msScreenOff) + ")";
        }
        TextView d = ui.text(detail, 13, ui.ink2);
        d.setPadding(0, ui.dp(6), 0, 0);
        card.addView(d);
        return card;
    }

    private static String maOrDash(double ma) {
        return Double.isNaN(ma) ? Fmt.DASH : Fmt.ma(ma) + " mA";
    }
}
