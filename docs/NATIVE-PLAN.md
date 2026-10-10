# Mavis Library — native Android app plan (approved scope)

Decision: Option 3. Native Kotlin + Jetpack Compose shell. Existing Netlify backend (15 functions) unchanged and reused.
Screens migrate to native one at a time. The EPUB reader stays on the web engine (embedded WebView) until last.
Supersedes the Capacitor-wrapper approach in docs/ANDROID-PRD.md. PRD sections 5, 6, 8 (CORS, no secrets in the app, Play policy, privacy pages, report action, test discipline) still apply.

## Approved features
Core (J's original asks)
- Camera cover scan: add a book by scanning its barcode or cover
- Read-aloud while driving; screen-off audio, notification and lock-screen controls, headset buttons, audio focus
- Light sensor: auto brightness and theme (day / evening)
- Offline mode: shelf, open books, Bible text, saved audio; background downloads on Wi-Fi; storage manager
- Entirely new look (three directions mocked up first; J picks)

Added (approved)
- Share into Mavis: Open-with / Share for EPUB, text, verses; share a verse out as an image card
- Drive mode: starts on car Bluetooth, huge controls, dimmed screen, resume position; keep a standard MediaSession so Android Auto can be added later
- Sleep timer: fade-out, end-of-chapter, shake to extend, pause on headphone unplug
- Widgets: Continue reading/listening, Verse of the day, Today's Bible plan
- Reading stats and streaks: local and private, goals, weekly recap
- Smart reminders: time-based first; place-based is optional and later (background location needs extra Play review)
- Hands-free voice commands: next page, define word, bookmark, read from here (push-to-talk or earbud button)
- Biometric lock: kids-mode grown-up screens, private notes, account
- Physical page turns: volume keys, earbud taps, haptic ticks, optional tilt

NOT in scope: page scanner / OCR (J removed it).

## Kids mode (new, for a 7-year-old, tablet-first)
- Separate bright, big-button game-style home; landscape tablet layouts; large touch targets; no reading of small print required
- Game formats: read-along with word highlight and star rewards; word pop / letter bubbles; sight-word match; Bible heroes memory match; story-builder (pick pictures, hear the story); daily streak with a friendly mascot and stickers
- Safety: no outside links, no store links, no free-text AI chat; only kid-safe AI already in the web app; grown-up gate by fingerprint or PIN; optional daily play-time limit; works fully offline
- Progress per child, shown on a parent screen
- DECIDED by J: this app will NOT go on Google Play. It is private / sideloaded only. Play Families policy, Data Safety form, App Links verification, the 13+ rating and the Play report-button requirement no longer drive design. Keep the report action and privacy pages anyway (harmless, already built).

## Milestones (each ends with an installable APK link sent to J in Telegram)
M1 Native foundation: Compose app, navigation, theme system with 3 look options, offline database (Room), secure storage, back button, edge-to-edge, API client to the Netlify functions, embedded web reader
M2 Audio: foreground media service, MediaSession, audio focus, notification and lock-screen controls, sleep timer, drive mode, physical page turns
M3 Shelf, search, Bible, settings, accounts and sync as native screens; offline downloads; share-in / share-out; biometric lock
M4 Kids mode (tablet games) plus parent screen
M5 Camera cover scan, light sensor, voice commands, widgets, stats, reminders
M6 Release prep: stable signing key (needed so updates install over the old APK), device test pass (PRD section 8), distribution as a download link in Telegram

## Rules
- One writer per tree. Hermes plans, verifies and integrates; Devin (Opus 5.5) builds; read-only review before each handoff.
- No secrets in the app or repo. Owner access code and account token in Android Keystore storage.
- Honest testing: anything not run on a real device is listed "not run".
- Keep the live website untouched until J approves a deploy.
