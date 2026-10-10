# Milestone 1 — Native foundation for Mavis Library (Kotlin + Jetpack Compose)

Repo: this directory (branch `native`, created from `android`). Read `docs/NATIVE-PLAN.md` first (approved scope) and `docs/ANDROID-PRD.md` sections 5-6 (backend contract, no secrets). You are the ONLY writer. Commit locally in small steps, do NOT push, do NOT touch anything outside this directory except installing Gradle dependencies.

## Environment (already set up, do not reinstall)
- JDK 21: `export JAVA_HOME=$HOME/.local/jdks/jdk-21.0.12.1+1 PATH=$JAVA_HOME/bin:$PATH`
- Android SDK: `export ANDROID_HOME=$HOME/Android/Sdk` (platforms android-34 and android-35, build-tools 34/35, emulator + system-images present, check `$ANDROID_HOME/system-images`).
- Node 22 for the web build: `npm run build:android` writes `dist-android/` (the existing web app with the Android CSP, API base `https://mavis-library.netlify.app`).
- The existing `android/` folder is the abandoned Capacitor wrapper. Leave it alone; do not delete it.

## Goal
A real native Android app in a NEW Gradle project at `native-android/` (Kotlin, Jetpack Compose, Material 3). applicationId `com.mavislibrary.app`, app name "Mavis Library", minSdk 26, compileSdk/targetSdk 35. The existing Netlify backend (15 functions under `netlify/functions`) is reused unchanged. Screens migrate to native over later milestones; in M1 anything not yet native is shown by hosting the existing web app inside the native shell.

## Tasks
1. Project scaffold: AGP/Kotlin/Compose BOM versions compatible with SDK 35 and the installed Gradle (use a Gradle wrapper; version catalog `gradle/libs.versions.toml`). Single-activity Compose app, Navigation Compose, edge-to-edge with correct insets, Android back button handled (dialogs first, then back stack, exit at the root). No cleartext traffic, `allowBackup=false`, no debuggable WebView in release.
2. Look system: a theme layer with THREE selectable looks, each a full Material 3 color scheme + typography (use the app's bundled fonts if available under `node_modules/@fontsource*`, else system serif/sans):
   - `PAPER`: warm cream pages, serif type, classic library feel.
   - `MIDNIGHT`: deep dark, calm, gold accents (default for evening).
   - `EDITORIAL`: white space, bold type, big cover tiles.
   Selector in Settings; persisted in DataStore. Also a separate `KIDS` theme (bright primary colours, large rounded shapes, big touch targets) defined but only previewed in M1. Provide a Compose preview/showcase screen listing all four themes.
3. Navigation shell: bottom bar with Shelf, Search, Bible, Listen, Settings. Settings is fully native (look picker, About with version/build, privacy link, "Owner access code" entry saved securely). Search is fully native (task 5). Shelf, Bible and Listen host the web app (task 4) at the matching hash route (`#/shelf`, `#/bible`, `#/listen`) until they are rewritten.
4. Embedded web host: `WebViewAssetLoader` serving the bundled `dist-android` build from app assets with domain `localhost` over https, so the page origin is `https://localhost` (already an allowed CORS origin). A Gradle task copies `../dist-android` into the app's assets before build (fail clearly if `dist-android` is missing and say how to create it). Restrict navigation to the app origin; open every external link in the system browser. Handle file chooser (EPUB import) via the Android document picker, microphone permission pass-through for WebView getUserMedia (ask at first use with a one-line reason), and back navigation inside the WebView. The web app must detect it is inside the native shell: inject `window.MavisNative = {version, platform:'android'}` before page scripts.
5. Native Search screen + API layer: Retrofit/OkHttp or Ktor with kotlinx.serialization against `https://mavis-library.netlify.app/api/catalog` and `/api/openlibrary` (read their handlers in `netlify/functions` to learn the exact query parameters and response shapes; do not guess). Cover images via Coil with a disk cache. Results grid in the active look, a detail sheet (title, author, cover, formats), and an "Add to shelf" action that stores the book in Room.
6. Offline foundation: Room database (books, shelf entries, cached catalog responses, reading progress stubs, download queue table), repository layer that serves cached data when offline and shows an honest "Offline" banner. A connectivity monitor flow. WorkManager configured for future background downloads (Wi-Fi only constraint) with a stub worker.
7. Secure storage: owner access code and any account token in Android Keystore-backed encrypted storage (no plain SharedPreferences). Never log secrets.
8. Tests: JVM unit tests for the repository/cache logic and the API response parsing (use recorded JSON from the real handlers' shapes, not live calls). Add at least one instrumentation or Robolectric test for the navigation shell if feasible. `./gradlew testDebugUnitTest assembleDebug lintDebug` must pass.
9. Emulator smoke (best effort): create/boot an AVD from an installed system image headless, install the debug APK, launch it, take `adb exec-out screencap -p` screenshots of: app start, Search with results (live network OK), Settings look picker in all three looks. Save under `native-android/docs/screens/`. If the emulator cannot run here, say so in RECEIPT.md and list the check as NOT RUN. Do not claim a screen works without a screenshot.

## Rules
- No secrets anywhere (no API keys in code, config, logs or screenshots). The owner access code is typed by the user at runtime only.
- Do not edit the web `src/` except the minimum to read `window.MavisNative` if truly needed; any such change must be behaviour-neutral in the browser.
- After each numbered task: run the relevant Gradle command, append a line to `PROGRESS.md` (task, files, command, result).
- Stop new work at ~80% of your budget and use the rest to write `RECEIPT.md`. ALWAYS write `RECEIPT.md` last: what was built, exact commands + real output summaries, screenshots list, what is NOT done or NOT run, and known problems. Report blockers honestly; never fabricate output.

## Acceptance
- `./gradlew assembleDebug` produces `native-android/app/build/outputs/apk/debug/app-debug.apk`; `aapt2 dump badging` shows package `com.mavislibrary.app`, minSdk 26.
- Unit tests pass; lint has no errors.
- Screenshots (or an honest NOT RUN) for start, search results, settings in three looks, and a hosted-web tab.
