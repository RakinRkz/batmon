package com.opendroid.batmon;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * Notification channels and the charge-limit / low-battery / temperature alerts. Each alert fires
 * once per crossing of its threshold and re-arms only after the battery has moved back past it by
 * a margin, so cable flaps, restarts and sessions don't repeat or swallow alerts.
 */
public final class Alerts {
    /** Default importance (with sound and vibration off) so the status-bar icon is shown. */
    public static final String CH_MONITOR = "live";
    public static final String CH_ALERTS = "alerts";
    public static final int ID_MONITOR = 1;
    private static final int ID_CHARGE = 10, ID_LOW = 11, ID_TEMP = 12;
    /** Level alerts re-arm once the level is this many points back on the other side. */
    private static final int REARM_POINTS = 3;
    /** The temperature alert re-arms once the battery has cooled this far below the limit. */
    private static final float TEMP_HYSTERESIS_C = 2f;

    private Alerts() {}

    public static void ensureChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel monitor = new NotificationChannel(CH_MONITOR, "Live monitor",
                NotificationManager.IMPORTANCE_DEFAULT);
        monitor.setDescription("Persistent notification with live current, power and temperature");
        monitor.setSound(null, null);
        monitor.enableVibration(false);
        monitor.setShowBadge(false);
        NotificationChannel alerts = new NotificationChannel(CH_ALERTS, "Battery alerts",
                NotificationManager.IMPORTANCE_HIGH);
        alerts.setDescription("Charge limit, low battery and high temperature");
        alerts.enableVibration(true);
        nm.createNotificationChannel(monitor);
        nm.createNotificationChannel(alerts);
    }

    public static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public static void check(Context c, BatterySnapshot s) {
        if (s.level < 0) return;
        Prefs p = Prefs.get(c);
        boolean f = p.bool(Prefs.FAHRENHEIT);

        // Charge limit: armed below the limit, fires when charging brings the level up to it, so
        // plugging in an already-charged phone stays quiet.
        int limit = p.integer(Prefs.ALERT_CHARGE_LEVEL);
        if (s.level <= limit - REARM_POINTS) p.set(Prefs.CHARGE_ALERT_ARMED, true);
        if (s.isPlugged() && s.level >= limit && p.bool(Prefs.CHARGE_ALERT_ARMED)) {
            p.set(Prefs.CHARGE_ALERT_ARMED, false);
            if (p.bool(Prefs.ALERT_CHARGE)) {
                notify(c, ID_CHARGE, "Battery at " + s.level + " %",
                        limit >= 100 ? "Fully charged. You can unplug the charger."
                                : "Reached your " + limit + " % limit. Unplug to reduce battery wear.");
            }
        }
        if (!s.isPlugged() || !p.bool(Prefs.ALERT_CHARGE)) cancel(c, ID_CHARGE);

        // Low battery: armed above the level, fires once on the way down.
        int low = p.integer(Prefs.ALERT_LOW_LEVEL);
        if (s.level >= low + REARM_POINTS) p.set(Prefs.LOW_ALERT_ARMED, true);
        if (!s.isPlugged() && s.level <= low && p.bool(Prefs.LOW_ALERT_ARMED)) {
            p.set(Prefs.LOW_ALERT_ARMED, false);
            if (p.bool(Prefs.ALERT_LOW)) {
                notify(c, ID_LOW, "Battery low: " + s.level + " %", "Below your " + low + " % alert level.");
            }
        }
        if (s.isPlugged() || !p.bool(Prefs.ALERT_LOW)) cancel(c, ID_LOW);

        // Temperature: one alert per overheating episode. Turning the alert off ends the episode.
        boolean active = p.bool(Prefs.TEMP_ALERT_ACTIVE);
        if (!p.bool(Prefs.ALERT_TEMP)) {
            if (active) {
                p.set(Prefs.TEMP_ALERT_ACTIVE, false);
                cancel(c, ID_TEMP);
            }
        } else if (!Float.isNaN(s.tempC)) {
            float max = p.integer(Prefs.ALERT_TEMP_C);
            if (!active && s.tempC >= max) {
                p.set(Prefs.TEMP_ALERT_ACTIVE, true);
                notify(c, ID_TEMP, "Battery hot: " + Fmt.temp(s.tempC, f),
                        "Above your " + Fmt.temp(max, f) + " limit. "
                                + (s.isPlugged() ? "Consider unplugging and letting it cool." : "Let the phone cool down."));
            } else if (active && s.tempC <= max - TEMP_HYSTERESIS_C) {
                p.set(Prefs.TEMP_ALERT_ACTIVE, false);
                cancel(c, ID_TEMP);
            }
        }
    }

    private static void notify(Context c, int id, String title, String text) {
        Notification n = new Notification.Builder(c, CH_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_bolt)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setContentIntent(openApp(c))
                .build();
        c.getSystemService(NotificationManager.class).notify(id, n);
    }

    private static void cancel(Context c, int id) {
        c.getSystemService(NotificationManager.class).cancel(id);
    }
}
