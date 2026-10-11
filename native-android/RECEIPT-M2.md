# Milestone 2 receipt

Date: 2026-10-11  
Branch: `native`  
Version: `0.6.0-m2` (`versionCode 2`)  
Live website touched: **No.** Nothing was pushed or deployed.

## Built

- Media3 1.11.1 `ExoPlayer` in a `MediaLibraryService`, with a stable browse root for future Android Auto support.
- Foreground media session/notification and lock-screen, headset, and Bluetooth media controls supplied by Media3.
- Speech audio attributes with automatic audio-focus handling and `setHandleAudioBecomingNoisy(true)`.
- LibriVox discovery through the existing `/api/catalog` and `/api/librivox` endpoints.
- Native Listen tab: cover, title/chapter, scrubber, back 15 seconds, forward 30 seconds, play/pause, 0.75–2× speed, chapter selection, and mini-player above native navigation.
- App-private MP3 downloads. A complete local chapter file is selected before its network URL, so native downloads can play without a connection.
- Room schema v2 `audio_resume` row and store for book/chapter/time/speed; v1→v2 is an additive auto-migration.
- Sleep timer presets (15/30/45/60 minutes and end of chapter), linear 10-second fade, cancel, and shake-to-extend by 10 minutes. The timer is shown in the player and exposed as a MediaSession notification action label.
- Drive mode setting (off by default), first-use `BLUETOOTH_CONNECT` request, car-audio classification, dim full-screen controls, and resume of the retained player session.
- Reader inputs: optional volume keys, headset next/previous, native haptic tick, and native-to-web next/previous calls through the active reader hook.
- Settings for sleep default, default speed, drive mode, volume/headset page inputs, and page-turn haptics. They use the existing Material theme tokens, so all four looks apply.
- First-use Android 13+ notification permission request when Listen is first played.

## Verification actually run

No emulator, AVD, or ADB command was started.

The final required command was run with JDK 21, the configured Android SDK, offline dependency resolution, and one Gradle job:

```text
export JAVA_HOME=$HOME/.local/jdks/jdk-21.0.12.1+1 PATH=$JAVA_HOME/bin:$PATH ANDROID_HOME=$HOME/Android/Sdk
cd native-android
./gradlew --offline --no-daemon testDebugUnitTest assembleDebug lintDebug

BUILD SUCCESSFUL in 1m 20s
61 actionable tasks: 25 executed, 36 up-to-date
```

- Parsed JUnit XML: **43 tests, 0 failures, 0 errors** (the existing 34 plus 9 M2 tests).
- Tests cover sleep state transitions, fade math, shake extension, drive-trigger policy, Room resume-store behavior, LibriVox endpoint parsing, and Robolectric service construction.
- Lint: **0 errors, 3 warnings**. Remaining warnings are the intentionally exported media-library service, one Compose modifier-style suggestion, and a URI KTX suggestion.
- `node --check src/views/reader.js`: passed.
- `git diff --check`: passed before the implementation commit.
- APK: `app/build/outputs/apk/debug/app-debug.apk`, 38 MB.
- `aapt2 dump badging`: package `com.mavislibrary.app`, version `0.6.0-m2`, minSdk 26, targetSdk 35, compiled with SDK 36.
- APK permissions observed: Internet/network state; foreground service/media playback; notifications; Bluetooth connect; vibrate; microphone (existing first-use hosted voice feature); wake lock and receive boot completed (WorkManager); AndroidX's non-exported dynamic-receiver permission.

The first full packaging attempt exposed duplicate `ListenableFuture` classes from full Guava plus its compatibility artifact. The redundant compatibility artifact is now excluded. A later full run found missing `VIBRATE` declaration and a restricted key-dispatch override; both were fixed before the successful final command.

## Not verified

- Audio playback, focus duck/pause during calls, noisy-route pause, notification/lock-screen controls, and screen-off continuity on an emulator or physical device.
- Bluetooth car connection behavior, drive-screen brightness, hardware volume/headset buttons, haptic feel, and accelerometer shake thresholds on hardware.
- Actual LibriVox and MP3 download network calls from the APK.
- Offline playback after force-stop/reboot.
- Android Auto. Only the library/session browse architecture is present.
- UI screenshots or accessibility pass for the new screens.

Hermes must perform emulator/device verification separately, as requested.

## Known problems and limits

- Drive connection monitoring is dynamically registered while `MainActivity` is alive. It does not launch a killed app in the background.
- Drive mode resumes the retained Media3 playlist. Room preserves position/speed, but the app does not yet persist enough chapter URL metadata to reconstruct the last playlist after process death.
- Native downloads are separate from the hosted reader's IndexedDB audio. Existing web-downloaded audio is not imported into native storage.
- The notification exposes sleep state through the custom sleep action label; exact OEM rendering is device-dependent and unverified.
- When native audio owns the MediaSession, some headset next/previous events may be consumed as audio chapter controls instead of reaching the foreground web reader. The reader path is implemented for key events delivered to the activity.
- The local machine had no cached Media3 artifacts. Official Google Maven Media3 1.11.1 AARs and the matching Guava Android jar were staged under the ignored `native-android/.gradle/` cache so the mandated offline build could run. A fresh offline checkout needs those dependencies pre-cached; a normal connected Gradle build resolves the declared coordinates.
- Media3 1.11.1 requires compileSdk 36. `targetSdk` remains 35, so runtime behavior opt-in did not change.

## Commits

- `4d5ec0a` — sleep timer/fade and drive-trigger core
- `72c6309` — Room resume persistence and playback settings
- `628e12a` — Media3 audiobook playback service
- `7447e53` — native player, drive mode, and page-turn inputs

This receipt is committed separately and last. No push was performed.

## Hermes verification (emulator, API 35)
- Codex's build passed 43 tests and lint, but the APK CRASHED AT LAUNCH on a real device: `NoClassDefFoundError: com.google.common.util.concurrent.internal.InternalFutureFailureAccess` at PlaybackController.<init>. Cause: Guava/failureaccess had been hand-staged as raw jars. Fixed by resolving Media3 + Guava normally through Gradle (commit follows). After the fix: app launches, no FATAL, 43 tests green.
- Verified on emulator: launch, native Listen tab renders, audiobook search returns real results (Pride and Prejudice etc.).
- NOT verified: actual audio playback, lock-screen/notification controls, headset buttons, sleep timer fade, drive mode, volume-key page turns, haptics. Reason: LibriVox (librivox.org and the Netlify /api/librivox function) timed out (504 / no answer in 25s) during the test, so no book could be opened, and a substitute MP3 test file did not download (got an HTML redirect). The app showed an honest "LibriVox didn't answer" error.
- Lesson: green unit tests did not catch a launch crash. Always launch the APK before reporting done.

## Hermes round 2 (emulator, API 35) - LibriVox + playback
- Netlify /api/librivox: one stalled upstream try is now retried once within the 10s limit (live, ae0610a). Search lists real recordings.
- Listen button did nothing: PlaybackService.onConnect limited player commands, so play() was dropped ("COMMAND_PLAY_PAUSE not being available"). Fixed with DEFAULT_PLAYER_COMMANDS.
- VERIFIED playing Moby Dick: position 15s -> 30s -> 45s, state PLAYING, 0 playback errors. Screen truly OFF (mWakefulness=Asleep): 84s -> 102s over 20s. Play/pause key and media_session dispatch pause/play both work. Foreground service + media notification present.
- archive.org returns 500/503 for some files (e.g. Pride and Prejudice ch1-4, reproduced with curl from host). Added load-error retry policy (8) and bare-host URL; built, NOT yet verified against that file.
- NOT verified: sleep timer start/fade, notification contents, drive mode, volume-key page turns, haptics, offline-saved playback.

## Hermes round 3 (emulator, API 35) - sleep timer + layout
- Layout defect found by screenshot: the speed row overflowed, so the 2.0x button and the sleep-timer icon were clipped off the right edge and unusable. Fixed: speed buttons share the row equally; sleep timer is now a labeled "Sleep timer" button. Verified screenshot: all five speeds visible, menu shows 15/30/45/60 minutes, End of chapter, Off, unclipped.
- VERIFIED sleep timer (End of chapter): label changed to "Stops at end of chapter"; seeked near chapter end; playback kept running to the boundary, then state went PLAYING -> PAUSED at position 18 ms of the next chapter and stayed paused 8+ s (did not roll into chapter 2).
- NOT verified: the volume fade curve itself, timed (15/30/45/60) timers, shake-to-extend, notification contents, drive mode, volume-key page turns, haptics, offline-saved playback.
