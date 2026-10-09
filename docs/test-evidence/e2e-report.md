# Mavis Library end-to-end results

Run: 2026-10-09 09:08 EDT · Chromium 141.0.7390.37 (headless, Linux) · 148s

**45 of 47 checks passed.**

| Result | Check |
|---|---|
| ✅ pass | Home loads anonymously with live catalog rows and cover fallbacks |
| ✅ pass | Search by title/author, pagination, and language filter |
| ✅ pass | Search failure shows an error with a working retry; empty results explain next steps |
| ✅ pass | Open Library discovery with honest borrow and buy links |
| ✅ pass | Free book details, real download through /api/epub, and reader opens |
| ✅ pass | EPUB content is sandboxed: scripts and inline handlers never run; external links ask first |
| ✅ pass | Table of contents, page turning (keys and buttons), and saved position after reload |
| ✅ pass | Bookmarks: add, list, jump back, remove |
| ✅ pass | Highlights and notes: create from a selection, persist across reload, list and delete |
| ✅ pass | Dictionary lookup from a selection and by typing |
| ✅ pass | Display settings: night theme, larger text, font, margins apply live |
| ✅ pass | Immersive focus mode hides controls and exits with Escape or the visible button |
| ✅ pass | Page-turn animation runs and follows navigation |
| ❌ fail | Read-aloud (simulated speech engine): reads the visible page, turns pages, pause/resume/stop — AssertionError: ['and the light became the only thing anyone could trust.'] |
| ❌ fail | Read-aloud restarts from the new page after manual navigation, and stops when the book closes — TimeoutError: Locator.click: Timeout 30000ms exceeded. |
| ✅ pass | Imports: EPUB, TXT (converted), PDF; rejects malformed, DRM, oversized, and unsupported files |
| ✅ pass | Shelf: collections, filter, sort, safe removal with confirmation and undo |
| ✅ pass | Keyboard only: reach search, submit, and dialogs trap and return focus |
| ✅ pass | Accounts not configured: account page says so plainly; guest mode keeps working |
| ✅ pass | Voice search (recognized speech) |
| ✅ pass | Voice search (permission denied) |
| ✅ pass | Voice search (unsupported browser) |
| ✅ pass | Reduced motion: pages turn without animation |
| ✅ pass | Phone layout at 360px: no sideways scrolling; reader works with swipes and tap zones |
| ✅ pass | Offline: app shell and a downloaded book reopen with no network after one online visit |
| ✅ pass | Installable PWA: manifest, icons, and service worker are valid |
| ✅ pass | Guest shelf → sign-up → migration into the account and push to the server |
| ✅ pass | Reading progress and notes sync to a second device; book files do not |
| ✅ pass | Conflict: the most recent change wins on both devices |
| ✅ pass | Another account cannot see this user's shelf |
| ✅ pass | Sign-out (with remove-from-device), wrong password, and email-confirmation flows |
| ✅ pass | Bible opens at a reference with verse focus, chapter navigation, and offline caching |
| ✅ pass | Verse lookup parses references like "1 Cor 13:4-7" and "ps 23" |
| ✅ pass | Bible verses: highlight, save as quote, note, copy with reference — and they persist |
| ✅ pass | Strong's concordance: tap a KJV word for Greek/Hebrew, then list every verse using it |
| ✅ pass | Concordance word and phrase search with scope, counts by book, and highlighted matches |
| ✅ pass | Cross-references and translation compare for a verse |
| ✅ pass | Church display mode shows large verses, steps with arrow keys, exits with Escape |
| ✅ pass | Bible read-aloud with the device voice follows verses and continues into the next chapter |
| ✅ pass | Owner access code unlocks cloud voice, AI, and Jev; a wrong code is refused |
| ✅ pass | Car mode with the cloud voice: MP3 audio from /api/tts plays, big controls pause and exit |
| ✅ pass | Ask Mavis: consent first, answers about the open chapter, and can start read-aloud |
| ✅ pass | Books: save a quote from a selection, share/copy with citation, and jump back from Saved quotes |
| ✅ pass | Ask Mavis inside a book sends the chapter and answers |
| ✅ pass | Cloud voice in a book reads the chapter as audio and follows along |
| ✅ pass | Picked for you: recommendations from shelf genres, ranked by Jev when available |
| ✅ pass | Phone at 360px: Bible, search, quotes, and settings fit without sideways scrolling |

## Console and CSP problems seen during the run

- None.
