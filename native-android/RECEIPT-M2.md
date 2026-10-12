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

## Hermes round 4 (emulator, API 35) - reader keys, settings
- Settings screen renders Drive mode (off by default), Volume keys, Headset next/previous, Haptic page tick toggles. Switched volume keys, headset keys and haptic on; confirmed checked.
- Opened Pride and Prejudice via Shelf > Discover > Download and read: reader loads real text, "page 1 of 38".
- VERIFIED volume keys: DOWN advanced pages 2->3->4->5->6, UP went back 6->5->4 (6/6 correct once paginated). One early DOWN press before pagination finished did nothing.
- NOT working / unproven: MEDIA_NEXT (keyevent 87) did NOT turn the page (adb key injection; real earbud behavior untested). Haptic: vibrator history shows short effects but I could not attribute them to page turns, so it is unverified. Drive mode untested (needs a car Bluetooth connect event; permission flow not exercised).
- Defect: in Search, the EPUB and Web page format chips are AssistChip(onClick = {}) - they do nothing. Reading starts from Shelf > Discover > Download and read instead.

## Hermes round 5 (emulator, API 35) - dead chips fixed, earbud keys still failing
- FIXED + VERIFIED: the Search book sheet no longer has dead EPUB/Web-page chips. Formats are plain text and there is a real "Open book page" button. Tapped it on Alice's Adventures in Wonderland: sheet closed and the Shelf tab showed that book's page (cover, "Download & read", "Want to read", LibriVox panel). Route encoding has 3 unit tests (46 total, 0 failures).
- Reader opens real text from there ("page 1 of 4"). On-screen next button turns pages, but only after the web reader finishes loading; on the cold emulator that takes ~20 s.
- STILL FAILING: earbud next/previous. Added a small active MediaSession (ReaderMediaSession) while the app is in front. `dumpsys media_session` shows it registered and active, but injected KEYCODE_MEDIA_NEXT/PREVIOUS (87/88) did not turn pages in 3+ attempts. dumpsys reports "Media button session is null", so Android is not routing media buttons to it. Likely needs the session to have been the most recently PLAYING one, or a registered media button receiver. NOT solved; do not rely on earbud page turns. ReaderMediaSession is therefore unproven code.
- Volume keys were verified in round 4. In this round they were not retested because the emulator is booted read-only and resets Settings, so the "Volume keys" switch was off.
- Still untested: haptic attribution, drive mode (needs car Bluetooth event), offline-saved audio, notification contents.

## Hermes round 6 - Discover home in the native app
- Search is now the start tab and opens on the same curated shelves as the website (/api/shelves + /api/catalog?ids=), plus a "New in tech & AI" section (/api/tech: AI news, research papers, tech front page; links open in the browser). Shelves load 3 at a time and are cached on the device by CachedFetcher, so they also show offline after one load.
- Tests: 50 pass, 0 fail (4 new for shelves/ids/tech parsing, shelf order, copyright filtering; the start-tab navigation test updated for the new start tab).
- Emulator check: shelf titles and descriptions render in the right order, but every shelf showed "Couldn't load this shelf right now." because Project Gutenberg itself was down at the time (www.gutenberg.org answered 503/504 or timed out directly from this host; /api/catalog returned 502/504). So the cover rows were NOT visually verified. The error state was verified: it degrades to a clear message instead of a blank page.
- RETEST once Gutenberg was back: VERIFIED on emulator. Search opens on the shelves with real covers (Most-loved classics, Children's, Maths & puzzles, Science fiction, ...) and the tech feed shows live items (Claude Haiku 5.5, Sonnet 5.5, ...). Screens 51-54.

## Hermes round 7 - earbud next/previous (STILL NOT WORKING)
- Tried: reader session reporting PLAYING at speed 0 + play/pause handlers; then FLAG_HANDLES_MEDIA_BUTTONS and a paused->playing transition after activation. Both builds install and run.
- Result: `dumpsys media_session` still says "Media button session is null" after injecting KEYCODE_MEDIA_NEXT, so Android never routes the key to the app. The session itself is registered and active.
- Not tried yet: setMediaButtonReceiver with a PendingIntent to a manifest receiver (Android may require it for non-foreground-service sessions), or routing earbud keys through the existing audiobook MediaSession.
- Also seen: on this emulator the web reader sometimes stays on the cover ("page 1 of 1") for 2+ minutes after Download & read; it did paginate fine in an earlier run. Probably emulator speed, not proven.
- Volume-key page turns (verified earlier) are unaffected.

## Hermes round 8 - earbud attempt 3 (manifest MEDIA_BUTTON receiver + setMediaButtonReceiver)
- Built and installed. `dumpsys media_session` still "Media button session is null" after KEYCODE_MEDIA_NEXT, and the reader never paginated in this run (page 1 of 1 for 160 s), so a page-turn could not be observed either way. No app errors in logcat; host had ~490 MB free RAM. UNVERIFIED, likely needs a real phone with real earbuds.

## Hermes round 9 - drive mode + offline audio
- Drive mode screen: VERIFIED on emulator through a debug-only intent (am start -a com.mavislibrary.app.DEBUG_DRIVE; compiled out of release). Dark screen, back-10 / play / forward-30 / sleep timer, close X returns to the app. Settings toggle "Start for car Bluetooth" turns on (BLUETOOTH_CONNECT granted).
- NOT verified: the real trigger. A simulated ACL_CONNECTED broadcast is rejected by the OS (protected broadcast), so car-Bluetooth entry is covered only by DriveModeTriggerTest unit tests. Brightness dimming not observed (dumpsys printed nothing).
- Offline audio: NOT verified. LibriVox search/open works (Moby Dick: Save offline + Listen buttons shown), but the full "save, cut network, play" run did not happen: Gutenberg answered 504 again and my text input doubled in the search box. A Christmas Carol (5 chapters) is the short test case to use next.
- Also added: MainActivity launchMode=singleTop.

## Hermes round 10 - delegated work verified (Codex gpt-6-astra + Devin gpt-6-sol-medium)
- Codex (worktree, merged): native Shelf tab (ui/shelf/*, ui/common/BookTile.kt). Emulator: fresh install shows "Your shelf", the web-shelf button, and Popular picks rows with covers; tapping a pick opens the book page; Add to shelf puts the book in a native grid; the three-dot menu removes it.
- Devin (worktree, merged): REVIEW.md security review of the owner-code handoff (fix needed: origin gate; plaintext web storage accepted; global hook minor) + 13 new tests (Discover VM, API edges).
- Codex fix pass on native: handoff now pushes only when the page is the app origin (isAppOrigin, 6 tests); empty id list resolved locally.
- Hermes check: 75 unit tests, 0 failures (own rebuild). Emulator: code typed in native Settings -> saved to Keystore -> after opening a web page the web app holds the exact code and the server returns owner:true.
- NOT verified: foreign-origin refusal on a live WebView (unit tests only); the earlier native-Settings typing doubled a character once (adb input quirk, not an app fault).

## Hermes round 11 - title match, download retry, offline audio PROVEN
- Codex gpt-6-astra: LibriVox title match ignores a leading a/an/the (netlify/functions/librivox.mjs, 9 cases; function tests 91 pass, own re-run exit 0). Live before fix: title=A%20Christmas%20Carol -> 0 versions, Christmas%20Carol -> 8.
- Codex gpt-6-astra: AudioFileStore retries each chapter 4x (1s/3s/8s) on 5xx/429/IO errors; 404 fails at once; finished chapters kept. 82 unit tests, 0 failures (own rebuild).
- Emulator, own run, build with retry: Anthem (1:18:06, 12 chapters) Save offline -> "Saved for offline", 12 mp3 files, 62 MB. Airplane mode ON, ping DOWN: Listen played from local files, position advanced (27 s -> 36 s), chapter 1 rolled into Part 2 offline and kept advancing.
- Before the retry fix the same save died at chapter 11 with HTTP 500 (archive.org answered 200 seconds later).
- Not verified: Christmas Carol on the LIVE site until deploy; real phone; Gutenberg flaps 502/504 for minutes at a time (not an app fault).

## Hermes round 12 - native Kids mode v1 (Codex gpt-6-astra started, Devin gpt-6-sol-medium finished)
- Codex hit its usage limit mid-job (build never ran, no receipt); tree failed to compile (KidsPrefs.kt takeLast). Devin fixed it, added route/shell tests, startup drive-mode guard, 30 s reading-log flush.
- Hermes own rebuild: 97 unit tests, 0 skipped/failed/errors; APK built.
- Emulator (Mavis_Fresh forced to 1600x2560 @280dpi to hit the tablet breakpoint), own run: Settings Kids section -> name Maya, 20 min, PIN 1234 x2 -> Start; kids home (greeting, ring 0 of 20, streak, week dots, 4 big tiles, no adult tabs); 3x system Back stays in kids home; Read = 4-column cover grid; book page -> Download & read -> reader; Listen list; Bible stories list (12) -> Noah's ark opens Genesis 6; lock icon -> PIN pad; 5 wrong PINs -> "Wait 30 seconds", correct PIN refused while locked, accepted after ~30 s -> adult shell; force-stop + relaunch re-opens kids home with 3 min counted.
- NOT verified: real tablet/hardware; LibriVox playback inside kids Listen; star earned at goal; PIN lockout surviving app kill; whether a child can escape via reader links (Devin review pending); Bible text is plain KJV, not simplified.

## Hermes round 13 - Kids hardening verified on emulator
- Devin gpt-6-sol-medium wrote KIDS-REVIEW.md (3 HIGH, MED, LOW findings; reasoning only) then hardened the reader (3fbd893, 578b50c, a20abba). Own rebuild: 105 tests, 0 failed/skipped.
- Emulator (tablet-size), owner code saved in adult Settings, then Kids mode on, Peter Rabbit open. Own CDP probes inside the live WebViews:
  - Network.requestWillBeSent, same two probe requests (fetch + XHR with x-mavis-access): ADULT page sends the header unchanged (control); KIDS reader sends only x-mavis-kids:1 - owner header stripped on both paths.
  - Kids page: shared IndexedDB has NO accessCode key (adult page has it); window.open -> null; navigator.share -> NotAllowedError; user-select none; a target=_blank anchor click left MainActivity on top (no external activity).
  - Long press on text: no selection toolbar or handles (screenshot 103). Reader still works: cover then page 3 of 27 text (screenshots 102, 104).
- Exit via PIN pad still returns to the adult shell. Kids WebView does not receive the owner code.
- NOT verified: EPUB subframe links (no EPUB tested; Peter Rabbit is HTML); real tablet; system Home/Recents (needs Android screen pinning, not app code); findings 5 (shared media service) and 6 (4-digit PIN KDF) deferred; Listen audio and goal star still untested.

## Hermes round 14 - merge of sensors/widgets/reminders + share/stats + barcode scan
- Merged tree (bf5b3d6 + 76f29a6 nav fix + devin/cover-scan): own build, 152 tests, 0 fail/0 err. Debug APK 42.1 MB (ZXing core + CameraX; bundled ML Kit measured +23.2 MiB and rejected).
- Emulator: Search has a Scan button; rationale screen shows BEFORE the system permission prompt; typed ISBN 9780547928227 -> live /api/openlibrary lookup -> "Found: The Hobbit by J.R.R. Tolkien" with Open book / Save to shelf; bad-checksum ISBN rejected with "Check the ISBN digits and checksum"; with CAMERA granted, CameraX opens (state OPEN) and the virtual-scene preview shows under the viewfinder overlay.
- Shared "John 3:16" opens Bible at John 3 after the tab-navigation fix; shared valid EPUB lists under Imported books; fake EPUB rejected.
- NOT verified: ZXing decoding a real barcode through the camera (headless emulator could not aim the virtual camera at a test barcode; the decoder has JVM tests only); torch; Match-the-room-light and reminder rows; widgets on a launcher; real phone/tablet.

## Hermes round 15 - merged voice commands + biometric lock + imported-EPUB reader
- Merge 790a3d0 (+ EPUB parser fix): own build, 243 tests, 0 fail/0 err. Workers: Codex gpt-6-astra (voice, codex/voice), Devin gpt-6-sol-medium (lock + EPUB, devin/lock-epub; the first Devin run ended without a summary, resumed and committed by Hermes).
- Emulator, device PIN set via `locksettings`:
  - App lock row refuses with a snackbar when no screen lock exists ("Set up a device PIN, face, or fingerprint first. Lock is still off"); with a PIN set it turns on; cold start shows "Mavis is locked" + Unlock, window has FLAG_SECURE, UI dump shows 0 app nodes while locked; Unlock opens the system BiometricPrompt, PIN 4321 unlocks to the app; after 70 s in background the app re-locks. Turning the lock off works.
  - Voice commands row (off by default): switch shows the honest mic rationale dialog (push-to-talk, max 6 s, no saved/uploaded audio, provider may use network, Kids mode disables) with Not now / Continue.
  - Imported EPUB reader BUG found on device: first tap showed "This EPUB couldn't be read here" for a valid minimal EPUB. Cause: Android's SAX parser throws on the Xerces-only setFeature(...) calls in NativeEpubText.parse(), which JVM tests did not catch. Fix: explicit DOCTYPE/ENTITY rejection + best-effort runCatching features. Re-tested: "Chapter 1 of 1 - Hello from a shared book." renders.
- NOT verified: real fingerprint/face prompt, speech recognition (emulator headless/no mic), headset long-press, mic permission accept path, owner-code-not-pushed-while-locked on a live page (Devin flagged WebHost.setOwnerCode(null) cannot clear a code already loaded in the page - known limitation), real phone/tablet.
