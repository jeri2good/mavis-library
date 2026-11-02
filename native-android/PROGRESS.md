# Kids Story Player - Chunk 1

- Read the existing kids PIN/back gate, reading accounting, Media3 audio service, and shared pack contract.
- Copied the contract verbatim into docs/KIDS-STORY-CONTRACT.md.
- Added typed catalog/story models, tier filtering, parent PIN verification without leaving kids mode, age/narrator preferences, and a debug pack-base override.
- Added offline catalog/covers, bounded/resumable downloads, SHA-256 verification, safe ZIP extraction and staged story installation. Verification follows in the fixture-test task.
