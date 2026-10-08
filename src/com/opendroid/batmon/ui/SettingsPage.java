package com.opendroid.batmon.ui;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.opendroid.batmon.BatteryReader;
import com.opendroid.batmon.Fmt;
import com.opendroid.batmon.HistoryDb;
import com.opendroid.batmon.MainActivity;
import com.opendroid.batmon.MonitorService;
import com.opendroid.batmon.Prefs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SettingsPage extends Page {
    private final Prefs prefs;
    /** Rows whose summary depends on state outside this page; refreshed on every show. */
    private final List<Runnable> refreshers = new ArrayList<>();
    private LinearLayout card;

    public SettingsPage(MainActivity act) {
        super(act);
        prefs = Prefs.get(act);
    }

    @Override
    protected View build() {
        ScrollView[] scroll = new ScrollView[1];
        LinearLayout col = scrollColumn(scroll);
        col.addView(ui.pageTitle("Settings"));

        section(col, "Monitoring");
        switchRow("Background monitoring", "Live notification, history, sessions and alerts", Prefs.MONITOR, on -> {
            if (on) MonitorService.start(act);
            else MonitorService.stop(act);
        });
        choiceRow("Update interval", "How often to sample while the screen is on", Prefs.INTERVAL,
                new String[] {"2 seconds", "5 seconds", "10 seconds", "30 seconds"}, new int[] {2, 5, 10, 30});
        choiceRow("Status bar icon", null, Prefs.NOTIF_ICON,
                new String[] {"Current (mA)", "Battery level (%)", "Temperature"},
                new String[] {"current", "level", "temp"});
        switchRow("Measure accurately while charging",
                "Keeps the CPU awake while plugged in so charge sessions have no gaps", Prefs.KEEP_AWAKE_CHARGING,
                on -> MonitorService.refresh(act));
        actionRow("Background restrictions", () -> isIgnoringOptimizations()
                ? "Unrestricted: monitoring can run in the background"
                : "Optimized: Android may pause monitoring. Tap to allow.", this::requestUnrestricted);
        if (Build.VERSION.SDK_INT >= 33) {
            actionRow("Notifications", () -> act.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED ? "Allowed" : "Blocked: the live notification and alerts are hidden. Tap to fix.",
                    this::openNotificationSettings);
        }

        section(col, "Alerts");
        switchRow("Charge limit alert", "Notify when charging reaches the limit", Prefs.ALERT_CHARGE,
                on -> MonitorService.refresh(act));
        choiceRow("Charge limit", null, Prefs.ALERT_CHARGE_LEVEL, percentLabels(60, 100, 5), range(60, 100, 5));
        switchRow("Low battery alert", "Notify when discharging falls to the level", Prefs.ALERT_LOW,
                on -> MonitorService.refresh(act));
        choiceRow("Low battery level", null, Prefs.ALERT_LOW_LEVEL, percentLabels(5, 50, 5), range(5, 50, 5));
        switchRow("High temperature alert", "Notify when the battery gets too hot", Prefs.ALERT_TEMP,
                on -> MonitorService.refresh(act));
        int[] temps = range(35, 50, 1);
        String[] tempLabels = new String[temps.length];
        for (int i = 0; i < temps.length; i++) tempLabels[i] = temps[i] + " °C";
        choiceRow("Temperature limit", null, Prefs.ALERT_TEMP_C, tempLabels, temps);

        section(col, "Display & measurement");
        choiceRow("Temperature unit", null, Prefs.FAHRENHEIT, new String[] {"Celsius", "Fahrenheit"},
                new boolean[] {false, true});
        choiceRow("Smoothing", "How the headline current is averaged", Prefs.SMOOTHING,
                new String[] {"Off (live reading)", "Normal (10 s)", "Heavy (30 s)"}, new int[] {0, 1, 2});
        choiceRow("Current unit", "What CURRENT_NOW reports on this phone", Prefs.CURRENT_UNIT,
                new String[] {"Auto-detect", "Microamps (µA)", "Milliamps (mA)"}, new String[] {"auto", "ua", "ma"});
        choiceRow("Current sign", "Flip it if charging shows as negative", Prefs.CURRENT_SIGN,
                new String[] {"Auto-detect", "Normal (+ is charging)", "Inverted"},
                new String[] {"auto", "normal", "inverted"});
        actionRow("Design capacity", () -> {
            int override = prefs.integer(Prefs.DESIGN_CAPACITY);
            int system = BatteryReader.systemDesignCapacityMah(act);
            if (override > 0) return Fmt.mah(override) + " (set by you)";
            return system > 0 ? Fmt.mah(system) + " (from the system)" : "Unknown. Tap to enter.";
        }, this::editDesignCapacity);

        section(col, "Data");
        choiceRow("Keep chart data for", "sessions are kept for a year", Prefs.HISTORY_DAYS,
                new String[] {"7 days", "14 days", "30 days", "90 days"}, new int[] {7, 14, 30, 90});
        actionRow("Clear history", () -> "Delete all samples and sessions", this::confirmClear);
        actionRow("Raw battery values", () -> "What the phone reports, for troubleshooting", this::showDiagnostics);

        section(col, "About");
        String version = "";
        try {
            version = act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        String v = version;
        actionRow("BatMon " + v, () -> "Free software under the GNU GPL v3. No internet access; your data stays on "
                + "this phone.", null);
        actionRow("Source code", () -> "github.com/RakinRkz/batmon · report issues and get updates",
                () -> openUrl(REPO_URL));
        return scroll[0];
    }

    private static final String REPO_URL = "https://github.com/RakinRkz/batmon";

    private void openUrl(String url) {
        if (!startFirst(new Intent(Intent.ACTION_VIEW, Uri.parse(url)))) {
            Toast.makeText(act, url, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Starts the first intent some app handles. TV, Automotive and some OEM builds lack several
     * Settings screens, and starting a missing one throws.
     */
    private boolean startFirst(Intent... candidates) {
        for (Intent i : candidates) {
            try {
                act.startActivity(i);
                return true;
            } catch (RuntimeException ignored) {
                // ActivityNotFoundException, or SecurityException on locked-down builds
            }
        }
        return false;
    }

    private void notAvailable() {
        Toast.makeText(act, "Not available on this device", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onShow() {
        for (Runnable r : refreshers) r.run();
    }

    // ---- row builders ----

    private void section(LinearLayout col, String title) {
        col.addView(ui.sectionLabel(title));
        card = ui.card();
        card.setPadding(0, ui.dp(4), 0, ui.dp(4));
        col.addView(card);
    }

    private LinearLayout rowShell(String title, TextView[] summaryOut) {
        LinearLayout row = ui.horizontal();
        row.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12));
        row.setMinimumHeight(ui.dp(56));
        LinearLayout text = ui.vertical();
        text.addView(ui.text(title, 15, ui.ink));
        TextView summary = ui.text("", 13, ui.ink2);
        summary.setPadding(0, ui.dp(4), 0, 0);
        summary.setVisibility(View.GONE);
        text.addView(summary);
        row.addView(text, Ui.weighted());
        summaryOut[0] = summary;
        card.addView(row);
        return row;
    }

    private static void setSummary(TextView t, CharSequence s) {
        t.setText(s);
        t.setVisibility(s == null || s.length() == 0 ? View.GONE : View.VISIBLE);
    }

    interface OnToggle { void toggled(boolean on); }

    interface Summary { String get(); }

    private void switchRow(String title, String summary, String key, OnToggle after) {
        TextView[] sum = new TextView[1];
        LinearLayout row = rowShell(title, sum);
        setSummary(sum[0], summary);
        Switch sw = new Switch(act);
        sw.setChecked(prefs.bool(key));
        sw.setClickable(false);
        sw.setPadding(ui.dp(12), 0, 0, 0);
        row.addView(sw);
        row.setOnClickListener(v -> {
            boolean on = !prefs.bool(key);
            prefs.put(key, on);
            sw.setChecked(on);
            if (after != null) after.toggled(on);
        });
        refreshers.add(() -> sw.setChecked(prefs.bool(key)));
    }

    private void actionRow(String title, Summary summary, Runnable onClick) {
        TextView[] sum = new TextView[1];
        LinearLayout row = rowShell(title, sum);
        Runnable refresh = () -> setSummary(sum[0], summary.get());
        refresh.run();
        refreshers.add(refresh);
        if (onClick != null) row.setOnClickListener(v -> onClick.run());
    }

    private void choiceRow(String title, String hint, String key, String[] labels, int[] values) {
        choice(title, hint, labels, () -> indexOf(values, prefs.integer(key)), i -> {
            prefs.put(key, values[i]);
            afterChange(key);
        });
    }

    private void choiceRow(String title, String hint, String key, String[] labels, String[] values) {
        choice(title, hint, labels, () -> {
            String cur = prefs.string(key);
            for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) return i;
            return 0;
        }, i -> {
            prefs.put(key, values[i]);
            afterChange(key);
        });
    }

    private void choiceRow(String title, String hint, String key, String[] labels, boolean[] values) {
        choice(title, hint, labels, () -> {
            boolean cur = prefs.bool(key);
            for (int i = 0; i < values.length; i++) if (values[i] == cur) return i;
            return 0;
        }, i -> {
            prefs.put(key, values[i]);
            afterChange(key);
        });
    }

    interface Index { int get(); }

    interface Picked { void picked(int i); }

    private void choice(String title, String hint, String[] labels, Index current, Picked picked) {
        TextView[] sum = new TextView[1];
        LinearLayout row = rowShell(title, sum);
        Runnable refresh = () -> {
            int i = current.get();
            String value = i >= 0 ? labels[i] : "";
            setSummary(sum[0], hint != null ? value + " · " + hint : value);
        };
        refresh.run();
        refreshers.add(refresh);
        row.setOnClickListener(v -> new AlertDialog.Builder(act)
                .setTitle(title)
                .setSingleChoiceItems(labels, current.get(), (d, which) -> {
                    picked.picked(which);
                    refresh.run();
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }

    private void afterChange(String key) {
        switch (key) {
            // Learned unit and sign are kept: auto-detect picks up where it left off if chosen again.
            case Prefs.CURRENT_UNIT:
            case Prefs.CURRENT_SIGN:
            case Prefs.INTERVAL:
            case Prefs.NOTIF_ICON:
            case Prefs.FAHRENHEIT:
                MonitorService.refresh(act);
                break;
            default:
                break;
        }
    }

    private static int indexOf(int[] values, int v) {
        for (int i = 0; i < values.length; i++) if (values[i] == v) return i;
        return -1;
    }

    private static int[] range(int from, int to, int step) {
        int[] r = new int[(to - from) / step + 1];
        for (int i = 0; i < r.length; i++) r[i] = from + i * step;
        return r;
    }

    private static String[] percentLabels(int from, int to, int step) {
        int[] r = range(from, to, step);
        String[] s = new String[r.length];
        for (int i = 0; i < r.length; i++) s[i] = r[i] + " %";
        return s;
    }

    // ---- actions ----

    private boolean isIgnoringOptimizations() {
        PowerManager pm = act.getSystemService(PowerManager.class);
        return pm != null && pm.isIgnoringBatteryOptimizations(act.getPackageName());
    }

    private void requestUnrestricted() {
        Intent list = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + act.getPackageName()));
        boolean started = isIgnoringOptimizations() ? startFirst(list, details)
                : startFirst(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + act.getPackageName())), list, details);
        if (!started) notAvailable();
    }

    private void openNotificationSettings() {
        boolean started = startFirst(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, act.getPackageName()),
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + act.getPackageName())));
        if (!started) notAvailable();
    }

    private void editDesignCapacity() {
        EditText input = new EditText(act);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("e.g. 5000");
        int override = prefs.integer(Prefs.DESIGN_CAPACITY);
        if (override > 0) input.setText(String.valueOf(override));
        LinearLayout box = ui.vertical();
        box.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0);
        box.addView(input);
        int system = BatteryReader.systemDesignCapacityMah(act);
        new AlertDialog.Builder(act)
                .setTitle("Design capacity (mAh)")
                .setMessage(system > 0 ? "The system reports " + Fmt.mah(system) + ". Leave empty to use that."
                        : "Find it on the battery label or the phone's spec sheet.")
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    int v = 0;
                    try {
                        v = Integer.parseInt(input.getText().toString().trim());
                    } catch (NumberFormatException ignored) {
                    }
                    prefs.put(Prefs.DESIGN_CAPACITY, v >= 500 && v <= 50_000 ? v : 0);
                    onShow();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmClear() {
        new AlertDialog.Builder(act)
                .setTitle("Clear history?")
                .setMessage("All logged samples and sessions will be deleted. Capacity measurements start over.")
                .setPositiveButton("Clear", (d, w) -> HistoryDb.IO.execute(() -> {
                    HistoryDb.get(act).clear();
                    act.runOnUiThread(() -> Toast.makeText(act, "History cleared", Toast.LENGTH_SHORT).show());
                }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showDiagnostics() {
        StringBuilder sb = new StringBuilder();
        for (String[] r : new BatteryReader(act).diagnostics()) {
            sb.append(r[0]).append(": ").append(r[1]).append('\n');
        }
        sb.append(String.format(Locale.US, "Android %s (API %d), %s %s", Build.VERSION.RELEASE,
                Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.MODEL));
        TextView t = ui.text(sb.toString(), 12, ui.ink);
        t.setTextIsSelectable(true);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setPadding(ui.dp(24), ui.dp(12), ui.dp(24), ui.dp(12));
        ScrollView sv = new ScrollView(act);
        sv.addView(t);
        new AlertDialog.Builder(act)
                .setTitle("Raw battery values")
                .setView(sv)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
