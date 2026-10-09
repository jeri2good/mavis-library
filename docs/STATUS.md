# Mavis Library — feature status (v0.4.0)

Live at **https://mavis-library.netlify.app** (Netlify, deployed from `main`).

**How each status was established**
- **Live** — exercised against the deployed site and the real outside services (Gutenberg, HelloAO, LibriVox, Open Library, OpenAI, Fish Audio, Netlify Blobs). Calls that need an account or a POST were checked with temporary, access-code-protected probe functions, which were deleted afterwards.
- **Browser** — covered by the 64-check Playwright suite in headless Chromium, with the production security headers and the real function code. Fixtures stand in for the outside services ([report](test-evidence/e2e-report.md), [screenshots](test-evidence/screenshots/)).
- **Function** — covered by the 65 Node checks (`npm run test:functions`). Accounts, sync, and groups run against `@netlify/blobs`' own local server.
- **Not verified** — needs a real phone, car, or long-term use.

## Library and reader

| Area | Status | Notes |
|---|---|---|
| Gutenberg catalog: search, topics, language, sort, curated shelves | Live · Browser · Function | Read from Gutenberg's own OPDS feeds; Gutendex blocks Netlify. Fixed live: books with no author showed their download count ("867 downloads") as the author. |
| Free EPUB download (`/api/epub`) | Live · Browser · Function | Public-domain check, gutenberg.org-only redirects, size cap with smaller-edition fallback, rate limit. |
| Open Library search, borrow and buy links | Live · Browser | Links only; no library-card or DRM integration, and the UI says so. |
| Real covers (`/api/covers`) | Live · Browser · Function | A lookup that fails is cached only briefly, so it retries. |
| Shelf, imports (EPUB, TXT→EPUB, PDF), DRM refusal | Browser | |
| Reader: pagination, TOC, bookmarks, highlights, notes, quotes, typography, themes, focus mode, page animation | Browser | PDFs open in the browser's viewer with reduced features, stated on screen. |
| Dictionary | Browser | English only (Free Dictionary API). |
| EPUB sandbox (no scripts, links confirm) | Browser | Hostile fixture EPUB. |
| Offline / installable PWA | Browser | Install on a real phone: not verified. |

## Listening

| Area | Status | Notes |
|---|---|---|
| Device read-aloud with page turns, sleep timer, media controls | Browser (simulated voice) | Real device voices not verified. |
| Cloud voice (Fish Audio) and car mode | Browser · Function | Same Fish Audio account as the voice library, which was checked live; cloud read-aloud audio itself wasn't re-checked live. Screen-off playback and steering-wheel buttons in a real car: not verified. |
| Whole book saved as audio for offline listening | Browser | Real-phone storage limits on long books: not verified. |
| Voice library and private own-voice copy | Live (list, clone, delete) · Browser · Function | The quality of a cloned voice depends on the recording. |
| Full cast (a voice per character) | Browser · Function | Speaker detection is AI-based and can be wrong; the cast can be edited. |
| LibriVox human-read audiobooks | Live (search, archive.org CORS) · Browser · Function | Six full recordings found for Pride and Prejudice, solo readers listed first; stage adaptations filtered out. |

## Bible

| Area | Status | Notes |
|---|---|---|
| KJV + Strong's, WEB, lookup, concordance, lexicon, cross-references, compare, church display, read-aloud | Browser | Data built into the app; works offline. |
| BSB, ASV, YLT, Geneva with headings and translators' notes | Live · Browser · Function | Fixed live: words joined around footnote markers ("onlySon"). Older cached copies are refreshed by a format version in the request URL. |
| Commentaries (Matthew Henry and six others) | Live (Matthew Henry) · Browser | |
| Interlinear Greek/Hebrew, Nave's topics, reading plans | Browser · Live (interlinear data) | |
| Verse pictures and verse/quote videos | Live (picture service) · Browser | Video uses MediaRecorder; recording on Android Chrome not verified. |

## AI, accounts, and groups

| Area | Status | Notes |
|---|---|---|
| Ask Mavis (assistant with app actions) | Live · Browser · Function | Needs the owner access code. |
| Reading companion: picture, spoken recap, character map, chapter notes | Live · Browser · Function | **Spoiler guard.** Given only Frankenstein's first lines, the model named "Robert Walton" from its own knowledge. Names not found in what the reader has read are now trimmed or removed; re-checked live, no names leak. |
| Word builder (save, spaced-repetition practice, "explain simply") | Live (explain) · Browser · Function | Practice runs on the device; words sync with an account. |
| Kids mode | Browser · Live (children's subject search) | "Children's literature" returned Tess and Bleak House, so the app uses the library "juvenile" headings. The PIN is a convenience lock kept on the device, not security. |
| Accounts, sync, recovery codes | Live (incl. 12 concurrent writes) · Browser · Function | Netlify Blobs; book files and audio never sync. |
| Book clubs and Bible study groups | Live (create, join, 8 simultaneous posts, leader handover, deletion, no emails exposed) · Browser · Function | Invite-code only. Members see display names, never emails. New posts appear within 20 seconds (polling). |
| Paid-feature protection (access code, same-origin POST, rate limits) | Function · Browser | |

## Not done, or limited

- **Android Auto app:** deferred. The PWA's media controls work through the phone's Bluetooth connection; a native Android Auto app would need a separate Android project.
- **Real-device pass:**
  - Android install, voices, microphone, and screen-off audio.
  - A long LibriVox download on a phone.
  - Video recording on Android.
- **Screen reader:** no full TalkBack/VoiceOver audit yet. The keyboard path, labels, and focus handling are tested.
- **Group notifications:** new posts aren't pushed to phones; members see them when they open the group.
- **Gutenberg's robot policy:** it may throttle automated traffic. Mavis fetches one file per tap and lets Netlify's CDN absorb repeats; no throttling seen so far.
