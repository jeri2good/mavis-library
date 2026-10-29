# RECEIPT — reader-app (Android builder)

Branch `reader-app`, base `f710873`. Not pushed. Devin took over from Codex after Feature 2.

## Commits
| Commit | Feature | Builder |
|---|---|---|
| 067dd6f | 1. `source` on SiteRule/FeedSource (default `"feed"`, old data decodes) | Codex |
| 127701b | 2. Add-site fallback to `/api/podsub/preview` + "No feed here — Mavis will watch this page" card (3 headlines) | Codex |
| ab8a47d | 3. Article lists for `source="page"` sites via `/api/podsub/page` (Codex's uncommitted edits, reviewed + verified) | Codex → Devin |
| 6595edc | 4. Native Reader + WebView fallback | Devin |
| e2cec8e | 5. Read/unread tracking, badges, Mark all read | Devin |
| d762735 | 6. "Up next" strip + "All new · N" merged unread list | Devin |

## Files (native-android/app/src, vs f710873)
Main:
- `MavisApp.kt`: `readerApi()`, `readerPrefs`, `readState`; FeedRepository gets the reader service
- `data/feeds/FeedRepository.kt`: page sources load from `/api/podsub/page`; cache keyed by `source:url`
- `data/feeds/FeedStore.kt`, `data/podsub/PodsubModels.kt`, `data/podsub/PodsubRepository.kt`: `source` field
- `data/reader/ReaderApi.kt`: preview/page/article calls. All go through `PodcastApi.request`, the same client, adult gate and owner/bearer headers
- `data/reader/ReaderStore.kt` (new): `ReaderPrefs` (text size S/M/L/XL, theme Light/Sepia/Dark) and `ReadStateStore` (keeps the newest 500 ids per site), both in DataStore
- `ui/reader/ReaderScreen.kt`, `ui/reader/ReaderViewModel.kt` (new)
- `ui/sites/SitesScreen.kt`, `ui/sites/SitesViewModel.kt`: unread badge, dimmed read rows, Mark all read (tile ⋮ and site-page ⋮), Up next strip, `AllNewScreen`
- `ui/MavisRoot.kt`: routes `reader` and `sites/new` (kids gate via `loungeRouteAllowed`); `playEpisodes` uses the same save-to-library + `PlaybackController.playPodcasts` path as the Lounge's PodsubSection

Tests: `data/reader/{SourceCompatibilityTest,PreviewTest,PageTest,ReaderTest,ReadStateTest}.kt`; small fixes in `PodsubTest.kt` and `PodcastNavigationTest.kt` (Codex).

## Build / tests (memory-safe command from the brief, no emulator, gradle.properties untouched)
- Final run: BUILD SUCCESSFUL. Results from `app/build/test-results/testDebugUnitTest`: **71 suites, 369 tests, 0 failures, 0 errors, 0 skipped**.
  - New: SourceCompatibilityTest 3, PreviewTest 3, PageTest 1, ReaderTest 3, ReadStateTest 2.
- APK: `native-android/app/build/outputs/apk/debug/app-debug.apk` (43,132,864 bytes, built 09:02).
- Flake seen: in 2 of 6 runs, `ShareAndroidTest` (pre-existing, Robolectric native runtime, `FileSystemAlreadyExistsException` / `UnsatisfiedLinkError` in `Paint`) failed, then passed on an unchanged rerun. It looks like a font-copy race between the two test forks. Not caused by this work, but it happens and isn't fixed.

## Unverified
- Nothing has been run on a device or emulator. All the Compose UI is compile-checked only: the Reader layout, Aa sheet, Up next strip, All new screen, badges, dimming and menus.
- All server endpoints (`/preview`, `/page`, `/article`, `/today`) were only exercised with fake HTTP against the contract. They have not been tested against the parallel server branch.
- Share intent, Coil hero image loading and real audio playback from Up next were not exercised.
- The Up next strip loads `today()` once when the Sites screen's ViewModel is created. It does not poll.
- The tile badge now shows "N unread" (read-state based) instead of the old "N new" (seen-since based). `newCount()` is kept but no longer shown on tiles; the "New" pill on story rows is unchanged.
- An untracked `node_modules/` exists in the worktree root. I didn't create it and didn't touch it.
