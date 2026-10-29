# PROGRESS-080 (bookshelf + record selector, ships as 0.8.0)

Branch `reader-api`, base 774b5af. Android only.

## Plan / findings
- Continue reading data: real sources only. `audio_resume` rows (non-podcast, position > 0, `updatedAt`) and the adult reading-stats log (`book:<Book.key>` per day, written by AdultReadingTracker from `#/read/<key>`). `reading_progress` table exists but is never written, so it is not used.
- Sites row: `FeedStore.sources` + `ReadStateStore.read` + `FeedRepository.load` (15-min process cache) for unread counts. Tap → `nav.navigate("site/$id")`, same as the Sites tab.
- Records: `PlaybackUiState` gets `mediaId` (podcast media id = `saved.resumeKey`) so the playing record spins.

## Status
- [x] Part 1 shelf (CircularRow helper, shelfRows builder, ShelfScreen redraw, VM changes, tests) — commit 1
- [ ] Part 2 records (RecordCrate logic, RecordSelector UI, Lounge + Listen, tests) — commit 2
- [ ] Full build + RECEIPT-080.md
- Part 1 verified: full testDebugUnitTest 403 tests, 0 failed (before Part 2).
