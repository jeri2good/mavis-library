# RECEIPT-081

Implemented BRIEF-081 on `reader-api`. Implementation commit: `1106eae` (`Add Bible chapter lessons, word actions, and My Bible`).

## Files changed

- `src/views/bible.js`: chapter podcast button and bridge handoff, My Bible entry points/route, word targets and hold menu, word annotation rendering, original-language fallbacks, gesture cleanup.
- `src/views/bible-study.js`: `renderMine`, searchable annotation sections, note editing/deletion and focused verse links.
- `src/lib/bible.js`: pure chapter payload, word token/index, and annotation filtering/sorting helpers.
- `src/styles/app.css`: native word selection/callout suppression, word marker and My Bible rows.
- `tests/unit/bible-081.test.mjs`: seven pure helper tests.
- `tests/unit/bible-081-browser.html`, `tests/unit/run-bible-081-browser.mjs`: isolated Chromium/IndexedDB fixture with fake bridge and intercepted service requests.
- `RECEIPT-081.md`: this receipt, written after the implementation commit.

## Features

1. **Bible lesson podcast** appears beside chapter tools only with `MavisPodcast` and outside kids mode. It sends `kind: chapter`, `book: Bible`, a plain Scripture reference in `chapter` (including `Psalm 23`), translation-labelled numbered chapter text capped at 400000 characters, and the pre-cap `originalChars`. Bundled translations retain their text; remote translations use KJV. Success shows “Opening the Podcast Lounge…”. No Android bridge change was necessary.
2. **Word hold menu** opens after 450 ms. Movement over 10 px, scrolling, pointer cancellation/leaving, and route cleanup cancel pending holds. Release clicks and chapter swipes are suppressed after a completed hold. Word spans preserve spacing; existing Strong’s token borders/tap targets wrap the new targets when enabled. Highlight word saves `wordhl` plus verse CFI, zero-based word index, word text and translation through the existing store APIs; repeating the action removes it. Verse highlights retain their previous behaviour. Original word uses KJV token/lexicon data, while words without a direct mapping open only the relevant interlinear verse with the required explanation. Non-KJV lookups explicitly disclose KJV wording; uniquely matching English words are used, and ambiguous/different wording falls back to interlinear rather than assuming equal word positions. Verse options selects that verse and opens the existing bar.
3. **My Bible** is available at `#/bible/mine` and from both the chapter header and Study tools. Notes, Saved verses, and Highlights sections are newest first and show reference, stored verse/word text, note, colour dot and date. Search covers reference, verse/word and note. Rows navigate to the focused verse. Notes can be edited or deleted using the existing sheet interaction pattern. Empty states explain how to add each kind.

## Verification

- `node_modules` already existed; did not run `npm ci`.
- `npm run build:android`: PASS, final build transformed 155 modules and generated `dist-android/index.html`.
- `node --test tests/unit/bible-081.test.mjs tests/unit/podcast-chapter.test.mjs`: **12 passed**, 0 failed (7 new Bible helpers + 5 existing chapter extraction tests).
- With `npm run dev -- --host 127.0.0.1 --port 5175 --strictPort`, ran `node tests/unit/run-bible-081-browser.mjs`: **25 browser assertions passed**. Covers payloads, remote KJV fallback, kids/bridge hiding, hold movement/scroll cancellation, Strong’s on/off, highlight persistence/removal, ordinary verse taps/highlights, original/fallback sheets, non-KJV disclosure, My Bible sections/search/edit/delete and focused links. Uses local Bible data, fresh browser storage and fake service responses; no live jobs. Test server stopped afterward.
- In `native-android`, `node scripts/test-account-transport.mjs`: PASS (script reports one aggregate pass covering fake fetch/XHR, headers, origin isolation, sign-out and subscriber gating).
- Required Android command:
  ```sh
  JAVA_HOME=~/.local/jdks/jdk-21.0.12.1+1 ANDROID_HOME=~/Android/Sdk ./gradlew testDebugUnitTest assembleDebug --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx1536m
  ```
  Final result: **BUILD SUCCESSFUL**, **418 tests across 78 suites**, 0 failures/errors/skips. Initial full run also passed. An intervening asset-refresh rerun failed two unchanged `ShareAndroidTest` cases with Robolectric native font-loading `FileSystemAlreadyExistsException` and consequent `UnsatisfiedLinkError`; an unchanged full-command retry passed. No Android source or test workaround was introduced. Logs: `/tmp/brief081-gradle.log`, `/tmp/brief081-gradle-final.log` (failed rerun), `/tmp/brief081-gradle-retry.log` (final pass); failure XML preserved at `/tmp/brief081-share-failure.xml`.
- `node --check` passed for all changed JavaScript modules and test scripts; `git diff --check` passed.
- Final APK: `native-android/app/build/outputs/apk/debug/app-debug.apk`.
  SHA-256: `c4c15a721309b33826f526c033f5b76d4ca75ed7bcfbd514c4c83747f5788769`.

## Scope and remaining work

No requested implementation work remains. No edits to store implementation, native source, Netlify/server, podcast studio, Shelf, Podcast Lounge UI, signing configuration, versionCode or versionName. No live accounts, owner decisions or podcast jobs were created. Device/live podcast acceptance remains with Hermes as instructed; browser gesture checks use synthetic pointer events, not physical-device touch. Pre-existing untracked `node_modules` was left untouched. Original source backups are in `/tmp/brief081-backup`; revert the implementation commit to roll back code.
