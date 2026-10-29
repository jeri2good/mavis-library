# RECEIPT-079: Briefing episodes show what they cover, and when

Branch `reader-api`, Android only. No `netlify/`, `tests/functions`, pushes, live accounts, or live podcast jobs.

## What changed
- **Model** (`data/podcast/PodcastModels.kt`): new `@Serializable PodcastStory(title, site = "", link: String? = null)`. `PodcastJob` gains `sites: List<String> = emptyList()` and `stories: List<PodcastStory> = emptyList()`. Old JSON and saved podcasts still decode.
- **Keeps the story list** (`data/podcast/PodcastSettings.kt`): the single-job status read (`/api/podcast/:id`, used by polling) doesn't include `sites`/`stories`. When a save arrives with both empty, it now keeps the list already saved, so a poll can't wipe the stories that `/list` delivered.
- **Shared helpers** (new `ui/podcast/EpisodeLabels.kt`):
  - `episodeHeadline`: removes the `"Thu, Oct 29 · "` prefix. For an old `"Daily briefing"` with stories it builds a title from the first 2 stories plus `+ N more`. Anything else is returned unchanged.
  - `episodeWhen`: `Today 6:44 AM` / `Yesterday 6:44 AM` / `Thu, Oct 29`. Uses the device time zone and returns empty if `createdAt` is blank or invalid.
  - `episodeSources`: names from `sites`, or the distinct story sites if there are none. Shows at most 3, then `+N`.
  - `episodeSubtitle`: when · sources · `N min`, skipping any empty part.
- **Episode details sheet** (new `ui/podcast/EpisodeDetailsSheet.kt`): headline, subtitle, "In this episode" numbered list (story title, site in small text) and a big Play button.
  - Tapping a story with an `https` link opens it in the existing in-app reader route (`openReader` → `ReaderScreen`). If no reader callback is passed in, the link opens outside the app.
  - Old episodes show "Story list not saved for this episode."
- **Up next strip** (`ui/sites/SitesScreen.kt`):
  - Cards are 272dp wide and show the headline (2 lines max) with the subtitle under it.
  - Tapping the card body opens the details sheet; the Play button still plays directly.
  - Existing tags `up_next_<id>` and `play_up_next_<id>` are kept; added `up_next_headline_<id>` and `up_next_subtitle_<id>`.
  - `SitesScreen` takes a new `onOpenReader` argument, which `MavisRoot` sets to `openReader`.
- **Podcast Lounge**:
  - Gallery rows (`EpisodeRow`) show the headline. The details line from `PodcastGallery.episodeDetails` is now `when · sources · length/left · style · Downloaded`.
  - Tapping a ready row now opens the details sheet; the Play icon still plays. **This changes behaviour: before, tapping the row started playback.**
  - The subscription "Today" rows (`PodsubSection`) show headline plus subtitle (or the stage label while the episode is being made), and a row tap opens the same sheet.
  - `PodcastLoungeScreen` takes `onOpenReader`, set by `MavisRoot`.

## Tests
Full command from the brief (`testDebugUnitTest assembleDebug`, memory-limited flags): **390 tests, 0 failed, 0 skipped. BUILD SUCCESSFUL.** `app-debug.apk` was built.

The first full run had 2 failures in the known-flaky `ShareAndroidTest`; it passed when rerun alone, and the final full run was clean. It was not edited.

New or updated tests (all fake data, no network):
- `ui/podcast/EpisodeLabelsTest` (5 tests):
  - new dated title
  - old "Daily briefing" with 4 stories and with 1 story
  - old briefing with no stories, and a non-briefing title
  - today / yesterday / older dates, including a UTC→New York date rollover
  - blank and invalid `createdAt`
  - sources with more than 3 sites, and sources taken from stories
  - subtitle combinations
- `data/podcast/PodcastStoriesJsonTest` (2): decode with `sites`/`stories` (with and without link or site); old job and old `SavedPodcast` JSON without them; a round trip.
- `data/podcast/PodcastSettingsTest.statusUpdatesWithoutStoriesKeepTheSavedStoryList` (1).
- `ui/lounge/PodcastGalleryTest.plainLanguageStatusAndDetails`: updated for the new date format, plus a briefing with sources.
- `ui/sites/UpNextStripTest` (3, Robolectric Compose):
  - the card shows headline and subtitle, and Play plays without opening the sheet
  - a card tap opens the sheet; tapping a story opens the reader with the right `ReaderTarget` and doesn't play
  - an old episode shows "Story list not saved…" and the sheet's Play plays it

## Not done / notes
- Story taps from the sheet open the reader with no `siteId`/`storyId`, so they don't mark the article read in the Sites unread counts. The server's story objects carry no ids.
- The Lounge's delete confirmation and "Transcript and sources" dialog still show the raw title.
- No live check on a device. Hermes owns live acceptance.
