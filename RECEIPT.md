# Sensors, widgets, and reminders receipt

Date: 2026-10-11 (EDT)  
Branch: `devin/sensors-widgets`  
No push, deployment, emulator, or device run.

## Built

- **Room light:** “Match the room light” is off by default. While the app is foregrounded and a `TYPE_LIGHT` sensor exists, a classifier uses 8/20 lux dark hysteresis, 100/120 lux day hysteresis, and a five-second transition dwell. Daylight keeps the user's chosen Look; evening/dark chooses Midnight. Kids always keeps Kids. With no sensor or with the setting off, the existing chosen/time-based Look applies. The sensor never controls window brightness.
- **Widgets:** Two small Paper-style `RemoteViews` widgets. Continue reading displays the last adult book opened via native Search/Shelf, then opens the Shelf tab. Verse of the day rotates through 60 bundled KJV verse excerpts by day-of-year and opens its Bible passage explicitly in KJV. Both read local Room/DataStore state; WorkManager and widget updates refresh daily, with an immediate refresh after an adult book opens. Kids mode shows only neutral “Open Mavis” content. Preview layouts are provided for API 31+ and a launcher-image fallback for older launchers.
- **Reading reminder:** Off by default, set to 20:00 until changed in Settings. Enabling on Android 13+ asks for the existing notification permission only at that action; denied permission leaves it off. A persisted, inexact, one-time WorkManager job schedules the next local calendar day after firing, with DST-aware next-fire computation; no exact alarm or location permission. The local notification channel is “Reading reminder”, and delivery is skipped while Kids mode is enabled or notification permission is missing. WorkManager owns reboot persistence; no new boot receiver was added.

## Files

19 implementation/test files changed, plus this root receipt:

- `native-android/app/src/main/AndroidManifest.xml`
- `native-android/app/src/main/java/com/mavislibrary/app/MainActivity.kt`, `MavisApp.kt`
- `native-android/app/src/main/java/com/mavislibrary/app/data/prefs/SettingsStore.kt`
- `native-android/app/src/main/java/com/mavislibrary/app/ui/MavisRoot.kt`, `ui/search/SearchScreen.kt`, `ui/settings/SettingsScreen.kt`, `ui/shelf/ShelfScreen.kt`, `ui/theme/LightObserver.kt`
- `native-android/app/src/main/java/com/mavislibrary/app/work/LibraryWidgets.kt`, `work/ReadingReminder.kt`
- `native-android/app/src/main/res/drawable/widget_paper_background.xml`, `layout/widget_paper.xml`, `values/strings.xml`, `xml/widget_continue.xml`, `xml/widget_verse.xml`
- `native-android/app/src/test/java/com/mavislibrary/app/ui/theme/LightClassifierTest.kt`, `work/LibraryWidgetsTest.kt`, `work/ReadingReminderTest.kt`

No changes to `ui/kids/*`, `data/kids/*`, `web/WebHost.kt`, `netlify/`, or `src/`. The pre-existing untracked `BRIEF.md` was left untouched.

## Verification actually run

- JDK 21 and `$HOME/Android/Sdk`, background Gradle with a log: `./gradlew testDebugUnitTest assembleDebug --no-daemon -q` — exit **0** after the final commit of implementation files.
- Read all 28 `native-android/app/build/test-results/testDebugUnitTest/TEST-*.xml` suite headers: **114 tests, 0 failures, 0 errors, 0 skipped**. Nine new tests: light classifier/fallback (3), widget picker/selection (2), reminder time/skip rules (4).
- `./gradlew lintDebug --no-daemon -q` — exit **0**. `app/build/reports/lint-results-debug.txt`: **0 errors, 10 warnings**; six are API 31+ widget sizing/preview attributes ignored on the min-SDK-26 fallback, and four are pre-existing warnings elsewhere.
- `git diff --check HEAD~4 HEAD` — passed before this receipt.
- APK produced: `native-android/app/build/outputs/apk/debug/app-debug.apk` (reported by `ls -lh` as **39M**).
- **New permissions declared: none.** `POST_NOTIFICATIONS` already existed for first-use Listen playback; that existing prompt remains. WorkManager's existing merged reboot support is reused; there is no new `RECEIVE_BOOT_COMPLETED` declaration here.

## Not verified / limits

- No physical light sensor, lifecycle registration, transition feel, widget launcher rendering/previews/taps, permission dialogue, real notification delivery, reboot restoration, or WorkManager timing was tested on a device. No emulator was used as requested. DST/midnight behaviour was tested with a fake clock, not with OS scheduling.
- WorkManager is inexact; reminders may arrive after the selected time. Time-zone changes while work is already enqueued were not exercised.
- There is no adult daily reading log to query. **Reading earlier today does not suppress a reminder.** The existing reading log is Kids-only and is not used for adult reminder decisions.
- Continue reading tracks native adult book-page opens, not openings performed exclusively inside the embedded web reader, and the widget opens the Shelf rather than resuming a reader position.
- Bundled verse text and its on-device display were not compared with a live Bible source or a real launcher. Accessibility/layout testing remains for a device pass.

Suggested device checks: install the APK, toggle room-light matching under bright/dim conditions for at least five seconds, add both widgets and check their taps, enter/leave Kids mode while watching their neutral state, then enable a reminder for an upcoming time and check permission, notification, Kids-mode skip, and reboot persistence. This is the native/default lane; without a sensor the selected Look is the fallback. No experimental voice or service lane changed.

Implementation commits: `31de46b`, `9617993`, `42c4533`, `bd3850d`. This receipt is committed last.
