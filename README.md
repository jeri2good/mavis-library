# Mavis Library

*Your books. Your library. Your imagination.*

An installable reading app (Progressive Web App) for Android phones and tablets and desktop browsers. Live at **https://mavis-library.netlify.app**, deployed from this repository by Netlify on every push to `main`.

**Reading**
- **Free books:** Project Gutenberg's own catalog (OPDS), downloaded through a locked-down server endpoint and kept on the device for offline reading.
- **Everything else:** Open Library search, with title-specific links to Libby/OverDrive, hoopla, Kobo, and Google Play Books. Those services handle loans, purchases, and DRM; Mavis says so plainly and never bypasses DRM.
- **Reader:** chapters, page turns, saved position, typography, Paper/Sepia/Night themes, bookmarks, highlights, notes, saved quotes, dictionary, and focus mode. Import your own DRM-free EPUB, TXT, or PDF.
- **Word builder:** save a word from the dictionary with the sentence you found it in. Short practice rounds (choose the word, choose the meaning, fill the blank, spell it) bring it back on a spaced-repetition schedule.

**Listening**
- **Read-aloud** with the device voice, or a **cloud voice** (Fish Audio or Google) that keeps reading with the screen off and answers car and Bluetooth buttons. **Car mode** has huge controls.
- **Whole books as audio**, saved on the device for offline listening, with chapter skip, speed, and sleep timer.
- **Scene film:** select a passage (or use the current page, a Bible verse, or a saved quote) and Mavis plans 2–5 shots, paints them, narrates them, and records a captioned video on your device. Optional real motion per shot comes from fal.ai (about $0.21 per 5-second shot); without it the shots are still pictures with camera moves.
- **Voices:** pick a narrator from Fish Audio's licensed library, or make a private copy of your own voice (with consent). **Full cast:** each character gets a fitting voice.
- **LibriVox:** human-read public-domain audiobooks, streamed from the Internet Archive or saved for offline, in the same player.

**Holy Bible, built in**
- KJV with Strong's numbers, the World English Bible, plus the Berean Standard Bible, ASV, YLT, and Geneva through HelloAO, with section headings and translators' notes.
- Verse lookup ("1 Cor 13:4-7"), concordance search, Greek and Hebrew word by word (interlinear) with grammar, Hebrew/Greek lexicon, cross-references, translation compare, seven commentaries (Matthew Henry, Gill, Clarke, Calvin, and more), Nave's topics, reading plans with streaks, highlights and notes, church display mode, read-aloud, and verse pictures and videos.

**Reading together and AI tools**
- **Book clubs and Bible study groups:** invite with a code, see each other's progress, and talk chapter by chapter. Posts from further along stay hidden until you get there. Share passages from any book or verses from the Bible; groups can follow a Bible reading plan together.
- **Ask Mavis**, an AI reading assistant (Anthropic or any OpenAI-compatible API). The reading companion can picture the scene, give a spoken "story so far" recap, and draw a character map. All three are spoiler-guarded: they only use text up to your page, and the server removes names the reader hasn't met yet. AI discussion questions for clubs work the same way.
- **Verse and quote videos:** narrated square videos made on the device, ready to share.
- **Picked for you:** recommendations from your shelf's genres, optionally ranked by Jev (TypeSafe AI).

**Kids mode** (Settings → Kids mode, protected by a 4-digit PIN)
- Children's books only (Gutenberg's "juvenile" subjects), plus a hand-picked story shelf and Bible stories.
- Bigger text, a daily reading goal with stars, and word practice.
- No store links, and child-safe AI prompts. Settings, accounts, and groups need the PIN.

**Accounts (optional)** keep the shelf, progress, notes, quotes, reading plans, and words in sync across devices, stored in Netlify Blobs. Book files and audio never leave the device.

See [docs/STATUS.md](docs/STATUS.md) for what's tested and how, and [docs/HANDOFF.md](docs/HANDOFF.md) for the architecture.

---

## Run it locally

Requires Node 20+.

```bash
npm ci
npm run build          # writes dist/ (app + generated service worker)
npx netlify-cli dev    # serves the app AND the /api functions on http://localhost:8888
```

`npm run dev` (plain Vite) serves the UI only. The catalog, downloads, and accounts need the functions, so use `netlify dev`.

## Deployment

The site deploys automatically: Netlify builds every push to `main` of `jeri2good/mavis-library`. `netlify.toml` sets the build command, publish folder, functions folder, Node version, and security headers (including the Content Security Policy). Everything runs on Netlify's free plan; functions must finish within about 10 seconds, which every endpoint is designed around.

## Settings in Netlify (environment variables)

Set these in Netlify under **Project configuration → Environment variables**. On the free plan, add them as plain values with the default scopes; "secret" or functions-only variables were silently dropped during setup.

| Variable | What it does |
|---|---|
| `AUTH_SECRET` | Turns on accounts, sync, and groups. A long random string; changing it signs everyone out. |
| `MAVIS_ACCESS_CODE` | Passphrase that unlocks the paid features on a device (enter it in Settings). Without it, paid features stay off. |
| `TTS_PROVIDER` | `fish` or `google` for the cloud voice. |
| `FAL_KEY` | fal.ai key for moving shots in scene films. Optional: `FAL_VIDEO_MODEL` (default Kling 2.5 Turbo image-to-video). Off without the key. |
| `FISH_AUDIO_API_KEY` | Fish Audio key (cloud voice, voice library, voice copies). Optional: `FISH_AUDIO_VOICE_ID`, `FISH_AUDIO_MODEL`. |
| `GOOGLE_TTS_API_KEY` | Google Text-to-Speech key, if `TTS_PROVIDER=google`. Optional: `GOOGLE_TTS_VOICE`. |
| `LLM_PROVIDER` | `anthropic` or `openai` (any OpenAI-compatible API). |
| `LLM_API_KEY` | Key for Ask Mavis, the reading companion, word explanations, and discussion questions. Optional: `LLM_MODEL`, `LLM_BASE_URL`, `LLM_REASONING_EFFORT`. |
| `IMAGE_MODEL`, `IMAGE_LLM_MODEL`, `IMAGE_QUALITY` | Optional picture settings (pictures use the OpenAI Responses API). |
| `TYPESAFE_API_KEY` or `EDENAI_API_KEY` | Jev ranking for "Picked for you". Optional: `JEV_MODEL`. |
| `GOOGLE_BOOKS_API_KEY` | Optional; raises Google Books' cover-lookup limits. |

Keys stay on the server. The browser only ever sends the access code. **Settings → Cloud voice and AI** shows what is switched on.

## Tests

```bash
npm run test:functions   # 65 checks: endpoints, accounts + sync and groups on a real local Blobs server, word builder logic
npm run build
npm run test:e2e         # 64 browser checks (Python Playwright + Chromium); writes docs/test-evidence/
```

The browser suite serves the built app with the production security headers and runs the real function code, with fixtures standing in for Gutenberg, HelloAO, LibriVox, OpenAI, and Fish Audio, and fal.ai. Accounts, sync, and groups run against `@netlify/blobs`' own local server.

## Path to an Android app

The PWA installs from Chrome on Android ("Install app"). For a Play Store package, wrap the live URL as a Trusted Web Activity with [Bubblewrap](https://github.com/GoogleChromeLabs/bubblewrap):

```bash
npx @bubblewrap/cli init --manifest https://mavis-library.netlify.app/manifest.webmanifest
npx @bubblewrap/cli build
```

Then publish the generated `assetlinks.json` at `/.well-known/assetlinks.json` (put it in `public/.well-known/`).

## Project layout

```
src/main.js                boot, routing, shell (incl. kids mode), accounts, service worker
src/views/                 home, kids-home, search, book, shelf, reader, listen, bible, bible-study,
                           quotes, words, groups, account, settings
src/lib/                   store + idb (IndexedDB), sync, auth, catalog, importer, reader helpers,
                           tts/speech, audiobook*, librivox, voices*, fullcast, companion, video,
                           bible, plans, morph, vocab, kids, groups*, features, ui toolkit
public/bible/              KJV (Strong's), WEB, lexicon, cross-references, interlinear, Nave's (generated)
scripts/                   build-bible.mjs, build-bible-extra.mjs regenerate public/bible
netlify/functions/         one file per /api endpoint (see docs/HANDOFF.md)
netlify/lib/               shared validation, Gutenberg OPDS parser, accounts, LLM client, spoiler guard
tests/                     function, unit, and browser tests plus fixtures
docs/                      status, handoff notes, test evidence and screenshots
```

## Credits and sources

**Bible**
- **KJV:** [CrossWire Bible Society](https://crosswire.org). Public domain; Crown copyright in the UK.
- **World English Bible:** [ebible.org](https://ebible.org/web/), public domain.
- **Through [HelloAO](https://bible.helloao.org):**
  - The Berean Standard Bible, ASV, Young's Literal, and Geneva translations (all public domain).
  - Commentaries by Matthew Henry, Jamieson-Fausset-Brown, John Gill, Adam Clarke, Keil & Delitzsch, and John Calvin (public domain).
  - Tyndale Open Study Notes (CC BY-SA 4.0, Tyndale House Publishers).
- **Interlinear and lexicon:**
  - Hebrew: [OSHB](https://hb.openscriptures.org).
  - Greek: STEPBible TAGNT/TVTMS and lexicon, [STEPBible.org](https://www.stepbible.org) (Tyndale House, Cambridge, CC BY 4.0).
- **Cross-references:** [OpenBible.info](https://www.openbible.info/labs/cross-references/), CC BY.
- **Nave's Topical Bible:** BradyStephenson/bible-data, CC BY.

**Books and audio**
- Free books: [Project Gutenberg](https://www.gutenberg.org).
- Human-read audiobooks: [LibriVox](https://librivox.org) volunteers (public domain), hosted by the [Internet Archive](https://archive.org).
- Discovery and covers: [Open Library](https://openlibrary.org) and Google Books.

**Other**
- Definitions: [Free Dictionary API](https://dictionaryapi.dev) (Wiktionary data).
- Reader engine: [epub.js](https://github.com/futurepress/epub.js).
- Fonts: Literata, Source Serif 4, Atkinson Hyperlegible Next, and Fraunces (SIL Open Font License, bundled via Fontsource).
