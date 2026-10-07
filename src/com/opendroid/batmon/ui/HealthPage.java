package com.opendroid.batmon.ui;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.opendroid.batmon.BatteryReader;
import com.opendroid.batmon.BatterySnapshot;
import com.opendroid.batmon.Fmt;
import com.opendroid.batmon.HistoryDb;
import com.opendroid.batmon.MainActivity;
import com.opendroid.batmon.Prefs;
import com.opendroid.batmon.Session;

import java.util.List;
import java.util.Locale;

/**
 * Battery wear: full capacity now vs. design capacity. Full capacity comes from charge sessions
 * measured by integrating current (independent of the phone's own gauge) when there are any,
 * otherwise from the fuel gauge's charge counter.
 */
public final class HealthPage extends Page {
    private static final long DAY = 86_400_000L;

    private TextView heroValue, heroCaption, heroStatus;
    private View heroDot;
    private LinearLayout meterTrack;
    private View meterFill;
    private LinearLayout rows, sessionsCard;
    private int loadToken;

    public HealthPage(MainActivity act) {
        super(act);
    }

    @Override
    protected View build() {
        ScrollView[] scroll = new ScrollView[1];
        LinearLayout col = scrollColumn(scroll);
        col.addView(ui.pageTitle("Health"));

        LinearLayout hero = ui.card();
        hero.setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(20));
        hero.addView(ui.text("Estimated capacity left", 13, ui.muted));
        heroValue = ui.medium(Fmt.DASH, 56, ui.ink);
        heroValue.setPadding(0, ui.dp(10), 0, 0);
        hero.addView(heroValue);
        LinearLayout status = ui.horizontal();
        status.setPadding(0, ui.dp(8), 0, ui.dp(14));
        heroDot = ui.dot(ui.muted, 8);
        status.addView(heroDot);
        heroStatus = ui.medium("", 14, ui.ink);
        heroStatus.setPadding(ui.dp(8), 0, 0, 0);
        status.addView(heroStatus);
        hero.addView(status);

        meterTrack = ui.horizontal();
        meterTrack.setBackground(ui.rounded(ui.grid, 999, 0));
        meterFill = new View(act);
        meterTrack.addView(meterFill, new LinearLayout.LayoutParams(0, ui.dp(10), 0f));
        hero.addView(meterTrack, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(10)));

        heroCaption = ui.text("", 13, ui.ink2);
        heroCaption.setPadding(0, ui.dp(12), 0, 0);
        hero.addView(heroCaption);
        col.addView(hero);

        LinearLayout details = ui.card();
        details.addView(ui.cardHeader("Capacity", null));
        rows = ui.vertical();
        details.addView(rows);
        col.addView(details);

        sessionsCard = ui.card();
        col.addView(sessionsCard);

        LinearLayout how = ui.card();
        how.addView(ui.cardHeader("How this is measured", null));
        how.addView(ui.text("Design capacity is what the battery held when new. Full capacity is what it "
                + "holds now. On Android 16 and later the phone's battery gauge reports the full capacity it "
                + "has learned, and BatMon uses that when it is available. BatMon also has two figures of its "
                + "own. The charge counter estimate divides the remaining charge by the battery level. The "
                + "measured figure adds up the current that flows in during a charge and scales it to 100 %.\n\n"
                + "For the measured figure, charge in one go from low to high, for example from below 40 % to "
                + "above 80 %, with background monitoring on. Each such charge adds one sample, and more "
                + "samples give a steadier figure.", 13, ui.ink2));
        col.addView(how);
        return scroll[0];
    }

    @Override
    public void onShow() {
        final int token = ++loadToken;
        final BatterySnapshot now = new BatteryReader(act).read();
        final int design = BatteryReader.designCapacityMah(act);
        final boolean designOverridden = Prefs.get(act).integer(Prefs.DESIGN_CAPACITY) > 0;
        HistoryDb.IO.execute(() -> {
            HistoryDb db = HistoryDb.get(act);
            // A battery can't hold less than ~30 % or more than ~130 % of its design capacity.
            double gauge = design > 0
                    ? db.gaugeCapacityMedianMah(System.currentTimeMillis() - 7 * DAY, design * 0.3, design * 1.3)
                    : db.gaugeCapacityMedianMah(System.currentTimeMillis() - 7 * DAY, 500, 50_000);
            final boolean rough = Double.isNaN(gauge);
            if (rough && now.chargeCounterUah > 0 && now.level >= 10) {
                gauge = now.chargeCounterUah / 1000.0 * 100.0 / now.level;
            }
            List<Session> charges = db.chargeSessions();
            double totalCharged = db.totalChargedMah();
            final double gaugeF = gauge;
            act.runOnUiThread(() -> {
                if (token != loadToken || act.isDestroyed()) return;
                render(now, design, designOverridden, gaugeF, rough, charges, totalCharged);
            });
        });
    }

    private void render(BatterySnapshot now, int design, boolean designOverridden, double gauge,
                        boolean gaugeRough, List<Session> charges, double totalCharged) {
        double sum = 0, weight = 0;
        int used = 0;
        for (Session s : charges) {
            double est = s.estimatedCapacityMah();
            if (Double.isNaN(est)) continue;
            sum += est * s.deltaLevel(); // longer charges are more trustworthy
            weight += s.deltaLevel();
            used++;
        }
        double measured = weight > 0 ? sum / weight : Double.NaN;
        double full;
        String basis;
        if (now.maxCapacityMah > 0) {
            full = now.maxCapacityMah;
            basis = "the capacity Android's battery gauge has learned";
        } else if (!Double.isNaN(measured)) {
            full = measured;
            basis = "measured charging";
        } else {
            full = gauge;
            basis = gaugeRough ? "the charge counter (rough until the battery has been above 30 %)"
                    : "the charge counter";
        }

        if (Double.isNaN(full) || design <= 0) {
            heroValue.setText(Fmt.DASH);
            heroStatus.setText(design <= 0 ? "Design capacity unknown" : "Not enough data yet");
            setDot(ui.muted);
            setMeter(0, ui.muted);
            heroCaption.setText(design <= 0 ? "Enter your battery's design capacity in Settings."
                    : "Keep monitoring on; an estimate appears once the battery is above 30 %.");
        } else {
            double pct = Math.min(100, full * 100 / design);
            heroValue.setText(String.format(Locale.getDefault(), "%.0f %%", pct));
            int color;
            String label;
            if (pct >= 80) {
                color = ui.good;
                label = "Good";
            } else if (pct >= 60) {
                color = ui.warning;
                label = "Worn";
            } else {
                color = ui.critical;
                label = "Heavily worn, consider replacing";
            }
            heroStatus.setText(label);
            setDot(color);
            setMeter((float) pct / 100f, color);
            heroCaption.setText(Fmt.mah(full) + " of " + Fmt.mah(design) + " design, based on " + basis + ".");
        }

        rows.removeAllViews();
        addRow("Design capacity", design > 0 ? Fmt.mah(design) : Fmt.DASH,
                designOverridden ? "Set in Settings" : design > 0 ? "Reported by the system" : "Unknown; set it in Settings");
        if (now.maxCapacityMah > 0) {
            addRow("Learned full capacity", Fmt.mah(now.maxCapacityMah), "Reported by Android's battery gauge");
        }
        addRow("Charge counter estimate", Fmt.mah(gauge), gaugeRough
                ? "Charge counter ÷ level, current reading only (rough below 30 %)"
                : "Charge counter ÷ level, median of the last 7 days");
        addRow("Measured from charging", Fmt.mah(measured),
                used > 0 ? used + (used == 1 ? " charge session" : " charge sessions") : "Needs a charge of at least "
                        + Session.MIN_ESTIMATE_DELTA + " percentage points");
        addRow("Charge cycles", now.cycleCount >= 0 ? String.valueOf(now.cycleCount) : Fmt.DASH,
                now.cycleCount > 0 ? "Reported by Android"
                        : now.cycleCount == 0 ? "Reported as 0; likely unsupported" : "Not reported on this device");
        String cycles = design > 0 && totalCharged > 0
                ? String.format(Locale.getDefault(), "≈ %.1f full cycles", totalCharged / design) : null;
        addRow("Charged while monitored", Fmt.mah(totalCharged), cycles);
        addRow("Android health status", Fmt.health(now.health), null);

        sessionsCard.removeAllViews();
        sessionsCard.addView(ui.cardHeader("Charge sessions used", "Each one implies a full capacity"));
        int shown = 0;
        for (Session s : charges) {
            double est = s.estimatedCapacityMah();
            if (Double.isNaN(est)) continue;
            addRowTo(sessionsCard, Fmt.dayTime(act, s.startTs),
                    Fmt.mah(est), s.startLevel + " % → " + s.endLevel + " %, " + Fmt.mah(s.mah) + " in");
            if (++shown == 10) break;
        }
        if (shown == 0) {
            sessionsCard.addView(ui.text("None yet. Charge from low to high with monitoring on.", 13, ui.ink2));
        }
    }

    private void setDot(int color) {
        ((android.graphics.drawable.GradientDrawable) heroDot.getBackground()).setColor(color);
    }

    private void setMeter(float fraction, int color) {
        meterFill.setBackground(ui.rounded(color, 999, 0));
        meterFill.setLayoutParams(new LinearLayout.LayoutParams(0, ui.dp(10), fraction));
        meterTrack.setWeightSum(1f);
    }

    private void addRow(String label, String value, String sub) {
        addRowTo(rows, label, value, sub);
    }

    private void addRowTo(LinearLayout parent, String label, String value, String sub) {
        LinearLayout row = ui.horizontal();
        row.setPadding(0, ui.dp(8), 0, ui.dp(8));
        LinearLayout left = ui.vertical();
        left.addView(ui.text(label, 14, ui.ink));
        if (sub != null) {
            TextView s = ui.text(sub, 12, ui.muted);
            s.setPadding(0, ui.dp(3), 0, 0);
            left.addView(s);
        }
        row.addView(left, Ui.weighted());
        TextView v = ui.medium(value, 15, ui.ink);
        v.setPadding(ui.dp(12), 0, 0, 0);
        row.addView(v);
        parent.addView(row);
    }
}
