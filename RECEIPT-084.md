# RECEIPT-084 — Devotional subscriptions, polished Subscriptions screen, podcast-ready notifications, interactive widgets

Implemented `BRIEF-084.md` on branch `reader-api` across four locally committed parts (`282d857` + `a38e881`, `de3441d`, `84f4263`, `fc7770e`).

## Part A — Server: devotional subscriptions (`282d857`, plus feed follow-up `a38e881`)

### Files changed

- `netlify/lib/podsub-bible.mjs` (new):
  - Robust multi-book / multi-chapter / multi-range Scripture reference parser (`parseReferences`, `formatReferences`, `sameReference`) supporting numbered books, Roman numerals, abbreviations, comma lists, and `;` lists without guessing.
  - Built-in curated table of topic-tagged passages (`pickCuratedPassage`) with keyword matching and recent-history exclusion, plus server-side BSB passage fetcher (`fetchPassage`) via HelloAO.
- `netlify/lib/podsub-devotional.mjs` (new, extended in `a38e881`):
  - Devotional item & schedule validation (`validateDevotionals`, `validateDevotionalSchedule`) with max 5 items, `site` HTTPS checks, `topic` ≤ 120 chars (required unless `surprise: true`), and default disabled 19:00 schedule.
  - `previewDevotionalSite` and `runDevotionalItem`: parses Scripture references directly from devotion pages (including RSS/Atom feed fallback when site library pages are JS-rendered such as `todaydevotional.com`), selects topic passages via `complete()` (`json: true`) with 60-entry recent-reference exclusion and deterministic curated fallback, fetches BSB text, formats Scripture-first titles (`"<Reference> — <Title>"`), and builds source text + stored `{ devotional, kind, reference, topic? }` metadata.
- `netlify/lib/podsub.mjs` (modified):
  - Extended `defaultPodsub`, `validatePodsub`, `saveSubscription`, `subscriptionStatus`, `episodesFor`, `runSubscription`, and 5-minute `tick` with independent devotional due keys, run records, `nextDevotionalAt`, per-devotional `lastEpisode`, `todayEpisodes` coverage, and shared per-user lock / daily cap / refund rules.
- `netlify/functions/podsub.mjs` (modified):
  - Wired devotional preview (`kind: "devotional"`) and extended subscription status/save payloads.
- `netlify/lib/podsub-page.mjs` (modified):
  - Preserved leading Scripture headings when extracting devotion HTML prose.
- `netlify/lib/podcast.mjs` (modified):
  - Stored optional devotional `meta` on local job rows without forwarding it to the upstream studio.
- `native-android/app/src/main/java/com/mavislibrary/app/data/podsub/PodsubModels.kt` (modified):
  - Mirrored `DevotionalRule`, `devotionals`, `devotionalSchedule`, `nextDevotionalAt`, and `SubscriptionDocument.validated()` rules on Android.
- `tests/functions/podsub-devotional.test.mjs` (new, extended in `a38e881`):
  - 44 tests covering Scripture reference parsing/rejection (38 cases), devotion HTML heading retention, validation, topic LLM exclusions + curated fallback, site uncovering + preview, JS-rendered site feed fallback, and multi-chapter HelloAO BSB fetches.
- `tests/functions/podsub.test.mjs` (modified):
  - Added independent devotional due-key, daily coverage, metadata, quota/refund, and passage-failure skip checks (40 total checks).
- `native-android/app/src/test/java/com/mavislibrary/app/data/podsub/DevotionalValidationTest.kt` (new) & `PodsubTest.kt` (modified):
  - Android validation parity tests for devotional rules and schedules.

## Part B — Android: polished Subscriptions screen (`de3441d`)

### Files changed

- `native-android/app/src/main/java/com/mavislibrary/app/ui/lounge/PodsubSection.kt` (modified):
  - Rebuilt Subscriptions UI into three Material 3 sections (**Daily briefing**, **Devotionals**, **Delivery**) with plain-language cards showing what each rule makes, next arrival, last episode with Play button when present in the Lounge, on/off switch, overflow/long-press Edit/Remove, pick-first **Add** sheet (*Website*, *Devotional site* with one-tap "Today Daily Devotional" + live preview, *Topic* with suggestion chips + "Surprise me"), and separate briefing vs devotional time/day editors + keep-for days.
- `native-android/app/src/main/java/com/mavislibrary/app/ui/lounge/PodsubViewModel.kt` (modified):
  - Added devotional preview, add/edit/remove/toggle mutations, and separate devotional schedule editing through the existing PUT path.
- `native-android/app/src/main/java/com/mavislibrary/app/data/podsub/PodsubApi.kt` & `PodsubRepository.kt` (modified):
  - Added devotional site preview call returning newest devotion title and Scripture reference.
- `native-android/app/src/main/java/com/mavislibrary/app/data/podcast/EpisodeKind.kt` (new), `PodcastModels.kt`, `PodcastSettings.kt`, `ui/lounge/PodcastGallery.kt`, `ui/lounge/PodcastLoungeScreen.kt`, `ui/podcast/EpisodeLabels.kt` (modified):
  - Added `DevotionalMeta` preservation and `isBibleLesson()` detection from `meta` or leading Scripture reference in title so Bible lessons and devotional episodes are always labelled **"Bible lesson"** rather than `"Deep dive"`.
- `native-android/app/src/test/java/com/mavislibrary/app/ui/lounge/Subscriptions084Test.kt` (new):
  - 3 Robolectric/Compose tests covering the three sections, adding a devotional site with preview, adding a topic devotional, editing the devotional delivery schedule, and `"Bible lesson"` labelling across devotional and Scripture-titled episodes.

## Part C — Android: "podcast ready" notifications (`84f4263`)

### Files changed

- `native-android/app/src/main/java/com/mavislibrary/app/data/briefing/PodcastReady.kt` (new):
  - `PodcastReadyRunner`, `ReadyHistoryStore` (`PodcastReadyStore` backed by DataStore with 14-day outcome pruning and first-run backlog suppression), and `PodcastReadyNotifications` posting to channel `"Podcasts ready"` (`IMPORTANCE_DEFAULT`) with tap → `MainActivity.LOUNGE` and immutable **Play** action, single-notification deduplication per `(jobId, outcome)` across `"ready"`, `"failed"` (studio reason), and 6-hour `"working"`, respecting `POST_NOTIFICATIONS` and Kids mode.
- `native-android/app/src/main/java/com/mavislibrary/app/work/PodcastReadyWorker.kt` (new):
  - Periodic 15-minute connected-network `CoroutineWorker` plus expedited one-time `PodcastReadyScheduler.soon(...)` with backoff; checks pending user-created jobs and today's briefing + devotional episodes, downloads ready audio offline via `audioFiles.downloadPodcast`, notifies once, and refreshes home-screen widgets.
- `native-android/app/src/main/java/com/mavislibrary/app/work/PodcastPlaybackAction.kt` (new):
  - Non-exported `BroadcastReceiver` starting or toggling a ready podcast episode directly in `PlaybackService` via Media3 `MediaController` without launching an Activity.
- `native-android/app/src/main/AndroidManifest.xml`, `MavisApp.kt`, `data/podcast/PodcastApi.kt`, `data/podcast/PodcastRepository.kt` (modified):
  - Registered `PodcastPlaybackAction`, scheduled `PodcastReadyScheduler.start(this)` on app start and `PodcastReadyScheduler.soon(app)` across all podcast creation paths (`PodcastApi.onCreated`), and added `PodcastRepository.check(id)`.
- `native-android/app/src/test/java/com/mavislibrary/app/work/PodcastReady084Test.kt` (new):
  - 5 Robolectric tests covering ready offline download + Play action + deduplication across runner instances, 6-hour still-working + failed reason + eventual ready transitions, first-run backlog suppression + 14-day pruning, `POST_NOTIFICATIONS` denial + Kids mode gate, and download retry + playback action routing.

## Part D — Android: interactive home-screen widgets (`fc7770e`)

### Files changed

- `native-android/app/src/main/AndroidManifest.xml` (modified):
  - Registered exported `PodcastLoungeWidget` (`@xml/widget_podcast`) alongside `ContinueReadingWidget` and `VerseOfDayWidget`.
- `native-android/app/src/main/res/xml/widget_podcast.xml` (new), `widget_continue.xml` (modified), `widget_verse.xml` (modified):
  - Configured `PodcastLoungeWidget` as `horizontal|vertical` resizable, 4×2 default (`targetCellWidth="4"`, `targetCellHeight="2"`, `minWidth="250dp"`, `minHeight="110dp"`), min 3×1 (`minResizeWidth="180dp"`, `minResizeHeight="40dp"`), with `@layout/widget_interactive`, static `previewLayout`s, and widget picker descriptions on all three providers.
- `native-android/app/src/main/res/layout/widget_interactive.xml`, `widget_podcast_preview.xml`, `widget_continue_preview.xml`, `widget_verse_preview.xml`, and `res/values/widget_styles.xml` (new):
  - Shared `RemoteViews` interactive card layout (optional cover image, heading, title, determinate progress bar, detail/status line, and up to four pill action buttons) plus dedicated Android 12+ picker preview layouts.
- `native-android/app/src/main/java/com/mavislibrary/app/work/LibraryWidgets.kt` (modified):
  - Implemented `InteractiveLibraryWidget` and `PodcastLoungeWidget`:
    1. **Podcast Lounge widget**: selects the active playing episode or newest ready episode (falling back to newest in-progress job when none is ready), renders duration + `"Ready"` / `"Making… (N in progress)"` / `"Played"`, wires `▶ Play` / `⏸ Pause` directly to `PodcastPlaybackAction` (`BroadcastReceiver` → `PlaybackService`, no app launch), wires `＋ New podcast` to `"new-podcast"`, opens `MainActivity.LOUNGE` on title/card tap, and adapts cleanly when resized down to 3×1 (`isCompactHeight`).
    2. **Continue reading widget**: shows book cover + title + determinate progress bar + `% read`, opens straight into the book at its saved CFI/chapter position on tap, shows `▶ Listen` only when `bookHasListenPath` (EPUB TTS or audiobook path) is true, and cycles through the 3 most recent adult books via `→` (`BOOK_CYCLE_MODULUS = 6` so both 2-book and 3-book lists cycle smoothly without modulo stutter).
    3. **Verse of the day widget**: shows verse text + reference with **Read** (opens Bible at exact verse), **Lesson** (opens New podcast pre-filled with that KJV chapter via the same `biblePickerDraft` path as the Bible mic), **Share** (implicit `ACTION_CHOOSER` wrapping `text/plain` `ACTION_SEND` with `"<text> — <reference>"`), and **⟳** (cycles to another verse).
  - Enforced `PendingIntent.FLAG_IMMUTABLE` with unique `id * 16 + slot` request codes across all actions and neutral Kids-mode suppression across all three widgets.
- `native-android/app/src/main/java/com/mavislibrary/app/work/WidgetRoutes.kt` (new):
  - Pure route/gating helpers (`recentReadingBooks`, `bookHasListenPath`, `podcastWidgetStatus`, `selectPodcastWidgetEpisode`, `widgetDestinationAllowed`, `widgetBookReaderRoute`, `widgetLessonDraft`).
- `native-android/app/src/main/java/com/mavislibrary/app/audio/PlaybackService.kt` (modified):
  - Updated `WidgetPlayback` when playback starts/stops and sequenced `WidgetPlayback.changed(..., false)` after `resumePositionStore.save(...)` completes so `"Played"` status reflects the persisted final position immediately.
- `native-android/app/src/main/java/com/mavislibrary/app/MainActivity.kt`, `ui/MavisRoot.kt`, `data/podcast/PodcastDraft.kt`, `ui/lounge/PodcastLoungeScreen.kt` (modified):
  - Routed `"new-podcast"` directly onto the `new_podcast_sheet` source cards (`PodcastDraft(pickSource = true)`), routed `"lesson:<bibleRoute>"` into `PodcastLoungeScreen` with the pre-filled KJV chapter `PodcastDraft`, routed `"read:<bookKey>"` into the WebView reader at the saved `ReadingProgressEntity.locator` CFI (or native `ImportedReader` at its saved chapter), routed `"listen:<bookKey>"` to native LibriVox playback when available or WebView EPUB TTS (`?listen=1`), refreshed widgets on podcast creation, and suppressed widget destinations in Kids mode.
- `native-android/app/src/main/java/com/mavislibrary/app/web/WebHost.kt` & `src/views/reader.js` (modified — **why `src/views/reader.js` was kept and completed for Part D**):
  - **Why needed for Part D**: Prior to Part D, `ReadingProgressDao.put` had zero callers in `native-android` — EPUB reading positions (`epubcfi(...)`) and percentage progress lived exclusively inside WebView IndexedDB, while `BookDao.recent()` already joined `reading_progress` (`LEFT JOIN reading_progress p ON p.bookKey = b.key ORDER BY COALESCE(p.updatedAt, b.updatedAt) DESC`). Without syncing progress from `src/views/reader.js` to Room, the out-of-WebView Continue reading widget could neither display real progress bars nor open a WebView-read book at its saved position (and `?listen=1` was needed for the widget's `▶ Listen` TTS action). `src/views/reader.js` now posts `{ key, source, sourceId, title, authors, coverUrl, cfi, percent }` to `window.MavisNative?.readingProgress` on relocation/close (validated in `WebHost.NativeBridge.readingProgress` behind `isAppOrigin`, `!kidsMode`, and `!kidsBook`) and honours `?listen=1` after opening at the saved CFI.
- `native-android/app/src/main/java/com/mavislibrary/app/ui/lounge/PodsubSection.kt` & `app/src/test/java/com/mavislibrary/app/ui/lounge/Subscriptions084Test.kt` (modified):
  - Small modal sheet (`skipPartiallyExpanded = true`) and `verticalScroll` stability polish on `SubscriptionEditor` so Robolectric Compose interactions reach lower schedule controls reliably on compact viewports.
- `native-android/app/src/test/java/com/mavislibrary/app/work/InteractiveWidgets084Test.kt` (new):
  - 6 Robolectric/Compose tests verifying widget provider XML/manifest metadata (4×2 default, 3×1 min resize, `previewLayout`s, descriptions), Podcast Lounge statuses (`Ready`, `Played`, `Making… (2 in progress)`) + direct `PodcastPlaybackAction` broadcast without Activity launch + `＋ New podcast` source-card sheet + compact 3×1 resize, Continue reading cover/progress/saved-CFI route/`▶ Listen` gating/2-book & 3-book cycling, Verse of the day Read/Lesson/Share/⟳ cycling, immutable PendingIntents with unique request codes, untrusted route rejection, and Kids-mode suppression.

## Verification & Exact Test Counts

- **Bundled Android web assets**:
  - `npm run build:android` → **PASS** (`dist-android/index.html` and hashed `reader-*.js` bundle generated).
- **Node server & unit tests (`node --test`)**:
  - `node --test tests/functions/podsub-devotional.test.mjs` → **PASS: 44/44 tests** (0 failed, 0 skipped).
  - `node --test tests/functions/podsub.test.mjs` → **PASS: 1/1 suite (40/40 subscription & devotional checks)** (0 failed).
  - `node --test tests/functions/podsub-page.test.mjs` → **PASS: 1/1 suite (12/12 page/article checks)** (0 failed).
  - `node --test tests/unit/podcast-chapter.test.mjs` → **PASS: 5/5 tests** (0 failed).
  - `node --test tests/functions/briefing-title.test.mjs tests/functions/subs.test.mjs tests/unit/bible-081.test.mjs tests/unit/podcast-picker.test.mjs` → **PASS: 14/14 tests** (including **54/54** subscription & podcast checks in `subs.test.mjs`, 0 failed).
  - `npm run test:functions` → **PASS** (`functions.test.mjs` 53/53, `accounts.test.mjs` 54+40+14 checks, `groups.test.mjs` 12/12, `cors.test.mjs` 7/7, `report.test.mjs` 6/6, `vocab.test.mjs` 8/8 — 0 failed).
  - `(cd native-android && node scripts/test-account-transport.mjs)` → **PASS**.
- **Android unit tests + debug APK build**:
  ```sh
  JAVA_HOME=~/.local/jdks/jdk-21.0.12.1+1 ANDROID_HOME=~/Android/Sdk ./gradlew testDebugUnitTest assembleDebug --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx1536m
  ```
  - Result: **BUILD SUCCESSFUL — 459/459 tests passed across 87 suites** (0 failures, 0 errors, 0 skipped).
  - BRIEF-084 suite breakdown:
    - `com.mavislibrary.app.data.podsub.DevotionalValidationTest`: **2/2 passed** (`PodsubTest`: **11/11 passed**)
    - `com.mavislibrary.app.ui.lounge.Subscriptions084Test`: **3/3 passed**
    - `com.mavislibrary.app.work.PodcastReady084Test`: **5/5 passed**
    - `com.mavislibrary.app.work.InteractiveWidgets084Test`: **6/6 passed** (`LibraryWidgetsTest`: **2/2 passed**)
- **Android Lint**:
  ```sh
  JAVA_HOME=~/.local/jdks/jdk-21.0.12.1+1 ANDROID_HOME=~/Android/Sdk ./gradlew lintDebug --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx1536m
  ```
  - Result: **BUILD SUCCESSFUL** (0 errors).
- **Syntax & whitespace checks**:
  - `node --check src/views/reader.js` → **PASS**.
  - `git diff --check` → **PASS**.
- **Debug APK**:
  - Path: `native-android/app/build/outputs/apk/debug/app-debug.apk`
  - SHA-256: `0e5163bc29de0c1eda0b572de4ffa37744e8a73776f6ce0d7d5bb1213f4eabd0`

## Scope Audit

- Untouched per `BRIEF-084.md` scope limits: `src/views/bible.js`, `PodcastSourcePicker.kt`, `PodcastPickerData.kt`, MP3 export files (`PodcastMp3Export.kt`, `EpisodeDetailsSheet.kt`, `RecordSelector.kt`), account/auth code, Modal (`~/podcast-lounge-modal`), signing config, and `versionCode`/`versionName`.
- All Kotlin regexes across `BRIEF-084` verified free of unescaped `{` / `}` for Android ICU regex compatibility.
- All tests ran offline with fake HTTP / fake clocks; zero live accounts, owner decisions, or live podcast jobs were created.
- Committed locally on `reader-api` with `[skip netlify]`; not pushed and not deployed.
