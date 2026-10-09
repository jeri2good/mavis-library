# Mavis Library — comparison handoff (Claude build, v0.2.0)

Prepared for the side-by-side review with the other implementation. Everything below describes this codebase as built; nothing here assumes any coordination has happened yet.

## 1. Architecture at a glance

```
Browser (PWA, vanilla ES modules, Vite build)
 ├─ views/        home · search · book · shelf · reader · account · settings   (hash routes, lazy-loaded)
 ├─ lib/store     IndexedDB: shelf, progress, annotations, files (book bytes), cache, kv  — all owner-scoped
 ├─ lib/sync      push dirty rows / pull by server cursor ⇄ Supabase PostgREST (RLS)
 ├─ lib/auth      Supabase Auth (PKCE, email+password, reset, optional Google)
 ├─ reader        epub.js in a sandboxed iframe + tts.js + dictionary + selection tools
 └─ sw.js         precached app shell, network-first catalog, capped cover cache

Netlify Functions (fixed upstreams, validated input, not proxies)
 ├─ /api/catalog      → gutendex.com/books   (forces copyright=false + EPUB available)
 ├─ /api/openlibrary  → openlibrary.org/search.json, /works/{id}.json
 └─ /api/epub?id=N    → confirms public domain via Gutendex → streams EPUB from gutenberg.org (≤19 MB)

Supabase (optional): Postgres tables shelf_items · reading_progress · annotations, RLS owner-only, LWW trigger
```

**Key tradeoffs**

- **No framework.** Vanilla modules with a tiny escaped-template helper (`lib/ui.js#html`). Small bundle, no hydration, easy to read; the cost is manual DOM updates. A port to React/Svelte is mechanical if the merged build prefers one.
- **epub.js 0.3.93** is old but stable and the only mature browser EPUB engine with CFIs, pagination, and annotations. Its `@xmldom/xmldom` dependency is overridden to a patched release. Two engine quirks are handled in `reader.js`: CFI display can land a page early (`displayCfi` nudges), and geometry must be measured untransformed (page animation hides with opacity while epub.js measures).
- **Local-first.** Every write lands in IndexedDB first; sync is a background layer. Guest mode is a full product, not a demo.
- **Book files stay on the device.** Syncing binaries would need storage, quotas, and copyright care for imports. Metadata, progress, and notes sync; imported books show “file on another device” elsewhere, and re-importing the same file reattaches it.
- **Read-aloud is page-scoped.** It reads only what is visible (CFI range of the current location), then turns the page itself. That makes highlighting exact and avoids reading hidden text, at the cost of a small pause at page breaks.

## 2. Design rationale

“A quiet reading room.” The owner liked warm green and cream, so the palette keeps that (paper `#f4efe3`, forest `#1f4a37`) and adds a single brass accent (`#9c6a22`) reserved for progress, bookmarks, and the hero’s last line. Typography: Fraunces (soft optical serif) for display only, Atkinson Hyperlegible Next for UI, Literata as the default reading face; all self-hosted so the reader works offline. Generated “cloth” covers stand in when a real cover is missing, so the shelf never shows broken images. Navigation is a bottom tab bar on phones and a slim rail on tablets/desktop. The reader has its own three themes independent of the app theme, and its controls fold away in focus mode.

## 3. File map

| Path | Purpose | Reusable on its own? |
|---|---|---|
| `src/lib/store.js`, `src/lib/idb.js` | Owner-scoped IndexedDB store with dirty flags, LWW apply, guest migration, quota errors | Yes |
| `src/lib/sync.js` | Supabase push/pull with cursors and status events | Yes (needs `auth.js`) |
| `src/lib/auth.js` | Supabase Auth wrapper with friendly errors; inert when unconfigured | Yes |
| `src/lib/tts.js` | `ReadAloud` class for any epub.js rendition; `sentencesInRange()` | Yes |
| `src/lib/voice-input.js` | Accessible listening sheet over Web Speech recognition | Yes |
| `src/lib/importer.js` | EPUB/TXT/PDF validation, DRM detection, TXT→EPUB builder, cover thumbnails | Yes |
| `src/lib/dictionary.js` | Free Dictionary API client with caching | Yes |
| `src/lib/links.js` | Lending and retail deep-link builders with honest copy | Yes |
| `src/lib/catalog.js` | `/api` client, normalization, streaming download with progress | Yes |
| `src/lib/ui.js` | `html` escaping template, icons, toasts, accessible dialogs/sheets, confirm | Yes |
| `src/views/reader.js` | Reader screen (EPUB + PDF) | Depends on the libs above |
| `src/views/*.js` | Other screens | |
| `src/sw-template.js`, `vite.config.js` | Service worker + build-time precache injection | Yes |
| `netlify/functions/*.mjs` | Server endpoints | Yes (Netlify Functions v2 format; Web `Request`/`Response`) |
| `supabase/migrations/0001_mavis_library.sql` | Schema, RLS, LWW trigger | Yes |
| `tests/e2e/server.mjs` | Fixture upstreams + Supabase Auth/REST emulator on PGlite | Useful for testing either build |

## 4. Data model

**Local (IndexedDB `mavis-library` v1).** Record ids are `${owner}|${key}` where owner is `guest` or the Supabase user id.

- `shelf`: `{ key, source: gutenberg|openlibrary|import, sourceId, title, authors[], coverUrl, languages[], subjects[], format: epub|pdf|null, fileName, fileSize, status: want|reading|finished, addedAt, lastOpenedAt, deleted, updatedAt, dirty }`
- `progress`: `{ bookKey, cfi, percent (0–1), chapter, updatedAt, dirty }`
- `annotations`: `{ uid, bookKey, kind: bookmark|highlight|note, cfi, text, color: sun|mint|sky|rose, note, chapter, percent, createdAt, updatedAt, deleted, dirty }`
- `files`: `{ blob, mime, size, savedAt }` — device only
- `cache`: epub.js location maps, imported cover thumbnails — device only
- `kv`: device settings (app theme, motion, reader prefs, voice, rate, sync cursors)

Book keys: `gutenberg:1342`, `openlibrary:OL893415W`, `import:<uuid>`.

**Server (Supabase).** Same three tables with snake_case columns, `user_id uuid default auth.uid()`, `client_updated_at bigint` (client ms) and `updated_at timestamptz` (server clock, pull cursor). Deletes are tombstones (`deleted = true`) so they sync.

## 5. API contracts

All `GET`, JSON errors as `{ error, message }` with 400/404/405/429/451/502/504.

- `GET /api/catalog?search=&topic=&languages=en,fr&page=1&sort=popular|ascending|descending&ids=1,2` → `{ count, page, pageSize: 32, hasNext, hasPrev, results: Book[] }`
- `GET /api/catalog?id=1342` → `{ book: Book }`
  - `Book = { id, title, authors[{ name, birthYear, deathYear }], translators[], subjects[], bookshelves[], languages[], summary, copyright, mediaType, downloads, cover, epub, epubUrl, htmlUrl, sourceUrl }`
- `GET /api/openlibrary?q=&page=` → `{ count, page, pageSize: 20, hasNext, hasPrev, results: [{ id, title, authors[], firstPublishYear, cover, editionCount, languages[], isbn, subjects[], ebookAccess, gutenbergIds[], sourceUrl }] }`
- `GET /api/openlibrary?work=OL893415W` → `{ work: { id, title, description, subjects[], firstPublishDate } }`
- `GET /api/epub?id=1342` → `application/epub+zip` stream (451 if not public domain in the USA)

Caching: browser `max-age` short; `Netlify-CDN-Cache-Control` longer so repeat requests don’t reach Gutendex/Gutenberg.

## 6. Auth and sync

- Supabase Auth with PKCE; session in Supabase’s default storage. Requests carry a bearer token (no cookies), so CSRF doesn’t apply; RLS enforces ownership server-side; a trigger pins `user_id`.
- On sign-in: owner switches to the user id, sync starts, and if guest items exist the user is asked to move them in (files are re-keyed locally).
- Push: dirty rows upserted in batches of 200 (`on_conflict` by key). Pull: `updated_at >= cursor` ordered, 500 per page. Triggers: change (2.5 s debounce), tab visible, back online, every 60 s, “Sync now”.
- Conflicts: last writer wins by client timestamp, enforced both on the client (`applyRemote`) and in the database trigger, so an older device can’t overwrite newer work.
- Sign-out pushes pending changes first; optional wipe removes that account’s rows and files from the device.

## 7. Security notes

- CSP (`netlify.toml`): scripts only from self; connect only to self, `*.supabase.co`, and the dictionary; frames only self/blob; no objects; `frame-ancestors 'none'`.
- EPUBs render in an iframe with `sandbox="allow-same-origin"` (no scripts, forms, or popups); scripts/iframes/forms and `on*` attributes are stripped as well; outbound links require confirmation and open with `noopener`.
- Imports are type-sniffed by magic bytes and size-limited; encrypted EPUBs (other than font obfuscation) are refused as DRM.
- Server endpoints accept only validated parameters and talk only to fixed hosts; `/api/epub` re-checks public-domain status and the final redirect host.

## 8. Test evidence

- `npm run test:functions` — 11 endpoint checks (validation, public-domain refusal, host pinning, HTML-instead-of-EPUB, size fallback, rate limit).
- `npm run test:db` — 13 checks running the real migration in PGlite (RLS isolation for read/insert/update/delete, anonymous denied, annotation hijack blocked, `user_id` pinned, LWW, constraints).
- `npm run test:e2e` — 31 browser checks in Chromium 141 with production headers; 22 screenshots in `docs/test-evidence/screenshots/`. Simulated pieces are listed in [STATUS.md](STATUS.md).

## 9. Known issues

1. **Not deployed yet.** The build sandbox could not reach Netlify; deployment is one CLI command (README).
2. **Gutenberg’s robot policy.** Project Gutenberg says its site is for humans and may block automated traffic. Mavis fetches one file per user tap with an identifying user agent and lets Netlify’s CDN absorb repeats, but Netlify IPs could still be throttled. If that happens, set `GUTENBERG_MIRROR` to a mirror that serves `/cache/epub/{id}/…`; the download screen already offers “Get the EPUB from Gutenberg, then import it” as a fallback.
3. Live upstream formats (Gutendex, Open Library, dictionary) were verified against documentation, not live calls.
4. Read-aloud pauses briefly at each page turn, and a sentence split across pages is read in two parts.
5. Reader display settings are per device (not synced).
6. Percentages appear after epub.js builds a location map on the first open of each book (cached afterward); very long books show “…” for a few seconds.
7. Styling assumes Chrome 111+ (CSS `color-mix`); older Android WebViews will get plainer chrome.

## 10. Highest-value next steps

1. Deploy, then run the browser suite against the live URL with real Gutendex/Gutenberg/Open Library.
2. Real-device pass on an Android tablet and phone: install, voices, microphone, offline, PDF fallback.
3. Supabase project + custom SMTP; verify email confirmation and reset end to end.
4. Search inside a book, and reading statistics (time read, streaks).
5. Optional AI reading assistant behind an explicit setup screen with a real model backend and clear consent before any passage leaves the device.
6. Screen-reader audit (TalkBack, VoiceOver) and a contrast audit of the sepia and night themes.


## 11. What changed in 0.2

- **Bible module** (`src/views/bible.js`, `src/lib/bible.js`, `public/bible/`): static JSON per book, so the Bible works offline and in church without a server. KJV verses are stored as `words{H430,…}` groups (untagged runs end in `{}`), parsed by `tokens()`. Search is a full scan in the browser (fast enough on phones; no index to keep in sync). Annotations use OSIS references (`John.3.16`, `John.3.16-John.3.18`) as their `cfi`, on the shelf key `bible`.
- **Narrator** (`src/lib/speech.js`): one player for device and cloud voices. Sources hand it batches of `{ text, onStart, onEnd }`; the cloud engine groups them into ~1,400-character requests to `/api/tts`, prefetches the next chunk, and estimates which item is playing from character share. The EPUB reader keeps the page-based `ReadAloud` for device voices and uses a chapter source (reads spine sections as text) for the cloud voice.
- **Car mode** (`src/lib/carmode.js`): a view-independent overlay so it survives chapter changes.
- **Paid endpoints** (`tts`, `assistant`, `rank`) require `MAVIS_ACCESS_CODE` via `x-mavis-access`, same-origin POSTs, and rate limits. `/api/features` reports which services are on, never keys.
- **Covers** (`/api/covers` + `src/lib/covers.js`): looked up lazily for covers on screen, three at a time, cached on device (misses retried after 7 days) and on Netlify’s CDN.
- **Recommendations** (`src/lib/recommend.js`): genre profile from shelf subjects weighted by status; candidates from Gutendex topics; optional Jev scores blended 70/30 with the local score.
- **Migration 0002** allows `source = 'bible'` and `kind = 'quote'`.

API additions: `GET /api/covers?title=&author=&isbn=` → `{ cover, source }`; `POST /api/tts {text, speed}` → `audio/mpeg`; `POST /api/assistant {messages, context}` → `{ reply, actions[], provider, model }`; `POST /api/rank {profile, candidates}` → `{ scores }`; `GET /api/features` → `{ owner?, accessCode, cloudVoice, assistant, jev }`.
