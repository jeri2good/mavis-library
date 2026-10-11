# Native Android fix receipt

Branch: native. One commit per fix:
- aa27937 — restrict owner-code handoff to the app origin.
- 37e27df — resolve empty catalog ID lists locally.

## Files
- native-android/app/src/main/java/com/mavislibrary/app/web/WebHost.kt — pure exact-origin helper; guard current WebView URL inside UI-thread injection and main-frame onPageFinished. Existing storage design unchanged.
- native-android/app/src/test/java/com/mavislibrary/app/web/AppOriginTest.kt — six JVM tests covering origin, paths/hash, null, foreign site, lookalike/userinfo, scheme/port/malformed URLs.
- native-android/app/src/main/java/com/mavislibrary/app/data/api/MavisApi.kt — reject empty ID serialization.
- native-android/app/src/main/java/com/mavislibrary/app/data/repo/Repositories.kt — empty IDs return an empty local result before any fetch.
- native-android/app/src/main/java/com/mavislibrary/app/data/repo/CachedFetcher.kt — LOCAL result origin.
- native-android/app/src/test/java/com/mavislibrary/app/data/api/ApiParsingTest.kt — adapt empty-ID expectation and add no-cache/no-network regression test.
- PROGRESS.md — line per fix and integrated validation results.
- RECEIPT-fix.md — this receipt, written last and left uncommitted.

## Evidence
Requested command ran in the background with JDK 21.0.12.1+1 and ANDROID_HOME=$HOME/Android/Sdk:
`cd native-android && ./gradlew testDebugUnitTest assembleDebug --no-daemon -q`

Exit code: 0. Log: /tmp/native-fix-gradle-approved.log. Exit record: /tmp/native-fix-gradle-approved.exit.
Initial sandbox attempt could not write the Gradle cache; approved retry completed.

Counted from native-android/app/build/test-results/**/*.xml after completion:
19 suites, 75 tests, 0 failures, 0 errors, 0 skipped.
ApiParsingTest: 15 tests. AppOriginTest: 6 tests.
Debug APK: native-android/app/build/outputs/apk/debug/app-debug.apk (40,144,349 bytes).
`git diff HEAD~2 HEAD --check` passed. Working tree was clean before this receipt.

## Not verified
No emulator or physical device was used. Live WebView redirects, external navigation, iframe behavior, and owner-code handoff were not exercised on a device. Existing web accessCode persistence was intentionally preserved. APK was built before the final PROGRESS-only commit amendment, so its embedded Git SHA identifies the pre-amend commit 21e7b0c; production and test sources are unchanged by that amendment.
