# Mavis Library end-to-end results

Run: 2026-10-09 00:17 EDT · Chromium 141.0.7390.37 (headless, Linux) · 53s

**31 of 31 checks passed.**

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
| ✅ pass | Read-aloud (simulated speech engine): reads the visible page, turns pages, pause/resume/stop |
| ✅ pass | Read-aloud restarts from the new page after manual navigation, and stops when the book closes |
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

## Console and CSP problems seen during the run

- None.
