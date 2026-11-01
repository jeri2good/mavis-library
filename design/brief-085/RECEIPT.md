# BRIEF-085 receipt: Listen redesign mockups

Design only. No app code changed, nothing committed, and no live API calls made. Controls were checked against the source on branch `reader-api` at `c1a78b2`.

- **Open:** `index.html` shows all screens side by side. Add `?screen=A` for one screen, or `?theme=paper` for the app's current cream theme.
- **PNGs:** `png/` holds 780×1688 renders (390×844 at 2x) from headless Chromium. `png/paper/` holds the same screens in Paper.
- **Fonts:** `fonts/` holds copies of the app's Fraunces and Literata.
- **References:** cited from the brief's list. I did not re-fetch the source pages.

## Decisions for J

1. **Theme conflict.** The brief asks for a "dark warm library theme matching the screenshots", but the screenshots show the light Paper theme. The app's only dark theme, Midnight, is navy and gold. I followed "dark warm" as the main render and also rendered Paper. A dark warm theme would be NEW; building these screens in Paper needs no new theme. **Recommendation: Paper**, because it is what's on the device today.
2. **One time for all devotionals.** The server has a single `devotionalSchedule`, not one per devotional. On screen D, changing the time moves every devotional. This is an existing limit and I did not draw around it. Daily cards can show one shared time, and "Morning" and "Evening" are only labels.
3. **Bible tab (E) is a web view.** It loads `#/bible` from `src/views/bible.js`, and `6-bible.png` was captured blank. I drew the surrounding book list from the picker and web labels, so only the Devotionals card is the actual proposal. Building it means editing web source and adding a native bridge or podsub call.

## Existing behaviour the build must handle

- **Start could silently do nothing.** `addDevotional` turns delivery on only for the first devotional. If delivery was switched off and the user adds another, Start would save a devotional that never arrives. Start must also set `enabled = true`.
- **Preview gating.** Site devotionals can't be added until the preview has loaded, and the server allows a 4-second timeout. Start needs a loading state. The 2-tap count assumes the preview succeeds.
- **Topics have no preview.** `/api/podsub/preview` only handles sites, because the passage for a topic is chosen when the episode runs. Picking a chip must replace the preview with text such as "A daily Bible lesson on Anxiety". Do not imply a known passage.
- **Episode title order.** The server writes `Revelation 22:12-21 — Alpha and Omega`, reference first. The brief's copy has the title first. The mockups show the preview's separate title and reference fields, which match the brief. Played episodes will read reference first.
- **Limits and modes.** Maximum 5 devotionals. Kids mode currently hides podcast settings, so it must also hide everything here, including the guide.

## A. Listen = Lounge

**Changed vs today:** The "Open Podcast Lounge" link and the Lounge's Back level are gone. Episodes come first and settings cards are removed. ＋ New is the only filled control, so Play becomes tonal.

**References:** HIG Tab bars (a top-level destination holds the feature); Apple Podcasts (opens on new episodes); Recognition rather than recall (Your Daily is always visible); Material 3 (one primary action).

| Control | Maps to | Status |
|---|---|---|
| Continue listening row + Play | Continue listening filter, playback | Existing data, NEW layout |
| New episodes crate, "22 new" | Vinyl gallery, New filter | Existing |
| Episode Play | `playToday` | Existing, restyled |
| Your Daily → Morning Briefing | Latest ready briefing, `nextRunAt` | NEW grouping of per-site cards |
| Your Daily → Evening Devotional | Latest ready episode, `nextDevotionalAt` | NEW label; the real rule name is "Today Daily Devotional" |
| Play on a daily card | `onPlay` for the last ready job | Existing; hide when nothing is ready |
| ＋ New | New podcast sheet | Existing action, NEW position |
| Gear | Opens screen F | NEW shortcut |

"3 min left" is sample data. Times come from `subscriptionArrival`.

**Open questions:**
- "Being made" isn't drawn, as the brief's layout has no slot for it. I suggest a row that appears only while jobs are running.
- The ⓘ and ⋮ episode actions aren't drawn. I suggest keeping them on the episode detail view.
- `ListenScreen` also holds audiobook search and the audiobook player. These stay below Your Daily, off the drawn frame.
- Sites with `delivery = separate` don't belong to Morning Briefing and need their own place.
- "Play all" from the Lounge's Today section isn't drawn. Keep or drop it?

## B. ＋ New sheet

**Changed vs today:** One sheet replaces both New podcast and Subscriptions → Add, split into two labelled lanes.

**References:** Recognition rather than recall (recurring options are visible, not remembered); HIG progressive disclosure; Material 3 bottom sheet.

| Control | Maps to | Status |
|---|---|---|
| Bible chapter, Book, Chapter, Article, Topic | `sourceTypes` in `PodcastSourcePicker` | Existing; "Bible" and "Website / Article" renamed; subtitles for Chapter and Article edited |
| Daily devotional from a site | Editor `devotional` | Existing, moved |
| Devotional on a topic | Editor `topic` | Existing, moved |
| Daily briefing | Editor `website` (adds a site to the briefing) | Existing, moved |

**Open question:** "Daily briefing" really means "add a website to my briefing". Once a briefing exists, the row should probably read "Add a site to your briefing".

## C. Bible picker

**Changed vs today:** A daily devotional card sits above the chapter list. This fixes the dead end where someone looking for devotionals found only single-chapter lessons. The chapter list is unchanged, under a new "Or make one lesson now" heading.

**References:** YouVersion Plans (a card leads to starting); Recognition rather than recall; Material 3 (one filled button).

| Control | Maps to | Status |
|---|---|---|
| Today Devotional | Preset for `todaydevotional.com/daily-devotional-library` | Existing preset, NEW entry point; opens D |
| Pick a topic | Topic editor | Existing, NEW entry point; opens D |
| Search, notes and highlights, chapter list | Current picker | Existing |

**Open questions:**
- The app's preset is labelled "Today Daily Devotional", while the brief says "Today Devotional". I used the brief's wording. Pick one for the build.
- Hide or change the card once that devotional is already running.

## D. Start daily devotional

**Changed vs today:** The route was Listen → Lounge → scroll → Add → Devotional site → preset → Preview → Add subscription. It is now 2 taps: **Today Devotional** on C, then **Start**. The source and 7:00 PM are preselected. From the New sheet, the route is the "Daily devotional from a site" row, then Start. A topic is a chip, then Start, once D is open.

**References:** YouVersion Plans (card → start → time → done); HIG progressive disclosure (days and time zone stay in Settings); Material 3 (one primary action).

| Control | Maps to | Status |
|---|---|---|
| Today Devotional | Site preset | Existing |
| Another site | URL field, Preview, Name | Existing; opens the existing editor |
| Topic chips | `DevotionalRule(kind="topic")` | Existing rule, NEW chip set (see below) |
| 7:00 PM / Change | `devotionalSchedule.readyBy` (default `19:00`), existing TimePicker | Existing |
| Today's devotion | `previewDevotional` returning title and reference | Existing, site only |
| Start | `addDevotional` + `save` | Existing, NEW single step, needs the fix above |

"Alpha and Omega" is the brief's copy, not a live fetch.

**Open questions:**
- The app's chips are Anxiety, Marriage, Fatherhood, Forgiveness, Purpose, Trusting God, Gratitude and Grief. The brief's list adds **Faith** and leaves out Forgiveness, Purpose and Trusting God. Topics are free text, so Faith works with no server change.
- Free-text topics and "Surprise me" exist but aren't drawn. I recommend a "More" chip so they aren't lost.
- "Every day" is true only if the schedule has all days set. Show the real days otherwise.

## E. Bible tab

**Changed vs today:** A Devotionals card at the top shows today's devotion and Start daily.

**References:** Recognition rather than recall; YouVersion Plans; Material 3 (one filled button).

| Control | Maps to | Status |
|---|---|---|
| Today's title and reference | `previewDevotional` on the preset | Existing endpoint, NEW use in the web view |
| Start daily | Opens D | NEW route from web to native |

**Open questions:**
- Where does the card sit on the real landing view? See decision 3.
- The card can't offer "listen now", because the app has no one-off podcast of a site's devotion. That would be a new feature, so I did not draw it.
- Once started, the button should become a status such as "Daily at 7:00 PM".

## F. Settings → Podcasts

**Changed vs today:** Schedules, usage, retention and subscription management move out of the Lounge into a NEW sub-page.

**References:** HIG progressive disclosure (advanced settings behind a gear).

| Control | Maps to | Status |
|---|---|---|
| Today: 4 of 30 used | `state.meter`, `DailyUsage` | Existing, read-only |
| Delivery switches, Ready by, days | `ScheduleCard` | Existing, moved |
| Time zone | `schedule.timezone` | Existing, **display only**; no picker exists today |
| Keep episodes for 7 days | `keepDays` (1–30) | Existing, moved |
| Briefing sites | Per-site switch, Top N, delivery, order, remove | Existing controls, NEW list page |
| Devotionals | Rule switch, edit, remove | Existing controls, NEW list page |
| Stories first | `rank` | Existing; "Stories first" label is NEW |
| Backup voices, Telegram | `engine`, `notify.telegram` | Existing, moved |
| Default style and length | `PodcastSettingsSection` | Existing, moved |
| Run briefing now | `vm.runNow` | Existing, moved |

`America/New_York` is this workstation's time zone, used as sample data.

**Open questions:**
- Retention now has two controls: `keepDays` here and the local "Delete finished podcasts older than N days" cleanup in `PodcastSettingsSection`. The cleanup isn't drawn. Merge them, or explain the difference on the page?
- `PodsubSection` saves through a "Save subscriptions" button after `edit`. For this page I suggest saving each change right away, which is why no Save button is drawn. That is a NEW behaviour.
- The run's skipped-site notes need a home. I suggest the Briefing sites page.

## G. First-run guide (3 cards)

**Changed vs today:** NEW; no guide exists now. It has Skip on every card, and each card explains controls that exist. Rendered as three PNGs, because one card shows at a time.

**References:** Recognition rather than recall (teaches where things are); Material 3 (one primary action per card).

| Control | Maps to | Status |
|---|---|---|
| Skip, Next, Done | Dismisses or advances | NEW; needs one stored "seen" flag |
| Sample art on cards | Illustrative, not tappable | NEW |

**Open question:** Should existing users see it once after the update? I recommend yes, because Lounge disappears for them.

## Entry points after this redesign

| Today | After |
|---|---|
| Listen tab, plus a separate Lounge | Listen only |
| New podcast and Subscriptions → Add | ＋ New only |
| Settings cards in the Lounge | Settings → Podcasts |
| Devotionals under Subscriptions only | ＋ New, Bible picker, Bible tab, all opening D |
