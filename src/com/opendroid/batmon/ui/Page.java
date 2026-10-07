package com.opendroid.batmon.ui;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.opendroid.batmon.MainActivity;

/** One bottom-navigation destination. Its view is built lazily and kept while the activity lives. */
public abstract class Page {
    protected final MainActivity act;
    protected final Ui ui;
    private View root;

    protected Page(MainActivity act) {
        this.act = act;
        this.ui = act.ui();
    }

    public final View view() {
        if (root == null) root = build();
        return root;
    }

    protected abstract View build();

    /** Called when the page becomes visible with the activity resumed. */
    public void onShow() {}

    /** Called when the page is hidden or the activity pauses. */
    public void onHide() {}

    /** Scrollable column with standard page padding; returns the column to fill. */
    protected LinearLayout scrollColumn(ScrollView[] out) {
        ScrollView scroll = new ScrollView(act);
        scroll.setFillViewport(true);
        LinearLayout col = ui.vertical();
        col.setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(16));
        scroll.addView(col);
        out[0] = scroll;
        return col;
    }
}
