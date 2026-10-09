# Mavis Library

*Your books. Your library. Your imagination.*

An installable reading app (Progressive Web App) for Android tablets, phones, and desktop browsers. Discover and read free public-domain books, find library and store editions of anything else, import your own DRM-free books, highlight and take notes, look up words, and listen with read-aloud.

- **Free books:** Project Gutenberg catalog through Gutendex, downloaded via a locked-down server endpoint and stored on the device for offline reading.
- **Everything else:** Open Library search, with title-specific links to Libby/OverDrive, hoopla, Kobo, and Google Play Books. Those services handle accounts, loans, purchases, and DRM; Mavis says so plainly.
- **Reader:** epub.js with chapters, page turns (swipe, tap zones, keys), saved position, typography, Paper/Sepia/Night themes, bookmarks, highlights, notes, dictionary, focus mode, and read-aloud.
- **Holy Bible, built in:** KJV (with Strong’s numbers) and the World English Bible, verse lookup (“1 Cor 13:4-7”), concordance search by word, phrase, or Strong’s number, Hebrew/Greek lexicon, cross-references, translation compare, highlights, notes, saved quotes, church display mode, and read-aloud. Works offline after the first open.
- **Real covers:** looked up from Open Library and Google Books, with designed covers as the fallback.
- **Picked for you:** recommendations from the genres on your shelf; optionally ranked by Jev (TypeSafe AI, via Eden AI).
- **Car mode and cloud voice:** large driving controls; an optional cloud voice (Fish Audio or Google) keeps reading with the screen off and responds to car/Bluetooth buttons.
- **Ask Mavis:** an optional AI reading assistant (Anthropic or any OpenAI-compatible API) that summarizes, explains, and can start read-aloud or jump to a chapter.
- **Saved quotes:** save, copy, and share passages with their citation from any book or the Bible.
- **Accounts (optional):** Supabase Auth plus Postgres with row-level security. Shelf metadata, progress, bookmarks, notes, and quotes sync across devices. Book files never leave the device.

See [docs/STATUS.md](docs/STATUS.md) for what's implemented and tested, and [docs/HANDOFF.md](docs/HANDOFF.md) for the architecture and comparison notes.

---

## Run it locally

Requires Node 20+.

```bash
npm ci
npm run build          # writes dist/ (app + generated service worker)
npx netlify-cli dev    # serves the app AND the /api functions on http://localhost:8888
```

`npm run dev` (plain Vite) serves the UI only; the catalog and downloads need the functions, so prefer `netlify dev`.

## Deploy to Netlify (public HTTPS URL)

The `/api/*` endpoints are Netlify Functions, so deploy with Git or the CLI. (Netlify's drag-and-drop "Drop" page does not deploy functions.)

**Option A — CLI from your computer (about 3 minutes):**

```bash
npm ci
npx netlify-cli login             # opens Netlify in your browser to approve
npx netlify-cli deploy --build --prod
```

The first run asks you to create or pick a site; choose a name such as `mavis-library` to get `https://mavis-library.netlify.app`.

**Option B — Git:** push this folder to a GitHub repository, then in Netlify choose *Add new site → Import an existing project*. `netlify.toml` already sets the build command, publish folder, functions folder, Node version, and security headers.

Both options work on Netlify's free plan. Check Netlify's current function limits if traffic grows.

## Turn on the cloud voice, Ask Mavis, and Jev (optional)

These use your paid accounts, so they are switched on with server-side environment variables in Netlify (**Site configuration → Environment variables**, scope **Functions**) and only answer devices that hold your access code. Keys never reach the browser.

1. `MAVIS_ACCESS_CODE` — any passphrase. Enter the same code on each of your devices under **Settings → Cloud voice and AI**.
2. Cloud voice: `TTS_PROVIDER=fish` with `FISH_AUDIO_API_KEY` (and optionally `FISH_AUDIO_VOICE_ID`), or `TTS_PROVIDER=google` with `GOOGLE_TTS_API_KEY`. Fish Audio’s API is billed from API credit, which may be separate from a Fish Audio app subscription.
3. Ask Mavis: `LLM_PROVIDER=anthropic` (or `openai` for any OpenAI-compatible API) with `LLM_API_KEY`; optional `LLM_MODEL` and `LLM_BASE_URL`.
4. Jev ranking: `TYPESAFE_API_KEY` (TypeSafe’s own API), or `EDENAI_API_KEY`.
5. Redeploy. **Settings → Cloud voice and AI** shows what is switched on.

## Turn on accounts and sync (optional)

Without these steps the app runs in guest mode and says so on the Account screen.

1. Create a free project at [supabase.com](https://supabase.com).
2. In **SQL Editor**, run `supabase/migrations/0001_mavis_library.sql`, then `0002_bible_and_quotes.sql`. (Or `supabase db push` with the Supabase CLI.)
3. In **Authentication → URL Configuration**, set *Site URL* to your deployed URL and add it under *Redirect URLs*.
4. Keep **Confirm email** on (default). Supabase's built-in email sender is rate-limited and meant for testing; add custom SMTP under *Authentication → Emails* before inviting many people.
5. In Netlify **Site configuration → Environment variables**, add:
   - `VITE_SUPABASE_URL` — Project Settings → API → Project URL
   - `VITE_SUPABASE_ANON_KEY` — Project Settings → API → `anon` public key
   - optional `VITE_AUTH_GOOGLE=true` after enabling the Google provider in Supabase
6. Redeploy (these values are baked in at build time).

The anon key is designed to be public; every table is protected by row-level security so each signed-in person can only read and write their own rows. Never put the `service_role` key in this app.

## Tests

```bash
npm run test:functions   # server endpoint validation, public-domain checks, size caps, rate limit (mocked upstream)
npm run test:db          # runs the real migration in PGlite: RLS isolation, last-writer-wins, constraints

# Browser tests (Python Playwright + Chromium):
npm run build
VITE_SUPABASE_URL=http://localhost:4322/sb VITE_SUPABASE_ANON_KEY=test-anon-key npx vite build --outDir dist-auth
npm run test:e2e         # writes docs/test-evidence/e2e-report.md and screenshots
```

The browser suite serves the built app with the production security headers, runs the real function code against fixture upstreams, and emulates the Supabase Auth/REST calls against the real migration in PGlite. See the report for exactly what was simulated.

## Path to an Android APK

The PWA is installable today from Chrome on Android ("Install app"). For a Play Store package, wrap the deployed URL as a Trusted Web Activity with [Bubblewrap](https://github.com/GoogleChromeLabs/bubblewrap):

```bash
npx @bubblewrap/cli init --manifest https://<your-site>/manifest.webmanifest
npx @bubblewrap/cli build
```

Then host the generated `assetlinks.json` at `https://<your-site>/.well-known/assetlinks.json` (add it under `public/.well-known/`). The TWA uses the same code, storage, and service worker as the web app.

## Project layout

```
index.html                 app entry
src/main.js                boot, routing, shell, account wiring, service-worker registration
src/views/                 home, search, book, shelf, reader, bible, quotes, account, settings
src/lib/                   store (IndexedDB), catalog client, importer, tts + speech (Narrator),
                           car mode, assistant UI, bible, covers, recommend, features, quotes,
                           voice input, dictionary, auth, sync, links, fonts, ui toolkit
public/bible/              KJV (Strong’s), WEB, lexicon, cross-references (generated)
scripts/build-bible.mjs    regenerates public/bible from the source packages
src/sw-template.js         service worker (precache list injected at build)
src/styles/app.css         design tokens and all styles
netlify/functions/         catalog, openlibrary, epub, covers, tts, assistant, rank, features (/api/*)
netlify/lib/shared.mjs     shared validation, timeouts, soft rate limiting
supabase/migrations/       database schema with row-level security
public/                    manifest and icons
tests/                     endpoint, database, and browser tests plus fixtures
docs/                      status matrix, handoff notes, test evidence
```

## Credits and sources

Bible: KJV from the [CrossWire Bible Society](https://crosswire.org) module (public domain; Crown copyright in the UK); [World English Bible](https://ebible.org/web/) (public domain); Strong’s lexicon from [STEPBible.org](https://www.stepbible.org) (Tyndale House, Cambridge, CC BY 4.0); cross-references from [OpenBible.info](https://www.openbible.info/labs/cross-references/) (CC BY). Free books: [Project Gutenberg](https://www.gutenberg.org) via [Gutendex](https://gutendex.com). Discovery: [Open Library](https://openlibrary.org). Definitions: [Free Dictionary API](https://dictionaryapi.dev) (Wiktionary data). Reader engine: [epub.js](https://github.com/futurepress/epub.js). Fonts: Literata, Source Serif 4, Atkinson Hyperlegible Next, Fraunces (SIL Open Font License, bundled via Fontsource).
