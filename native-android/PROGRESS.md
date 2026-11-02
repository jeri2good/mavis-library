# Kids Story Player - Chunk 1

- Read the existing kids PIN/back gate, reading accounting, Media3 audio service, and shared pack contract.
- Copied the contract verbatim into docs/KIDS-STORY-CONTRACT.md.
- Added typed catalog/story models, tier filtering, parent PIN verification without leaving kids mode, age/narrator preferences, and a debug pack-base override.
- Added offline catalog/covers, bounded/resumable downloads, SHA-256 verification, safe ZIP extraction and staged story installation. Verification follows in the fixture-test task.
- Added a fully local two-page fixture with tiny JPEG and one-second silent MP3s, plus Robolectric tests for parsing, all age boundaries, page filtering, prefs defaults/persistence, PIN-preserving parent unlock, ZIP traversal, SHA mismatch, HTTP Range resume and servers ignoring Range. Full memory-limited Gradle verification is running.
- Replaced the Bible web list with a responsive native cover shelf and download rings; added PIN-gated age/narrator controls, bounded previews, and verbatim non-clickable catalog credits.
- Added a private MediaSessionService with narration + independently ducked looping music, wake locks, manual/swipe paging, delayed auto-advance, end-story question, and service-owned elapsed-time sleep deadlines. The session retains DEFAULT_PLAYER_COMMANDS but rejects arbitrary media replacement.
- Preserved the native Back/PIN gate, paused adult audio before story/parent entry, locked parent settings on background, and included story playback in reading-time accounting. No emulator is used.
