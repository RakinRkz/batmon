package com.opendroid.batmon;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.util.Locale;

/**
 * Foreground service behind the live notification: samples the battery, logs history, tracks
 * sessions and raises alerts. Samples often while the screen is on, rarely while it is off, and
 * (optionally) holds a wakelock while charging so charge sessions are measured without gaps.
 *
 * Sampling, database writes and notifications run on one worker thread; the main thread only
 * starts and stops the service.
 */
public final class MonitorService extends Service {
    private static final String TAG = "BatMon";
    public static final String ACTION_REFRESH = "com.opendroid.batmon.REFRESH";
    private static final long SCREEN_OFF_INTERVAL_MS = 60_000;
    private static final long CHARGING_SCREEN_OFF_INTERVAL_MS = 15_000;
    /** History charts bucket at 45 s or more, so finer logging only grows the database. */
    private static final long MIN_LOG_INTERVAL_MS = 15_000;
    private static final long MIN_BROADCAST_TICK_MS = 20_000;
    private static final long PRUNE_EVERY_MS = 6 * 3_600_000L;

    private HandlerThread thread;
    private Handler handler;
    private final Runnable tick = this::tick;
    private BatteryReader reader;
    private Prefs prefs;
    private HistoryDb db;
    private SessionTracker tracker;
    private PowerManager pm;
    private NotificationManager nm;
    private PowerManager.WakeLock wakeLock;
    private volatile BatterySnapshot lastSnapshot;
    private volatile boolean stopped;
    // Worker-thread state. Times are elapsedRealtime, which keeps counting in deep sleep and
    // ignores wall-clock changes.
    private long lastLogAt = -1, lastPruneAt = -1, lastTickAt = -1;
    private volatile String lastNotificationKey;
    private volatile boolean lastNotifiedPlugged;

    public static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, MonitorService.class));
        } catch (RuntimeException e) {
            Log.w(TAG, "could not start monitor", e);
        }
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, MonitorService.class));
    }

    /** Re-reads settings and refreshes the notification, if monitoring is enabled. */
    public static void refresh(Context c) {
        if (!Prefs.get(c).bool(Prefs.MONITOR)) return;
        try {
            c.startForegroundService(new Intent(c, MonitorService.class).setAction(ACTION_REFRESH));
        } catch (RuntimeException e) {
            Log.w(TAG, "could not refresh monitor", e);
        }
    }

    /** Delivered on the worker thread (see registerReceiver below). */
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (Intent.ACTION_POWER_CONNECTED.equals(a) || Intent.ACTION_POWER_DISCONNECTED.equals(a)) {
                schedule(1500); // let the battery broadcast catch up with the plug state
            } else if (Intent.ACTION_SCREEN_ON.equals(a) || Intent.ACTION_SCREEN_OFF.equals(a)) {
                schedule(0);
            } else if (Intent.ACTION_BATTERY_CHANGED.equals(a)
                    && (lastTickAt < 0 || SystemClock.elapsedRealtime() - lastTickAt > MIN_BROADCAST_TICK_MS)) {
                schedule(0); // level changes wake the phone; record them even with the screen off
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        reader = new BatteryReader(this);
        prefs = Prefs.get(this);
        db = HistoryDb.get(this);
        tracker = new SessionTracker(db, () -> BatteryReader.designCapacityMah(this));
        pm = getSystemService(PowerManager.class);
        nm = getSystemService(NotificationManager.class);
        Alerts.ensureChannels(this);
        lastSnapshot = reader.read();
        goForeground(lastSnapshot);

        thread = new HandlerThread("BatMonMonitor");
        thread.start();
        handler = new Handler(thread.getLooper());
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        f.addAction(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(receiver, f, null, handler); // system broadcasts only, so no export flag is needed
        schedule(0);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Every start must be followed by startForeground, including refreshes and sticky restarts.
        goForeground(lastSnapshot);
        if (intent != null && ACTION_REFRESH.equals(intent.getAction())) {
            lastNotificationKey = null; // settings may have changed what the notification shows
            schedule(0);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopped = true;
        try {
            unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
        }
        handler.removeCallbacksAndMessages(null);
        // Runs after any tick already in progress, so nothing re-posts the notification afterwards.
        handler.post(() -> {
            try {
                tracker.closeOpen();
            } catch (RuntimeException e) {
                Log.e(TAG, "closing session failed", e);
            }
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            nm.cancel(Alerts.ID_MONITOR);
            thread.quitSafely();
        });
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void schedule(long delayMs) {
        if (stopped) return;
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, delayMs);
    }

    private void tick() {
        if (stopped) return;
        BatterySnapshot s = reader.read();
        lastTickAt = s.elapsed;
        boolean screenOn = pm.isInteractive();
        try {
            tracker.onSample(s, screenOn);
            if (lastLogAt < 0 || s.elapsed - lastLogAt >= MIN_LOG_INTERVAL_MS) {
                db.insertSample(s, screenOn);
                lastLogAt = s.elapsed;
            }
            if (lastPruneAt < 0 || s.elapsed - lastPruneAt > PRUNE_EVERY_MS) {
                long day = 86_400_000L;
                db.prune(s.time - prefs.integer(Prefs.HISTORY_DAYS) * day, s.time - 365 * day);
                lastPruneAt = s.elapsed;
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "history write failed", e);
        }
        Alerts.check(this, s);
        if (screenOn || s.isPlugged() != lastNotifiedPlugged) postNotification(s);
        boolean measuring = wantWakeLock(s);
        updateWakeLock(measuring);
        lastSnapshot = s;

        long next;
        if (screenOn) next = prefs.integer(Prefs.INTERVAL) * 1000L;
        else if (measuring) next = CHARGING_SCREEN_OFF_INTERVAL_MS;
        else next = SCREEN_OFF_INTERVAL_MS;
        schedule(next);
    }

    /**
     * Only while the battery is actually charging: not when it is full, held at an OS charge
     * limit, or paused for heat (all of which report a status other than CHARGING).
     */
    private boolean wantWakeLock(BatterySnapshot s) {
        return s.isPlugged() && s.isCharging() && s.level < 100 && prefs.bool(Prefs.KEEP_AWAKE_CHARGING);
    }

    private void updateWakeLock(boolean want) {
        if (want) {
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BatMon:charging");
                wakeLock.setReferenceCounted(false);
            }
            wakeLock.acquire(5 * 60_000L); // renewed every tick; lapses if the service stalls
        } else if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private void goForeground(BatterySnapshot s) {
        Content content = new Content(s);
        Notification n = build(content);
        lastNotificationKey = content.key;
        lastNotifiedPlugged = s.isPlugged();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Alerts.ID_MONITOR, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(Alerts.ID_MONITOR, n);
            }
        } catch (RuntimeException e) {
            // Android 12+ can refuse when the system restarts the service in the background;
            // the next app launch or boot starts it again.
            Log.w(TAG, "startForeground refused", e);
            stopSelf();
        }
    }

    /** Re-posts only when what the notification shows has changed. */
    private void postNotification(BatterySnapshot s) {
        Content content = new Content(s);
        lastNotifiedPlugged = s.isPlugged();
        if (content.key.equals(lastNotificationKey)) return;
        lastNotificationKey = content.key;
        nm.notify(Alerts.ID_MONITOR, build(content));
    }

    /** The notification's text and status-bar icon, computed once per sample. */
    private final class Content {
        final String title, text, iconTop, iconBottom, key;

        Content(BatterySnapshot s) {
            boolean f = prefs.bool(Prefs.FAHRENHEIT);
            String state;
            if (!s.isPlugged()) state = "Discharging";
            else if (s.isFull()) state = "Full";
            else state = "Charging";
            title = state + "  " + Fmt.signedMa(s.currentMa) + " mA  ·  "
                    + Fmt.watts(s.powerW(s.currentMa)) + " W";
            StringBuilder t = new StringBuilder();
            t.append(s.level).append(" %  ·  ").append(Fmt.temp(s.tempC, f))
                    .append("  ·  ").append(Fmt.volts(s.voltageMv));
            if (s.isPlugged()) t.append("  ·  ").append(Fmt.plug(s.plugged));
            text = t.toString();

            String top = null, bottom = null;
            switch (prefs.string(Prefs.NOTIF_ICON)) {
                case "level":
                    if (s.level >= 0) {
                        top = String.valueOf(s.level);
                        bottom = "%";
                    }
                    break;
                case "temp":
                    if (!Float.isNaN(s.tempC)) {
                        top = String.valueOf(Math.round(Fmt.tempValue(s.tempC, f)));
                        bottom = Fmt.tempUnit(f);
                    }
                    break;
                default:
                    if (s.hasCurrent()) {
                        int abs = Math.abs(s.currentMa);
                        String sign = s.currentMa < 0 ? "-" : "";
                        if (abs >= 1000) {
                            top = sign + String.format(Locale.US, "%.1f", abs / 1000f);
                            bottom = "A";
                        } else {
                            top = sign + abs;
                            bottom = "mA";
                        }
                    }
            }
            iconTop = top;
            iconBottom = bottom;
            key = title + '\n' + text + '\n' + top + '\n' + bottom;
        }
    }

    private Notification build(Content c) {
        Icon icon = c.iconTop != null ? Icon.createWithBitmap(textIcon(c.iconTop, c.iconBottom))
                : Icon.createWithResource(this, R.drawable.ic_stat_bolt);
        Notification.Builder b = new Notification.Builder(this, Alerts.CH_MONITOR)
                .setSmallIcon(icon)
                .setContentTitle(c.title)
                .setContentText(c.text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(Alerts.openApp(this));
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    /** Status-bar icon drawn as text: a number over its unit. */
    private static Bitmap textIcon(String top, String bottom) {
        int size = 96;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        p.setTextSize(62);
        float w = p.measureText(top);
        if (w > size) p.setTextSize(62f * size / w);
        c.drawText(top, size / 2f, 56, p);
        p.setTextSize(36);
        w = p.measureText(bottom);
        if (w > size) p.setTextSize(36f * size / w);
        c.drawText(bottom, size / 2f, 92, p);
        return bmp;
    }
}
