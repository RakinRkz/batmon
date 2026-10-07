package com.opendroid.batmon;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.opendroid.batmon.ui.DashboardPage;
import com.opendroid.batmon.ui.HealthPage;
import com.opendroid.batmon.ui.HistoryPage;
import com.opendroid.batmon.ui.Page;
import com.opendroid.batmon.ui.SettingsPage;
import com.opendroid.batmon.ui.Ui;

/** Hosts the four pages behind a bottom navigation bar, drawn edge to edge. */
public final class MainActivity extends Activity {
    private static final int REQ_NOTIFICATIONS = 1;
    private static final String[] TAB_LABELS = {"Now", "History", "Health", "Settings"};
    private static final int[] TAB_ICONS = {R.drawable.ic_tab_now, R.drawable.ic_tab_history,
            R.drawable.ic_tab_health, R.drawable.ic_tab_settings};

    private Ui ui;
    private Prefs prefs;
    private FrameLayout content;
    private final Page[] pages = new Page[4];
    private final View[] tabIcons = new View[4];
    private final TextView[] tabLabels = new TextView[4];
    private int current = -1;
    private boolean resumed;

    public Ui ui() { return ui; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = new Ui(this);
        prefs = Prefs.get(this);
        drawEdgeToEdge();

        LinearLayout root = ui.vertical();
        root.setBackgroundColor(ui.page);
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        View divider = new View(this);
        divider.setBackgroundColor(ui.border);
        root.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, ui.dp(1))));
        LinearLayout nav = buildNav();
        root.addView(nav);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                l = i.left;
                t = i.top;
                r = i.right;
                b = i.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft();
                t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight();
                b = insets.getSystemWindowInsetBottom();
            }
            content.setPadding(l, t, r, 0);
            nav.setPadding(l, 0, r, b);
            return insets;
        });
        setContentView(root);

        pages[0] = new DashboardPage(this);
        pages[1] = new HistoryPage(this);
        pages[2] = new HealthPage(this);
        pages[3] = new SettingsPage(this);
        int tab = savedInstanceState != null ? savedInstanceState.getInt("tab", 0) : 0;
        select(Math.max(0, Math.min(3, tab)));

        Alerts.ensureChannels(this);
        requestNotificationsOnce();
        if (prefs.bool(Prefs.MONITOR)) MonitorService.start(this);
    }

    private void drawEdgeToEdge() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        } else {
            View d = w.getDecorView();
            d.setSystemUiVisibility(d.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        if (Build.VERSION.SDK_INT >= 29) w.setNavigationBarContrastEnforced(false);
    }

    private LinearLayout buildNav() {
        LinearLayout nav = ui.horizontal();
        nav.setBackgroundColor(ui.surface);
        for (int i = 0; i < 4; i++) {
            LinearLayout item = ui.vertical();
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(0, ui.dp(10), 0, ui.dp(10));
            item.setContentDescription(TAB_LABELS[i]);

            FrameLayout pill = new FrameLayout(this);
            ImageView icon = new ImageView(this);
            icon.setImageResource(TAB_ICONS[i]);
            FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER);
            pill.addView(icon, ilp);
            item.addView(pill, new LinearLayout.LayoutParams(ui.dp(60), ui.dp(30)));
            tabIcons[i] = pill;

            TextView label = ui.medium(TAB_LABELS[i], 12, ui.ink2);
            label.setGravity(Gravity.CENTER_HORIZONTAL);
            label.setPadding(0, ui.dp(4), 0, 0);
            item.addView(label);
            tabLabels[i] = label;

            final int idx = i;
            item.setOnClickListener(v -> select(idx));
            nav.addView(item, Ui.weighted());
        }
        return nav;
    }

    private void select(int i) {
        if (i == current) return;
        if (current >= 0 && resumed) pages[current].onHide();
        current = i;
        content.removeAllViews();
        content.addView(pages[i].view());
        if (resumed) pages[i].onShow();
        for (int t = 0; t < 4; t++) {
            boolean sel = t == i;
            tabIcons[t].setBackground(sel ? ui.rounded(Ui.alpha(ui.accent, 0.18f), 999, 0) : null);
            ImageView icon = (ImageView) ((FrameLayout) tabIcons[t]).getChildAt(0);
            icon.setImageTintList(ColorStateList.valueOf(sel ? ui.ink : ui.muted));
            tabLabels[t].setTextColor(sel ? ui.ink : ui.muted);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (current >= 0) pages[current].onShow();
    }

    @Override
    protected void onPause() {
        resumed = false;
        if (current >= 0) pages[current].onHide();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", current);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (current != 0) select(0);
        else super.onBackPressed();
    }

    private void requestNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        if (prefs.bool(Prefs.ASKED_NOTIF_PERMISSION)) return;
        prefs.put(Prefs.ASKED_NOTIF_PERMISSION, true);
        requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQ_NOTIFICATIONS && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            MonitorService.refresh(this); // re-post the notification now that it can be shown
        }
    }
}
