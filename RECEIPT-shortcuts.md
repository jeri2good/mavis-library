# Podcast shortcuts receipt

Branch: `devin/podcast-shortcuts`, based on `native` at `7308e1a` (includes `02a5b96`).

## Exact entry points

- **Book WebView reader (`#/read/<key>`)**: bottom toolbar, immediately below page navigation: **Make podcast of this chapter**. Covers Gutenberg EPUBs and EPUB/TXT imports opened through the bundled reader, including the legacy Web Shelf. Gutenberg readers also show a separate **Whole book podcast** button; only this explicit choice sends `kind=book` with `gutenbergId`.
- **Android-shared EPUB reader (Shelf → Imported books)**: above the chapter text: **Make podcast of this chapter**. **More podcast options → Whole book** remains explicit and sends imported text, never a Gutenberg ID.
- **Native Search → New in tech & AI**: **Make podcast** on every article card, across AI news, Research papers, Tech front page, Mine, and each custom feed. Tapping the card opens the new article reader.
- **Opened article reader**: persistent top toolbar: **Make podcast of this article**; **Open original in browser** remains available. Navigation updates the source URL/title.
- **Legacy WebView Discover → New in tech & AI**: **Make podcast** on every article card. Article title links now open the native article reader when the Android bridge is available.
- **Android Share → Mavis, HTTPS links/articles**: opens the prefilled Lounge directly, including shared title/subject or title + URL text. No extra review dialog for URLs. Signed-in Lounge article forms also have **Read article**, which opens the article reader with its podcast toolbar.

All shortcuts open the existing Lounge with kind/title/source prefilled and saved style/length/engine defaults. Only the Lounge's **Make podcast** submits. Existing sign-in and **Request access** messages remain unchanged. Kids navigation hides all entry points; the main-frame-only, app-origin WebView bridge also rechecks persisted Kids mode. External article WebViews have no native/account bridge.

Chapter extraction uses full plain text, not selected text, visible-page text, or the old 24k recap. TOC fragment boundaries select the current chapter; unclear/missing boundaries fall back to the current spine section. Titles use `Book - Chapter`; text is capped at 400,000 characters with original size retained. The basic native import reader treats each readable spine section as its displayed chapter. PDF remains the existing browser-only viewer, without EPUB chapter extraction. An updated Android System WebView with web-message-listener support is required for bundled-web shortcuts. No experimental podcast lane introduced.

## Verification

- `npm run build:android` — passed.
- `JAVA_HOME=~/.local/jdks/jdk-21.0.12.1+1 ANDROID_HOME=~/Android/Sdk ./gradlew testDebugUnitTest assembleDebug --no-daemon` in `native-android` — passed; 324 tests, zero failures.
- `node --test tests/unit/podcast-chapter.test.mjs` — 5 passed, including fragment boundaries, full-section fallback, hidden/script exclusion, and >24k source.
- `node scripts/test-account-transport.mjs` in `native-android` — passed.
- `npm run dev -- --host 127.0.0.1 --port 5175 --strictPort`, then `node scripts/test-podcast-shortcuts-browser.mjs` — real Chromium smoke passed for visible chapter action, complete chapter boundaries, explicit whole-book ID, web-imported EPUB, web feed prefill/open, and Kids hiding; all bridge delivery is captured and external requests intercepted.
- Native Compose tests passed for the opened-article button, Kids hiding, and selecting chapter 2 in an Android-imported EPUB. Payload tests verify title/source/cap/default choices; existing fake-HTTP account/subscription/job tests remain green.
- One full run hit existing ShareAndroidTest Robolectric font ZIP/native-loader failures; isolated affected tests and the final full suite passed unchanged.

APK: `native-android/app/build/outputs/apk/debug/app-debug.apk`.

No live podcast jobs, account/subscriber mutations, device installs, or `netlify/` edits. Physical-device/live-studio acceptance remains with Hermes. Safe demo: open a chapter or article, tap its shortcut, inspect the prefilled Lounge and saved preferences; do not tap **Make podcast** during no-live-job verification.
