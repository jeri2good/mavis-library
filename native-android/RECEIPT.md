# Milestone 1 receipt (Hermes first-hand verification)

Built: native Kotlin/Compose shell (package com.mavislibrary.app, 0.5.0-m1, minSdk 26), 4 looks (Paper, Midnight, Editorial, Kids preview), native Search + Settings, hosted web Shelf/Bible/Listen, Room offline layer, encrypted owner-code storage.
Verified by Hermes: assembleDebug green (37s); 34 unit tests, 0 failures; function tests pass with TYPESAFE_API_KEY/FISH_AUDIO_API_KEY unset.
Screenshots (clean API 35 emulator, docs/screens/): 05 start, 06 search with live results, 07b Bible (John 1 KJV), 08 settings, 09-11 Paper/Midnight/Editorial, 12/12b Themes incl. Kids.
Permissions: INTERNET, ACCESS_NETWORK_STATE, RECORD_AUDIO (declared; asked at first use), WAKE_LOCK, FOREGROUND_SERVICE, RECEIVE_BOOT_COMPLETED (from WorkManager, to be reviewed).
NOT working / NOT verified:
- Offline banner did NOT appear with Wi-Fi/data off (screenshot 13). Defect.
- /api/features blocked by CORS: Phase A backend not deployed to Netlify.
- First load of Bible/Shelf takes 15-30s on the emulator; not measured on a real phone.
- Mic prompt, book detail sheet, Add to shelf, file import: not exercised.
- No screen-off audio (M2).
- Removed "Made with AI" badges per J; Report buttons kept.
