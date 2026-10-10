# Phase A progress

Tests run as `env -u TYPESAFE_API_KEY -u FISH_AUDIO_API_KEY npm run test:functions` (those two vars are exported in this shell and break the rank test; baseline with them unset is green).

- Task 1 isNativeApp — src/lib/native.js — test:functions: 32 endpoint checks passed;14 account and sync checks passed;12 group checks passed;8 word builder checks passed
- Task 2 apiUrl — src/lib/api.js + 36 call sites in 19 files (src/lib/*, src/views/words.js); npm run build OK — test:functions: 32 endpoint checks passed;14 account and sync checks passed;12 group checks passed;8 word builder checks passed
- Task 3 withCors — netlify/lib/cors.mjs (new), netlify/lib/shared.mjs (sameOrigin accepts the 2 app origins), all 15 netlify/functions/*.mjs wrapped (POST-only configs now method ['POST','OPTIONS']; voices allows DELETE), tests/functions/cors.test.mjs (new, 7 checks), package.json test:functions — test:functions: 32 endpoint checks passed;14 account and sync checks passed;12 group checks passed;7 CORS checks passed;8 word builder checks passed
