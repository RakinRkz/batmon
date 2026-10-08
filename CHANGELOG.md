# Changelog

## 1.0.1 (2026-10-08)

Fixes from a full code review.

- History: the 7-day range drew no lines, and the newest point was often missing.
- Clear history no longer loses the session in progress.
- Sessions: a session left open by a stopped service or a dead phone no longer swallows a later plug
  cycle (and its alerts). Turning monitoring off closes the session.
- Alerts fire once per crossing of the threshold: no repeat low-battery alerts on a loose cable, no
  charge-limit alert when plugging in above the limit, and turning the temperature alert off and on
  works.
- The charging wakelock is held only while the battery is actually charging, not at an OS charge limit
  or when paused for heat.
- Screen-off drain is measured with the charge counter instead of two instantaneous readings, so it is
  no longer overestimated.
- Choosing a current unit or sign no longer wipes what was learned. A mid-session change, or a sign
  learned from a stale reading, no longer corrupts a session; fast charging no longer counts as a faked
  level jump.
- Android 8: an unsupported current sensor no longer reads as 0 mA, and the navigation buttons are
  visible in the light theme.
- The Now page keeps its readings across rotation and theme changes, and the headline average never
  includes readings from before a pause.
- The °C/°F label in History follows the setting. Session cards show net charge with a sign and the real
  reason a charge gives no capacity estimate.
- Time-axis labels stay on local midnight across daylight-saving changes. Settings screens a device
  lacks no longer crash the app. The charge counter's unit is detected reliably near empty.
- The monitor runs on a background thread and re-posts its notification only when it changes.
- The APK now carries the license texts, including the notice for the Material Icons it uses
  (`THIRD_PARTY_NOTICES.md`).

## 1.0.0 (2026-10-08)

First release.

- Live charge and discharge current with outlier-trimmed averaging, power, min/avg/max and a
  3-minute chart.
- Level, temperature, voltage, health, power source, technology, remaining charge, cycle count, full
  capacity and time to full or empty.
- Battery health from Android 16's learned gauge capacity, from measured charge sessions, or from
  charge counter ÷ level.
- History: level, current and temperature charts (6 h to 7 days) and charge and discharge sessions,
  including drain with the screen on and off.
- Live notification with a status-bar icon showing mA, %, or °C.
- Alerts for the charge limit, low battery and high temperature.
- Detects whether the phone reports current in µA or mA and which sign means charging. Both can be
  set manually.
- Light and dark themes. No internet permission.
