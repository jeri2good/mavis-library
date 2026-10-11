# Milestone 2: native audio, sleep timer, drive mode, page-turn keys

Repo: this dir, branch `native`. Read `docs/NATIVE-PLAN.md`, `native-android/RECEIPT.md`. Work only in `native-android/` (and a tiny behaviour-neutral hook in web `src/` only if needed). Commit small steps. Do NOT push.

## HARD RULES (a crash source was found)
- Machine has 7.8 GB RAM, swap nearly full. Run ONE heavy job at a time. NEVER start the Android emulator: Hermes runs it separately after you finish. No emulator, no AVD, no adb to emulators.
- Gradle: `export JAVA_HOME=$HOME/.local/jdks/jdk-21.0.12.1+1 PATH=$JAVA_HOME/bin:$PATH ANDROID_HOME=$HOME/Android/Sdk; cd native-android && ./gradlew --offline --no-daemon <task>`. Run in the background with a log file and poll; never block over 3 minutes.
- No secrets. Keep the 34 existing unit tests green; add tests for everything below. `testDebugUnitTest assembleDebug lintDebug` must pass.

## Tasks
1. Playback service: Media3 (ExoPlayer + MediaSessionService), foreground service type mediaPlayback, notification with lock-screen controls, headset/Bluetooth buttons, audio focus (duck/pause on calls), becoming-noisy pause. Plays an audio URL (LibriVox/audiobook MP3 stream, cached when downloaded) with resume position saved in Room. Declare only the permissions needed (FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK, POST_NOTIFICATIONS requested at first use). Design the MediaLibrary/browse tree so Android Auto can be added later.
2. Native Listen tab: replace the hosted web Listen screen with a native player screen (cover, title, scrub bar, 15s back/30s forward, speed 0.75-2x, chapter list when available) plus mini-player above the bottom bar. Read how the web app lists audiobooks (netlify functions + `src/views/listen.js`) and reuse the same endpoints. Offline: downloaded files play with no network.
3. Sleep timer: presets (15/30/45/60 min, end of chapter), 10-second volume fade, shake to extend by 10 min, auto-pause if headphones unplug. State shown in the player and notification.
4. Drive mode: starts when a Bluetooth audio/car device connects (user-toggleable in Settings, off by default), full-screen huge controls (play/pause, back 15, forward 30, sleep), dimmed, resumes the last book. Only needs BLUETOOTH_CONNECT at first use.
5. Page-turn inputs for the hosted web reader: volume keys, headset next/previous, and optional haptic tick via `window.MavisNative.haptic('tick')` (inject a bridge; the web reader already calls it if present) and a way for the native side to call the reader's next/prev page. Settings toggles for each.
6. Settings additions: sleep defaults, drive mode toggle, page-turn inputs, playback speed default. Respect all four looks.
7. Tests: unit tests for the sleep-timer state machine, fade math, resume-position store, and the drive-mode trigger logic (pure Kotlin, no emulator). Robolectric for the service where feasible.
8. Write `native-android/RECEIPT-M2.md` LAST: what was built, exact commands with real output summaries, what is NOT verified (on-device playback is verified later by Hermes on the emulator), known problems. Also append checkpoint notes per ~/.codex/AGENTS.md. Never fabricate.
