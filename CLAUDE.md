# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

BatMon (`com.opendroid.batmon`) is an Ampere-style Android battery monitor. It shows live charge and
discharge current, sessions and history, alerts, and battery wear/health. It is plain Java on framework
APIs only. There is no Gradle, AndroidX, Kotlin or library code.

## Build, install, verify

```sh
scripts/setup-toolchain.sh   # one-time: JDK 17 + SDK platform 35 + build-tools 35.0.0 (Linux x86_64)
./build.sh                   # → build/BatMon.apk, debug key (wipes build/ first)
./build.sh install           # build + adb install -r
./build.sh run               # build + install + launch MainActivity
KEYSTORE=… KS_PASS=… ./build.sh release   # → build/BatMon-<version>.apk + .sha256, prints cert digest
```

- `build.sh` sets `MIN_SDK=26`, `TARGET_SDK=35` and the version, and copies `LICENSE` and
  `THIRD_PARTY_NOTICES.md` into `assets/licenses/` (git-ignored) so they ship in the APK. Then it calls
  `scripts/build-apk.sh`.
  The version comes from `VERSION_CODE` / `VERSION_NAME`, with defaults in `build.sh`. The script runs
  aapt2 → javac `--release 8` → d8 → zipalign → apksigner. It uses the toolchain in
  `~/.local/share/android-min-toolchain`.
- **Releases:**
  - Bump the defaults in `build.sh`. `VERSION_CODE` must go up every release.
  - Add a `CHANGELOG.md` entry.
  - Sign with the maintainer's release key. It is kept outside the repo, and every release must use
    the same key, whose certificate digest is in `README.md`.
  - Tag `vX.Y.Z` and attach the APK and its `.sha256` to the GitHub release.
  - A debug-signed install can't be upgraded in place to a release-signed one. Switching means
    uninstalling, which wipes history.
- There are no tests and no linter. Verify on a device:
  - crashes: `adb logcat -d -t 500 | grep -E "FATAL|AndroidRuntime: "`
  - service state: `adb shell dumpsys activity services com.opendroid.batmon`
  - notifications: `adb shell dumpsys notification --noredact | grep -A40 pkg=com.opendroid.batmon`
  - screenshot: `adb exec-out screencap -p > shot.png`
- Settings → "Raw battery values" in the app lists every BatteryManager property and battery-broadcast
  extra the phone reports. Use it first when readings look wrong.
- The test phone is usually someone's daily phone. Don't `logcat -c`. Don't uninstall without asking:
  that wipes the history DB, and the debug signature differs per machine.
- `docs/screenshots/` holds the README images. Take them as full-color PNGs, because palette
  quantization turns the thin green chart line black. Make sure no other app's floating overlay is in
  the shot.

## Constraints that shape the code

- **Java 8 language level, framework APIs only.** No AndroidX, Material Components or Fragments. All UI
  is built in code from `ui/Ui.java` factories, with no layout XML. Guard any API above 26 with
  `Build.VERSION.SDK_INT`.
- **Sources contain literal UTF-8 characters.** These are NBSP ` `, `−`, `·` and `—` inside
  strings. The build passes `-encoding UTF-8`. Python/sed edits that match on ` ` escapes will
  miss them.
- **Theme tokens** live in `res/values{,-night}/colors.xml`, are resolved once into `Ui`, and follow
  the system light/dark mode. Status colors (`good`/`warning`/`serious`/`critical`) are the same in
  both themes. Charging is `good` and discharging is `serious`. Text stays in ink colors; series colors
  are only for marks.
- **Edge to edge.** Target 35 forces it on Android 15+. `MainActivity` applies the system-bar insets as
  padding on the content frame and on the bottom nav.
- **Notification channels can't be raised in importance once created.** The live channel is `"live"` at
  IMPORTANCE_DEFAULT with no sound or vibration, because LOW ("silent") hides the status-bar icon. To
  change a channel's importance, create a new ID and delete the old one in `Alerts.ensureChannels`.

## Architecture

Data flows one way: `BatteryReader` → `BatterySnapshot` → consumers. There are two independent consumers.

1. **`MonitorService`** is a foreground service of type `specialUse`. All its work (ticks, broadcasts,
   database writes, notifications) runs on one `HandlerThread` ("BatMonMonitor"); the main thread only
   starts and stops it. The tick runs every `INTERVAL` seconds while the screen is on, every 15 s while
   the wakelock is held, and every 60 s otherwise. Screen and plug broadcasts and level changes also
   trigger a tick. Intervals and throttles use `elapsedRealtime` (`BatterySnapshot.elapsed`), never the
   wall clock. The wakelock is held only while status is CHARGING and the level is below 100. Each tick:
   - feeds `SessionTracker`, which splits samples into charge (plugged) and discharge sessions, integrates
     current as mAh, attributes each interval to the screen state at its start, and persists the open
     session every tick;
   - writes a sample to `HistoryDb` (SQLite, WAL), at most once per 15 s (session integration still uses every tick);
   - runs `Alerts.check`;
   - re-posts the notification and its text-bitmap status-bar icon, but only when their content
     changed.

   `BootReceiver` restarts the service after `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`. `onDestroy`
   closes the open session on the worker thread.
2. **The UI** is `MainActivity`, which hosts four `ui/*Page` objects behind a custom bottom nav, with
   `onShow` / `onHide` tied to resume and pause. `DashboardPage` samples on its own 1 s ticker into a
   process-wide `Live` holder (so it survives recreation), which a plug-state receiver resets; it does
   not talk to the service. The headline averages only readings from the last N seconds. `HistoryPage` and `HealthPage` query
   `HistoryDb` on the `HistoryDb.IO` executor. `ChartView` is the single custom time-series view.

The service and the UI share `Prefs` (typed SharedPreferences; the defaults live in its `bool` / `integer`
/ `string` switch statements) and the `HistoryDb` singleton.

### Measurement normalization (`BatteryReader`)

- `CURRENT_NOW` units vary by device (µA on most, mA on some). Auto mode learns µA permanently once any
  reading has |raw| ≥ 15000.
- The sign convention varies too. Auto mode learns it from readings taken while unplugged and
  DISCHARGING, and needs 5 consistent votes before flipping. Internally, + always means current into the
  battery.
- Both learned values live in `Prefs` and can be pinned in Settings. Changing a setting doesn't reset
  them. Sign votes count only distinct readings taken at least 15 s after the reader last saw the phone
  plugged in.
- Before Android 9, `getIntProperty` returns 0 (not MIN_VALUE) for unsupported properties, so on
  API < 28 a 0 current counts as unsupported until a non-zero reading has been seen.
- Design capacity comes from, in order: the user override, the Android 16 broadcast extra
  `android.os.extra.DESIGN_CAPACITY` (µAh), then the hidden `PowerProfile.getBatteryCapacity()` via
  reflection.

### Health (`HealthPage`)

Full capacity comes from the first available of:

1. `android.os.extra.MAXIMUM_CAPACITY`, the gauge's learned value (Android 16+).
2. The weighted mean of `Session.estimatedCapacityMah()`. That is integrated mAh ÷ Δlevel, and only
   counts charge sessions with Δ ≥ 15 points and gaps ≤ 10 % of their duration.
3. The 7-day median of charge counter ÷ level, from samples at ≥ 30 %, bounded to 30–130 % of design
   capacity.

### Sessions (`SessionTracker`)

- Current is integrated with the trapezoid rule only between samples ≤ 90 s apart. Longer spans (the
  CPU slept) use the charge counter's delta and count as unmeasured (`gapMs`).
- A session left open by an earlier run is resumed only after a pause of ≤ 30 min with the level still
  moving the session's way; otherwise it is closed and a new one starts.
- If `updateSession` finds the row gone (Clear history), a new session starts from the current sample.
- A session is tainted (a year added to `gapMs`) when the level moves more than twice what the measured
  current allows plus 3 points (faked states), or when the current's unit or sign convention changes
  mid-session.

### Alerts

The level alerts use armed flags in `Prefs`, not session ids. Charge limit arms at ≤ limit − 3 and fires
when plugged in at ≥ limit. Low battery arms at ≥ level + 3 and fires when unplugged at ≤ level. The
temperature alert is per overheating episode, and turning it off ends the episode.

## Testing with simulated battery states

`adb shell dumpsys battery set level N`, `dumpsys battery unplug` and `dumpsys battery reset` work for
exercising alerts and sessions. They have side effects:
- An unplug at a low level can auto-enable Battery Saver. Check with `adb shell settings get global low_power`.
- The fake samples go into history, and the fuel-gauge median can pick them up. Clear history afterwards
  (Settings → Clear history).
