# Mavis Library — handoff notes (v0.4.0)

What's here, how it fits together, and why it was built this way. Feature-by-feature test status is in [STATUS.md](STATUS.md).

## 1. Architecture

```
Browser (PWA · vanilla ES modules · Vite build · hash routes, lazy-loaded views)
 ├─ IndexedDB "mavis-library" v3 (all rows owner-scoped: `${owner}|${key}`, owner = guest or user id)
 │    synced:  shelf · progress · annotations · plans · vocab
 │    device:  files (book bytes) · audiobooks + audio (saved audio) · cache · kv (settings)
 ├─ lib/sync        push dirty rows / pull by cursor ⇄ /api/sync      (last-writer-wins on updatedAt)
 ├─ lib/auth        email + password, tokens, recovery codes        ⇄ /api/account/:action
 ├─ reader          epub.js in a sandboxed iframe · ReadAloud (device voice) · Narrator (cloud voice)
 └─ sw.js           precached shell · Bible data and extra translations cached offline · /api/* otherwise live

Netlify Functions (v2, ~10 s limit on the free plan; fixed upstream hosts, validated input)
 ├─ public:   catalog · epub · openlibrary · covers · bible-ext · librivox · features
 ├─ account:  account · sync · groups                       (Bearer token; data in Netlify Blobs)
 └─ paid:     tts · voices · assistant · study · rank       (need MAVIS_ACCESS_CODE via x-mavis-access)
```

## 2. Decisions worth knowing

- **Gutenberg OPDS, not Gutendex.** Gutendex answers Netlify's servers with Cloudflare 403s. `netlify/lib/gutenberg.mjs` parses Gutenberg's own OPDS Atom feeds. Subject search is loose, so kids mode searches `juvenile` (library-catalog headings), not "children's literature".
- **Netlify Blobs instead of a database.** The owner's Supabase quota was used up. Hosted Blobs' conditional writes (If-Match) proved unreliable under bursts, so sync never rewrites a shared blob:
  - Each push writes a new immutable batch, `<uid>/<table>/b/<13-digit ms>-<hex10>`.
  - A pull reads batches newer than the cursor, minus a 2-minute overlap.
  - Old batches are compacted into snapshots (`/s/`) when more than 40 pile up.
  - Verified live with 12 concurrent pushes.
  - Groups follow the same rule: each member writes only their own record, and every post is a new key.
- **Paid features behind an access code.** Cloud voice, voices, AI, and Jev spend the owner's credits. They answer only requests carrying `MAVIS_ACCESS_CODE` (the device enters it once in Settings), plus same-origin and rate-limit checks. Keys never reach the browser.
- **Free-plan environment variables** must be plain, default-scope variables. "Secret" or functions-only scopes were silently dropped during setup.
- **10-second budget.** Every function is written to finish inside it:
  - Covers share one time budget.
  - Voice cloning is split into two requests.
  - Pictures run as background OpenAI Responses jobs that the app polls (`?picture=<id>`).
- **Spoiler guard** (`netlify/lib/textcheck.mjs`). The AI tools only receive text up to the reader's position. A live test showed the model still names characters from its own knowledge of famous books. So character maps, summaries, and recaps are post-checked:
  - Proper names must appear in what the reader has read: the current text, earlier chapter notes, and the words of finished chapters (sent by the app).
  - Names the text partly supports are trimmed ("Margaret Saville" → "Mrs. Saville").
  - Unsupported names, and sentences that mention them, are removed.
  - Book-club posts carry the poster's percentage, and posts beyond your own position are collapsed.
- **Book files and audio stay on the device.** Only metadata, progress, notes, quotes, plans, and words sync.
- **Kids mode** is a device setting (`kidsMode`) with a salted SHA-256 PIN in `kv`:
  - It changes the shell (nav, home, search, reader defaults) and hides grown-ups' shelf books.
  - It adds `x-mavis-kids: 1` to AI calls, and the server then appends child-safe rules (`KIDS_RULES` in `lib/llm.mjs`).
  - Settings, account, and groups need the PIN.
  - It is a convenience lock, not security.

## 3. Server data (Netlify Blobs)

| Store | Keys |
|---|---|
| `mavis-users` | `e/<sha256(email)>` → `{ id }` · `u/<id>` → user (scrypt hashes, `tokenVersion`) · `t/<sha256(email)>` → sign-in throttle |
| `mavis-data` | `<uid>/<table>/b/<batch>` → `{ rows }` · `<uid>/<table>/s/<batch>` → snapshot |
| `mavis-groups` | `g/<gid>/meta` · `g/<gid>/m/<uid>` (member, display name, progress) · `g/<gid>/p/<ms>-<hex8>` (posts) · `inv/<CODE>` → gid · `u/<uid>/<gid>` |

Tokens are `v1.<payload>.<HMAC>` signed with `AUTH_SECRET`. A password reset or "sign out everywhere" increments `tokenVersion`, which ends all sessions.

## 4. API

Errors are always `{ error, message }` with a suitable status code.

**Public**
- `GET /api/catalog?search=&topic=&languages=&sort=&page=` · `?ids=1,2` · `?id=N`
- `GET /api/epub?id=N` — EPUB stream; 451 if the book isn't public domain in the USA.
- `GET /api/openlibrary?q=` · `?work=`
- `GET /api/covers?title=&author=&isbn=`
- `GET /api/bible-ext?tr=BSB&b=John&c=3&f=2` · `?cm=matthew-henry&b=John&c=3` — `f` is a text-format version used to bust caches.
- `GET /api/librivox?gid=&title=&author=` → `{ versions: [{ id, readers, totalSecs, sections }] }`
- `GET /api/features` — which services are on; never keys.

**Account** (Bearer token)
- `/api/account/{signup,signin,me,recover,password,recovery-code,signout-all,delete}`
- `POST /api/sync { changes, cursors }` → `{ rows, cursors, more }`
- `POST /api/groups { action, … }`. Actions:
  - `create`, `join`, `list`, `get`
  - `post`, `delete`, `progress`, `leave`
  - leader only: `invite`, `remove`, `update`
  - `questions` (also needs the access code)

**Paid** (`x-mavis-access`)
- `POST /api/tts`
- `/api/voices` — library, clone, delete.
- `POST /api/assistant { messages, context }`
- `POST /api/study { task: summarize|recap|characters|cast|picture|word, … }` · `GET ?picture=<id>`
- `POST /api/rank`

## 5. Security

- **Content Security Policy** (`netlify.toml`): scripts from the app's own origin only. Network access is limited to the app itself, the dictionary, and archive.org; media to self, blob:, Fish Audio, and archive.org; finished motion clips are fetched from `*.fal.media`. No frames from other sites.
- **EPUBs:** shown in a sandboxed frame with no scripts; script tags and inline handlers are stripped, and outbound links ask first.
- **Imports:** checked by their actual file contents and size-limited; DRM-encrypted EPUBs are refused.
- **Server input:**
  - Every function accepts only validated parameters and talks to fixed hosts.
  - Group posts are length-capped and their references are validated.
  - Cover URLs must be HTTPS.
  - Groups never return emails.

## 6. Tests

- `npm run test:functions`: 65 checks.
  - 31 endpoint checks.
  - 14 account and sync checks.
  - 12 group checks.
  - 8 word-builder checks.
  - The Blobs-backed suites use a real local Blobs server.
- `npm run test:e2e`: 64 browser checks. Four local servers run:
  - public;
  - accounts;
  - paid features on fixtures;
  - accounts with paid features (for groups).
- Two timing-sensitive checks were hardened this release, and the suite passed twice in a row.
- The fixture server (`tests/e2e/server.mjs`) imitates Gutenberg OPDS, HelloAO, LibriVox, OpenAI chat and image responses, Fish Audio, and the fal.ai queue. `/__test/*` endpoints expose what was called.

## 7. Known issues and next steps

1. **Real-device pass on an Android phone and tablet:**
   - install;
   - voices and microphone;
   - screen-off cloud audio and car buttons;
   - a large LibriVox download;
   - video recording.
2. **Android Auto** (deferred): needs a native companion app with a media browser service; the PWA can't appear in Android Auto by itself.
3. **Group push notifications** (currently polling every 20 seconds while a group is open).
4. **Screen-reader audit** (TalkBack, VoiceOver).
5. **Spoiler guard limits:** it checks names, not events. A model could still paraphrase a later plot point without naming anyone. The prompts forbid it and only earlier text is sent, but it isn't mechanically checked.
6. Reader display settings are per device (not synced).

## Scene films
- Client: `src/lib/scenefilm.js` (maker dialog + canvas/MediaRecorder renderer). Server: `study.mjs` tasks `storyboard`, `picture` (scene/look/shape) and `motion`; `GET ?motion=<job>` polls. `netlify/lib/motion.mjs` wraps the fal.ai queue; job tokens only point at queue.fal.run, results only at `*.fal.media`.
- Motion needs `FAL_KEY` (set in Netlify). Without it the checkbox is disabled and shots are still pictures with camera moves. Cost is about $0.21 per 5 s shot.
- Failed or declined shots fall back to the still picture.
