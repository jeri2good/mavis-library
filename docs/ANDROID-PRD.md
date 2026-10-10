# Mavis Library – Android APK PRD

Oct 9, 2026 · @Jermaine Hawthorne

## 1. Summary and goals

Build a signed Android app for Mavis Library by wrapping the existing web app (v0.4.0, live at https://mavis-library.netlify.app) in a Capacitor shell, adding only the native pieces a browser cannot do, and shipping it through Google Play. The web code and the Netlify backend stay the single source of truth. No feature is rewritten.

**Goals**

- Every shipped web feature works in the APK: library and EPUB reader, Bible, read-aloud, audiobooks and LibriVox, AI tools, full cast and voices, verse videos and scene films, word builder, kids mode, book clubs and Bible study groups, accounts and sync.
- Audio keeps playing with the screen off, with lock-screen and notification controls and headset or Bluetooth buttons.
- The app installs and opens with no network and shows the shelf, open books and saved audio.
- A debug APK for sideloading on day 5 of work, a signed release bundle on Google Play internal testing by the end of the plan.

**Non-goals for this release**

- Android Auto. It is deferred; the media-session design in section 4 must not block it.
- iOS, a native rewrite, or a second codebase for any screen.
- New features. The only new UI is what Google Play policy or the platform forces (section 6).
- Bypassing DRM or adding any store that sells protected books. The app stays public-domain and user-imported files only.

**Success measures**

| Measure | Target |
| --- | --- |
| Cold start to shelf on a mid-range phone | Under 3 seconds |
| Audio playback with screen off | 30 minutes uninterrupted on Android 12 and 15 |
| Crash-free sessions in the first two weeks of internal testing | 99% or better |
| Existing automated suites (65 browser checks, 65 function checks) | Still pass unchanged |
| Release bundle size | Under 40 MB |

## 2. Architecture decision

Use Capacitor, Android only, and bundle the built web app inside the APK. The decision is made; do not re-open it unless a test in section 8 proves it unworkable.

&#91;embedded content: architecture · app, Netlify, outside services\]

The app is the same web build the site serves, packaged with native plugins for the few things a browser cannot do. Every data call goes to the existing Netlify functions.

**Why Capacitor**

- A native rewrite would duplicate nine features and double the maintenance.
- A Trusted Web Activity (a thin Chrome shell) gives no native file saving, speech recognition or secure storage, and no control over how audio behaves in the background. Background audio is the core need.
- Capacitor keeps one codebase, runs the same service worker, IndexedDB and canvas recording, and lets plugins supply the native pieces.

**Why bundle the app instead of loading the live site**

- The app opens with no network and shows the shelf, books and saved audio.
- Google Play reviewers see a real app, not a browser tab.
- The cost: a screen change needs a new release. API changes still reach every install at once.

**Fixed choices.** Capacitor current stable release, Android scheme `https` (the page origin is `https://localhost`), `minSdk` 26, portrait and landscape. The Android System WebView on the phone renders the app, so the developer tests on devices with an older WebView too.

## 3. Functional requirements

Every row must behave in the APK exactly as it does in the web app today, with the listed native change. "Web as is" means no code change beyond the API base URL in section 2.

| Area | Requirement in the APK | Native change |
| --- | --- | --- |
| Library, search, shelf, EPUB import | Web as is. Imported books stay in IndexedDB. | File picker through the system document picker so "Import a book" opens Android's file chooser. |
| Reader, dictionary, quotes, notes | Web as is. | None. |
| Bible (HelloAO text, plans, search) | Web as is, including offline Bible text. | None. |
| Device read-aloud (speechSynthesis) | Works with the screen on. | Verify voices exist on the device; show the existing "no voice" message if not. |
| Cloud read-aloud, audiobooks, LibriVox, offline audiobook | Keeps playing with the screen off and with the app in the background. Resumes where it stopped. | Foreground media service and native MediaSession (section 4). |
| Car mode | Web as is. | Keep the screen awake while car mode is open. |
| Voice input and Ask Mavis by voice | Works. | Native speech recognition plugin, because the Android WebView has no SpeechRecognition API. |
| Voice cloning (recording a sample) | Works after the user grants the microphone. | RECORD\_AUDIO permission prompt, and WebView getUserMedia permission pass-through. |
| AI tools, companion, full cast | Web as is. All calls go to the Netlify functions. | None. |
| Verse videos and scene films | Render on the device and save or share. | Save through the Filesystem plugin and share through the native share sheet, because `<a download>` and `navigator.share({files})` are unreliable in the WebView. |
| Word builder and kids mode | Web as is. PIN gate unchanged. | None. |
| Book clubs, Bible study groups, invite links | Invite links open the app when it is installed. | Android App Links (section 4). |
| Accounts and sync | Web as is. | Access code and account token stored in secure storage, not plain preferences. |
| Settings, owner access code | Web as is. | Add an "About" line with app version and build number. |
| Install prompt and update toast | Hidden in the APK. | Detect the native shell and skip both. |

## 4. Native work

Use Capacitor plugins that are maintained and compatible with the Capacitor version chosen in section 2. Where a plugin below no longer exists or is unmaintained, pick the closest maintained equivalent and note the swap in the pull request.

1. **Screen-off audio.** Run a foreground service of type `mediaPlayback` while any audio plays (cloud read-aloud, audiobook, LibriVox, car mode). Show a persistent media notification. Stop the service when playback ends or the user dismisses it.
2. **MediaSession.** Bridge the web `navigator.mediaSession` (already used in `src/lib/audiobook-player.js`) to a native Android MediaSession so lock-screen controls, the notification, headset buttons and Bluetooth buttons work. Metadata (title, author, cover) and position state must update. Build it on a `MediaSessionCompat` or Media3 session so a later Android Auto step can attach a `MediaBrowserService` without a rewrite.
3. **Audio focus.** Pause for calls and other apps, resume when focus returns, and duck for short notifications.
4. **Back button.** Handle the Android back button through the app plugin: go back in the hash router, close open dialogs first, and exit only at the shelf root.
5. **Edge-to-edge and safe areas.** Android 15 forces edge-to-edge. Use `env(safe-area-inset-*)` in the existing CSS for the top bar and the bottom tab bar. Status bar colour `#1f4a37`, light icons.
6. **Files.** Save videos and exported text to the Documents or Movies folder through the Filesystem plugin, then offer the native share sheet. The web download link stays as the fallback in the browser.
7. **External links.** Open store, source and Gutenberg links in the system browser (or a Custom Tab) so the app is never navigated away from its own origin.
8. **App Links.** Verify `https://mavis-library.netlify.app` for the app with `assetlinks.json` (section 5). Invite links produced by `inviteLink()` in `src/lib/groups.js` must open the join screen in the app, and in the browser when the app is absent.
9. **Icons, splash and name.** Adaptive icon from `public/icons/maskable-512.png` and `icon.svg` (generate with the Capacitor assets tool), splash in `#f4efe3` background, app name "Mavis Library".
10. **Permissions.** Declare only: `INTERNET`, `RECORD_AUDIO`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `WAKE_LOCK`. Ask for the microphone and notifications at the moment of first use, with a one-line reason, never at launch.
11. **Orientation.** Allow portrait and landscape, as the web manifest does.
12. **Secure storage.** Keep the owner access code and the account token in Android Keystore-backed storage. Everything else stays in IndexedDB and local storage.

## 5. Changes to the web app and backend

The APK page runs from `https://localhost` inside the app, so it talks to the Netlify functions across origins. These changes are required and must keep the browser version working unchanged.

1. **API base URL.** The app has 38 call sites that use relative `/api/...` paths. Add one helper, `apiUrl(path)`, that returns the path unchanged on the web and prefixes `https://mavis-library.netlify.app` when built for Android (`VITE_API_BASE`). Replace every call site, including `<audio src>`, cover images and the `?motion=` and `?picture=` polling URLs.
2. **CORS on every function.** Add a shared `withCors(handler)` wrapper, applied to each default export in `netlify/functions`. It answers `OPTIONS` with 204 and, only for the allowed origins `https://localhost` and `https://mavis-library.netlify.app`, returns `Access-Control-Allow-Origin` (that origin, never `*`), `Vary: Origin`, methods `GET, POST, OPTIONS`, and headers `authorization, content-type, x-mavis-access, x-mavis-kids, x-mavis-source, x-mavis-voice`. Netlify `[[headers]]` rules do not cover function responses, so this has to live in code. Add function tests for allowed origin, refused origin and preflight.
3. **Content Security Policy.** The APK cannot read the headers in `netlify.toml`. Put an equivalent policy in a `<meta>` tag in the Android build of `index.html`, with the Netlify origin added to `connect-src`, `img-src` and `media-src`. Drop `frame-ancestors` and `upgrade-insecure-requests`, which do not work in a meta tag. Keep `script-src 'self'`.
4. **Invite links.** `inviteLink()` in `src/lib/groups.js` builds the link from `location.origin`, which would be `https://localhost` in the APK. Use a fixed public origin, `https://mavis-library.netlify.app`, in both builds.
5. **Service worker.** Keep it registered in the APK. Offline audiobooks, the Bible text cache and offline reading depend on it, and the WebView supports it on `https://localhost`. In the Android build, skip the update toast and the install prompt. If the offline-audio test on a device fails (section 8), move audio file storage to the Filesystem plugin and keep the same UI.
6. **App Links file.** Serve `/.well-known/assetlinks.json` with `Content-Type: application/json`, listing the package name and the SHA-256 fingerprints of the Play App Signing key and the upload key.
7. **Privacy policy and account-deletion pages.** Add public pages at `/privacy` and `/delete-account`. The policy must name every outside service that receives data: OpenAI (AI text and pictures), fal.ai (scene film motion), Fish Audio (voices and cloud read-aloud), Gutenberg, Open Library, LibriVox and Internet Archive (public requests), Netlify (hosting and storage). The deletion page explains the in-app route (Settings, Account, Delete account) and gives a contact address for people who cannot sign in. The owner supplies the contact address.
8. **Report button for AI output.** Google Play requires apps with AI-generated content to let users report it. Add a "Report this" action to AI answers, pictures, summaries and scene films that sends a short note (what, which book or passage, optional text) to a new `report` function. It stores the note in Netlify Blobs and returns a confirmation. The owner reviews them.
9. **Native shell detection.** Add `isNativeApp()` (true when `window.Capacitor?.isNativePlatform()`) in one small module. Use it for the hidden install prompt, the save and share paths, and the native speech and permission paths.

## 6. Security, privacy and Google Play policy

The rule that decides everything here: no secret ever ships inside the APK.

- **No keys in the app.** The OpenAI, Fish Audio and fal.ai keys stay in Netlify environment variables. The APK, the repository and the build logs must never contain them. A release build that contains a string matching a key pattern fails CI (add a grep step).
- **Owner access code.** It is typed by the owner in Settings and stored in secure storage (section 4). It is never compiled in. The paid features keep their current server-side checks and rate limits.
- **Network.** HTTPS only. Set `android:usesCleartextTraffic="false"`. Do not add a network security config that trusts user certificates. Allow backups of nothing sensitive: set `android:allowBackup="false"` and exclude secure storage.
- **WebView.** Disable remote debugging in release builds. Keep `allowNavigation` limited to the app's own origin. Open all other links outside the app.
- **Kids mode.** The app is for ages 13 and over, so do not enter the Designed for Families programme. Kids mode stays a parent-controlled setting. Its PIN and the `x-mavis-kids` behaviour are unchanged.
- **Data safety form.** Declare what the accounts feature stores (email, a salted password hash, synced shelf, progress, notes, plans and vocabulary), that data is encrypted in transit, and that users can delete it in the app and on the web. Declare microphone audio used only for voice features. Declare that text the user selects is sent to AI providers when they use AI tools. The owner confirms each answer against `docs/HANDOFF.md` before submission.
- **AI-generated content.** Ship the report action from section 5, plus a visible label on AI pictures and films ("Made with AI"). Scene films already carry a watermark by default.
- **Content rating.** Complete the questionnaire honestly: books and Bible text, user-to-user messages only inside book clubs and study groups, AI-generated text and images.
- **Copyright.** The app reads public-domain books and files the user imports. It must not add any feature that removes DRM. Keep the existing public-domain check on downloads.
- **Account deletion.** Both the in-app route and the web page from section 5 must work before the Play submission.

## 7. Build, signing and release

| Item | Decision |
| --- | --- |
| Repository | `jeri2good/mavis-library`. Add an `android/` folder (Capacitor project) and `capacitor.config.ts`. Same repo, same branch flow. |
| Application ID | `com.mavislibrary.app`. The owner confirms this before the first upload to Google Play, because it can never change afterwards. |
| Capacitor shell | `appId` as above, `appName` "Mavis Library", `webDir` `dist-android`, `server.androidScheme` `https`. |
| Build target | New script `npm run build:android` that runs Vite with `VITE_API_BASE` and the Android `index.html`, then `npx cap sync android`. The existing `npm run build` for Netlify stays untouched. |
| SDK levels | `minSdk` 26 (Android 8). `targetSdk` at the level Google Play currently requires for new apps (35 or higher; the developer confirms in Play Console). `compileSdk` one above target. |
| Version | `versionName` from `package.json` (first release 1.0.0). `versionCode` is a CI run counter, always increasing. |
| Signing | Enrol in Play App Signing. The owner generates an upload keystore and keeps it, with its password, outside the repository. CI reads them from GitHub Actions secrets. Nothing signing-related is committed. |
| CI | GitHub Actions workflow on tags `android-v*`: install, run the web test suites, build, produce a signed release bundle (`.aab`) for Play and a signed `.apk` for sideloading, attach both to the workflow run. |
| Developer account | Google Play Console, one-time registration fee. If the owner's LLC is formed first, register as an organization; a personal account must complete a closed test with at least 12 testers for 14 days before production access (the developer confirms the current rule in Play Console). |
| Release path | Internal testing, then closed testing, then production. The first signed `.apk` is sideloaded to the owner's phone before any Play upload. |
| Store listing | Name, short and full description, 512 px icon from `public/icons/icon-512.png`, a 1024x500 feature graphic, at least four phone screenshots (shelf, reader, Bible, scene film). The owner approves the text. |
| Updates | Web changes reach the installed app only through a new release, because the page is bundled. Add the "Updating the app" steps to `docs/HANDOFF.md`. API-only changes reach every install at once through Netlify. |

Keep the web build and the Android build in one source tree. A change to a feature must not need two edits.

## 8. Test plan and acceptance criteria

The release is accepted when every item below passes on real devices and the existing suites still pass. Report results honestly: a test that was not run is listed as not run.

**Devices.** One Pixel on Android 15, one Samsung on Android 12 or 13, one older device on Android 9 or 10, and one tablet. The emulator is acceptable for smoke tests only.

**Automated**

- [ ] `npm run test:functions` passes, with new checks for CORS (allowed origin, refused origin, preflight) and for the report function.
- [ ] The 65 browser checks pass on the web build, unchanged.
- [ ] CI builds the signed bundle and APK from a tag, and fails if a key pattern is found in the output.
- [ ] A smoke flow on an emulator (Maestro or equivalent): launch, open a book, open the Bible, search, change a setting.

**On device**

- [ ] Fresh install opens to the shelf and the sample or imported book reads. Import an EPUB from the Android file chooser.
- [ ] Airplane mode: shelf, an open book, the Bible text and a saved audiobook all work.
- [ ] Cloud read-aloud plays, the screen turns off, 30 minutes later it is still playing. Lock-screen play, pause, next and seek work. A Bluetooth headset button pauses it.
- [ ] A phone call pauses audio and it resumes afterwards. Another app taking audio focus pauses Mavis.
- [ ] Offline audiobook: save a chapter set, switch to airplane mode, kill the app, reopen, resume at the same position.
- [ ] LibriVox recording streams and keeps playing with the screen off.
- [ ] Voice input, Ask Mavis by voice, and a voice-clone sample recording each ask for the microphone once and then work.
- [ ] A verse video and a scene film render, then Save puts a playable file in Movies and Share opens the Android share sheet.
- [ ] Open a book club invite link from a messaging app: the installed app opens on the join screen. Without the app, the link opens the web app.
- [ ] Sign up, sync to a second device, sign out, sign in, delete the account from Settings.
- [ ] Kids mode: turn on with a PIN, confirm grown-up screens ask for the PIN, turn it off.
- [ ] Back button closes a dialog first, then walks back through screens, then exits at the shelf.
- [ ] Rotation, dark theme, 200% font size, and TalkBack on the shelf and reader cause no cut-off or trapped focus.
- [ ] Cold start under 3 seconds on the older device; no ANR or crash in a 30 minute mixed-use session.
- [ ] The report action sends a note and the owner sees it.
- [ ] Pre-launch report in Play Console has no critical findings.

## 9. Milestones, risks and handoff

**Milestones.** Estimated at about six weeks for one developer who has shipped a Capacitor app. Each milestone ends with a build the owner can install.

&#91;embedded content: roadmap · 5 milestones, 3 gates\]

**Risks**

| Risk | Likelihood | Mitigation |
| --- | --- | --- |
| Android WebView stops or kills audio in the background | High if skipped | Foreground media service plus native MediaSession in M2, tested with the screen off before anything else is built on top. |
| Offline audio in the service worker cache is evicted or unavailable in the WebView | Medium | Device test in M2. Fallback: store audio files with the Filesystem plugin behind the same UI. |
| `MediaRecorder` output for scene films differs on some devices (codec, WebM only) | Medium | Test on all four devices in M3. Fallback: keep WebM and share it as a file; do not promise MP4. |
| Plugin for MediaSession or foreground service is unmaintained | Medium | Pick the maintained one at M0; write a thin custom Capacitor plugin if none fits. |
| Google Play rejects the AI-content or data-safety answers | Medium | Report button, "Made with AI" labels and the privacy page are built in M4 before submission. |
| CORS or CSP mistakes expose the API to other origins | Low | Allowlist of two origins, tests for refused origins, no wildcard. |
| Owner's Play account needs the 12-tester closed test | Known | Start recruiting testers in M3. |

**Deferred, with one rule for now.** Android Auto comes after this release. Do not build it, but keep the native MediaSession from section 4 on a standard Android session so a `MediaBrowserService` can be added later.

**The owner provides**

- A Google Play Console account, the final application ID, and a contact email for the privacy and deletion pages.
- An upload keystore, created by the developer's instructions and kept by the owner.
- Approval of the store text, screenshots and feature graphic.
- The Netlify and GitHub access the developer needs to add functions and a workflow (never the API keys).

**The developer delivers**

- The `android/` project and workflow in the repository, built from a tag.
- A signed `.apk` for sideloading and a signed `.aab` for Google Play.
- Updated `README.md`, `docs/STATUS.md` and `docs/HANDOFF.md` with the Android build, signing and update steps, and an honest list of what was and was not tested on devices.
- The test results for section 8, one line per item, marked passed, failed or not run.
