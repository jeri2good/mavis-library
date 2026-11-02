# RECEIPT — BRIEF-086-NATIVE: read aloud with on-device voices

**Status:** built, unit-tested and compiling. **Not yet run on any Android device or emulator**: audio, the WebView
shim and the real model on a phone are all unproven until Hermes runs acceptance. Not pushed.

## Decision to check: the model differs from the handoff note
The handoff named `kokoro-en-v0_19`, but no model URL was actually in the tree. I pinned
`kokoro-int8-multi-lang-v1_0` instead, which the brief allows ("or newer multi-lang int8 if smaller"):
- `kokoro-en-v0_19` has 11 voices and none is `af_heart`, the brief's required default.
- It is 319.6 MB; the int8 multi-lang archive is 132.4 MB.

## Pins
| | |
|---|---|
| sherpa-onnx | v1.13.8, `sherpa-onnx-static-link-onnxruntime-1.13.8.aar` |
| AAR URL | https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-static-link-onnxruntime-1.13.8.aar |
| AAR SHA-256 | `b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471` |
| Model URL | https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2 |
| Model SHA-256 | `72c347230107ef8a31098ec4d468a605244beef5f1ed00db97efa13518800653` |
| Model size | 132,353,410 bytes download; 189.9 MB unpacked |

Both SHA-256 values match GitHub's published asset digests and a local `sha256sum` of the downloaded file.

## What was built
- **Shim:** `assets/native-speech-synthesis.js`, injected at document start for `WEB_ORIGIN`, top frame only, and only
  where the Web Speech objects are missing. Transport is the `MavisSpeech` WebMessageListener.
- **Queue:** `readaloud/SpeechDispatcher.kt`. Cancel follows Chrome desktop as I understand it: the current utterance
  errors `interrupted`, queued ones error `canceled`, no `end` fires, and `paused` clears. This was not measured
  against a live Chrome. The web player ignores both codes.
- **Phone voice:** `PhoneVoiceEngine.kt` (`TextToSpeech`, word boundaries, offline voices first). Added the
  `TTS_SERVICE` manifest query, without which Android 11+ hides speech engines.
- **Natural voice:** `KokoroEngine.kt` generates per sentence on one thread while another plays through `AudioTrack`.
  Model loads at first use and is released after 3 idle minutes. Voices: Heart (default), Bella, Michael, Emma, George.
- **Download:** `KokoroModelStore.kt` does Range resume, SHA-256 verification, path-escape rejection and atomic install.
- **Engine choice:** Kokoro if downloaded, otherwise phone. Non-English text with no named voice goes to phone. If
  Kokoro fails before speaking, the utterance falls back to phone once.
- **Audio focus:** media focus, held 2 s past each utterance so other apps don't surge between verses.
- **Settings:** "Read-aloud voice" section with download/progress/pause/remove, voice picker with ▶ samples, phone voice
  and Android voice settings. Choice persists in DataStore and sets the `getVoices()` order and default.
- **Kids mode:** Settings is blank there, so no download prompt can appear. Uses Kokoro if present, otherwise phone.

## Review of the previous run's uncommitted edits
All kept. The AAR pin, packaging excludes and R8 rules were right; I corrected one inaccurate comment on which ABIs the
AAR ships.

## APK size
| Build | Size | Delta |
|---|---|---|
| Debug before (local build from 19:57 today, before these edits) | 43,964,427 B | |
| Debug after | 96,412,500 B | **+52.4 MB** |
| Release after (unsigned) | 69,395,100 B | no baseline measured |

- `libsherpa-onnx-jni.so` accounts for 24.2 MB (arm64-v8a) + 27.4 MB (x86_64), stored uncompressed. Every install
  downloads both ABIs because the APK is universal.
- No model file is in the APK (checked by listing it).
- The app's existing small androidx libs remain for armeabi-v7a and x86, so those phones can still install and use the
  phone voice. "arm64 + x86_64 only" is applied to the sherpa libs.

## Tests
- `testDebugUnitTest assembleDebug`: **BUILD SUCCESSFUL**, 503 tests, 0 failures, 0 errors, 0 skipped (28 new in `readaloud`).
- `assembleRelease`: **BUILD SUCCESSFUL**; R8 keeps the sherpa classes unrenamed.
- `node scripts/test-speech-shim.mjs` (new) and `node scripts/test-account-transport.mjs`: pass.
- All builds ran with `--no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2g`. No emulator was started.
- Coverage: queue and cancel with fake engines, stale callbacks, pause/replay, fallback, default-engine selection,
  settings persistence, SHA rejection, resume, Range ignored, dropped connection, path escape, removal.
- One-off checks, not committed:
  - The store unpacked the real 132 MB archive, served from a local fake server; result was identical to plain `tar`.
  - Desktop x86_64 sherpa-onnx 1.13.8 (Python) ran the same model settings with all five speaker ids: 1.9 s load,
    351 MB RSS, audio produced. Voices were not checked by ear.

## Known gaps
1. **Pauses between verses are likely.** The web player sends one verse at a time and waits for `end`, so native cannot
   prepare the next verse ahead without a change in `src/` (off limits here). Look-ahead only works between sentences
   inside one utterance. On this machine (2 threads, under load) 2.6 s of speech took 4.4 s to generate. **Phone speed
   is unknown and is the main thing to measure.** The engine uses up to 4 threads.
2. **The web watchdog can fire on slow phones.** `speech.js` moves on after `len/(13·rate)·2500+6000` ms. A cold model
   load on a short heading could trip it, queueing the next verse and drifting the highlight.
3. **Background reading is unproven.** There is no foreground service, so Android may stop it when the app is backgrounded.
4. **Focus loss shows odd copy.** A call or another player stops reading with "Speech stopped (audio-busy). Try another
   voice." I chose stopping over the web player queueing on silently; better copy needs `src/`.
5. **British voices use the US lexicon**, so Emma and George may sound less British on some words.
6. **Kokoro limits:** pitch is ignored, and boundaries are per sentence only (phone voice gives word boundaries).
7. **No phone-voice picker in Settings;** it uses Android's default. All phone voices are still in `getVoices()`.
8. **A voice saved in the web app wins** over the Settings default (`ttsVoice`, unchanged web behaviour).
9. **The download needs the app process alive.** It survives leaving Settings; after a process death the partial file
   stays and the button offers Resume.
10. **Stopping a sentence mid-generation** relies on the sherpa callback's return value, not exercised on a device.
    Audio stops at once regardless; the next utterance could wait for that generation to finish.

## For Hermes to check first
Bible → Read with phone voice; download Kokoro; Read again and time verse gaps; cancel/skip mid-verse; incoming call;
background the app; Kids mode.
