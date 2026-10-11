# Brief (Devin, gpt-6-sol-medium): FINISH native Kids mode v1 (continue Codex's half-done work)
You are the ONLY writer in this tree (branch native, native-android/). Codex hit its usage limit mid-job. FIRST read BRIEF-kids.md in the repo root: it is the full spec and still applies. Then read the two Codex commits (f546e04, 2ddfeb3) and finish the job in the same style; do not rewrite what works.
Toolchain: export JAVA_HOME=$HOME/.local/jdks/jdk-21.0.12.1+1 PATH=$JAVA_HOME/bin:$PATH ANDROID_HOME=$HOME/Android/Sdk. Run gradle in the BACKGROUND with a log and poll (`cd native-android && ./gradlew testDebugUnitTest assembleDebug --no-daemon -q`). NO emulator.
Known state (verified by Hermes): the build FAILS: data/kids/KidsPrefs.kt:77 "Unresolved reference takeLast/it" (fix first). Existing: KidsPrefs.kt, KidsRoot.kt, KidsSetup.kt, KidsWebReader.kt, KidsContent.kt, KidsPrefsTest.kt, partial MavisRoot wiring (adultRoutesAllowed).
Still MISSING versus BRIEF-kids.md (Hermes grep found none of these in ui/kids or data/kids): 
 - daily goal ring + stars screen ("My stars"), streak and last-7-days dots matching src/lib/kids.js goalStreak/lastWeek
 - PIN 5-miss 30 s lockout with injectable clock, and the Grown-ups corner PIN pad
 - reading-time accumulator (reader open + audio PLAYING in kids mode) feeding the log
 - Settings "Kids mode" section (name, goal chips 10/15/20/30, set PIN twice, Start Kids mode) in ui/settings/SettingsScreen.kt, and update Look.KIDS description (no longer "preview only")
 - tablet layout (>=600 dp: 2x2 giant tiles, 3-5 column grids, targets >=64 dp, body >=20 sp, headings >=32 sp)
 - Listen tile (LibriVox lookup with gid, existing PlaybackController) and Bible stories tile (12 stories from src/views/kids-home.js) if not complete; verify what exists before adding
 - tests for everything in BRIEF-kids.md section 6
Rules: same touch-list as BRIEF-kids.md. Do not touch src/, netlify/, audio/ internals, WebHost owner-code logic. Child-facing text must suit a 7-year-old. Honest labels: do not claim what you did not build. Commit in steps. Last action: RECEIPT-kids.md (files, test counts READ from app/build/test-results/**/*.xml, what is NOT verified). Do not push. Never fabricate results.
