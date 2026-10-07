package com.opendroid.batmon.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.opendroid.batmon.Fmt;
import com.opendroid.batmon.R;

/** Theme tokens (resolved for the current light/dark mode) and small view factories. */
public final class Ui {
    public static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    public final Context c;
    public final int page, surface, ink, ink2, muted, grid, baseline, border, accent;
    public final int good, warning, serious, critical, seriesLevel, seriesTemp;
    private final float density;

    public Ui(Context c) {
        this.c = c;
        page = c.getColor(R.color.page);
        surface = c.getColor(R.color.surface);
        ink = c.getColor(R.color.ink);
        ink2 = c.getColor(R.color.ink2);
        muted = c.getColor(R.color.muted);
        grid = c.getColor(R.color.grid);
        baseline = c.getColor(R.color.baseline);
        border = c.getColor(R.color.border);
        accent = c.getColor(R.color.accent);
        good = c.getColor(R.color.good);
        warning = c.getColor(R.color.warning);
        serious = c.getColor(R.color.serious);
        critical = c.getColor(R.color.critical);
        seriesLevel = c.getColor(R.color.series_level);
        seriesTemp = c.getColor(R.color.series_temp);
        density = c.getResources().getDisplayMetrics().density;
    }

    public int dp(float v) { return Math.round(v * density); }

    public float dpf(float v) { return v * density; }

    public float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, c.getResources().getDisplayMetrics());
    }

    public static int alpha(int color, float a) {
        return (Math.round(a * 255) << 24) | (color & 0x00FFFFFF);
    }

    /** {@code a} blended toward {@code b} by {@code t}. */
    public static int mix(int a, int b, float t) {
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    public TextView text(CharSequence s, float sizeSp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    public TextView medium(CharSequence s, float sizeSp, int color) {
        TextView t = text(s, sizeSp, color);
        t.setTypeface(MEDIUM);
        return t;
    }

    public LinearLayout vertical() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public GradientDrawable rounded(int fill, float radiusDp, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dpf(radiusDp));
        if (stroke != 0) d.setStroke(Math.max(1, dp(1)), stroke);
        return d;
    }

    /** A rounded surface with a hairline ring; children go inside 16dp padding. */
    public LinearLayout card() {
        LinearLayout l = vertical();
        l.setBackground(rounded(surface, 16, border));
        int p = dp(16);
        l.setPadding(p, p, p, p);
        l.setLayoutParams(blockParams(12));
        return l;
    }

    public LinearLayout.LayoutParams blockParams(int bottomMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(bottomMarginDp);
        return lp;
    }

    public static LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    public TextView pageTitle(String s) {
        TextView t = medium(s, 28, ink);
        t.setPadding(dp(4), dp(12), dp(4), dp(16));
        return t;
    }

    public TextView sectionLabel(String s) {
        TextView t = medium(s, 14, ink2);
        t.setPadding(dp(4), dp(12), dp(4), dp(8));
        return t;
    }

    /** Card heading: title plus an optional muted subtitle. */
    public View cardHeader(String title, String subtitle) {
        LinearLayout l = vertical();
        l.addView(medium(title, 15, ink));
        if (subtitle != null) {
            TextView s = text(subtitle, 13, muted);
            s.setPadding(0, dp(4), 0, 0);
            l.addView(s);
        }
        l.setPadding(0, 0, 0, dp(12));
        return l;
    }

    public View dot(int color, int sizeDp) {
        View v = new View(c);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        v.setBackground(d);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    /** Lays views out in rows of {@code cols} equal columns with 12dp gutters. */
    public LinearLayout grid(int cols, View... views) {
        LinearLayout outer = vertical();
        outer.setLayoutParams(blockParams(0));
        for (int i = 0; i < views.length; i += cols) {
            LinearLayout row = horizontal();
            row.setGravity(Gravity.TOP);
            row.setBaselineAligned(false);
            for (int j = 0; j < cols; j++) {
                LinearLayout.LayoutParams lp = weighted();
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                if (j > 0) lp.leftMargin = dp(12);
                View v = i + j < views.length ? views[i + j] : new View(c);
                row.addView(v, lp);
            }
            LinearLayout.LayoutParams rp = blockParams(12);
            outer.addView(row, rp);
        }
        return outer;
    }

    /** A labelled value tile with an optional caption. */
    public final class Tile {
        public final LinearLayout view;
        private final TextView value, sub;

        public Tile(String label) {
            view = vertical();
            view.setBackground(rounded(surface, 16, border));
            view.setPadding(dp(16), dp(14), dp(16), dp(14));
            view.addView(text(label, 13, muted));
            value = medium(Fmt.DASH, 22, ink);
            value.setPadding(0, dp(8), 0, 0);
            view.addView(value);
            sub = text("", 12, ink2);
            sub.setPadding(0, dp(4), 0, 0);
            sub.setVisibility(View.GONE);
            view.addView(sub);
        }

        public void set(CharSequence v, CharSequence caption) {
            value.setText(v);
            if (caption == null || caption.length() == 0) {
                sub.setVisibility(View.GONE);
            } else {
                sub.setText(caption);
                sub.setVisibility(View.VISIBLE);
            }
        }
    }
}
