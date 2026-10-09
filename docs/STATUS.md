# Mavis Library — feature status (v0.2.0)

**Legend.** *Tested* = exercised in a real browser (headless Chromium) or real database engine with assertions. *Simulated* = tested, but with a stand-in for an outside service or device capability that the build sandbox doesn't have. *Inspected* = code reviewed, not executed. *Unverified* = needs a live deployment or real device.

Build sandbox limits: no outbound access to Gutendex, Project Gutenberg, Open Library, dictionaryapi.dev, Netlify, or Supabase; no speech voices or microphone; one browser engine (Chromium 141, headless, Linux). Full run log: [test-evidence/e2e-report.md](test-evidence/e2e-report.md).

| Area | Status | Evidence and limits |
|---|---|---|
| **Discovery** — search by title/author, curated classics (live ids), topic shelves, language filter, sort, pagination, loading skeletons, empty and error states with retry | Implemented · Tested (simulated upstream) | Real `/api/catalog` code against Gutendex-shaped fixtures. Live Gutendex responses unverified from the sandbox. |
| Open Library “All books” search and work descriptions | Implemented · Tested (simulated upstream) | Never implies a book is free or available. |
| Cover images with typographic fallback | Implemented · Tested | Broken covers swap to a generated cloth cover. |
| **Free ebooks** — download through `/api/epub` | Implemented · Tested (simulated upstream) | Numeric id only, public-domain check, gutenberg.org-only hosts, EPUB content-type, 19 MB streaming cap, smaller-edition fallback, per-client rate limit, CDN caching. **Unverified:** whether Gutenberg throttles Netlify IPs (see Known issues). |
| Source links and regional-rights notice | Implemented · Tested | |
| **Public libraries** — Libby/OverDrive, hoopla title search, library finders | Implemented · Tested (link targets) | Links only. No library-card integration, loan sync, or DRM reading, and the UI says so. Link formats should be spot-checked live. |
| **Purchasing** — Kobo, Google Play Books (ISBN when known) | Implemented · Tested (link targets) | External checkout; no prices shown or collected. |
| **Shelf** — Reading / Want to read / Finished, filter, sort, file filters, progress, on-device badges, imported labels, “file on another device”, safe removal with confirm + undo, storage meter | Implemented · Tested | |
| **Import** — EPUB, TXT (converted to EPUB), PDF; rejects non-ZIP, broken container, DRM-encrypted, oversized, unsupported types | Implemented · Tested | Limits: EPUB 60 MB, TXT 10 MB, PDF 100 MB. |
| **Reader** — epub.js pagination, two-page spreads on wide screens, table of contents, keys/buttons/tap zones/swipe, saved position across reload, slider by percentage | Implemented · Tested | Swipe gesture code inspected; tap zones tested at 360 px. |
| Typography — 4 bundled typefaces + publisher default, size, line spacing, margins, alignment; Paper/Sepia/Night | Implemented · Tested | Settings are per device. |
| Bookmarks (multiple), highlights (4 colors), notes, panel listing with jump/edit/delete | Implemented · Tested | Persist across reload; sync tested via emulator. |
| Dictionary — from selection, typed, or spoken | Implemented · Tested (simulated API) | English only (Free Dictionary API). |
| EPUB safety — sandboxed frame without scripts, scripts/handlers stripped, CSP, external links confirm first | Implemented · Tested | Hostile fixture EPUB proves scripts and inline handlers never run. |
| PDF | Limited · Tested | Shown in the browser’s own viewer with an “open in new tab” fallback. Bookmarks, highlights, read-aloud, progress are disabled for PDFs and the screen says so. Android Chrome can’t render PDFs inline; the new-tab button is the path there. |
| **Read-aloud** — reads only the visible page sentence by sentence with highlight, turns pages, crosses chapters, play/pause/resume/stop, previous/next sentence, voice and speed, sleep timer (minutes or end of chapter), media-session controls, wake lock, stops on close/book switch, restarts after manual page turns, discloses online voices | Implemented · Tested (simulated speech engine) | Real device voices unverified. “Pause” intentionally restarts the current sentence (Android Chrome’s native pause is unreliable). |
| **Voice search / lookup** — mic only after a tap, listening state, cancel, shows recognized text, permission-denied and unsupported handling, privacy note | Implemented · Tested (simulated recognizer) | Real microphone and provider behavior unverified. Firefox has no speech recognition; typing fallback shown. |
| **Focus mode** — hides controls, optional fullscreen, visible exit button, Escape, tap to peek | Implemented · Tested | Fullscreen request is best-effort. |
| **Page animation** — slide / fade / none; honors reduced motion and an app-wide motion setting | Implemented · Tested | Geometry is measured with the view untransformed so positions stay exact. |
| **Offline / PWA** — manifest, icons (any + maskable), generated precache service worker, offline reopen of shell and downloaded book, catalog fallback message | Implemented · Tested | Auth/sync traffic and book downloads are never cached by the service worker. Install prompt on a real phone unverified. |
| **Accounts** — sign up (with email confirmation), sign in, wrong-password message, password reset and set-new-password, sign out with optional on-device wipe, Google (flag) | Implemented · Tested (Supabase emulator) | Emulator implements the Auth/REST calls Mavis makes against the real migration in Postgres (PGlite). Reset email delivery and Google OAuth unverified. |
| Guest → account migration | Implemented · Tested (emulator) | |
| Cross-device sync of shelf, progress, bookmarks, notes; last-writer-wins conflicts; per-user isolation | Implemented · Tested (emulator + DB tests) | Two browser contexts as two devices; a third account sees nothing. 13 database checks on RLS and LWW. Book files never sync (stated in UI). |
| Accessibility — semantic landmarks, labeled icon buttons, focus trap and return in dialogs, Escape, visible focus, 44 px targets, reduced motion, contrast-minded palettes | Implemented · Partly tested | Keyboard path and dialog focus tested. No screen-reader pass yet. |
| Responsive — 360 px phone, tablet, desktop | Implemented · Tested at 360 px and 1280 px | No sideways scrolling on any screen. Tablet portrait/landscape inspected only. |
| Older Android | Unverified | Uses CSS `color-mix()`, container query units, `Intl.Segmenter` (with fallbacks for the last). Expect Chrome 111+ for full styling. |
| **Public deployment** | **Not done** | The sandbox cannot reach Netlify. Ready to deploy with one command; see README. |
| **Mavis AI reading assistant** | Implemented · Tested (simulated provider) | See “Ask Mavis” below. Off until the owner adds keys. |

## Added in 0.2

| Area | Status | Evidence and limits |
|---|---|---|
| **Holy Bible** — KJV with Strong’s tags (31,102 verses, 343,937 tagged word groups) and WEB (31,103 verses), book/chapter picker, translation switch and side-by-side, swipe between chapters | Implemented · Tested | Data generated from CrossWire KJV, WEB, STEPBible TBESH/TBESG, OpenBible cross-references (`scripts/build-bible.mjs`). About 5 MB compressed; cached for offline on first open. |
| Verse lookup (“1 Cor 13:4-7”, “ps 23”, “jn 3 16”) | Implemented · Tested | |
| Concordance search: words (all must appear), exact phrase in quotes, Strong’s number (H/G); scope by testament or book; counts by book; highlighted matches; load more | Implemented · Tested | Full-Bible search runs in the browser (no server). |
| Strong’s lexicon sheet (lemma, transliteration, gloss, definition) and “every verse with Gxxxx” | Implemented · Tested | Definitions trimmed to ~900 characters. |
| Cross-references (top OpenBible votes) and KJV/WEB compare | Implemented · Tested | |
| Verse highlights (4 colors), notes, saved quotes, copy/share with citation | Implemented · Tested | Stored as annotations on the `bible` shelf item; sync via migration 0002. |
| Church display mode (full-screen large verses, arrow keys, Escape) | Implemented · Tested | Fullscreen request is best-effort. |
| Bible read-aloud: verse by verse, continues into next chapter/book, listen bar | Implemented · Tested (simulated speech engine) | |
| **Saved quotes** page (quotes, highlights, notes from every book and the Bible; filter; copy/share/delete; jump back) | Implemented · Tested | |
| Save quote / share quote from a text selection in any book | Implemented · Tested | |
| **Real covers** via `/api/covers` (Open Library by ISBN or title, then Google Books), only for covers on screen, cached on device and CDN | Implemented · Tested (simulated upstream) | Live match quality unverified from the sandbox. |
| **Picked for you** from shelf genres; Jev re-ranking through Eden AI when configured | Implemented · Tested (simulated Jev) | Jev’s Eden AI endpoint is marked alpha; format checked against Eden’s published example, not a live call. |
| **Car mode**: full-screen dark player, 150 px play button, ±skip, sleep timer, exit | Implemented · Tested | |
| **Cloud voice** (`/api/tts`, Fish Audio or Google) playing through an audio element with media-session controls; reads by chapter without the screen; follows along when visible | Implemented · Tested (simulated voice service) | Screen-off playback and steering-wheel buttons need a real phone to verify. |
| **Ask Mavis** (`/api/assistant`, Anthropic or OpenAI-compatible): consent first, chapter + selection as context, tools for read aloud, stop, go to chapter/verse, car mode, define | Implemented · Tested (simulated provider) | Real answers depend on the provider key. |
| Paid-feature protection: owner access code, same-origin POST, rate limits, keys only in server env | Implemented · Tested | 7 new endpoint tests. |
| Motion and phone polish: screen transitions, staggered cards, cover fade-in, tab indicator, tighter phone layouts, 5-tab bar | Implemented · Tested at 360 px | Respects reduced motion. |

