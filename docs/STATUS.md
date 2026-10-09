# Mavis Library — feature status (v0.1.0)

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
| **Mavis AI reading assistant** | Planned (off) | Not built, and nothing in the UI pretends otherwise. |
