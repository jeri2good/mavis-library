# Kids Mode Round 2 Receipt - 2026-11-02

Implementation revision: `1ee4db8` (this receipt follows it).
Starting revision: `babcd08`; worktree was clean.

Read native-android/RECEIPT.md, PROGRESS.md, recent git history, both round-two
briefs (including Added by Hermes), and ~/kids-stories/CONTRACT.md before changes.
Existing downloads, picture-story UI, PIN containment, decimal-minute support,
published fixtures, and the unique `kids-story` MediaSession were reused.
All changes are under native-android/. No emulator/device, push, deployment,
content generation, paid service, or live catalog/audio request was used.

## Delivered

- StoryVoice accepts optional tiers; StoryTier accepts optional defaultVoice.
  Unknown metadata, including obsChapters, remains ignored rather than failing
  parsing. Existing Map<String, Double> minutes parsing is preserved.
- Narrator choices and labels come from the catalog, including British accent.
  Storyteller is offered only for ages 8-10 and defaults there when available;
  tier defaultVoice is honored. Older catalogs without Storyteller use host.
  Changing age does not silently persist an automatic narrator as a parent
  choice. Explicit existing/saved choices survive age changes and reopening.
- Each page uses the chosen clip when present, otherwise the host clip for that
  tier. If neither exists, the existing explicit unavailable-page error remains.
- Long Listen has a separate Kids home entry, responsive cover grid, book detail
  chapter list, reader names, age hint, duration, Resume / Play, and Save offline.
  It loads longlisten.json from the same configured pack base as catalog.json.
  Validated metadata is cached atomically; HTTP or invalid-refresh failures fall
  back to that cache, including after a new repository instance is constructed.
- Streaming and local playback reuse KidsStoryService, its speech ExoPlayer,
  private controller, foreground-service lifecycle, and MediaSession ID
  `kids-story`. No additional session or adult playback lane was introduced.
  Long Listen never starts the picture-story background soundtrack. Existing
  picture-story music behavior across page gaps remains intact.
- Long Listen displays cover art and chapter title, with play/pause/retry and
  previous/next chapter buttons, automatic chapter advance, and service-owned
  sleep deadlines for 15/30/45/60 minutes or end of chapter.
- Resume uses the existing Room ResumePositionStore under per-book
  `kids-long:<catalog-id>` keys. Position snapshots are ordered, saved every five
  seconds and at pause/transition/stop/destruction, and survive service teardown
  through the application scope. Initial loading cannot overwrite a saved
  position with an unrelated/empty player snapshot. Explicit chapter selection
  starts at zero; Resume / Play restores the book's chapter and position.
- Save offline calls the existing AudioFileStore download/retry implementation.
  Completed chapters are retained on failure and skipped on retry. Playback
  prefers saved files without a network check; cache IDs use `kids-librivox-*`
  to avoid adult audiobook collisions. This is chapter-level retry, not a new
  byte-range downloader.
- Credits retain catalog.credits verbatim, including the Brad Harrington
  Storyteller credit, and add the LibriVox public-domain/volunteer/Archive note.
  Credits are plain text, not clickable links. Long Listen offers no outbound
  links, browser, sharing, or arbitrary media replacement; disabled Kids mode
  cannot start a Long Listen book.

## Default, Fallback, Experimental

- Default: the existing BuildConfig.KIDS_PACK_BASE release asset lane; picture
  stories play installed clips, Long Listen streams approved Archive HTTPS URLs.
- Fallback: validated cached catalogs, existing image cache, saved audio chapters,
  and host narration for individual missing selected-voice clips.
- Experimental: none added. No cloud TTS, voice-brain, HA, or adult player changes.

## Verification

Ran from native-android using the prescribed memory-safe command:

```sh
JAVA_HOME=/home/hawthornes/.local/jdks/jdk-21.0.12.1+1 \
ANDROID_HOME=/home/hawthornes/Android/Sdk \
./gradlew testDebugUnitTest assembleDebug --no-daemon --max-workers=2 \
  -Dorg.gradle.jvmargs="-Xmx1536m -XX:MaxMetaspaceSize=512m" \
  -Pkotlin.compiler.execution.strategy=in-process \
  -Pkotlin.daemon.jvmargs=-Xmx768m
```

Final unchanged-source rerun: BUILD SUCCESSFUL in 1m 1s; 98 XML suites,
524 tests, 0 failures, 0 errors, 0 skipped. gradle.properties is unchanged.
Existing bundled web assets were reused.

New regressions cover both real-catalog-v1.json and real-longlisten-v1.json:
decimal durations, all eight published Long Listen books and their chapters,
tier/defaultVoice behavior, catalog narrator labels and credit, old-catalog
compatibility, page-level host fallback, preserved explicit preferences, invalid
refresh/offline caching, and rejected offsite/local/plain-HTTP media URLs.
Service tests exercise cached-book startup, Room resume, local-file preference,
chapter next/previous/automatic progression, audio-only soundtrack isolation,
all four sleep durations, end-of-chapter stop, deadline expiry, Kids-mode gating,
the unique session ID, and the media-replacement restriction. Service fixtures
install local silent MP3s; no Archive streams are fetched in tests.
The full suite also includes the existing AudioFileStore retry regressions.

Also passed: `node scripts/test-account-transport.mjs` (fake fetch/XHR) and
`git diff --check`.

Run history: the first build assembled an earlier source snapshot while a new
test was being added, yielding one NoSuchMethodException among 524 tests.
A settled-source full run then passed. After a small music-gap preservation
change, another full run hit the previously documented Robolectric native-font
initialization flake in unchanged ShareAndroidTest: FileSystemAlreadyExistsException
in DefaultNativeRuntimeLoader.maybeCopyFonts, then a missing Paint native binding.
All round-two tests passed in that run; the unchanged full rerun passed all 524.
No unrelated test was disabled or modified to hide these failures.

APK: app/build/outputs/apk/debug/app-debug.apk, 96,745,699 bytes.
SHA-256: f450054d05581fe727ba71a36a556f8806e939c2cf021db6892c940345607857

## Device Acceptance Still Required

No audible playback, live published-asset availability, screen-off output,
notification/lock-screen behavior, or actual phone/tablet layout is claimed
verified by these JVM/Robolectric/build checks. Hermes should:

1. Install the APK, enter Kids mode, unlock parent settings, and compare ages
   7/8/10. Verify catalog labels, automatic Storyteller selection, persistence of
   an explicit dad/host choice, and the plain-text Storyteller/LibriVox credits.
2. Play a t3 story with a missing recorded frame; confirm host plays only that
   frame and the selected recorded narrator resumes on the next available frame.
3. Open Long Listen on phone/tablet, inspect chapters, stream a book, use next/
   previous, pause, reopen, and confirm that book's resume position. Start a
   second book and verify its independent position. Confirm Back/Home/PIN gating.
4. Save chapters, interrupt connectivity, retry, and confirm already completed
   chapters remain. With airplane mode on, reopen metadata and play saved audio.
5. Listen with screen off across chapter boundaries; test focus/headset removal,
   notification return to Kids mode, timed stop, and end-of-chapter stop. Confirm
   there is no background music under Long Listen and no external navigation.

Durable lesson: test published metadata, not only handwritten fixtures; keep
decimal minutes and unknown-field tolerance, and reuse the uniquely named Kids
session rather than adding a default-ID MediaSession to the same process.

## Reviewer Addendum Items 1-4 - 2026-11-02

Starting revision: `2eb023a`; worktree was clean. Read BRIEF-round2-addendum.md,
BRIEF-app.md, AGENTS.md, this receipt, git history, production code, and existing
tests before editing. Ran the exact memory-safe full Gradle command recorded
above before changes: BUILD SUCCESSFUL in 2m50s.

1. Already done; verified, not duplicated. Commit `1ee4db8` added
   RoundTwoCatalogTest. Its publishedCatalogAcceptsDecimalsUnknownFieldsAndTierDefaults
   parses real-catalog-v1.json with StoryPacks.catalog; its
   publishedLongListenParsesAndConvertsAllChapters parses real-longlisten-v1.json
   with LongListen.parse. Both production-parser tests pass in the final suite.
2. Added adultAndKidsSessionsCoexistInEitherCreationOrder in KidsStoryServiceTest
   (commit `1a03e20`). Robolectric creates PlaybackService and KidsStoryService
   together in each order, asserts distinct session IDs and `kids-story`, and
   connects real MediaControllers to both. Adult media replacement stays
   available; Kids media replacement stays blocked. The targeted service suite
   passed before implementing notification isolation.
3. Fixed the evidenced notification collision using Media3's existing
   DefaultMediaNotificationProvider, configured during KidsStoryService.onCreate.
   Kids picture stories and Long Listen now share their own notification ID
   `1002`, channel `kids_story_playback`, and resource-backed channel name
   "Kids stories and Long Listen". Adult playback retains Media3's default ID
   `1001` and default channel; no adult service or playback policy was changed.
   The new notification regression failed before the fix: expected IDs
   [1001, 1002], observed only [1001]. After the fix it checks simultaneous
   notifications, independent titles/channels, the Kids channel name, adult
   notification updates preserving Kids, and clearing Kids media without
   cancelling the adult notification. Idle local items exercise the real
   Media3 notification path without opening audio or contacting the network.
4. Already done; verified, not duplicated. Commit `1ee4db8` includes Storyteller
   in STORY_VOICES, persists/accepts it in KidsPrefs and preview handling, and
   resolves service narration through catalog.narrator. Catalog voice tier
   restrictions and tier.defaultVoice are honored; Storyteller remains t3-only.
   Existing RoundTwoCatalogTest, KidsPrefsTest, and page-clip fallback tests
   pass unchanged. No new narrator lane or duplicate fixture tests were added.

Final verification uses the exact full command above (no test filters):
BUILD SUCCESSFUL in 2m47s, including assembleDebug; 98 XML suites, 526 tests,
0 failures, 0 errors, 0 skipped. KidsStoryServiceTest has 3 passing tests;
RoundTwoCatalogTest has 4 and KidsPrefsTest has 13. Also passed
`node scripts/test-account-transport.mjs` (fake fetch/XHR) and `git diff --check`.
An initial targeted command placed --tests after assembleDebug and was rejected
before test execution; corrected by placing the filter after testDebugUnitTest.
The only subsequent test failure was the intentional pre-fix collision repro.

APK: app/build/outputs/apk/debug/app-debug.apk, 97,678,530 bytes.
SHA-256: 3ca75c3ac3707b7d56a0a3a111b26e6784469347217b366c9c06db411a0320c4
This APK was built with HEAD `1a03e20` plus the notification fix/test changes
that accompany this receipt; its embedded GIT_SHA therefore reads `1a03e20`.

All tracked changes stay under native-android/. gradle.properties and bundled
web source are unchanged; existing web assets were reused. No emulator/device,
push, deployment, Netlify action, or live content/audio request was performed.
Default/fallback content and voice lanes remain as documented earlier;
no experimental lane was introduced.

Robolectric verifies notification generation/identity/cancellation, not actual
phone lock-screen rendering or audible playback. Hermes device acceptance
remains required: start an adult audiobook, enter Kids, play a picture story
and Long Listen, inspect the separate Kids notification channel and return
route, then stop Kids and confirm no adult notification is overwritten or
cancelled. Retain the existing screen-off, PIN/back, and audio-focus checks.
