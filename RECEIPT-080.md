# RECEIPT-080: Bookshelf Shelf tab + record-crate podcast selector (ships as 0.8.0)

Branch `reader-api`, Android only, base 774b5af. No `netlify/` or `tests/functions` changes, no pushes, no live accounts, no live podcast jobs. The Sites tab is unchanged.

Commits:
- `3b6b0d5` Part 1: the Shelf tab becomes a wooden bookshelf with looping rows
- `9720c03` Part 1 polish, found by rendering snapshots: well-spread cover colours and heights, long titles fit, and shelves read as one bookcase
- `79279f2` Part 2: podcasts become a record-crate selector on Listen and in the Lounge

## Part 1: Shelf
- **Drawing** (new `ui/shelf/ShelfArt.kt`). Everything is drawn in Compose; there are no image assets.
  - Each shelf bay has a warm back wall, shade from the shelf above, and wooden uprights on both sides.
  - The plank has a lit top face and a grained front edge (the grain is deterministic per row and cached with `drawWithCache`).
  - Each category label is a brass plate with an engraved serif label and two screws. It is marked as a heading for accessibility.
- **Books**:
  - Each book shows its cover via Coil `AsyncImage`, the same source as `BookTile`, drawn over a coloured cloth cover.
  - Every book has a dark spine edge on the left, cover lighting, and a drop shadow.
  - Height varies a little (128–148dp) and is fixed per key.
  - A book with no cover, or one still loading or failed, shows a cloth cover. Its colour comes from the key, it has gilt rules, and the title and author are in serif.
  - Each book is one ≥92×128dp target with a merged content description.
- **Sites as books**:
  - Cloth colour comes from the site id, the site name is in serif, and a gilt emblem shows the favicon. The favicon is `https://host/favicon.ico`, only for https sites, and the first letter shows while it loads or if it fails.
  - A red bookmark ribbon shows the unread count when it is above 0.
  - Tapping a site calls `nav.navigate("site/$id")`, the same route the Sites tab uses.
- **Looping rows** (`CircularRow` in new `ui/shelf/ShelfRows.kt`):
  - A row uses a `LazyRow` over `Int.MAX_VALUE/2` items. It starts at the middle, aligned to item 0, and maps indexes back with `floorMod`, so it loops in both directions.
  - Keys are `"$virtualIndex|$bookKey"`.
  - Rows that fit on screen (based on measured width) don't loop and stay left-aligned.
- **Row order** (`shelfRows`):
  1. My books.
  2. Continue reading.
  3. Each curated row from `curatedShelves()`.
  4. Sites, shown only when you follow at least one site.

  The empty My books shelf shows "Save books from Search to see them here" and the Scan button (`shelf_scan`).
- **Continue reading uses real data only** (`readingActivity`):
  - It shows saved books with reading time in the adult reading-stats log (`book:<key>` entries per day, written by `AdultReadingTracker` from `#/read/<key>`, day precision) or a started audiobook position in `audio_resume` (`updatedAt`). New `ResumePositionStore.bookActivity()` reads the latter.
  - Most recent comes first. With no activity, the row is skipped.
  - The `reading_progress` table is never written by the app, so it isn't used. `lastOpenedBook` only records that a book was opened, not progress, so it isn't used either.
- **ViewModel** (`ShelfViewModel`):
  - Popular rows now always load. Loading, error and Try again behave as before.
  - New optional `activity` flow and a `ShelfSites` source (`FeedStore.sources`, `ReadStateStore.read`, `FeedRepository.load` with its 15-minute cache).
  - Only enabled sites are fetched, each once. Read-state changes recount unread without fetching again.
- **Long-press** on a My books book opens the existing menu: Make a podcast, Remove from shelf. It has a long-click accessibility label.
- **"Open web shelf (collections, import, quotes)"** is now a small text button in the header (`open_web_shelf`).
- **Tags**: `shelf_grid` (scrolling container), `shelf_row_<id>` (`mine`, `continue`, curated ids, `sites`), `shelf_book_<key>`, `shelf_site_<id>`.

## Part 2: Record selector (new `ui/lounge/RecordSelector.kt`, `ui/lounge/RecordCrate.kt`)
- **Chips**: New, Continue listening, Being made, Played, All, each with a count.
  - Default is New if it has episodes, otherwise All.
  - All lists every episode, newest first, including "Couldn't be made".
- **Carousel**: a snapping `HorizontalPager`.
  - The centred record is large (64% of width, max 280dp).
  - Neighbours are scaled to 72%, faded to 50%, tilted (rotationY/rotationZ), pulled in to overlap, and z-ordered behind.
  - The records stand in a crate: a wooden front lip with a warm glow behind. Tapping a neighbour scrolls to it. The carousel doesn't loop.
- **Records**:
  - Black vinyl with fine grooves, wider track gaps, a rim, and a fixed sheen that doesn't rotate.
  - The coloured label shows `episodeHeadline` above the spindle hole and `episodeWhen` below it. Label colour comes from the first site, otherwise the episode id.
  - Being made: translucent, with `stageLabel` on the label and a progress arc.
  - Failed: cracked, greyed and dusty, with "Couldn't be made".
- **Below the carousel**:
  - Headline, then `episodeDetails` (or the stage and progress, or the error).
  - A 56dp Play button that reads Play, Resume or Pause.
  - An info button (details) and the overflow menu.
  - Tapping the centred record's vinyl plays it; tapping its label opens `EpisodeDetailsSheet`.
- **Spinning**: the record that is loaded spins (4 s per turn) while playing and stops where it is when paused. `PlaybackUiState` gains `mediaId`, which for a podcast is `saved.resumeKey`.
- **Lounge**:
  - The section lists are replaced by the selector.
  - Play still uses `upNext()` and resumes Continue episodes. It now goes through the new `playableQueue` helper; `visiblePodcasts` replaces the inline filter.
  - Download, Transcript and sources, Try again, and Delete/Cancel moved to the centred record's overflow menu.
  - The old `EpisodeRow` and `PodcastThumb` composables were removed, since nothing else used them.
- **Listen tab**:
  - The "Podcast Lounge" card is replaced by a "Podcasts" header with the summary, an "Open Podcast Lounge" text button (same text, which `PodcastNavigationTest` uses), and the same selector.
  - Listen now uses the same visibility rules as the Lounge: the episode's studio or a download, and clean-up days.
  - Play builds the same queue and URIs as the Lounge and is blocked in Kids mode.
  - The overflow has "Manage in Podcast Lounge".
  - The details sheet opens stories in the in-app reader (`ListenScreen` takes `onOpenReader`, which `MavisRoot` sets).

## Tests (fake data, no network)
Full command from the brief (`testDebugUnitTest assembleDebug`, memory-limited flags): **409 tests, 0 failed, 0 skipped. BUILD SUCCESSFUL.** `app-debug.apk` was built. `ShareAndroidTest` passed and was not touched.

New or updated tests (19 new; the base was 390):
- `ui/shelf/ShelfRowsTest` (8):
  - circular index both ways and the start index
  - rows that fit don't loop; empty rows
  - unique loop keys
  - row order and labels
  - empty states (no Continue or Sites rows)
  - reading activity from stats days plus audiobook positions, skipping podcasts, the Bible and zero-time days
  - site unread counts and favicon URL
  - stable colour and height per key
- `ui/shelf/ShelfViewModelTest`:
  - updated: popular rows now load even with saved books
  - new: Continue reading follows the activity flow; sites count unread, fetch only enabled sites, and don't refetch on reads
- `ui/shelf/ShelfScreenTest` (4, Robolectric Compose):
  - rows render, a book opens, a site opens, web shelf works
  - long-press menu: Make a podcast, Remove
  - empty plank, Scan, and popular Try again
  - a long row has the last book just before the first and scrolls back past it; a one-book row shows once
- `ui/lounge/RecordSelectorTest` (6):
  - chip filtering and default chip
  - visible/playable episode helpers
  - Play and a tap on the centred record call `onPlay` with the right episode and the `upNext()` queue
  - the label and info button open details
  - swipe and chips change the centred record, including a Continue episode followed by every new one
  - Being made and failed records can't play
  - a loaded record resumes through toggle; the overflow menu works
- The old Lounge, Listen and Shelf layouts had no UI tests tied to them, other than `PodcastNavigationTest`'s "Open Podcast Lounge" text, which is kept. Nothing was deleted.

## Not done / notes
- **Version**: the app version isn't set in the repo. `versionName` comes from the release workflow's `mavisVersionName`, so "0.8.0" has to be set when releasing.
- **Visual check**: I rendered the shelf, the crate, and the failed/being-made records with a temporary Robolectric native-graphics snapshot test and adjusted from those images. I deleted the test afterwards. That renderer skips drop shadows and may not hyphenate. I haven't looked at it on a phone or checked 60fps on a device; Hermes owns that.
- **Continue reading precision**: reader activity is per day (that is all the stats log stores), so two books read on the same day are ordered by audiobook time or saved order.
- **Favicon**: the Sites tab has no favicon loading. The shelf requests `https://<host>/favicon.ico` directly from the site, and the letter shows if that fails.
- **Listen overflow**: the menu only links to the Lounge. Delete and download stay in the Lounge because they need the studio repository there.
- **Being made "dusty"**: these records are translucent with a progress arc. The dust speckle is only drawn on failed records.
