# Phase A receipt: web and backend prep for the Mavis Library Android app

Branch `android`, local commits only. Nothing was pushed. Base: `main` (v0.4.0). The PRD and brief commit (`768abbc`) was already on this branch and isn't on `main`, which is why those two files appear in `git diff main`.

## Read this first
- **Baseline environment issue.** This shell exports `TYPESAFE_API_KEY` and `FISH_AUDIO_API_KEY`. I checked the names only and never read the values. They leak into `process.env` and break the existing rank test (`sent` is undefined because the TypeSafe route is chosen instead of Eden AI). It fails on untouched `main` in this shell too. Every test run below used `env -u TYPESAFE_API_KEY -u FISH_AUDIO_API_KEY npm run test:functions`. With those two unset the baseline was green (66 checks). CI or a clean shell doesn't need the `env -u`.
- **`npm run test:e2e` was NOT run.** Python `playwright` isn't installed for `python3`. The only installed copy (in another project's venv, 1.62.0) expects Chromium revision 1234, but only 1243 is cached. Making it work meant installing packages or browsers outside this directory, which the brief forbids. Instead I ran a **temporary smoke script**, then deleted it and didn't commit it (results below). It used that venv's Playwright with `executable_path` set to the cached headless shell, against the built web app on the repo's own e2e fixture server.

## Tasks
| # | Task | Status |
|---|---|---|
| 1 | `src/lib/native.js` `isNativeApp()` | Done, exactly as specified |
| 2 | `src/lib/api.js` `apiUrl()` plus every call site | Done. 34 existing call sites wrapped, plus 1 new (report) |
| 3 | `netlify/lib/cors.mjs` `withCors`, applied to all functions, tests | Done |
| 4 | Android CSP meta, `build:android`, `.env.android` | Done |
| 5 | `inviteLink()` uses the fixed public origin | Done |
| 6 | No update toast or install hint when `isNativeApp()`; SW kept | Done |
| 7 | `assetlinks.json` plus a `[[headers]]` rule | Done (placeholders) |
| 8 | `/privacy` and `/delete-account` pages | Done (contact is `CONTACT_EMAIL_PLACEHOLDER`) |
| 9 | `report` function, tests, Report this UI, Made with AI labels | Done |
| 10 | Settings About line with version and build | Done |

### Details and deviations
- **apiUrl.** Returns `path` unchanged unless `VITE_API_BASE` is set. Absolute URLs, `//` URLs and non-strings pass through unchanged. In the web bundle `BASE` is `''`, so behaviour is unchanged. The PRD says about 38 sites; grep finds 34 real call sites in `src/`. The rest of the `/api/` matches are comments, `sw-template.js` pathname checks or `api.js` itself. No `<audio src>`, cover `<img src>` or EventSource used a relative `/api/` URL. Audio comes back as blobs through `ownerPost`, and covers resolve to absolute HTTPS URLs.
- **CORS.**
  - The allowlist is exactly `https://localhost` and `https://mavis-library.netlify.app`. The allowed origin is echoed back, never `*`, and no credentials header is sent.
  - Response headers: `Vary: Origin`, methods `GET, POST, OPTIONS`, the six required headers, and `Access-Control-Expose-Headers: content-length` (the client reads `content-length` on `/api/epub`).
  - A preflight from an allowed origin gets 204. A preflight from a refused origin gets 403 with no CORS headers. An `OPTIONS` request with no `Origin` header still goes to the handler as before.
  - **Deviations, all needed for the feature to work:**
    - `voices` passes `methods: 'GET, POST, DELETE, OPTIONS'`, because deleting a cloned voice uses DELETE.
    - The five POST-only configs (`assistant`, `groups`, `rank`, `sync`, `tts`) now declare `method: ['POST', 'OPTIONS']`. Otherwise Netlify's router would never pass the preflight to the function. Every `path` and `rateLimit` is unchanged.
    - Responses that carry `netlify-cdn-cache-control` (catalog, covers and so on) get `Vary: Origin` even when there is no Origin header. Without it, the CDN could serve a web copy with no CORS headers to the app, or the reverse.
  - Same-origin checks: `onlyPost` and the voices check now use the shared `sameOrigin()`. It accepts no Origin, the same host, or one of the two allowed origins. Every other cross-site origin is still refused; the tests cover `http://localhost`, `https://localhost:8443`, a lookalike suffix and `null`.
- **Android CSP.**
  - Built in `vite.config.js` mode `android` by a `transformIndexHtml` plugin. It reads the policy from `netlify.toml`, so there is one source tree and one policy.
  - It drops `frame-ancestors` and `upgrade-insecure-requests` and adds `https://mavis-library.netlify.app` to `connect-src`, `img-src` and `media-src`.
  - The meta tag goes right after `<meta charset>`, before any script. `'self'` already means `https://localhost` inside the app, so it isn't listed separately.
  - The build fails loudly if `VITE_API_BASE` or the charset line is missing.
  - `.gitignore` now ignores `dist-android/` and un-ignores `.env.android` (it only holds the public URL).
- **Service worker.** `__API_ORIGIN__` is injected at build time: `null` on the web, the public site in the Android build. That keeps the catalog and bible-ext offline caching working for the app's cross-origin API calls. On the web the logic is equivalent, because `null` never matches.
- **Invite links.** `PUBLIC_ORIGIN` is used in both builds, as the brief asks. This means web invites from a deploy preview or local dev now point at production too. The recovery-code text file (`account.js`) uses `location.origin` on the web, as before, and the public site only inside the native app.
- **Install prompt.** There is no `beforeinstallprompt` handler anywhere. The only install UI is the Settings "Installed app" hint, which now reads "Yes, the Android app" when running natively.
- **Settings > Account.** `Account` was only a top-level nav item. I added an `Account` link to the Settings "Accounts" row so the route "Settings > Account > Delete account" named in the PRD actually exists.
- **Privacy page accuracy.** Checked against the code and `docs/HANDOFF.md`:
  - Deleting an account removes `mavis-users` and `mavis-data`, but **not** group membership or posts. The page says so and tells people to leave groups first.
  - It also lists the other services the code really contacts: Free Dictionary API, Google Books and HelloAO.
  - It lists the optional ones the owner can switch on: Anthropic, Google TTS, and TypeSafe or Eden AI (which receive liked genres and recently read titles).
  - "No ads, analytics or tracking cookies" was checked by grep; there are none.
- **Report function.**
  - JSON POST only (`onlyPost`), 4 KB body cap (413), `what` limited to `answer|picture|summary|film`.
  - `ref` is capped at 300 characters and `text` at 1000, both control-character-cleaned. `version` must match `^[\w.+-]{1,40}$`.
  - Soft limit of 10 per minute per IP, plus the platform `rateLimit` of 20 per minute.
  - Stores only `{what, ref, text, version, at}` under `r/<timestamp>-<hex>` in the Blobs store `reports`. No IP, account or email is stored. If Blobs fails it returns 503.
- **Report UI.** `src/lib/report.js` adds a "Report this" button (`btn btn-sm btn-quiet`) and one delegated click handler installed at boot. The dialog has an optional note and shows a toast confirmation. Buttons were added to:
  - Ask Mavis answers;
  - the AI picture view;
  - Story so far (recap) and the Characters summary;
  - scene film results;
  - verse videos, only when an AI picture background was used.

  "Made with AI" badges (the existing `.badge` style) are on scene films, AI-background verse videos and the AI picture gallery heading. The picture viewer already said "Made with AI from …" in its caption.
- **Version.** `__APP_VERSION__` comes from package.json. `__APP_BUILD__` is `''` on the web, and in the Android build it is `MAVIS_BUILD_NUMBER`, or `local` if that isn't set. Settings shows "Version 0.4.0" on the web and "Version 0.4.0 (build N)" in Android, plus a privacy link.

## Files changed (`git diff main --stat`)
```
 .env.android                       |   3 +
 .gitignore                         |   2 +
 ANDROID-PHASE-A-BRIEF.md           |  30 ++++++   (pre-existing branch commit)
 PROGRESS.md                        |  14 +++
 docs/ANDROID-PRD.md                | 206 +++++   (pre-existing branch commit)
 netlify.toml                       |  20 ++++
 netlify/functions/account.mjs      |   5 +-
 netlify/functions/assistant.mjs    |   7 +-
 netlify/functions/bible-ext.mjs    |   5 +-
 netlify/functions/catalog.mjs      |   5 +-
 netlify/functions/covers.mjs       |   5 +-
 netlify/functions/epub.mjs         |   5 +-
 netlify/functions/features.mjs     |   5 +-
 netlify/functions/groups.mjs       |   7 +-
 netlify/functions/librivox.mjs     |   5 +-
 netlify/functions/openlibrary.mjs  |   5 +-
 netlify/functions/rank.mjs         |   7 +-
 netlify/functions/report.mjs       |  48 +++++++++
 netlify/functions/study.mjs        |   5 +-
 netlify/functions/sync.mjs         |   7 +-
 netlify/functions/tts.mjs          |   7 +-
 netlify/functions/voices.mjs       |  13 +--
 netlify/lib/cors.mjs               |  49 +++++++++
 netlify/lib/shared.mjs             |  16 +--
 package.json                       |   3 +-
 public/.well-known/assetlinks.json |  13 +++
 public/delete-account.html         |  63 ++++++++++++
 public/privacy.html                |  94 +++++++++++++++++
 src/lib/api.js                     |  14 +++
 src/lib/assistant-ui.js            |   8 +-
 src/lib/audiobook-player.js        |   3 +-
 src/lib/audiobook.js               |   3 +-
 src/lib/auth.js                    |   3 +-
 src/lib/bible.js                   |   6 +-
 src/lib/catalog.js                 |  12 ++-
 src/lib/companion.js               |  20 ++--
 src/lib/covers.js                  |   3 +-
 src/lib/features.js                |   3 +-
 src/lib/fullcast.js                |   3 +-
 src/lib/groups.js                  |   5 +-
 src/lib/librivox.js                |   3 +-
 src/lib/native.js                  |   5 +
 src/lib/recommend.js               |   3 +-
 src/lib/report.js                  |  65 ++++++++++++
 src/lib/scenefilm.js               |  13 ++-
 src/lib/speech.js                  |   3 +-
 src/lib/sync.js                    |   3 +-
 src/lib/version.js                 |  10 ++
 src/lib/video.js                   |   7 +-
 src/lib/voices.js                  |   9 +-
 src/main.js                        |   5 +-
 src/styles/app.css                 |   3 +
 src/sw-template.js                 |  10 +-
 src/views/account.js               |   4 +-
 src/views/settings.js              |   7 +-
 src/views/words.js                 |   3 +-
 tests/functions/cors.test.mjs      | 121 ++++++++++++++++++++++
 tests/functions/report.test.mjs    | 103 +++++++++++++++++++
 vite.config.js                     |  73 ++++++++++---
 59 files changed, 1092 insertions(+), 95 deletions(-)
```
(RECEIPT.md and the final PROGRESS.md line were added in the last commit, after this stat was taken.)

## apiUrl call sites changed (34 existing, plus 1 new)
- `src/lib/assistant-ui.js:60` `ownerPost(apiUrl('/api/assistant'))`
- `src/lib/audiobook-player.js:74` `/api/tts`
- `src/lib/audiobook.js:147` `/api/tts`
- `src/lib/auth.js:41` `` `/api/account/${action}` ``
- `src/lib/bible.js:63` bible-ext translation, `:68` bible-ext commentary
- `src/lib/catalog.js:107` catalog search, `:112` catalog id, `:118` openlibrary search, `:123` openlibrary work, `:135` epub download
- `src/lib/companion.js:41` picture start, `:47` `?picture=` poll, `:76` summarize, `:207` recap, `:272` characters
- `src/lib/covers.js:39` covers
- `src/lib/features.js:21` features
- `src/lib/fullcast.js:63` study cast
- `src/lib/groups.js:20` groups
- `src/lib/librivox.js:21` librivox
- `src/lib/recommend.js:73` rank
- `src/lib/scenefilm.js:350` motion start, `:355` `?motion=` poll, `:441` storyboard, `:455` tts
- `src/lib/speech.js:125` tts
- `src/lib/sync.js:43` sync
- `src/lib/video.js:252` tts
- `src/lib/voices.js:21` library, `:24` mine, `:33` clone POST, `:37` DELETE
- `src/views/words.js:152` study word
- new: `src/lib/report.js` `/api/report`

(Line numbers are from the moment of wrapping; the import lines added afterwards shift them by 1 or 2.)

## Evidence (real command output)

### `env -u TYPESAFE_API_KEY -u FISH_AUDIO_API_KEY npm run test:functions`
```
> mavis-library@0.4.0 test:functions
> node tests/functions/functions.test.mjs && node tests/functions/accounts.test.mjs && node tests/functions/groups.test.mjs && node tests/functions/cors.test.mjs && node tests/functions/report.test.mjs && node tests/unit/vocab.test.mjs

32 endpoint checks passed.
14 account and sync checks passed.
12 group checks passed.
7 CORS checks passed.
6 report checks passed.
8 word builder checks passed.
exit=0
=== total ✓: 79
```
The new CORS checks:
- allowed origins get their own origin back (never `*`), plus `Vary: Origin`, methods and headers;
- refused origins and same-origin requests get no CORS headers;
- preflight returns 204 with headers for allowed origins and 403 for refused ones, **on every function** (it also checks each config keeps its `/api/` path and lets OPTIONS through);
- OPTIONS without an Origin header still reaches the handler;
- cross-site POSTs stay refused for other origins, and the two app origins pass the origin check;
- CDN-cached answers vary by Origin;
- responses with immutable headers are copied, not mutated.

The new report checks: config; success with the stored fields verified (no IP); field length caps and refusals; oversize body gets 413 and stores nothing; a refused origin gets 403 and stores nothing, while the app origin is accepted with CORS headers; only JSON POSTs; rate limit.

The existing "paid endpoints reject a wrong code and cross-site posts" test and the accounts "cross-site posts and non-JSON bodies are refused" test pass unchanged.

### Builds
```
=== npm run build
✓ built in 1.11s
=== npm run build:android
✓ built in 888ms
=== CSP meta in dist-android/index.html
1
frame-ancestors|upgrade-insecure in dist-android/index.html: 0
CSP meta in web dist/index.html: 0
```
`dist-android/index.html`, head:
```
<meta charset="utf-8" />
<meta http-equiv="Content-Security-Policy" content="default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline' blob:; img-src 'self' data: blob: https://www.gutenberg.org https://gutenberg.org https://covers.openlibrary.org https://*.archive.org https://books.google.com https://*.googleusercontent.com https://mavis-library.netlify.app; font-src 'self' data: blob:; connect-src 'self' https://api.dictionaryapi.dev https://archive.org https://*.archive.org https://*.fal.media https://mavis-library.netlify.app; frame-src 'self' blob:; worker-src 'self' blob:; media-src 'self' blob: https://*.fish.audio https://archive.org https://*.archive.org https://mavis-library.netlify.app; object-src 'none'; base-uri 'self'; form-action 'self'" />
```
`diff` of web vs Android `index.html` (with hashes normalised) shows only that one added line. The service worker is `const API_ORIGIN = null;` on the web and `"https://mavis-library.netlify.app"` in Android. Both pass `node --check`. Bundles: web `Version ${v}`; Android `Version ${v}${` (build ${"local"})`}`.

### No unwrapped relative `/api/` call remains
```
=== quoted /api/ literals in src + index.html NOT wrapped in apiUrl()
src/sw-template.js:65:  const api = url.pathname.startsWith('/api/') && (...)
src/sw-template.js:67:    if (url.pathname.startsWith('/api/epub')) return; ...
src/sw-template.js:68:    if (url.pathname.startsWith('/api/catalog') || url.pathname.startsWith('/api/openlibrary')) {
src/sw-template.js:83:    if (url.pathname.startsWith('/api/bible-ext')) {
src/lib/api.js:7:/** Prefix a relative `/api/...` path with VITE_API_BASE when set; ... */
TOTAL wrapped call sites: 35
```
The remaining matches are service worker pathname checks (not requests) and a doc comment. `index.html` has none.

### Key-pattern grep over the diff and the build output
The diff grep (`sk-…`, `fal_…`, `Bearer <long token>`, `<KEY_NAME> = <value>`) matched one line: `process.env.MAVIS_ACCESS_CODE = 'right-code';` in `tests/functions/cors.test.mjs`. That is the same dummy test value the existing `functions.test.mjs` uses. The broad grep also turns up `'Bearer abc'`, a dummy header in `report.test.mjs`. Everything else is prose naming the services (PRD, brief, privacy page). There are **no real keys**. Over `dist/` and `dist-android/`:
```
build-output key-pattern matches: exit=1 (1 = none)
```

### Browser smoke test (temporary script, deleted, not committed)
Run against `dist/` on `tests/e2e/server.mjs --accounts` with fixture AI and the netlify.toml CSP headers:
```
console errors: []
  PASS settings About shows "Version 0.4.0" (no build number on web), privacy link, Account link
  PASS Ask Mavis answer -> Report this -> dialog -> Send -> confirmation toast (POST /api/report stored in local Blobs, under the netlify.toml CSP)
  PASS privacy.html and delete-account.html render under the site CSP
```

## Not done or not verified
- **`npm run test:e2e` (the browser suite) was not run.** See the top section. The owner should run it in a clean environment before merging.
- I didn't browser-test the Report buttons on pictures, recap, characters, scene films or verse videos. Only the Ask Mavis path was exercised. Those are markup additions reached through the same delegated handler.
- Nothing was run on a device. The Android build was not loaded in a WebView, CORS and CSP were not checked against the live Netlify site, and `cap sync` was not run (Phase B).
- In the APK, a new service worker version will sit in "waiting" until the app is fully closed, because the update toast that triggered `skipWaiting` is hidden. New bundles still load, because navigation is network-first from the bundled files. Check this on a device in Phase B.
- Owner-supplied values are still placeholders:
  - `CONTACT_EMAIL_PLACEHOLDER` (privacy and delete pages);
  - `REPLACE_WITH_PLAY_APP_SIGNING_SHA256` and `REPLACE_WITH_UPLOAD_KEY_SHA256` (assetlinks.json);
  - the application ID `com.mavislibrary.app` still needs the owner's confirmation.
- `docs/HANDOFF.md`, `README.md` and `docs/STATUS.md` weren't updated. That's a PRD deliverable for the full project, not part of this brief.
- Group posts aren't deleted along with an account. That is existing server behaviour, which I documented rather than changed.

## Camera barcode scan receipt (2026-10-12; branch `devin/cover-scan`)

This section records the native Android barcode-scan addition; the older Phase A receipt above remains unchanged. No push or deployment was performed.

### Files

- `native-android/gradle/libs.versions.toml`, `native-android/app/build.gradle.kts`: camera and barcode dependencies.
- `native-android/app/src/main/AndroidManifest.xml`: optional camera hardware and CAMERA permission.
- `native-android/app/src/main/java/com/mavislibrary/app/ui/MavisRoot.kt`: guarded scan route, entry points, pending-ISBN retry on reconnection.
- `native-android/app/src/main/java/com/mavislibrary/app/ui/search/SearchScreen.kt`, `ui/shelf/ShelfScreen.kt`: Scan buttons, hidden in Kids mode and when there is no camera feature.
- `native-android/app/src/main/java/com/mavislibrary/app/ui/scan/BarcodeFrameDecoder.kt`, `PendingScans.kt`, `ScanLogic.kt`, `ScanScreen.kt`: on-device ZXing decoding, DataStore queue, ISBN checks/debounce/resolver, CameraX preview and UI.
- `native-android/app/src/test/java/com/mavislibrary/app/ui/scan/BarcodeFrameDecoderTest.kt`, `OpenLibraryScanResolverTest.kt`, `ScanLogicTest.kt`, `ScanManifestTest.kt`: 10 JVM tests across four suites.
- This root `RECEIPT.md`: final verification and limitations.

### Behavior and dependencies

- First-use camera rationale precedes the runtime permission prompt; denied and permanently denied paths offer retry or app settings respectively. Scanner has manual ISBN entry, flash-conditional torch, viewfinder, lookup states and haptic feedback. Camera binding and analysis executor are released on composition disposal. Kids mode uses `adultRoutesAllowed` at the entry points and route; a JVM test checks the gate.
- A valid ISBN-10 normalizes to ISBN-13; valid 978/979 EAN-13s are accepted. The only search request is the existing `/api/openlibrary?q=isbn:<normalized digits>&page=1`; no image/frame is uploaded. Open Library's existing `q` search is reused; the Netlify function was not changed. Lookup may show a discovered listing, not a freely readable book.
- Offline ISBNs are stored only when the user chooses Save ISBN for later; while the adult app is open, going online retries pending entries, saves found books to the shelf, reports missing books, and leaves transient failures pending. This is not a background WorkManager job.
- New direct dependencies: `androidx.camera:camera-core:1.4.2`, `androidx.camera:camera-camera2:1.4.2`, `androidx.camera:camera-lifecycle:1.4.2`, `androidx.camera:camera-view:1.4.2`, `com.google.zxing:core:3.5.3`. The bundled on-device ML Kit candidate was rejected: the resume brief reports a +23.2 MiB measurement from the previous attempt, which this run did not remeasure. ZXing decoding itself is on-device and needs neither Google Play Services nor network access.
- New permission: `android.permission.CAMERA` (requested from the scan screen only after the rationale). New feature declaration: `android.hardware.camera` with `required="false"`. The built merged debug manifest has this optional feature and no other new permission; existing Internet, network state, foreground media service, notifications, Bluetooth, vibrate, microphone, wake/boot and dynamic-receiver permissions remain.

### Verified locally

- Ran `./gradlew testDebugUnitTest assembleDebug --no-daemon -q` in the background with JDK 21 and Android SDK configured, logging to `native-android/.gradle/scan-build.log`; final command exited 0. Initial run failed two decoder tests because ZXing's YUV source cannot rotate; rotating luminance bytes fixed both, and two subsequent full runs passed.
- Read `native-android/app/build/test-results/testDebugUnitTest/TEST-*.xml`: **37 suites, 152 tests, 0 failures, 0 errors, 0 skipped**. The four scan suites contain 1 manifest + 2 decoder + 5 logic + 2 resolver tests = **10 scan tests**. These verify synthetic EAN-13 rotation, ISBN checks, debounce/repeats, fake resolver states, endpoint parameters and Kids/no-camera route gating; they do not exercise a physical camera.
- Final `native-android/app/build/outputs/apk/debug/app-debug.apk`: **42,113,566 bytes**. Supplied baseline: **39,345,477 bytes**. Difference: **+2,768,089 bytes (+2.64 MiB)**, below the 8 MiB threshold. Package/version in built metadata: `com.mavislibrary.app`, `0.6.0-m2`, code 2.
- `git diff --check` passed for implementation before committing. Implementation commits: `22e0d1d`, `11702a7`; this receipt is the final separate commit.

### Not verified / deferred

- No emulator or physical Android device was run: real-camera focus, barcode decoding, orientation, overlay/torch appearance, haptic feel, hardware absence, permission dialogs and permanently denied behavior remain unverified on-device.
- No live Open Library/Netlify lookup, offline-to-online retry or shelf save was tested end-to-end from the APK. DataStore pending persistence and reconnect behavior were not independently exercised in a JVM test; no background retry after a closed app is provided.
- Only ISBN barcode scanning is implemented. Recognizing a title from a cover image (OCR/visual cover search) is deferred; image data is never sent for scanning.
- Lint, accessibility, APK installation and release signing were not run in this session. The old Phase A limitations above describe a separate historical scope.

## Hermes verification - My tech sites (Codex gpt-6-astra wrote it; hit its usage limit before building/receipt, Hermes built, tested, fixed)
- Own build: 265 tests, 0 fail/0 err (264 from Codex + 1 Hermes regression test).
- Emulator, live internet: "+ Add site" in Discover -> dialog ("Fetched by your phone directly from the site") -> "Try World of AI" -> Find feed -> "Found: World of AI / 118 posts, newest Oct 9, 2026 / https://rss.beehiiv.com/ai_podcasts/5F2wtaPWzH.xml" (the Cloudflare-blocked, no-<link rel=alternate> case) -> Add -> "World of AI" + "Mine" chips + "Manage sites"; stories list with descriptions.
- Refused with "Use an HTTPS address with a public site name, without credentials": 192.168.1.1, http://example.com, localhost, 10.0.0.5/feed, https://127.0.0.1/, https://169.254.169.254/latest.
- BUG found on device: numeric entities in titles showed literally ("&#128202;"). Fix: FeedParser.decodeEntities (numeric dec/hex, surrogate/out-of-range rejected, &amp; last) + test; re-tested, emoji render.
- NOT verified: Manage sites rename/disable/remove on device, "Mine" merge with 2+ sources, offline cache, DNS-rebinding refusal against a real hostile resolver (UrlPolicy.requirePublic runs in the OkHttp Dns hook, JVM-tested only), Kids-mode hiding on device (JVM gate test only), real phone.

## Podcast Lounge — native Android completion (2026-10-12)

Branch: `devin/lounge-app`. The model-switch WIP was reviewed as unverified; the existing partial podcast files were rewritten before testing. First full green implementation checkpoint: `cd9a845`. Nothing was pushed, installed, deployed, or sent to a live studio. No emulator or ADB session was started.

### Implemented

- Adult-only Podcast Lounge at the top of Listen, with the existing five bottom tabs unchanged. Both the adult shell and Lounge enforce the Kids-mode gate; the incoming share is discarded in Kids mode.
- Native source selection: saved Shelf books, Gutenberg title search through the existing catalog API, imported EPUB text through `NativeEpubText`, current native imported-reader chapter / whole-book menu, HTTPS website/article URL, pasted/shared article text, and topic query. Native book detail, Shelf options, Discover/My-tech-sites stories, and topic search results prefill the Lounge.
- EPUB/source text is capped at 400,000 characters, with the actual count and original count when truncated. Large extracted sources use a bounded preview rather than rendering the entire book in a text field immediately. An Open Library listing without supplied text prefills an explicitly labeled topic discussion; the app does not pretend to possess that book's full text.
- Deep dive / Quick brief / Debate / Critique; short / standard / long; Auto or Local-only. Auto is the default studio lane, with NotebookLM when available and local voices as the service's fallback. This is not a replacement for Mavis's realtime voice brain. Health and per-job engine labels are honest; an online health endpoint plus failed authenticated job listing is distinguished from an unreachable studio.
- Exact `/v1` API routes, serialized models, human-readable 422 errors, independent transport, 15-second connect and 120-second read timeouts, no cookies, no proxy, no app/owner-code interceptors, and no followed redirects.
- Persistent local DataStore library containing job metadata, transcript/sources, retry request and completed local-file metadata, partitioned by studio. Three-second job polling, exponential network-failure backoff capped at 30 seconds, terminal failure/ready states, explicit cancellation/deletion, and a 25-minute wait deadline. Polling stops when the Lounge's lifecycle stops and resumes on re-entry; server jobs themselves remain queued and are rediscovered from the list endpoint.
- Existing Media3 playback service/controller, MediaSession, seek/speed controls, sleep timer, drive mode, headset and voice-control paths reused. Studio audio requests carry the key only for the exact configured origin and `/v1/podcasts/{id}/audio` route, support Range, and reject redirects. No token is put in media metadata or URLs. Podcast resume keys use the existing Room resume store and cannot collide across studios; pause also saves position.
- Completed MP3 copies use the existing app-private `AudioFileStore` area with atomic partial-to-complete promotion, studio-separated names, cancellation checks, MIME/size checks and a 250 MB download limit. Local copies are selected before any studio stream. Covers and lock-screen artwork are generated on-device from a gradient and title, without image fetching. Ready cards have Play, Download, Delete and expandable transcript/sources; no share-link feature was added.
- Settings: initially blank studio address, masked/pasteable key, saved-connection health test, default style/length/engine, and explicitly confirmed local age cleanup. Keys reuse AES-GCM encryption with the existing non-exportable Android Keystore key; preferences contain ciphertext, not plaintext. Changing the address clears the old key. Cleanup leaves studio originals untouched, retains unknown dates and unfinished jobs, and prevents aged finished entries from being re-added by reconnect listing.
- Existing SEND `text/plain` routing was extended rather than adding a conflicting handler: HTTPS URLs and article text open a “Podcast this?” review before creation; Bible references retain their route and EPUB imports retain their existing handler. Shared navigation explicitly uses Android's main dispatcher, matching the Bible path. The initial new navigation regression failed until this was fixed.

### Cleartext boundary

`android:usesCleartextTraffic="false"` remains intact. The added network security config has `base-config cleartextTrafficPermitted="false"`; the packaged APK was inspected with `aapt2` to confirm both. Android cannot dynamically add a user-entered domain to that XML policy, and OkHttp `ConnectionSpec` does not override its cleartext restriction. Therefore HTTPS uses the independent OkHttp client normally; the sole private-HTTP studio lane uses a code-scoped, socket-based HTTP/1.1 transport producing OkHttp request/response models. It accepts only the exact user-configured IPv4 origin in RFC1918 or Tailscale 100.64.0.0/10 (including emulator 10.0.2.2), bounds headers and body framing, handles Content-Length/chunking, closes on cancellation, and never follows a redirect. Public HTTP, localhost/loopback, credentials/userinfo, paths/query/fragment in the base address, and other destinations are rejected. WebView and all other app clients remain under the normal cleartext ban. The Settings UI warns that private HTTP itself is unencrypted. No TLS trust or hostname verification was weakened.

### Verification actually run

The mandated background build passed, then was committed. A subsequent lint pass found two pre-existing compatibility errors in reused paths: API-33-only `InputStream.readNBytes` in EPUB extraction, and the deprecated Media3 bad-value result constant. Both were fixed without raising minSdk or suppressing lint. The final background command, after waiting for other Gradle builds to clear, was:

```text
export JAVA_HOME=$HOME/.local/jdks/jdk-21.0.12.1+1
export PATH=$JAVA_HOME/bin:$PATH ANDROID_HOME=$HOME/Android/Sdk
cd native-android
./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon -q
```

- Final command exit status: **0**. Quiet-mode log: `native-android/lounge-final-build.log` (local build log, not committed).
- Counts READ from all 57 `app/build/test-results/testDebugUnitTest/TEST-*.xml` suite headers: **298 tests, 0 failures, 0 errors, 0 skipped**. This includes 31 new podcast tests and 2 EPUB compatibility regressions. No live studio or external service is used by the new tests: API responses, audio bodies and socket streams are fake; coroutine polling uses virtual time.
- New coverage: fixtures for all six statuses and optional fields, health/create/error round-trips, all request kinds and the 400k cap, studio URL/range boundaries and userinfo rejection, exact routes and authorization, 422 detail, foreign-host/port/scheme refusal, redirect refusal, real DataSource Range/ignored-Range behavior with fake audio, private HTTP framing and chunking with in-memory sockets, completed/private downloads and local cleanup, encrypted settings and origin-bound key changes, offline metadata/retry preservation, 3-second polling/backoff/failure/cancellation/25-minute timeout/re-entry, gradient artwork, real Compose share confirmation and Kids isolation, and EPUB extraction on Robolectric API 26. Existing Bible/EPUB sharing and Kids tests also pass.
- Lint report read from `app/build/reports/lint-results-debug.xml`: **0 errors, 18 warnings, 1 hint**. Remaining warnings include existing WebView feature-check/widget/exported-media-service warnings and KTX/modifier suggestions; the hint is a primitive-state suggestion. Protected WebHost owner-code logic and Kids files were not altered.
- `git diff --check`: passed. Protected `ui/kids/*`, `data/kids/*`, `WebHost.kt`, `netlify/`, `src/`, dependencies and toolchain settings have no changes from the WIP checkpoint.
- **New dependencies: none. New permissions: none.** INTERNET and POST_NOTIFICATIONS were already declared. No podcast-ready notification or new permission request was added; first playback reuses the existing media-notification permission flow.
- APK: `native-android/app/build/outputs/apk/debug/app-debug.apk`, **42,566,939 bytes**; **+212,091 bytes (+0.501%)** versus the supplied 42,354,848-byte baseline.
- APK SHA-256: `a9230492d1e4239957141cc23d4c387f2e34dbd9bcd5b324861237bb4b1964bd`.
- Debug build, package `com.mavislibrary.app`, existing version `0.6.0-m2` / code 2, minSdk 26, targetSdk 35, compileSdk 36. APK build SHA is the implementation checkpoint `cd9a845`; it includes the subsequently tested EPUB/Media3 compatibility fixes. The final receipt/compatibility commit does not rebuild or re-sign the APK.

### NOT verified / deliberate limits

- No live backend exists for this worker: actual studio authentication, book/article sourcing, NotebookLM login/generation, local TTS generation and engine fallback have **not** been exercised.
- No emulator or physical phone test: APK installation/launch, real HTTP/Tailscale connectivity, audible streaming, seeking against the studio's real Range implementation, background/screen-off continuity, lock-screen/headset/voice/drive controls, timer fade and resume after process death remain **not verified on-device**. Reusing the existing stack is implementation evidence, not an audible-playback claim.
- Real offline listening after disconnect/reboot and the Android share sheet's appearance on hardware are not verified. JVM fake downloads are bytes, not an audio-codec/acoustics test.
- Polling/downloads are screen-scoped work, not a new generation foreground service. The studio continues its queued work while the Lounge/app is closed; the app checks again on return. No ready notification was implemented. Existing playback remains service-backed.
- Current-chapter extraction applies to the native imported-EPUB Reader. The bundled web reader was not modified, and browser-only imported books are not silently copied out of its storage.
- Release signing/distribution, accessibility review and screenshots were not run. No push or website/backend deployment was performed. The worker's untracked `BRIEF-orig.md` and `BRIEF-resume.md` were left untouched and uncommitted.

### Device/studio handoff checks (not run here)

1. In adult Settings, save the actual iMac/Tailscale studio address and key, then Test saved connection. Confirm the reported engines match the studio and a wrong key does not produce a successful job list.
2. Listen → Podcast Lounge: make a short Local-only topic episode; watch every stage, confirm its engine label, play it, seek, pause/resume, lock the phone, use headset/media controls and a short sleep timer. Then repeat Auto with NotebookLM available and unavailable.
3. Download the ready episode, disconnect network, play/seek it, close and reopen the app, and confirm the stored resume position. Restore connectivity and check server-side queued jobs reappear.
4. Try a Gutenberg book, imported EPUB whole book and current chapter (including text above 400k), tech-site article, topic-search prefill and a shared HTTPS URL. Confirm review happens before submission. Share a Bible reference and an EPUB to ensure their old paths still work.
5. Enable Kids mode and try the same URL share: no adult tabs, Lounge, confirmation dialog or studio request should become reachable. Return through the normal adult gate, then test failed-job Retry, explicit Delete and local age cleanup without deleting studio originals.
