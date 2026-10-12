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
