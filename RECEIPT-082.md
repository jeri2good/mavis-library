# BRIEF-082 receipt — implementation awaiting scope clarification

Status: **PARTIAL, not committed or pushed.** The user required committing when done. The remaining integration needs one argument in `ui/MavisRoot.kt`, which is outside the brief's explicitly listed scope. Approval was requested asynchronously; no answer has arrived. Reviewable proposed patch: `/tmp/brief082-webview-wiring.patch`. It passes `pickerWebView = webHost?.webView` to the Lounge. Until applied, web annotations and downloaded web-book chapters use the intentional unavailable-WebView fallback.

## Changes

- `native-android/app/src/main/java/com/mavislibrary/app/ui/lounge/PodcastLoungeScreen.kt`: five type cards, selected-source confirmation, Change, Edit details, existing podcast options and prefilled shortcut handling.
- `ui/lounge/PodcastSourcePicker.kt`: full-height Material 3 picker sheets with optional search, Bible annotation groups and canonical chapter grid, library/import selection, chapter selection, followed-site articles, paste-link fallback, and topic suggestions.
- `ui/lounge/PodcastPickerData.kt`: KJV payload port, robust annotation parsing/cap, grouping/sorting, bounded shared-WebView promise query with cleanup.
- `data/feeds/FeedRepository.kt`: small read-only cached-headline helper.
- `src/lib/podcast-picker.js`, `src/main.js`: read-only annotation whitelist and detached EPUB chapter query exposed on MavisApp; reuses the reader's `extractPodcastChapter` without changing the reader UI.
- `PodcastPickerTest.kt`, `PodcastSourcePickerTest.kt`, test resources `picker/John.json` and `picker/Ps.json`: Kotlin/Compose verification.
- `tests/unit/podcast-picker.test.mjs`, `tests/e2e/podcast-shortcuts.html`: query tests, current-JS parity fixture verification, real Chromium picker/reader parity checks.

## Sources

- Bible: bundled `web/bible/index.json` and KJV JSON assets, stripped Strong's tags and numbered verses. Annotation section reads `listAllAnnotations()` through the shared WebView query, restricted to allowed fields and 2,000 rows, newest chapter groups first.
- Books: native shelf repository and EpubImports, with native reading_progress records sorted first. Gutenberg uses PodcastDraft.book; imports retain chooseImported.
- Chapters: downloaded web EPUBs use the same extraction helper as the reader, with CFI-based marked and last-read detection. Native imports use NativeEpubText and the same chapter labels as ImportedReader. Native imports currently do not persist progress or annotations; no reader changes were made.
- Sites/articles: FeedStore, FeedRepository and ReadStateStore; date and unread indication; existing article draft.
- Topics: existing typed topic draft, plus already-cached feed headlines without a topic-triggered refresh.

## Verification

- `npm run build:android`: PASS, `/tmp/brief082-web.log`.
- Exact requested Gradle command: `JAVA_HOME=~/.local/jdks/jdk-21.0.12.1+1 ANDROID_HOME=~/Android/Sdk ./gradlew testDebugUnitTest assembleDebug --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx1536m`: PASS; **429 tests, 80 suites, zero failures/errors/skips**; `/tmp/brief082-gradle-final.log`.
- Initial Gradle run: 425 tests, two unchanged ShareAndroidTest failures (ZIP filesystem initialization and Robolectric native loading); the subsequent full run passed.
- Node picker/chapter tests: **10/10 PASS**, `/tmp/brief082-node.log`.
- Real Chromium shortcut/picker fixture: **19 checks PASS**, `/tmp/brief082-browser.log`; external requests intercepted, bridge messages captured, no jobs created. Initial new test needed to wait for the reader's debounced position save; corrected fixture passed.
- Fake-fetch/XHR account transport check: PASS, `/tmp/brief082-transport.log`.
- `node --check` on changed JS and `git diff --check`: PASS.

## Remaining

1. Obtain scope clarification for the one-line shared-WebView argument, apply it if authorized, and rerun the bounded build.
2. Commit implementation with message ending `[skip netlify]`, then update this receipt with commit evidence.
3. Device acceptance, TalkBack, light/dark appearance, and measured <300 ms cached sheet opening remain for Hermes; no device performance claim is made.

No server, netlify, studio, Shelf, Bible reader UI, record selector, signing or version edits. No live accounts, owner decisions or podcast jobs. No push. Pre-existing untracked `node_modules` remains untouched.

## Hermes completion (Codex hit its usage limit before committing)

- Applied Codex's proposed one-line wiring (`pickerWebView`) and fixed device-only defects found on the emulator:
  1. Annotations never loaded: the shared WebView was not booted when the Lounge opened first → `WebHost.warm()` + wait for `MavisApp.annotationsSummary`.
  2. Picking a Bible chapter threw `PatternSyntaxException` on Android (unescaped `}` in `Regex("\\{[HG0-9,]*}")`, accepted by desktop JVM) → escaped.
  3. Book/Chapter pickers empty: web-reader books live in IndexedDB, not the native `books` table → `MavisApp.booksSummary()` + `parseWebBooks` (Robolectric test, 2 cases).
  4. Badges showed raw kinds (`wordhl`) → friendly labels.
- Emulator acceptance (no podcast created): Bible (John 1:1 word highlight → "Bible - John 1 · 51 verses"), Book (Pride and Prejudice under Continue reading), Chapter (P&P chapter list → "Chapter III · 9,676 characters"), Website (6 sites → Future Tools latest articles with Unread).
- Tests: targeted picker suites 13/13 pass (PodcastPickerTest 8, PodcastSourcePickerTest 3, PodcastWebBooksTest 2).
