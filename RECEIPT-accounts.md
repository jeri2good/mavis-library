# Native accounts, subscriptions, and public-gateway Podcast Lounge

Branch: `devin/accounts-app`, based on `native` at `196c690`.

## Delivered

- Native Settings account forms: Sign in, Create account, Forgot password using a recovery code, and device-local Sign out. Signup and recovery display the newly returned recovery code once in a screenshot-protected, explicitly acknowledged dialog. Passwords/recovery codes stay in memory only.
- Account token and user identity are stored atomically using the existing Android Keystore AES-256-GCM SecureStore. IMPORTANT: the actual existing repository implementation is encrypted DataStore, not EncryptedSharedPreferences. It was reused rather than adding another secret store/dependency. `/account/me` confirms identity and saves refreshed tokens. Sign-out clears account identity/token without removing the owner code.
- Subscription card fetches real `/api/subscription/status`: none, pending, active, denied, revoked; active plan and today's UTC feature usage; `podcastEnabled`; expired subscriptions explicitly shown as expired. Requests are available for none/revoked and denied after the seven-day server decision cooldown. Pending says “Waiting for approval.” Explicit Refresh account rechecks changes made by the owner.
- Owner-only approvals section appears only with a stored owner code. It fetches all paginated records, displays pending/active users, and confirms approve/deny/revoke before sending the exact owner-header API request. Signup email ownership is explicitly not assumed verified.
- Podcast Lounge production transport now uses `BuildConfig.API_BASE` and `/api/podcast/create`, `/list`, `/job/<id>`, `/audio/<id>`. No address/key pasting or direct-studio toggle is exposed. Owner bypass still works; signed-in requests include the account Bearer header.
- Gateway create serialization sends Gutenberg IDs as strings, includes defaults, and respects the 300-character topic/7-digit Gutenberg limits. Shared plain text is a chapter source, not an invalid URL-less article. Brief is removed from both style pickers, including normalization of an old saved default to deep-dive; decoding/retrying old saved brief jobs remains compatible.
- Lounge handles sign-in/subscription/daily-limit/studio-unavailable responses with friendly messages. Studio-busy and active-job limits have distinct messages. Progress is interpreted as integer percent.
- Playback/download resolve gateway-authorized signed HTTPS audio URLs. Media items retain only the non-secret gateway identity URL; every streaming/Range open obtains a new signed link. Studio requests carry Range only, never account/owner credentials. Redirects are not followed. Invalid/expired/malformed audio links fail safely without exposing the URL in errors.
- Podcast metadata is tagged with account identity and filtered on account switches; owner-mode listing is intentionally global. Existing local copies are not migrated; the existing opt-in local cleanup setting and confirmation controls are preserved.
- The Android-only WebView transport asset attaches account Authorization to assistant/tts/rank/study calls to the exact backend origin, alongside existing owner-code behavior. Tokens are memory-only inside the WebView and cleared on sign-out, app lock, and Kids-mode gating. `/voices` is not granted subscriber credentials.
- Legacy bundled web feature pickers use an `owner` boolean as their paid-feature UI gate. The native adapter enables that UI gate only after a real active, unexpired subscription status; it labels the adapted response `nativeSubscriber`. This is UI compatibility only, not an owner credential or backend-admin entitlement. Native approvals still require a stored owner code, and backend voice administration remains owner-only.
- Kids mode blocks Settings/account controls, Lounge, and approvals. Account repository mutations and gateway requests additionally recheck the current Kids state before dispatch; account authentication checks again before storing the session.
- No `netlify/` or `src/` web source edits. No dependencies added. `AGENTS.md` records the verified native build/test commands.

## Verification performed

From repository root:

```sh
npm ci --ignore-scripts --no-audit --no-fund
npm run build:android
```

From `native-android`:

```sh
JAVA_HOME=~/.local/jdks/jdk-21.0.12.1+1 ANDROID_HOME=~/Android/Sdk ./gradlew testDebugUnitTest assembleDebug --no-daemon
node scripts/test-account-transport.mjs
```

Final results: **BUILD SUCCESSFUL; 307 tests; zero failures/errors.** The Node fake-fetch/XHR transport suite also passed. `git diff --check` passed, and `git diff --name-only -- netlify src` was empty.

New/updated coverage includes all subscription states, usage/cooldown parsing, signup/recovery payloads, token refresh and secure account identity lifecycle, owner header separation, approval action routes/pagination, friendly status mapping, gateway routes/Gutenberg serialization/list pagination, signed-audio credential isolation/Range/redirects/invalid links, anonymous Lounge/account navigation, and Kids-mode UI/repository/gateway gates. Tests use injected fake HTTP responses and synthetic credentials, not production accounts.

An initial development run hit two obsolete Lounge expectations and transient existing Robolectric native-graphics/ZIP failures. After updating the Lounge tests, subsequent full runs passed; no existing sharing functionality/test expectations were changed to hide those failures.

Debug APK (not exported): `native-android/app/build/outputs/apk/debug/app-debug.apk` (approximately 41 MB). Built by `assembleDebug` with the ordinary debug build configuration only; no release APK signing/export.

## Known gaps / boundaries

- No live account creation, recovery, approval/deny/revoke, access request, or podcast jobs were performed. Hermes must verify actual Netlify routing, entitlement transitions, studio availability, generation, signed-audio/Range streaming, and device playback.
- No physical-device/emulator Chromium or real MP3/audio-output test was performed. Compose navigation is tested with Robolectric; the real Android WebView adapter is tested with fake fetch/XHR, not Chromium. Old WebViews without document-start injection retain the existing best-effort page-start fallback.
- The native account lane does not synchronize the separate legacy web account/group/sync session. Paid AI requests use the native token; legacy web account/sync features may still require their own session. Native password-change/account-deletion/sign-out-all screens are outside this brief. Sign out here is local only.
- Legacy voice management may still be visible through old bundled UI when subscriber paid-feature gates are enabled, but subscribers are not given owner credentials or voice-management authorization; the backend denies those operations.
- No direct-studio fallback is exposed. Previously configured direct addresses/keys may remain in existing device storage but are not consulted by production Lounge/playback. Old server-only direct jobs are not migrated/indexed by the gateway; old downloaded copies are accessible in owner mode. Server deletions of old direct jobs are not attempted.
- Recovery codes intentionally are not persisted across process death/rotation. Save the one-time code immediately. The API has no reset-email delivery.
- Status/usage is refreshed on entering Settings or via Refresh account; it is not a push subscription. Lounge refreshes jobs on entry/periodically. `podcastEnabled` is configuration, not a studio-health claim; “Gateway connected” does not certify that a generation engine is healthy.

## Hermes demo acceptance (live actions reserved for Hermes)

1. Install the debug APK on the demo device. Open Settings → Account. Sign in with an existing test account, or create an approved disposable account privately and save its recovery code immediately. Restart the app and confirm account persistence.
2. Confirm real subscription state. For a non-subscriber, request access and verify “Waiting for approval.” On an owner-code device, Settings → Owner approvals → Manage subscribers, verify identity out-of-band, approve, then refresh the member's account and confirm plan/usage.
3. Listen → Open Podcast Lounge. Choose a small topic; deep-dive should be selected, with no Brief option or studio address/key fields. Create exactly one approved test job; confirm queued → ready and today's usage once. Try signed audio Play/seek and Download offline; never paste/log the signed URL.
4. Check assistant/TTS/rank/study from the bundled views with a subscriber, then owner-code-only access. Confirm 401/403/429/503 messaging using approved staging conditions rather than generating production load.
5. Sign out: Lounge should prompt sign-in unless owner access is still stored. Verify another member cannot see the previous member's cached podcast metadata.
6. Enter Kids mode: account changes, Lounge, and approvals must disappear/block. Exit using the parent PIN and verify normal routes return.
