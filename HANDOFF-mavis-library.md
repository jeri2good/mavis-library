# HANDOFF: Mavis Library / Podcast Lounge / Subscriptions / Modal studio
Written 2026-10-12 ~09:30 EDT by Hermes. Read this first in a new chat, then verify live state (processes, git, modal) before acting. Never print secrets.

## Handback 2026-10-20: J returned the work to Hermes. Read this first.
- **All of the cloud session's work is in PR jeri2good/mavis-library#1** (branch `claude/clever-thompson-ave0f4`, into `main`). It is pushed, with nothing uncommitted. The PR description lists every change and how each was verified. The branch already contains `native`'s handoff commit.
- **Open decision: which key signs Mavis.** The cloud session made a new key because it couldn't reach `~/.secure`. The workflow (`.github/workflows/android-release.yml`) currently expects that new key: alias `mavis`, pinned fingerprint `8c754baa9acd605f4a469533e3a6564a455fb00fd9af91468cdba1e4b8a8afb9`. J has the key text and password as files from the chat. Neither is in the repository, and no repository secrets are set yet.
  - **To keep the v4 key instead** (no reinstall on phones), change three things in the workflow:
    - the `MAVIS_KEY_ALIAS: mavis` line becomes `trainingboard-v4`;
    - the `MAVIS_KEY_PASSWORD` line reads the v4 key password (a new secret, if it differs from the store password);
    - the pinned fingerprint becomes the v4 certificate's SHA-256.
    Then set `MAVIS_SIGNING_KEYSTORE_BASE64` and `MAVIS_KEYSTORE_PASSWORD` from `~/.secure/TrainingBoardKiosk-v4-release.jks`, and update docs/ANDROID-UPDATES.md to match.
  - **To adopt the new key,** J pastes the two values into the repository secrets. Each phone then uninstalls the v4-signed copy once.
- **A preview APK** (build 100, `0.7.0-preview`, new key) was sent to J in chat. If J installed it, that phone is already on the new key.
- **Not verified:** a real phone (daily podcast scheduling, notifications, downloads, the in-app updater) and a signed GitHub build.

## Update 2026-10-20 (Claude Code, cloud session; branch `claude/clever-thompson-ave0f4`, PR jeri2good/mavis-library#1 into `main`)
- **Verified live:** `GET /api/features` on mavis-library.netlify.app reports `subscriptions:true, podcast:true`, so the subscriber layer and `STUDIO_URL` are deployed (NEXT STEP 3). `/api/subscription/status` and `/api/podcast/list` return 401 without sign-in, as designed. The Modal studio is live per the `modal` branch's RECEIPT-modal.md.
- **Inferred from commit history, not verified:** NotebookLM on Modal (STEP 2: `3464026`, `769e991`) and the app's sign-in/subscription UI (STEP 5: `4d6a023`, `7308e1a`) are done. J reports listening to a podcast the app made. STEP 4 (seeding subscribers) can't be checked from the cloud.
- **PR #1 adds:**
  - a Sites tab with a daily podcast from followed sites (combined briefing plus per-site episodes, made by a WorkManager job through the existing gateway);
  - a gallery-first Podcast Lounge;
  - an in-app updater;
  - the **Android release** GitHub workflow, which builds and signs on GitHub. Pushes to `main` become releases that phones update to; `native` and `claude/*` pushes become test-build prereleases with a download link.
- **Signing (changed 2026-10-20, J's decision: "take over every aspect"):** Mavis has a new key: alias `mavis`, RSA 4096, fingerprint `8c754baa…afb9`. It lives only in the `MAVIS_SIGNING_KEYSTORE_BASE64` and `MAVIS_KEYSTORE_PASSWORD` repository secrets plus J's private backup; it is not in the public repository. The tower's TrainingBoardKiosk-v4 key is no longer used for Mavis, and HANDOFF STEP 6's "sign with the v4 keystore" is superseded. Builds come from GitHub (releases from `main`, test-build links from branches); no machine builds locally. Details in docs/ANDROID-UPDATES.md.
- **Each phone uninstalls the old (v4-signed) Mavis once** and installs a new-key build; after that the app updates itself from `main` releases.

## Standing rules (from J)
- Mavis mode = fully autonomous. Devin ALWAYS `gpt-6-1-sol-medium` (changed from gpt-6-sol-medium). Codex `gpt-6-astra` (quota resets ~10:56 EDT). One worker per codebase.
- Nothing new heavy on the iMac: new services go to MODAL (workspace jermaine-hawthorne, `modal` CLI authed on iMac).
- App is private sideload (com.mavislibrary.app). Deliver any rebuild as APK download link in chat. Package/repo: ~/DevinWorkspaces/mavis-library-android, branch `native`, GitHub jeri2good/mavis-library.
- Fabricated success is worse than a reported blocker. Verify with own tests, not worker self-reports or exit codes.
- Local emulator: 7.8 GB RAM, run emulator/gradle sequentially. Gradle: JAVA_HOME=~/.local/jdks/jdk-21.0.12.1+1, ANDROID_HOME=~/Android/Sdk, `./gradlew testDebugUnitTest assembleDebug --no-daemon`. Untracked BRIEF*.md blocks merges: now gitignored.

## Feature request being delivered
"Podcast Lounge": native Book Club/Listen-tab feature to generate a podcast about any book, chapter, article/website or topic, via NotebookLM (unofficial notebooklm-py) with local-engine fallback. Must be on OPEN internet but gated: user must be a SUBSCRIBER for this and ALL AI features. Manual subscribers for now (no payments): jermaine.hawthorne@gmail.com and christie.hawthorne@gmail.com. J needs an approve/deny flow for requests. Pay model later.

## DONE and verified by Hermes
- Android native releases M2j (scan), M2k (voice, lock, EPUB), M2l (custom feeds) exported; `native` HEAD at time of writing b8a4b25.
- App-side Podcast Lounge merged into native (commit 2527b5f line): 298 unit tests pass (2 flaky env failures on first run, clean on rerun), APK +212 KB. BUT it talks to a studio directly with a pasted URL+key; it has NO sign-in / subscription / request-access UI yet.
- Netlify subscriber layer merged into native (netlify/lib/subs.mjs, access.mjs, functions/subscription.mjs, podcast.mjs; tests/functions/subs.test.mjs; docs/SUBSCRIPTIONS.md, docs/PODCAST-GATEWAY.md, RECEIPT-subs.md). All 6 function test files pass (51 sub/podcast checks). NOT DEPLOYED to Netlify. Owner code still works everywhere (fail-closed 503 on store errors). Admin (owner code only): list/approve/deny/revoke/add/create-account. Daily caps (assistant 150, voice 80, rank 300, study 60, podcast 3). Denied wait 7 days. No Telegram notification path existed so none invented.
- Studio (iMac prototype, ~/podcast-lounge, 213 pytest pass): real MP3s made; contract in docs/STUDIO-CONTRACT.md; signature check proven compatible with the Netlify signer (HMAC sha256 over "id.exp.uid", exp = epoch seconds, <=600s ahead). Gateway headers X-Studio-Key + X-Mavis-User.
- NotebookLM PROVEN from iMac with J's Google account (Chrome profile signed in as jermaine.hawthorne@gmail.com): created throwaway notebook, source add, `generate audio --format brief --length short`, completed ~4 min, downloaded 80 s two-host AAC, transcribed OK, notebook deleted. Login stored at ~/.notebooklm/profiles/default/storage_state.json (mode 600) and as Modal Secret `notebooklm-auth` (STORAGE_STATE_JSON, ACCOUNT_EMAIL). Other Modal secrets involved: `podcast-studio` (exists). Do NOT touch mavis-gravity-pr, tb-wakeword-data, mww-negative-cache.
- Christie account CREATED on live site (christie.hawthorne@gmail.com), signin verified. Temp password + recovery code in ~/.secure/christie-account.txt (600); J told to hand them over. She has no subscription yet (live site has no subscriber layer).

## IN FLIGHT (check `ps -eo etime,cmd | grep devin`)
1. Devin (gpt-6-1-sol-medium) porting the studio to Modal: dir ~/podcast-lounge-modal, branch `modal`, brief BRIEF-modal.md, log ~/.hermes/cache/devin-modal.log, proc_started ~09:20 EDT. Target: new Modal app `mavis-podcast-studio`, web layer + GPU functions (open LLM script writer, Kokoro-style TTS, edge-tts fallback), Volume state, cost guards (GPU caps, STUDIO_ENABLED kill switch, content-hash cache), Secret `podcast-studio` holds STUDIO_SHARED_SECRET (also at ~/.secure/podcast-studio-shared-secret, 600) + owner bearer. Receipt RECEIPT-modal.md.
2. QUEUED (not yet launched): NotebookLM-on-Modal brief at ~/.hermes/cache/scratch/BRIEF-nlm-modal.md. Launch AFTER the Modal port commits (same dir). Key risk: Google may challenge datacentre IPs; if so report honestly and consider a Tailscale exit node/residential route. Auto engine falls back to local.

## NEXT STEPS (in order)
1. Hermes verifies the Modal studio itself (pytest, health, create via gateway headers, signed audio URL, Range, tamper/expired/wrong-uid 401, other uid 404, no-key 401; `modal app list` shows no stray GPU). Record cost estimate; ask J to set a Modal workspace spend limit.
2. Launch NotebookLM-on-Modal brief; verify a live nlm job from Modal.
3. Set Netlify env: STUDIO_URL (the Modal web URL), STUDIO_SHARED_SECRET (same as Modal secret, from ~/.secure/podcast-studio-shared-secret), subscriber store vars per docs/SUBSCRIPTIONS.md. Deploy `native` backend to the live Netlify site (mavis-library.netlify.app); retest existing endpoints first.
4. Seed subscribers via the owner admin route: J (his existing account, signed up with his Gmail, already exists, find its exact email in the store/ask) and Christie (christie.hawthorne@gmail.com): mark active. Do NOT pre-approve unregistered emails (signup does not verify email; takeover risk).
5. Build app UI (Devin gpt-6-1-sol-medium, new worktree off native): Sign in / Create account, Subscription screen with Request access + real state (pending/active/denied/revoked), point Lounge + all AI features at public site with the account token (not pasted studio key), friendly errors (sign in / waiting / daily limit), owner-only approvals screen in Settings (owner code). Kids mode must block it.
6. Emulator test full path, build + sign APK (v4 keystore ~/.secure/TrainingBoardKiosk-v4-release.jks alias trainingboard-v4), export `MavisLibrary-native-M2m.apk` and send J a download link. Retain a summary to Hindsight.

## Open questions for J
- None blocking. CONFIRMED by J: his existing account email is jermaine.hawthorne@gmail.com (do not create a duplicate; seed it as active subscriber after deploy). Christie's creds handed off by J.
