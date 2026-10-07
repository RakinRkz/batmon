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

    private final TextView[] chips = new TextView[RANGES.length];
    private int range = 1;
    private ChartView levelChart, currentChart, tempChart;
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

        boolean f = Prefs.get(act).bool(Prefs.FAHRENHEIT);
        levelChart = chartCard(col, "Battery level", "Percent",
                new ChartView(ui).color(ui.seriesLevel).fixedRange(0, 100).gap(20 * 60_000)
                        .tipFormat(v -> Math.round(v) + " %"));
        currentChart = chartCard(col, "Current", "mA · above zero is charging",
                new ChartView(ui).signedColors(ui.good, ui.serious).minSpan(100).gap(20 * 60_000)
                        .tipFormat(v -> Fmt.signedMa(Math.round(v)) + " mA"));
        tempChart = chartCard(col, "Temperature", Fmt.tempUnit(f),
                new ChartView(ui).color(ui.seriesTemp).minSpan(4).gap(20 * 60_000)
                        .tipFormat(v -> String.format(Locale.getDefault(), "%.1f %s", v, Fmt.tempUnit(f))));

        col.addView(ui.sectionLabel("Sessions"));
        sessionList = ui.vertical();
        col.addView(sessionList);
        return scroll[0];
    }

    private ChartView chartCard(LinearLayout col, String title, String sub, ChartView chart) {
        LinearLayout card = ui.card();
        card.addView(ui.cardHeader(title, sub));
        chart.tipTime(t -> Fmt.dayTime(act, t));
        chart.setContentDescription(title + " chart");
        card.addView(chart, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(160)));
        col.addView(card);
        return chart;
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
                empty.setVisibility(s.n == 0 && sessions.isEmpty() ? View.VISIBLE : View.GONE);
                for (ChartView c : new ChartView[] {levelChart, currentChart, tempChart}) c.setRange(from, to);
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

        String main = s.startLevel + " % → " + s.endLevel + " %   ·   "
                + (s.charge ? "+" : Fmt.MINUS) + Fmt.mah(s.mah) + "   ·   avg " + maOrDash(s.avgMa());
        TextView mainT = ui.medium(main, 14, ui.ink);
        mainT.setPadding(0, ui.dp(10), 0, 0);
        card.addView(mainT);

        String detail;
        if (s.charge) {
            double est = s.estimatedCapacityMah();
            detail = (Float.isNaN(s.maxTemp) ? "" : "Max " + Fmt.temp(s.maxTemp, Prefs.get(act).bool(Prefs.FAHRENHEIT)) + "   ·   ")
                    + (Double.isNaN(est) ? "Too short to estimate capacity" : "Implies " + Fmt.mah(est) + " full capacity");
        } else {
            detail = "Screen on " + maOrDash(s.avgMaScreenOn()) + " (" + Fmt.duration(s.msScreenOn) + ")   ·   "
                    + "off " + maOrDash(s.avgMaScreenOff()) + " (" + Fmt.duration(s.msScreenOff) + ")";
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
