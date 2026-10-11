# Native Kids mode v1 — receipt

## Delivered

- Native Kids mode persists its on/off state, child name, 10/15/20/30-minute goal, salted-hash 4-digit PIN, five-miss/30-second lockout, local-date reading log, seven-day history, and streak. The accumulated reading/listening interval splits across local midnight.
- Adult Settings has name, goal chips, twice-entered PIN, and Start Kids mode. The child shell replaces adult navigation while on, absorbs Back, and has a PIN-protected Grown-ups corner. Startup drive mode is suppressed while Kids mode is on.
- Kids home and My stars show the goal ring, streak, and seven-day indicators. Read and Listen use the 21 curated Gutenberg IDs, the existing cached catalog repository, LibriVox lookup with Gutenberg ID, and PlaybackController. Bible stories contains the 12 web story passages. The restricted bundled-web handoff handles book details/download/reader and those Bible routes. The Kids look and tablet grid/large controls are in place.
- The existing reader and audio PLAYING state feed the Kids log, with periodic writes and flushes on state changes, pause, and exit. There is no separate child-facing assistant, owner-code UI, or drive screen.

## Files

- `native-android/app/src/main/java/com/mavislibrary/app/data/kids/KidsPrefs.kt`
- `native-android/app/src/main/java/com/mavislibrary/app/ui/kids/{KidsContent,KidsRoot,KidsSetup,KidsWebReader}.kt`
- `native-android/app/src/main/java/com/mavislibrary/app/ui/{MavisRoot.kt,settings/SettingsScreen.kt,theme/Look.kt}`
- `native-android/app/src/main/java/com/mavislibrary/app/MainActivity.kt`
- `native-android/app/src/test/java/com/mavislibrary/app/data/kids/KidsPrefsTest.kt`
- `native-android/app/src/test/java/com/mavislibrary/app/ui/{KidsContentTest,NavigationShellTest}.kt`
- `PROGRESS.md`, `RECEIPT-kids.md`

## Verification

- With JDK 21 and `ANDROID_HOME=$HOME/Android/Sdk`, background `./gradlew testDebugUnitTest assembleDebug --no-daemon -q` exited **0**. APK: `native-android/app/build/outputs/apk/debug/app-debug.apk`.
- Read from `native-android/app/build/test-results/testDebugUnitTest/TEST-*.xml`: **22 suites, 97 tests, 0 failures, 0 errors, 0 skipped**. `KidsPrefsTest`: 12; `KidsContentTest`: 2; `NavigationShellTest`: 3 (including the Robolectric child-shell/Back test). `git diff --check` passed before the receipt.
- Commits continuing Codex's `f546e04` and `2ddfeb3`: `de1e99b` (compile/PIN recovery), `0537fc0` (startup isolation, UI/time fixes, route and shell tests). No push.

## Not verified

- No emulator or physical tablet was used. Actual WebView document-start route enforcement, book download and EPUB reading, Android audio service playback/notification controls, real LibriVox response, background listening time, and responsive visual sizing need an on-device pass. Robolectric and pure-route tests do not prove those runtime boundaries.
- Offline cached catalog behavior follows the existing Room/CachedFetcher path but was not tested end-to-end from a child device. A book's downloadable file must also be downloaded into the bundled reader before it reads offline; seeing a catalog cover alone does not download the book.

## Device check

1. In Settings > Kids mode, set a name, select each goal chip, enter the same four digits twice, then Start Kids mode. Relaunch and confirm it opens on Kids home without adult tabs; Back stays inside Kids mode.
2. On a tablet, check the 2-by-2 home tiles, 3–5-column cover grid, large controls, goal ring, and seven days. Open a Read book, download it, read, and confirm minutes rise; retry that downloaded book offline.
3. Try Listen with and without a LibriVox match, pause/resume playback, and check the log only grows during PLAYING. Open a Bible story and try navigating outside its selected route.
4. At the lock icon, make five wrong PIN attempts, confirm a 30-second wait, then use the correct PIN to return to the adult shell. Check drive mode does not launch during Kids mode.
