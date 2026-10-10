# Phase A progress

Tests run as `env -u TYPESAFE_API_KEY -u FISH_AUDIO_API_KEY npm run test:functions` (those two vars are exported in this shell and break the rank test; baseline with them unset is green).

- Task 1 isNativeApp — src/lib/native.js — test:functions: 32 endpoint checks passed;14 account and sync checks passed;12 group checks passed;8 word builder checks passed
