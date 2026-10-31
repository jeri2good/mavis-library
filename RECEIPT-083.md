# RECEIPT-083 — Save and share podcast episodes as MP3

Implemented `BRIEF-083.md` on branch `reader-api` (native Android only).

## Files changed

- `native-android/app/src/main/java/com/mavislibrary/app/data/podcast/PodcastMp3Export.kt` (new):
  - `sanitizePodcastTitle` and `podcastMp3FileName`: NFC-normalised filename sanitiser converting path/colon separators (`/`, `\`, `:`) to `-`, stripping control/FAT-illegal characters, `..` traversal sequences, and emoji/pictographs/ZWJ/variation selectors/surrogates, enforcing a grapheme-safe 120-character cap (including `.mp3` and duplicate suffixes), falling back to `"Mavis Podcast"`, and deduplicating case-insensitively with `" (2)"`, `" (3)"`, etc.
  - `PodcastAudioSource` and `PodcastApi.asAudioSource()`: streams raw studio MP3 bytes via `PodcastApi.audio(api.audioUrl(id))` (resolving a fresh signed HTTPS studio URL through `/api/podcast/audio/{id}` without forwarding account or owner credentials to the audio host), verifying `audio/mpeg` / `audio/mp3` MIME, content length, 250 MB cap, cancellation, and `(bytesWritten, totalBytes)` progress callbacks.
  - `savePodcastMp3`, `buildOpenSavedMp3Intent`, and `openSavedPodcastMp3`: copies `saved.localFile` (or `AudioFileStore.podcastLocal`) byte-for-byte when already downloaded locally, or streams via `PodcastAudioSource`, writing raw studio MP3 bytes (preserving embedded ID3 title/artist `"Mavis Podcast Lounge"` and intro/outro music with no re-encoding) into public `Download/Mavis Podcasts/` via `MediaStore.Downloads` (`IS_PENDING` 1 → 0, deleting pending rows on failure/cancellation; API 26–28 fallback kept behind `@RequiresApi(Q)` split for `minSdk = 26`). `"Open"` launches `Intent.ACTION_VIEW` with `audio/mpeg` and `FLAG_GRANT_READ_URI_PERMISSION`, falling back to `DownloadManager.ACTION_VIEW_DOWNLOADS`.
  - `preparePodcastShare`, `buildPodcastSendIntent`, `buildPodcastShareChooser`, and `sharePodcastMp3`: exposes existing offline downloads under `filesDir/audio/podcasts/` directly through `FileProvider` without copying, or writes an atomic cache copy under `cacheDir/podcast_share/<id>/<safeTitle>.mp3` (expiring >24h cache entries), then launches `Intent.createChooser` wrapping `Intent.ACTION_SEND` (`type = "audio/mpeg"`, `EXTRA_STREAM = contentUri`, `EXTRA_SUBJECT = title`, `EXTRA_TEXT = "<title> — from Mavis Library"`, `ClipData` carrying `audio/mpeg` + URI, and `FLAG_GRANT_READ_URI_PERMISSION`).
- `native-android/app/src/main/res/xml/share_paths.xml` (modified):
  - Preserved `<cache-path name="verse_cards" path="share/" />` and added `<cache-path name="podcast_share" path="podcast_share/" />` plus `<files-path name="podcast_downloads" path="audio/podcasts/" />` for the existing `${applicationId}.share` `FileProvider` (`AndroidManifest.xml` already declared `FileProvider` with `exported="false"` and `grantUriPermissions="true"`, so `AndroidManifest.xml` required no edits).
- `native-android/app/src/main/java/com/mavislibrary/app/ui/podcast/EpisodeDetailsSheet.kt` (modified):
  - Added optional `onSaveMp3`, `onShare`, and `actionsEnabled` parameters (plus an internal `MavisApp.container` fallback with inline progress and snackbar so existing callers outside `ui/lounge/` remain untouched) and rendered **Save MP3** (`episode_details_save_mp3`) and **Share** (`episode_details_share`) buttons on every finished episode (`job.status == "ready"`).
- `native-android/app/src/main/java/com/mavislibrary/app/ui/lounge/RecordSelector.kt` (modified):
  - Added long-press (`combinedClickable`) on carousel records (`record_<id>` and `record_label_<id>`) opening the episode `DropdownMenu` alongside the centered `record_menu` overflow button.
  - Added both long-press (`combinedClickable`) and a trailing overflow `IconButton` (`record_next_menu_<id>`) on `UpNextRow` (`record_next_<id>`).
- `native-android/app/src/main/java/com/mavislibrary/app/ui/lounge/PodcastLoungeScreen.kt` (modified):
  - Added **Save MP3** and **Share** items to the record / Up next `DropdownMenu` whenever `job.status == "ready"`, wired `onSaveMp3` and `onShare` into `EpisodeDetailsSheet` (for both crate records and Today subscription episodes), added linear/indeterminate export progress (`podcast_export_progress`), and added a `SnackbarHost` (`podcast_lounge_snackbar`) displaying `"Saved to Downloads/Mavis Podcasts"` with an `"Open"` action.
- `native-android/app/src/test/java/com/mavislibrary/app/data/podcast/PodcastMp3ExportTest.kt` (new):
  - 8 Robolectric/unit tests covering filename sanitising (slashes, FAT-illegal characters, `..` traversal, emoji/ZWJ/surrogates, 120-character cap including `.mp3` and duplicate suffixes, case-insensitive `" (2)"` / `" (3)"` deduplication), local-file vs signed-URL raw byte preservation, zero credential leakage to the signed audio host, pending MediaStore row cleanup on truncated streams, `FileProvider` MIME (`audio/mpeg`), `OpenableColumns.DISPLAY_NAME`, `ClipData`, `FLAG_GRANT_READ_URI_PERMISSION`, private-file isolation (`imports/secret.epub` rejected), and Kids mode / unfinished episode rejection.
- `native-android/app/src/test/java/com/mavislibrary/app/ui/podcast/EpisodeDetailsSheetTest.kt` (new):
  - 3 Compose/Robolectric tests verifying **Save MP3** and **Share** on finished episodes, hiding on unfinished/failed episodes, and end-to-end `PodcastLoungeScreen` Save MP3 → `"Saved to Downloads/Mavis Podcasts"` snackbar → `"Open"` (`ACTION_VIEW`, `audio/mpeg`) plus Up next overflow → **Share** (`ACTION_CHOOSER` / `ACTION_SEND`, `audio/mpeg`, subject, text, `content://` stream URI).
- `native-android/app/src/test/java/com/mavislibrary/app/ui/lounge/RecordSelectorTest.kt` (modified):
  - Added `longPressAndOverflowMenusOfferSaveMp3AndShareOnReadyEpisodesOnly` verifying **Save MP3** and **Share** via record long-press, Up next overflow button, and Up next row long-press, and verifying omission on unfinished episodes.
- `RECEIPT-083.md` (new):
  - This implementation and verification receipt.

## Verification

- Bundled Android web assets: `npm run build:android` → **PASS** (`dist-android/index.html` generated).
- Android WebView account transport check: `(cd native-android && node scripts/test-account-transport.mjs)` → **PASS**.
- Podcast chapter unit tests: `node --test tests/unit/podcast-chapter.test.mjs` → **PASS** (5/5).
- Required Android Gradle test + debug APK build:
  ```sh
  JAVA_HOME=~/.local/jdks/jdk-21.0.12.1+1 ANDROID_HOME=~/Android/Sdk ./gradlew testDebugUnitTest assembleDebug --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx1536m
  ```
  Result: **BUILD SUCCESSFUL** — **443 tests across 83 suites**, 0 failures, 0 errors, 0 skipped.
- Android Lint check:
  ```sh
  JAVA_HOME=~/.local/jdks/jdk-21.0.12.1+1 ANDROID_HOME=~/Android/Sdk ./gradlew lintDebug --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx1536m
  ```
  Result: **BUILD SUCCESSFUL** (0 errors).
- `git diff --check`: **PASS**.
- Debug APK: `native-android/app/build/outputs/apk/debug/app-debug.apk`
  - SHA-256: `ac6450a8d903a52ec2367c7c1421e52b36a7225ed0fa24f66e71e644c42a12f4`

## Scope audit

- Untouched: `ui/lounge/PodcastSourcePicker.kt`, `ui/lounge/PodcastPickerData.kt`, `src/`, `netlify/`, server code, podcast studio, Bible reader, Shelf, signing config, `versionCode`/`versionName`, and `AndroidManifest.xml`.
- All tests used fake HTTP / in-process providers; no live accounts, owner decisions, or live podcast jobs were created.
- Committed locally on `reader-api` with `[skip netlify]`; not pushed. Pre-existing untracked `node_modules` left untouched.
