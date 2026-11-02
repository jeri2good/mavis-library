# Kids Story Player - Chunk 1

- Read the existing kids PIN/back gate, reading accounting, Media3 audio service, and shared pack contract.
- Copied the contract verbatim into docs/KIDS-STORY-CONTRACT.md.
- Added typed catalog/story models, tier filtering, parent PIN verification without leaving kids mode, age/narrator preferences, and a debug pack-base override.
- Added offline catalog/covers, bounded/resumable downloads, SHA-256 verification, safe ZIP extraction and staged story installation. Verification follows in the fixture-test task.
- Added a fully local two-page fixture with tiny JPEG and one-second silent MP3s, plus Robolectric tests for parsing, all age boundaries, page filtering, prefs defaults/persistence, PIN-preserving parent unlock, ZIP traversal, SHA mismatch, HTTP Range resume and servers ignoring Range. Full memory-limited Gradle verification is running.
- Replaced the Bible web list with a responsive native cover shelf and download rings; added PIN-gated age/narrator controls, bounded previews, and verbatim non-clickable catalog credits.
- Added a private MediaSessionService with narration + independently ducked looping music, wake locks, manual/swipe paging, delayed auto-advance, end-story question, and service-owned elapsed-time sleep deadlines. The session retains DEFAULT_PLAYER_COMMANDS but rejects arbitrary media replacement.
- Preserved the native Back/PIN gate, paused adult audio before story/parent entry, locked parent settings on background, and included story playback in reading-time accounting. No emulator is used.
- First full build assembled the APK; all new tests passed, but two shell tests exposed Robolectric's null service binding. Matched the existing PlaybackController opt-out for automatic Robolectric bindings while retaining a direct-token service integration test.
- Added corrupt-download rejection/clean retry, cached-catalog fallback, media command containment and elapsed-time sleep expiry tests. Fixed pause/resume during the auto-advance gap, audio-focus/music synchronization, and ignored stale ended events.
- Second full memory-safe build passed: 96 suites, 515 tests, zero failures/errors/skips; assembleDebug succeeded. Initial native font-loader failures did not recur. Final verification/receipt follows.
- Final verification on code commit c44a561 passed again in 2m49s: 96 suites / 515 tests / zero failures, errors or skips, plus assembleDebug. APK SHA-256: 4173788eec1b096d20ae4ab8edbc0e6bef7869d0c8f4dbba899833ea4eea81cf.
- Re-synced the shared contract after its concurrent OBS-mapping correction (Daniel/Jonah absent; nullable/multi-chapter obs already supported). App code is unchanged by that documentation update.
- Appended the Chunk 1 receipt and Hermes device acceptance checklist to native-android/RECEIPT.md. All changes stay under native-android; gradle.properties is unchanged; no emulator, deployment or push.
