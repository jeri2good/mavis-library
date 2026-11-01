# BRIEF-085 BUILD — Listen = Lounge redesign (Devin, Claude Opus 5.5)

Approved mockups: `design/brief-085/png/*.png`, `design/brief-085/index.html`, spec `design/brief-085/RECEIPT.md`.
Look at every PNG before coding. Build the Android native app (Jetpack Compose) to match them.

## Repo facts
- App: `native-android/app/src/main/java/com/mavislibrary/app/`. Tabs in `ui/MavisRoot.kt` (Tab.LISTEN route,
  Lounge route ~line 415-430). Listen today: `ui/listen/ListenScreen.kt`. Lounge: `ui/lounge/PodcastLoungeScreen.kt`,
  `RecordSelector.kt`/`RecordCrate.kt` (record crate — KEEP its look; `rememberUpdatedState` + `getOrNull` pager
  guards there are a crash fix: do not remove), new-podcast sheet `PodcastSourcePicker.kt` + `PodcastPickerData.kt`,
  subscriptions `PodsubSection.kt` + `PodsubViewModel.kt` (addDevotional auto-enables delivery and saves immediately —
  preserve), settings `ui/settings/SettingsScreen.kt`, Bible UI under `ui/reader` or web bundle (find it).
- Verification steps in `AGENTS.md`. Never create live podcast jobs or live subscriptions; fake HTTP in tests.

## Build (in this order, commit after each with message prefix `BRIEF-085:` and `[skip netlify]`)
1. **Listen tab IS the Lounge** (mock A): top Continue listening, New episodes crate, "Your Daily" cards
   (briefing + each devotional rule: title, next delivery time, Play latest), single primary "＋ New" button.
   Remove the "Open Podcast Lounge" link; keep the old Lounge route working (redirect to Listen) so widgets/
   notifications deep links still land. Notification "Play" and widget actions must still work.
2. **＋ New sheet** (mock B) with lanes **"Every day" FIRST** then "Make one now" (approved change).
   Every day: Daily devotional from a site / Devotional on a topic / Daily briefing → open flow D or briefing settings.
3. **Bible picker** (mock C): top card "Make it a daily devotional" → flow D.
4. **Start daily devotional** (mock D): source (Today Devotional / site URL) or topic chips (Anxiety, Marriage,
   Fatherhood, Faith, Grief, Gratitude, + custom). **Selected chip must be visually filled** (approved change).
   Time picker default 7:00 PM, device time zone. Live preview via existing devotional preview call. One "Start"
   → uses PodsubViewModel.addDevotional (already saves + enables delivery). Confirmation shows "First one: tonight 7:00 PM".
5. **Bible tab** devotionals row (mock E) — only if the Bible screen is native Compose; if it is the web bundle,
   skip and say so in the receipt.
6. **Settings → Podcasts** (mock F): move schedules, delivery time/zone, keep-for-days, daily cap usage,
   briefing sources management here (reuse PodsubSection pieces). Lounge no longer shows settings cards.
7. **First-run guide** (mock G1-3): 3 skippable cards, shown once (DataStore flag).
8. Record label: long titles must shrink/ellipsize cleanly (approved change); full title shown below.

## Rules
- Reuse existing view models/data; no server changes; no new network endpoints.
- Material 3, existing theme/fonts. Minimum 48dp touch targets. Content descriptions on icons.
- Unit tests for: lane order, chip selection state, devotional Start calls addDevotional with chosen time/topic,
  first-run flag shown once, lounge-route redirect. Run full `testDebugUnitTest assembleDebug`; all must pass.
- Do NOT push. Do NOT run on the emulator (Hermes does device verification).
- Write `RECEIPT-085.md`: per step done/skipped, files changed, tests run with counts, known gaps. Then stop.
