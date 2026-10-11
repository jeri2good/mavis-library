# Offline audio retry receipt

Commit: `a998293` on `native` (one commit).

Files committed:
- `native-android/app/src/main/java/com/mavislibrary/app/audio/AudioFileStore.kt`
- `native-android/app/src/test/java/com/mavislibrary/app/audio/AudioFileStoreTest.kt`

Downloads retry each chapter up to four attempts for HTTP 5xx, HTTP 429, and IOException, using injectable 1s/3s/8s delays. Other HTTP failures stop immediately. Stale `.part` files are deleted before attempts, completed chapters are retained, and errors identify the chapter and explain how to continue.

No build files or dependencies changed; MockWebServer was already available.

Validation:
- Background `./gradlew testDebugUnitTest assembleDebug --no-daemon -q`, using the requested JDK 21 and Android SDK: exit 0.
- Log: `/tmp/native-retry-gradle-approved.log`; exit: `/tmp/native-retry-gradle-approved.exit`.
- Counted from `native-android/app/build/test-results/**/*.xml`: 20 suites, 82 tests, 0 failures, 0 errors, 0 skipped.
- `AudioFileStoreTest`: 7 passing tests covering 500 then success, four 500s with preservation/resume, immediate 404, IOException then success and stale partial cleanup, second-call skipping, 429 retry, and exhausted IOException retries.
- `git diff --check` passed before commit.
- Debug APK: `native-android/app/build/outputs/apk/debug/app-debug.apk` (40,144,349 bytes).
- Initial sandbox build could not write the Gradle cache; approved background rerun passed.

Not verified: emulator/device behavior, live archive.org recovery, on-device offline playback. No emulator was used.

Existing `RECEIPT-article.md` was left untouched. This receipt was written last and is uncommitted.
