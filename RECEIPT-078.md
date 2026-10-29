# RECEIPT-078 — Android 0.7.8 (build 108)

Branch `reader-api`, Android only. No `netlify/` changes, no pushes, no live accounts or podcast jobs. Every test uses fake HTTP.

## Commits
- `0483297` Sites tab mirrors the server briefing site list (Feature 1)
- `4489d7c` Page sites without owner access ask for the owner code (Also fix)
- `fe96759` Forgot password on the sign-in form (Feature 2)

## Feature 1: "Sites you follow" mirrors the briefing list
- `data/feeds/FeedStore.kt`: new `addMissing(sites)`. It adds only sites whose normalized feed URL (and id) is not already followed. It never removes or rewrites local sites, skips invalid URLs, keeps `source` (`feed`/`page`), and respects the 20-site limit.
- `data/podsub/PodsubRepository.kt`: new read-only `serverSites()`, which is just `GET /api/podsub`.
- `data/podsub/PodsubModels.kt`: `SiteRule.toFeedSource()`. It keeps the server id, name, siteUrl and source. Mode and delivery map to the local podcast mode and Top N.
- `ui/sites/SitesViewModel.kt`: `mirrorServerSites()` runs once when the screen loads (before the existing podsub sync) and once on each refresh ("Check for new posts", the Sites tab's refresh action). Failures are swallowed: the local list shows as-is with no error banner. Without an owner code or sign-in, the gateway throws 401 before any HTTP call, so nothing happens. New sites load through the existing sources collector, and page sites go through the `FeedRepository` page reader path.
- Tests:
  - New `ui/sites/SitesMirrorTest.kt` (4 tests, fake gateway HTTP): empty phone + 3 server sites gives 3 sites, and the page site keeps `source=page` and loads via `/api/podsub/page`; overlap is not duplicated and local-only sites stay; refresh picks up new server sites; a failed fetch leaves the local list untouched with no error and no PUT.
  - New test in `FeedStoreTest`: `addMissing` normalization, dedupe, source and limit.
  - `SitesViewModelTest`: the fake server site's feedUrl now matches the site the test follows (`https://blog.com/`). The old one was a different URL, so the new mirror correctly added it as a second site and broke that test's `.single()` assumptions.

## Feature 2: Forgot password
- `data/api/AccountRepository.kt`: new `resetPassword(email, recoveryCode, password, confirm)`. It rejects blank fields or mismatched passwords locally, so nothing is sent. Otherwise it calls the existing `authenticate("recover", …)`, which saves the session through the SecureStore sign-in path. It returns the new recovery code and never stores it. The server's error message is shown as-is; only when a 401 comes back with no message does it show "That email and recovery code don't match."
- `ui/settings/AccountSettingsSection.kt`:
  - The sign-in form has a "Forgot password?" text button.
  - The recover form has: email (carried over from sign-in), recovery code, new password, confirm new password (with an inline mismatch hint), the "Reset password" button, the line "No recovery code? Ask the owner to reset your password.", and "Back to sign in".
  - On success, the one-time secure dialog shows "Save this. Your old recovery code no longer works." and a Copy button. Copy puts the code on the clipboard only, flagged sensitive on Android 13+.
  - Password, confirm and code fields are cleared after submit.
- Tests in `SecureStoreTest` (4 new, fake HTTP): success (request body, session saved, only token and user id stored), mismatch (server message, nothing saved, plus the fallback text), weak password (server explanation), confirm mismatch (no HTTP call).
- `PodcastNavigationTest`: updated for the new flow. It now opens Forgot password from the sign-in form and checks the confirm field, the "Reset password" button and the owner line.

## Also fix: page sites without an owner code
- `data/feeds/FeedRepository.kt`: added `OWNER_CODE_NOTE` and `needsOwnerCode()`. When a page site gets a 401/403 (including the no-credentials 401), the note now says "Add your owner code in Settings to load this site".
- `data/reader/ReaderApi.kt`: when adding a site, a 401/403 page preview shows the same message instead of "No readable articles found".
- Test: new `PageTest.pageAndPreviewWithoutOwnerAccessAskForOwnerCode` covers 401, 403, no credentials, and checks that feed sites keep their normal message.

## Verification
The full brief command (`testDebugUnitTest assembleDebug`, memory-limited flags) passed: **379 tests, 0 failures, 0 errors**, BUILD SUCCESSFUL. `app-debug.apk` was built. One earlier full run hit the known-flaky `ShareAndroidTest`; it passed when rerun alone and in the final full run. That test was not edited.

## Not done / notes for Hermes
- Not done: live acceptance testing. For Hermes: on a clean phone with an owner code, the Sites tab should show the 3 briefing sites. Then do a recover-flow reset on a test account.
- The existing `PodsubRepository.load()` sync (from earlier features, unchanged) still adds local follows to the server's briefing. The new mirror itself only reads (GET) and never pushes. If "never push local-only sites" should also apply to that existing sync, it needs a separate decision.
- No pull-to-refresh gesture exists on the Sites tab. The mirror runs on screen load and on the existing refresh action.
