# Milestone 1 receipt (Hermes first-hand verification)

Built: native Kotlin/Compose shell (package com.mavislibrary.app, 0.5.0-m1, minSdk 26), 4 looks (Paper, Midnight, Editorial, Kids preview), native Search + Settings, hosted web Shelf/Bible/Listen, Room offline layer, encrypted owner-code storage.
Verified by Hermes: assembleDebug green (37s); 34 unit tests, 0 failures; function tests pass with TYPESAFE_API_KEY/FISH_AUDIO_API_KEY unset.
Screenshots (clean API 35 emulator, docs/screens/): 05 start, 06 search with live results, 07b Bible (John 1 KJV), 08 settings, 09-11 Paper/Midnight/Editorial, 12/12b Themes incl. Kids.
Permissions: INTERNET, ACCESS_NETWORK_STATE, RECORD_AUDIO (declared; asked at first use), WAKE_LOCK, FOREGROUND_SERVICE, RECEIVE_BOOT_COMPLETED (from WorkManager, to be reviewed).
NOT working / NOT verified:
- Offline banner: VERIFIED with airplane mode (screens 14-15): banner + honest "offline, not saved yet" message. Earlier miss was a bad test (Wi-Fi off only; emulator also has a simulated mobile link).
- /api/features blocked by CORS: Phase A backend not deployed to Netlify.
- First load of Bible/Shelf takes 15-30s on the emulator; not measured on a real phone.
- Mic prompt, book detail sheet, Add to shelf, file import: not exercised.
- No screen-off audio (M2).
- Removed "Made with AI" badges per J; Report buttons kept.

---

# Kids Story Player - Chunk 1 Receipt (2026-11-02)

Implemented BRIEF-app.md in the kids-stories worktree. This receipt records local
source/JVM/build verification, not first-hand device or audible-playback proof.

## Delivered

- Added age 2-10 (default 5), narrator host/dad/grandma (default host), tier mapping
  and page filtering. Parent settings use the existing salted PIN and shared
  five-miss lockout without disabling kids mode. Parent authorization is transient
  and clears on background/Back/Home; leaving kids mode still checks the PIN.
- Replaced the Bible web-story list with a native shelf: two phone columns,
  three/four tablet columns, cached cover art, tier-specific minutes, downloaded
  checks, per-card progress and retry/resume on tap.
- Added bounded catalog/asset reads, cached catalog fallback, streamed ZIP
  downloads with persistent Range partials, three transport attempts, SHA-256
  verification, canonical-path/zip-slip checks, extraction bounds, required-file
  validation and staging before install into filesDir/kids/<id>/.
- Added a private Media3 MediaSessionService and controller. Narration uses the
  selected tier/voice; a second ExoPlayer loops music at 0.14 volume and ducks to
  0.045 while narration actually plays. Speech owns audio focus; music follows
  focus suppression, pause and headphone disconnection. Both use local wake locks.
- Added picture/text player, large rounded narration text, swipe and previous/next
  buttons, play/pause, approximately one-second auto-advance gaps, end-of-story /
  10 / 20 minute sleep controls, and The End with the t3 question and shelf return.
  Sleep deadlines live in the service, not a composable timer.
- Kept DEFAULT_PLAYER_COMMANDS, explicitly removed arbitrary media replacement,
  restricted controller/custom-command access, and kept the service non-exported.
  Player and credits have no external-link or sharing actions. Existing kids
  WebView hardening is untouched. Story playback participates in reading time.
- Copied the shared contract to docs/KIDS-STORY-CONTRACT.md. The source changed
  during implementation to correct OBS mappings; the final copy matches that
  update. Nullable/multi-chapter obs metadata is supported. Catalog availability,
  not hardcoded or invented source mappings, determines which stories appear.

## Lanes And Content Boundaries

- Default: catalog/assets from the GitHub kids-content-v1 pre-release URL in the
  contract; local Media3 playback after a verified story download.
- Fallback: cached catalog, covers and installed stories work without network.
  There is no web-text, cloud TTS or alternative-voice fallback for missing clips.
  A missing selected narration produces an explicit retry/page error.
- Previews: parents can select any of the three narrators and request up to three
  seconds of preview, measured after playback begins. An optional voices[].preview
  relative path is accepted; otherwise preview-host.mp3, preview-dad.mp3 and
  preview-grandma.mp3 are tried at the pack base. Missing previews show a message
  without discarding the saved voice selection.
- Test override: debug builds accept -PkidsPackBase=https://fixture-host/pack/
  to set BuildConfig.KIDS_PACK_BASE. Release builds keep the contract URL.
  The production HTTPS/network-security policy is unchanged.
- Experimental lanes: none added. No Mavis conversational voice route or voice
  identity is changed. Content publishing and Daniel/Jonah source approval are
  outside this Android brief; no release assets were uploaded or claimed live.

## Verification

Code/artifact revision: c44a561; this receipt/contract-sync commit follows it.

Ran the required command from native-android with JDK 21 and the existing SDK:

```sh
JAVA_HOME=/home/hawthornes/.local/jdks/jdk-21.0.12.1+1 \
ANDROID_HOME=/home/hawthornes/Android/Sdk \
./gradlew testDebugUnitTest assembleDebug --no-daemon --max-workers=2 \
  -Dorg.gradle.jvmargs="-Xmx1536m -XX:MaxMetaspaceSize=512m" \
  -Pkotlin.compiler.execution.strategy=in-process \
  -Pkotlin.daemon.jvmargs=-Xmx768m
```

Final run: BUILD SUCCESSFUL in 2m49s. XML totals: 96 suites, 515 tests,
0 failures, 0 errors, 0 skipped. The prior full rerun also passed.

New tests: StoryPackTest (8) and KidsStoryServiceTest (1). They cover all age
boundaries, tier filtering, catalog/story parsing, fixture extraction/file
validation, SHA rejection, zip-slip/active-path rejection, defaults and preference
persistence, PIN-preserving parent unlock, partial Range resume, ignored Range,
offline re-open, catalog fallback, corrupt-download/clean retry, session command
containment and ten-minute elapsed-time sleep expiry.

The fixture under app/src/test/resources/fixtures/kids uses synthetic text,
a 32x32 generated JPEG and mono 44.1kHz one-second silent MP3s (container duration
1.044898s including encoding padding). ZIP/catalog bytes are generated locally in
tests; HTTP tests use MockWebServer, never the live release.

First run: 514 tests / four failures, although assembleDebug succeeded. Two were
the new automatic controller binding in Robolectric; fixed by following the
existing PlaybackController test-safe binding pattern. Direct session-token
integration remains tested. Two were Robolectric native-font initialization
failures, which did not recur on either subsequent full run.

APK: app/build/outputs/apk/debug/app-debug.apk (approximately 93 MiB).
SHA-256: 4173788eec1b096d20ae4ab8edbc0e6bef7869d0c8f4dbba899833ea4eea81cf

No emulator or device was started/used. No live content/audio, screen-off output,
notification behaviour or phone/tablet layout is claimed verified here.
gradle.properties is unchanged. Existing bundled web assets were reused.

## Hermes Device Acceptance

1. Install the APK and enter Kids mode. Confirm the adult shell, external shares
   and Back navigation remain gated. Unlock parent settings with the existing
   PIN; change age/narrator, inspect plain-text credits, background/resume, and
   verify parent authorization resets. Check all preview choices, including
   missing-asset handling and the three-second cutoff when clips are available.
2. With a published pack or HTTPS fixture base, open the shelf on phone and
   tablet sizes. Download a story, interrupt connectivity midway, retry and
   confirm resumed progress/downloaded check. Reopen it after airplane mode and
   an activity restart. The catalog and downloaded covers must remain visible.
3. Test ages 4/5/7/8 and each narrator against available content. The local fixture
   has one t1 page and two t2/t3 pages. Verify pictures/text, swipes/arrows,
   approximately one-second narration gaps and the t3 end question/shelf return.
4. During a real narration, turn the screen off and listen across page changes.
   Check music ducking and pause/resume, headset unplug/audio focus loss,
   notification/lock-screen controls, and that notification return stays in kids
   mode. Check pause/resume during a page-end gap specifically.
5. Exercise ten/twenty-minute timers and story-end stop with screen off. Confirm
   both players stop and manual resume works. Use a fixture with a deliberately
   incorrect catalog SHA to confirm it cannot install or show as downloaded.

Commits before this receipt:

- 65db9f3 - pack storage, contract, preferences.
- d2baf0a - local fixture and pack regressions.
- e64e1cc - shelf, parent UI, background player.
- c44a561 - playback/session fixes and integration verification.

Every commit includes [skip netlify]. No push or deployment was performed.
