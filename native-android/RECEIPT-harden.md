# Native Kids hardening receipt

Branch: `native`. Scope: `KidsWebReader.kt`, `KidsContent.kt`, `MainActivity.kt`, their JVM tests, and this review/receipt. No `src/` or `netlify/` changes; no push.

## Delivered

- Navigation decisions now cover main frames and subframes, restricting them to the selected localhost route or EPUB-inert subframe schemes. Popups, file chooser and downloads are denied; main-document queries and userinfo are rejected.
- The Kids WebView suppresses Android text action mode, long-click and haptics; document-start CSS/listeners suppress selection/context/drag and disable Web Share and `window.open`.
- Document-start `fetch` and XHR wrappers remove `x-mavis-access` and force `x-mavis-kids: 1` for those APIs. This does **not** isolate or remove the cached owner code from shared origin storage.
- Drive debug intents and monitor activations are ignored while Kids mode is enabled; active drive state and screen-brightness override are cleared on entry. Debug drive still works with Kids mode off.

## Evidence

`./gradlew testDebugUnitTest assembleDebug --no-daemon -q` completed successfully with Java 21 and the configured Android SDK. Counts read from `app/build/test-results/testDebugUnitTest/TEST-*.xml` suite attributes: **25 suites, 105 tests, 0 failures, 0 errors, 0 skipped**. `app/build/outputs/apk/debug/app-debug.apk` exists. JVM tests cover route/header-script text decisions and Robolectric checks `onNewIntent` drive brightness in Kids and adult modes. No emulator was used.

## Not verified / deferred

Not device-verified: EPUB subframes and redirects, subresource behavior, target-blank and file/download surfaces, actual selection/share UI, script execution in blob/data frames, network header rewriting, cold-start debug intent and Bluetooth drive monitor, and end-to-end Read/Listen/Bible/PIN behavior. The adult web runtime and owner credential cache still share the localhost origin; the wrappers only cover `fetch`/XHR and cannot be claimed as an unforgeable security boundary. Findings 5 (media service) and 6 (PIN KDF/lockout) are **deferred, needs design**; finding 9 requires OS-level containment. See `KIDS-REVIEW.md` → Hardening for scope and limits.
