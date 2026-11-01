# RECEIPT-085: Listen is the Lounge (Android native build)

Branch `reader-api`, built on `7bd4640` (approved mockups and brief). One commit per step, each prefixed `BRIEF-085:` and tagged `[skip netlify]`. Nothing has been pushed. Nothing ran on the emulator. No server changes and no new endpoints. Tests use fake HTTP only, with no live jobs or subscriptions.

## Per step

| # | Step | Status | Commit |
|---|---|---|---|
| 1 | Listen tab is the Lounge (mock A) | Done | `e43c95d` |
| 2 | ＋ New sheet with "Every day" first (mock B) | Done | `2a8558a` |
| 3 | Bible picker daily devotional card (mock C) | Done | `607ba14` |
| 4 | Start a daily devotional (mock D) | Done | `1cc1511` |
| 5 | Bible tab devotionals row (mock E) | **Skipped**: the Bible tab is the web bundle (`Tab.BIBLE` loads `#/bible` in `WebScreen`), not native Compose | none |
| 6 | Settings → Podcasts (mock F) | Done | `43b070c` |
| 7 | First-run guide (G1 to G3) | Done | `0ce0592` |
| 8 | Record label: long titles shrink or ellipsize, full title shown below | Done | `ebe74c7` |

### 1. Listen = Lounge
- The Listen tab now renders the Lounge. From the top:
  - "Listen" title with a gear that opens Settings → Podcasts.
  - **Continue listening** row: the latest episode in progress, with time left and a tonal Play/Pause.
  - **New episodes** header with an "N new" count, then the existing record crate (`RecordSelector`).
  - **Your Daily**: one briefing card if any site feeds the briefing, then one card per devotional rule. Each card shows the title, the ready-by time and "next: tonight 7:00 PM" from `subscriptionArrival`. Play latest appears only when that rule's latest episode is ready.
  - Audiobook player and free audiobook search at the bottom.
- **＋ New** is a single filled extended FAB. The crate's Play button is now tonal.
- Removed: "Open Podcast Lounge", the Lounge Back button and the "New podcast" button. The "Studio connected" line is hidden. The status line only appears while connecting, offline or waking.
- **Old route still works.** `openLounge(draft)` sets the draft and calls `goTab(Listen)`. The old `lounge` nav route stays registered and redirects to Listen through `loungeDestination()`.
- **Unchanged destinations:**
  - Widget and "Podcast ready" notification destinations (`lounge`, `new-podcast`, `lesson:`) land on Listen. `new-podcast` still opens the New sheet.
  - Notification **Play** is the `PodcastPlaybackAction` broadcast receiver, which doesn't use navigation and is untouched.
- **Record crate:** the `rememberUpdatedState` and `getOrNull` pager guards are kept.

### 2. ＋ New sheet
- `newSheetLanes` puts **Every day** first, with these rows:
  - Daily devotional from a site
  - Devotional on a topic
  - Daily briefing
- **Make one now** follows: Bible chapter, Book, Chapter, Article, Topic, with the edited subtitles.
- Daily rows use filled cards. One-time rows use outlined cards.
- Where the Every day rows go:
  - Devotional rows open flow D (`daily/{mode}`).
  - Daily briefing opens Settings → Podcasts → Briefing sites.
- Between steps 2 and 4 the devotional rows briefly used the old editors. Step 4 pointed them at D.

### 3. Bible picker
- A "Make it a daily devotional" card sits at the top. **Today Devotional** opens D with that site selected, and **Pick a topic** opens D in topic mode.
- The headings now read "Or make one lesson now", "From your notes & highlights" and "Any chapter · Old/New Testament".
- Once the Today Devotional rule exists, its button reads "Already daily" and is disabled.

### 4. Start a daily devotional (`DailyDevotionalScreen`)
- **Source and topic:** choose Today Devotional or Another site (URL, Name, Preview), or a topic chip: Anxiety, Marriage, Fatherhood, Faith, Grief, Gratitude, or ＋ Custom. These are mutually exclusive.
- **Chips:** the selected chip is **filled with the primary colour** and is a Selected semantics node.
- **Time:** defaults to **7:00 PM**, or the existing shared devotional time if any devotionals exist. Changing it uses the M3 TimePicker. The device time zone (`ZoneId.systemDefault()`) is sent. A note says all devotionals share this time.
- **Preview:**
  - Sites use the existing `previewDevotional` call, sent once the view model is idle.
  - Topics show "A daily Bible lesson on X". No passage is implied.
- **Start loading state:** Start is disabled until a site preview has loaded. It reads "Working…" while saving.
- **Start** calls the new `PodsubViewModel.startDevotional`. That:
  - calls `addDevotional`, reusing an existing rule for the same site or topic instead of duplicating it;
  - sets `readyBy` and the time zone and sets `enabled = true` even when this isn't the first devotional (the "Start could silently do nothing" fix);
  - saves. The 5-devotional limit is refused with a clear message.
- **Confirmation:** "You're all set" plus **"First one: tonight 7:00 PM"** from `firstDeliveryLabel`, which respects the schedule's days.
- The nav bar is hidden on this route, to match the mock.

### 6. Settings → Podcasts (`PodcastSettingsContent` / `PodcastSettingsScreen`, route `podcast-settings/{page}`)
- **Main page, top section:**
  - Usage meter ("Today: N of M used") with a progress bar.
  - Briefing delivery and Devotional delivery cards: switch, "Ready by" with time picker, time zone (display only) and day chips.
  - "Keep episodes for N days" slider.
- **Main page, rows and toggles:**
  - Briefing sites, Devotionals and Stories first (a Newest / Most important dialog).
  - Use backup voices if needed, and Telegram notifications.
  - Default style and length, which expands the existing `PodcastSettingsSection` (local cleanup still inside).
  - Run briefing now.
- **Sub-pages:**
  - **Briefing sites:** per-site switch, Top N, Briefing/Separate, move up/down, remove, Add a site, and the skipped-site notes.
  - **Devotionals:** switch, edit, remove, plus Start a daily devotional (opens D), Add a site and Add a topic.
- **Saving:** each change saves immediately through the new `PodsubViewModel.commit`. Only a typed site name waits for "Save changes".
- **Reached from:** the Settings tab row "Podcasts" (`podcast_settings`), the Listen gear, Your Daily cards and Sites ("Podcast subscriptions in Settings"). Settings stays highlighted in the nav bar.
- **Removed from Listen:** the subscription section, the Today list, "Play all" and "Save subscriptions". `PodsubSection` became the settings page.
- **Signed out:** the page shows a sign-in prompt plus the local defaults. Kids mode never shows it.

### 7. First-run guide
- `ListenGuide` is a full-screen dialog with 3 cards, using the mock G1 to G3 copy and illustrative, non-tappable art.
- Every card has Skip. The button reads Next, Next, then Done.
- Skip and Done both store `listen_guide_085_seen` in the settings DataStore (`SettingsStore.listenGuideSeen` / `markListenGuideSeen`).
- `ListenGuideHost` waits for the stored value, so it never flashes. It shows once, for existing users too. It never shows in Kids mode.

### 8. Record label
- The label uses `BasicText` with `TextAutoSize.StepBased`. The font starts at the old size, steps down to 7sp, and ellipsizes after 3 lines.
- The headline under the crate (`record_headline`) no longer has `maxLines`, so the full title always shows.

## Files changed (`7bd4640..HEAD`, 23 files, +1352 / −258)
- **Main:**
  - `ui/MavisRoot.kt`, `ui/listen/ListenScreen.kt`
  - `ui/listen/ListenGuide.kt` (new)
  - `ui/lounge/ListenParts.kt` (new), `ui/lounge/DailyDevotionalScreen.kt` (new)
  - `ui/lounge/PodcastLoungeScreen.kt`, `ui/lounge/PodcastSourcePicker.kt`, `ui/lounge/PodsubSection.kt`, `ui/lounge/PodsubViewModel.kt`, `ui/lounge/PodcastSettingsSection.kt`, `ui/lounge/RecordSelector.kt`
  - `ui/settings/SettingsScreen.kt`, `ui/sites/SitesScreen.kt`, `data/prefs/SettingsStore.kt`
- **Tests:**
  - New: `ui/lounge/Listen085Test.kt`, `ui/lounge/DailyDevotional085Test.kt`, `ui/listen/ListenGuide085Test.kt`
  - Updated: `ui/PodcastNavigationTest.kt`, `ui/lounge/PodcastSourcePickerTest.kt`, `ui/lounge/Subscriptions084Test.kt`, `ui/lounge/RecordSelectorTest.kt`, `ui/podcast/EpisodeDetailsSheetTest.kt`, `work/InteractiveWidgets084Test.kt`

## Required tests
| Brief requirement | Test |
|---|---|
| Lane order | `PodcastSourcePickerTest.newSheetPutsEveryDayFirstThenFiveOneTimeChoices`: data order plus Every day drawn above Make one now; every row clickable |
| Chip selection state | `DailyDevotional085Test.selectedTopicChipIsFilledAndExclusiveAndStartSendsTopicAndTime` |
| Start calls addDevotional with chosen time/topic | `DailyDevotional085Test.startCallsAddDevotionalWithChosenTopicAndTimeAndTurnsDeliveryOn`: the fake PUT has the topic, `06:30`, the zone and `enabled = true` on a non-first devotional; no duplicate rule. Also `sixthDevotionalIsRefusedWithoutSaving` |
| First-run flag shown once | `ListenGuide085Test.guideIsShownOnceThenSkipStoresTheFlag` and `threeCardsAdvanceWithNextAndFinishWithDone` |
| Lounge-route redirect | `PodcastNavigationTest.loungeRouteAndNotificationDestinationRedirectToListen`: `loungeDestination` plus a real `lounge` notification intent landing on Listen |

Also added or updated:
- `Listen085Test`: Your Daily cards
- `biblePickerOpensWithDailyDevotionalCardThatStartsFlowD` and `dailyCardDisablesTodayDevotionalOnceRunning`
- `siteModePreviewsTodayDevotionalAndWaitsForItBeforeStart` and `firstDeliveryLabelAndDefaults`
- the settings page save-immediately flows in `Subscriptions084Test`
- `longTitlesAreCappedOnTheLabelAndShowInFullBelow`

## Verification run
- In `native-android`: `JAVA_HOME=~/.local/jdks/jdk-21.0.12.1+1 ANDROID_HOME=~/Android/Sdk ./gradlew testDebugUnitTest assembleDebug --no-daemon` gave **BUILD SUCCESSFUL: 475 tests, 0 failures, 0 errors, 0 skipped**, and `app-debug.apk` was built.
  - The first full run of the same command had 2 failures in `ShareAndroidTest` (`UnsatisfiedLinkError` in Robolectric native graphics, and `FileSystemAlreadyExistsException`). That class passed 8/8 on its own, and the immediate full re-run passed 475/475. These look like order-dependent Robolectric flakes in a class I didn't touch.
- `node scripts/test-account-transport.mjs` passed.
- `dist-android/index.html` already existed and no web source changed, so `npm run build:android` was not re-run.

## Known gaps and decisions for J / Hermes
- **Step 5 skipped.** The Bible tab is a web view. Building card E means editing `src/views/bible.js` and adding a native bridge, which is out of scope here.
- **Theme:** built in the existing theme and Material 3 tokens, with no new dark-warm theme. This matches the design receipt's recommendation (Paper and Midnight as they are today).
- **Filter chips kept** above the record crate (New / Continue / Being made / Played / All), although mock A doesn't draw them. Without them, "Being made", "Played" and failed episodes would have no home on Listen.
- **Separate-delivery briefing sites** don't get their own Your Daily card. They're listed under Settings → Podcasts → Briefing sites.
- **Dropped from Listen:** the old "Today" list and "Play all", following the design's open question. Each daily rule's Play latest replaces them.
- **One shared devotional time.** That's a server limit. Screen D says so when devotionals already exist.
- **Retention has two controls,** as before: the server-side Keep-for-days slider and the local cleanup in "Default style and length". They haven't been merged.
- **Labels:** the UI uses the brief's "Today Devotional". New preset rules are saved with that name, while existing rules keep "Today Daily Devotional". The widget, media metadata and server error strings still say "Podcast Lounge".
- **Not checked by tests:**
  - **Label auto-size:** Robolectric's text metrics are synthetic, so the step-down from step 8 needs to be checked on a device.
  - **First-run guide on the real Listen tab:** there's no activity-level test. The DataStore delegate is shared across Robolectric tests, so the activity tests mark the guide as seen first. The guide behaviour itself is tested in isolation.
- **Device checks for Hermes:**
  - FAB clearance over the last row: there's 104dp of bottom padding.
  - Guide timing on an existing install.
  - D preview against the live `todaydevotional.com` site.
  - Notification Play and widget taps landing on Listen.
