# Mavis Library Sites Tab Redesign Receipt (Magazine Shelf)

Repo: `native-android`
Constraint Checklist & Compliance:
- Work completed inside `native-android`: Yes
- Do NOT push: Respected (no git push)
- No live podcast jobs created in tests or local verification: Respected (fake HTTP and deterministic stubs used)
- Verified unit tests for touched modules pass: Yes (`com.mavislibrary.app.ui.sites.*`, `com.mavislibrary.app.data.feeds.*`, `com.mavislibrary.app.data.podsub.*`, `com.mavislibrary.app.data.podcast.*`)
- `assembleDebug`: Built successfully

---

## Changes Delivered

### 1. Magazine Shelf Grid (`SitesScreen.kt`)
- Replaced the clunky single-column search-to-articles layout with an adaptive magazine shelf grid (`LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 145.dp))`).
- **Big + Add site card first**: Prominently placed as the very first card in the grid (`add_site_card`), featuring an add icon, bold title, and subtitle to follow feeds.
- **Magazine rack tiles**: Each followed site displays a styled cover tile with its capital initial letter, subtle themed surface colors, unread badge pill (e.g. `1 new`, `3 new`), site name, and domain hostname.
- **Overflow menu**: Each site tile includes a 3-dot overflow menu with options:
  - **Top N**: Off, Top 1, Top 2, Top 3, Top 5 (with checkmarks for active selection).
  - **Delivery**: Briefing vs Separate.
  - **Unfollow**: Directly unfollows and removes site from local storage and Podsub sync.

### 2. Immediate Articles View & Multi-Select Combined Podcast (`SitesScreen.kt`, `SiteScreen`)
- Tapping a followed site opens its articles immediately.
- Displays article title, snippet/summary, published date/time, a **Read** button (opens reader), and a **Make podcast** button.
- **Multi-select articles**: Checkbox selection for articles, selection bar showing count of selected items, "Select all", "Clear", and a **Make combined podcast** button.
- **Single request for combined podcast**: Generates aggregated chapter text using `Briefing.text` and issues `POST /api/podcast/create` exactly once.

### 3. Instant Feedback & Double-Tap Prevention (`SitesViewModel.kt`)
- **Instant feedback**: Displays `"Queued #N (~M min)"` directly on the button/status when queued (e.g. `Queued #1 (~5 min)`, `Queued #2 (~10 min)`).
- **Double-tap protection**: Disables both individual and combined podcast buttons immediately while request is in flight (`inFlightRequests`) and remains disabled once queued.

### 4. Data Layer & Tests (`FeedStore.kt`, `PodcastModels.kt`, `SitesViewModelTest.kt`)
- `FeedSource` supports `topCount` persistence with backward-compatible defaults.
- `PodcastCreated` and `PodcastJob` support optional `queuePosition` and `etaMin` attributes.
- Comprehensive unit tests in `SitesViewModelTest`:
  - `followLoadsPostsCountsNewKeepsChoicesAndUnfollows`: verifies follow, discovery, refresh, and unfollow.
  - `overflowMenuUpdatesTopNAndDeliveryAndUnfollows`: verifies Top N (Off/1/2/3/5), Briefing/Separate, and Podsub/FeedStore synchronization.
  - `makeArticlePodcastInstantFeedbackAndBlocksDoubleTap`: verifies single article podcast creation, double-tap prevention, and `"Queued #1 (~5 min)"` instant feedback using fake HTTP.
  - `makeCombinedPodcastCombinesArticlesAndCallsCreateOnce`: verifies multi-selection, combination of story text into one payload, single `POST /api/podcast/create` call, and `"Queued #2 (~10 min)"` feedback.

---

## Files Touched
1. `app/src/main/java/com/mavislibrary/app/ui/sites/SitesScreen.kt` - Magazine shelf grid, Big + Add site card, overflow menu, and immediate articles screen with multi-select.
2. `app/src/main/java/com/mavislibrary/app/ui/sites/SitesViewModel.kt` - Selection state, in-flight tracking, instant queue feedback, Top N & delivery settings, combined podcast creation.
3. `app/src/main/java/com/mavislibrary/app/data/feeds/FeedStore.kt` - Added `topCount` field and `setTopCount` helper.
4. `app/src/main/java/com/mavislibrary/app/data/podcast/PodcastModels.kt` - Added optional `queuePosition` and `etaMin` to `PodcastCreated` and `PodcastJob`.
5. `app/src/test/java/com/mavislibrary/app/ui/sites/SitesViewModelTest.kt` - Complete test suite with fake HTTP and Podsub mocks testing all new interactions without live jobs.

---

## Screenshots / Visual UI Description

### Screen 1: Magazine Rack Shelf (Sites Tab)
- **Top Bar**: "Sites you follow" headline with "Follow" button and "Podcast subscriptions in Lounge" link.
- **Grid Layout**: Two-column (or adaptive multi-column on tablets) grid layout resembling a newsstand/magazine rack.
- **Tile 1 (Top-Left)**: Big `+ Add site` card styled with dashed/accent border, circular container with plus icon, bold "Add site" title, and "Follow a feed" subtitle.
- **Followed Site Tiles**:
  - Rectangular cards with styled tonal covers.
  - Prominent letter tile in the center (e.g. bold white/tonal "W" for "World of AI", "H" for "Hacker News").
  - Top-left badge: Vibrant pill indicating unread items (e.g. `3 new`).
  - Top-right 3-dot overflow icon button triggering the dropdown menu.
  - Bottom section with site name and host domain.
- **Overflow Dropdown Menu**:
  - `Top: Off`
  - `Top 1 ✓`
  - `Top 2`
  - `Top 3`
  - `Top 5`
  - Divider
  - `Briefing ✓`
  - `Separate`
  - Divider
  - `Unfollow` (highlighted in error color)

### Screen 2: Site Articles & Multi-Select View
- **Header**: Back arrow icon button returning to the shelf, with site name headline and host.
- **Multi-Select Bar**: Positioned above the article cards:
  - Counter text: `2 selected`
  - Text actions: `Clear` and `Select all`
  - Prominent Button: `Make combined podcast (2)` (or `"Queueing combined…"` when in-flight, or `"Queued #1 (~5 min)"` when finished).
- **Article Cards**:
  - Top row: Multi-select checkbox, published date (`Oct 27`), and `New` badge if published since last visit.
  - Headline: Bold article title.
  - Body: 3-line excerpt snippet summarizing the post.
  - Bottom Actions:
    - `Read` text button: Launches reading view.
    - `Make podcast` filled button: When tapped, button disables immediately and updates text to `"Queued #1 (~5 min)"`.

---

## Verification Evidence
- `./gradlew testDebugUnitTest --tests "com.mavislibrary.app.ui.sites.*" --tests "com.mavislibrary.app.data.feeds.*" --tests "com.mavislibrary.app.data.podsub.*" --tests "com.mavislibrary.app.data.podcast.*" --no-daemon`: **PASSED (BUILD SUCCESSFUL)**
- `./gradlew assembleDebug --no-daemon`: **PASSED (BUILD SUCCESSFUL)**
- `node scripts/test-account-transport.mjs`: **PASSED**
