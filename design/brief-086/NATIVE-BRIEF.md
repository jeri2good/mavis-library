# BRIEF-086-NATIVE — Read aloud with on-device voices (Devin, Claude Opus 5.5)

## Problem (seen on J's phone)
Bible tab (web bundle in the app's WebView) → headphones "Listen to this chapter" → toast about the cloud voice not
being enabled. Android WebView has NO `window.speechSynthesis`, so `src/lib/tts.js` reports `ttsSupported=false`.
Every read-aloud path (Bible `startListening` in `src/views/bible.js` ~619, EPUB reader `src/lib/tts.js`,
`speakWord`, `src/lib/companion.js`) depends on Web Speech API.

## Approach: native speechSynthesis polyfill (do NOT rewrite the web read-aloud code)
1. In `native-android`, inject at document start (WebViewCompat.addDocumentStartJavaScript, origin = existing
   WEB_ORIGIN in `web/WebHost.kt`) a JS shim that defines `window.speechSynthesis` and
   `window.SpeechSynthesisUtterance` (+ `SpeechSynthesisVoice` shape) ONLY when they are missing.
   Must support what the web code uses: `speak`, `cancel`, `pause`, `resume`, `speaking`, `pending`, `paused`,
   `getVoices()`, `voiceschanged` event, utterance `text/voice/lang/rate/pitch/volume`, events `start`, `end`,
   `error`, `boundary` (word boundaries if available — the reader highlights by sentence; boundary is a bonus).
   Queueing semantics like the spec (speak appends; cancel clears and fires no `end` for cleared items… match
   Chrome: cancelled utterances fire `error` with `interrupted` or nothing — pick Chrome desktop behaviour and
   document it).
   Transport: a WebMessageListener (like the existing "MavisPodcast" one) — not addJavascriptInterface.
2. Engines behind the shim (Kotlin):
   a. **Phone voice**: android.speech.tts.TextToSpeech, UtteranceProgressListener → start/end/error/range(boundary).
      Voices: TextToSpeech.voices (offline-capable first), exposed with `localService: true`.
   b. **Natural voice — Kokoro on-device** via sherpa-onnx Android (k2-fsa/sherpa-onnx, Apache-2.0; prebuilt AAR
      or JNI libs from its GitHub releases — pin a version, record SHA256). Model: kokoro-en-v0_19 (or newer
      multi-lang int8 if smaller) downloaded ON DEMAND (not in the APK) from the sherpa-onnx GitHub release
      `tts-models` to app files dir, SHA256-verified, with progress + resumable download, Wi-Fi recommended.
      Expose Kokoro voices (e.g. af_heart, af_bella, am_michael, bf_emma, bm_george) as SpeechSynthesisVoice
      entries named "Natural — Heart (Kokoro)" etc., `localService: true`, `default` = af_heart when installed.
      Generate per sentence on a background thread; start playing sentence N while generating N+1 (low latency).
      Respect rate. Audio via AudioTrack, media audio focus, stops on cancel immediately.
   c. Default engine order: Kokoro if downloaded, else phone voice.
3. Settings (native Settings screen, existing style): "Read-aloud voice" section: Natural (Kokoro) — Download
   (~size MB) / Downloaded ✓ / Remove; voice picker with a ▶ sample per voice; Phone voice option + "Open Android
   voice settings". Choice persists (DataStore) and the shim's `getVoices()` default follows it.
4. Kids mode: allowed (read-aloud is safe) but no download prompts — use phone voice if Kokoro absent.

## Rules
- Work in `native-android/` only. Do not edit `src/` (another worker is changing Bible icons there).
- APK size: no model inside the APK. Native libs: arm64-v8a + x86_64 only (emulator), note size delta in receipt.
- Unit tests: shim queue/cancel semantics (JS tested via a JVM JS-free approach is fine: test the Kotlin
  dispatcher with fake engines), download SHA verification, settings persistence, default-engine selection.
- Run `testDebugUnitTest assembleDebug` (AGENTS.md); all green. Commit per step with `BRIEF-086:` prefix and
  `[skip netlify]`. Don't push. Don't use the emulator (Hermes will).
- `RECEIPT-086-NATIVE.md`: what was built, the pinned sherpa-onnx version + model URL + SHA256, APK size delta,
  tests, known gaps. Then stop.
