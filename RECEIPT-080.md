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

---

# BRIEF-080b follow-up (Hermes review of /tmp/shelf.png and /tmp/records.png)

Commits:
- `2284820`: Shelf, items 1 to 4 and 7.
- `294b43f`: Records, items 1, 5 and 6.

Full build command from the brief (`testDebugUnitTest assembleDebug`, memory-limited flags): **415 tests, 0 failed, 0 skipped. BUILD SUCCESSFUL.** `app-debug.apk` was built. That is 409 before plus 6 new tests. `ShareAndroidTest` was not touched and passed on the first run.

Both PNGs were re-rendered to `/tmp/shelf.png` and `/tmp/records.png`. I used a temporary Robolectric native-graphics test inside the app's real `MavisTheme(Look.MIDNIGHT)` and `Surface`, then deleted it; it is not committed.

**What I found first.** I re-rendered the code at `d300bdf` before changing anything:
- The original PNGs were made by a harness that didn't wrap the screens in `MavisTheme`, and it had its own fake data.
- Items 1 and 5 are caused by that harness, not by the app. Inside the real theme the headers were already cream, and the same strings showed no stray "a".
- Item 3 was already in the code but had never been checked in a render.
- Item 2 was partly fixed by `9720c03`, which spread the hues so colours were no longer all the same purple. The brief asks for a leather palette, so I made that change anyway.

1. **Dark headers.** The black text came from the old render having no theme: `Text` fell back to black `LocalContentColor`. In the app, `Scaffold` provides `onBackground`. "Your shelf" (ShelfScreen) and the record headline (RecordSelector) now set `MaterialTheme.colorScheme.onSurface` explicitly, so they don't depend on a parent. Up next rows also use onSurface and onSurfaceVariant.
2. **Leather tones.** New `Leather` enum with 10 tones: oxblood, navy, forest, mustard, teal, brown, plum, charcoal, tan and slate. `leatherOf(key)` picks a tone with the well-spread hash. `ClothCover` uses it, so books without covers and site books both get it. Test: ≥8 tones, the same key always gets the same tone, gutenberg:1–12 use ≥6 tones with never three alike in a row, sites vary, and all 10 tones are used.
3. **Covers.** `ShelfBook` already draws the real cover (Coil `AsyncImage`, Crop) over the cloth. The spine edge, lighting and drop shadow are drawn on top of it.
   - Verified in the new render: "The Odyssey" uses a local fake cover image (`file://` PNG) and stands on the plank with its spine edge.
   - Why the cover never showed before: Robolectric can't run Android's `ImageDecoder`, so the harness's Coil loader uses `imageDecoderEnabled(false)` (BitmapFactory). That was a test-only problem; the app code is unchanged.
4. **Mid-word breaks.** New pure `fitText()` in ShelfArt, drawn by `FitText` using `rememberTextMeasurer`.
   - It tries 14→9sp for titles and 10→8sp for authors, wrapping only at spaces. It uses the largest size where every word fits on one line and the result fits in max lines (4 for titles, 2 for authors, 3 for site names).
   - Only when the smallest size still doesn't fit does it cut the word or the last line with "…".
   - The lines are drawn exactly as measured (`softWrap = false`), so Android can't break them again. The old `Hyphens.Auto` guess is gone.
   - In the render: "Frankenstein" is on one line, and "Moby / Dick" and "Pride and / Prejudice" wrap at spaces. "Mary Wollstonecraft S…" is the last-resort ellipsis: the author needs 3 lines at 8sp and only 2 are allowed.
   - Tests use a fake fixed-width measure: shrink-to-fit sizes, whole words only, and last-resort ellipsis for one long word and for too many lines.
5. **Stray "a".** Not reproducible.
   - `episodeHeadline`, `episodeSources` and `episodeDetails` never append anything, and the server's `briefingTitle` only adds " + N more" or "…".
   - Rendering exactly "Thu, Oct 29 · Google Expands AI Mode Ads + 2 more" with sites ["Future Tools"], themed and unthemed, shows no "a".
   - The old harness is deleted, so I can't prove its cause. Most likely it added the fake episode id ("a") to its fake title and site strings to keep them unique.
   - Regression test `briefingHeadlineAndSubtitleHaveNoStrayCharacters` pins the exact headline ("Google Expands AI Mode Ads + 2 more") and subtitle ("Today 6:00 AM · Future Tools · 10 min · Deep dive"), both from the helpers and as rendered in `record_headline` and `record_subtitle`.
6. **Empty bottom half.** There is now an "Up next" heading and compact rows under the Play button (`record_up_next`, `record_next_<id>`).
   - Each row has a small record in the episode's label colour, the headline on one line, then date · length. Being-made episodes show the stage instead of the length, and failed ones show "Couldn't be made".
   - Each row is a ≥52dp target with a "Select" click label. Tapping a row animates the crate to that record.
   - Order: the episodes after the centred one first, then those before it (`upNextRows`). The list follows the selected chip.
   - Tests: the helper's order and indexes; tapping n2 makes it the centred record and removes it from the list; the stage and length are shown.
7. **Authors.** `ShelfBook` uses each book's own `authorLine` (`authors.joinToString(", ")`) for both the cover and the content description. "Jane Austen" everywhere was only the old harness's fake data. The new render has real per-book authors. New test `eachBookShowsItsOwnAuthors` covers single and multiple authors.

Notes:
- Two different keys can share a tone (10 tones). For example, Moby Dick and Frankenstein are both plum in the render. That is expected with a fixed palette.
- The render still can't show drop shadows (a renderer limit). I haven't checked it on a phone; Hermes owns that.
