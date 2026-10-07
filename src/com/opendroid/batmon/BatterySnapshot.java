package com.opendroid.batmon;

import android.os.BatteryManager;

/** One reading of every battery value, already normalized (mA, mV, °C, µAh). */
public final class BatterySnapshot {
    public static final int NONE = Integer.MIN_VALUE;

    public long time;
    /** Net current into the battery in mA: positive while charging, negative while discharging. */
    public int currentMa = NONE;
    public int currentAvgMa = NONE;
    /** BATTERY_PROPERTY_CURRENT_NOW exactly as the device reports it. */
    public int rawCurrent = NONE;
    public int voltageMv;
    public float tempC = Float.NaN;
    public int level = -1;
    public int status = BatteryManager.BATTERY_STATUS_UNKNOWN;
    public int plugged;
    public int health = BatteryManager.BATTERY_HEALTH_UNKNOWN;
    public String technology;
    public boolean present = true;
    public long chargeCounterUah = -1;
    public int cycleCount = -1;
    public long chargeTimeRemainingMs = -1;
    /** Fuel-gauge learned full capacity and design capacity, in mAh (Android 16+ broadcast extras). */
    public int maxCapacityMah = -1;
    public int designCapacityMah = -1;

    public boolean hasCurrent() { return currentMa != NONE; }
    public boolean isPlugged() { return plugged != 0; }
    public boolean isFull() { return status == BatteryManager.BATTERY_STATUS_FULL; }

    /** Power flowing into (+) or out of (−) the battery, in watts. */
    public float powerW(int ma) {
        if (ma == NONE || voltageMv <= 0) return Float.NaN;
        return ma * (float) voltageMv / 1_000_000f;
    }
}
