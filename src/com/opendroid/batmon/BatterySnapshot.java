package com.opendroid.batmon;

import android.os.BatteryManager;

/** One reading of every battery value, already normalized (mA, mV, °C, µAh). */
public final class BatterySnapshot {
    public static final int NONE = Integer.MIN_VALUE;

    /** Wall-clock time, for storing and display. */
    public long time;
    /** SystemClock.elapsedRealtime(): monotonic and counts deep sleep, for intervals between samples. */
    public long elapsed;
    /** Net current into the battery in mA: positive while charging, negative while discharging. */
    public int currentMa = NONE;
    /** BATTERY_PROPERTY_CURRENT_NOW exactly as the device reports it. */
    public int rawCurrent = NONE;
    /** How the raw current was converted (bit 0: µA, bit 1: inverted); -1 without a reading. */
    public int convention = -1;
    public int voltageMv;
    public float tempC = Float.NaN;
    public int level = -1;
    public int status = BatteryManager.BATTERY_STATUS_UNKNOWN;
    public int plugged;
    public int health = BatteryManager.BATTERY_HEALTH_UNKNOWN;
    public String technology;
    public long chargeCounterUah = -1;
    public int cycleCount = -1;
    public long chargeTimeRemainingMs = -1;
    /** Fuel-gauge learned full capacity in mAh (Android 16+ broadcast extra), or -1. */
    public int maxCapacityMah = -1;

    public boolean hasCurrent() { return currentMa != NONE; }
    public boolean isPlugged() { return plugged != 0; }
    public boolean isCharging() { return status == BatteryManager.BATTERY_STATUS_CHARGING; }
    public boolean isFull() { return status == BatteryManager.BATTERY_STATUS_FULL; }

    /** Power flowing into (+) or out of (−) the battery, in watts. */
    public float powerW(int ma) {
        if (ma == NONE || voltageMv <= 0) return Float.NaN;
        return ma * (float) voltageMv / 1_000_000f;
    }
}
