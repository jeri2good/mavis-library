# Subscriber access + podcast gateway receipt

Branch: `devin/subs`. Backend-only implementation; sole writer, no subagents. No push, deploy, production onboarding, payment integration, or secret printing. First commit: `c1540ba` — made after the first green targeted subscription/podcast test run. Final review/docs/test changes are included in the subsequent final commit; use `git log -2 --oneline` for its hash.

## Built

- `netlify/lib/subs.mjs`: strong-consistency `mavis-subs` records, legal CAS state transitions, seven-day denied-request cooldown, idempotent requests, append-only metadata-only audit records, durable five-per-hour user request throttle, UTC daily CAS feature counters and environment overrides.
- `netlify/lib/access.mjs`: constant-time existing owner-code bypass, existing signed account sessions with user/tokenVersion verification, fresh subscription/expiry check per request, truthful 401/403 responses with `code`, and fail-closed 503 store handling. Owner code remains unlimited for subscriber quotas; `requireOwner` was not changed.
- AI endpoint gates: assistant, Jev rank, study POST and GET, cloud TTS, group discussion questions. Existing Kids safety prompts are retained; the Kids header neither grants nor removes entitlement.
- `netlify/functions/subscription.mjs`: private status/usage, request, owner-only paginated list/approve/deny/revoke/existing-account add/new-account create. Generated temporary password and recovery code return once with no-store and work with the existing signin/recovery routes; credentials are hashed, not stored/logged in plaintext.
- Account deletion removes the subscription record and keeps audit history. Existing signout-all version bumps invalidate sessions. Session parsing now rejects suffix garbage, malformed encodings/version fields, and expiration at the exact boundary.
- No email pre-approval whitelist: J must verify an existing account or create an account himself. A fresh signup using J's intended email is tested to get 403 `subscription_required`. No J/Christie accounts or subscriptions were seeded; Christie's exact email is still required.
- `netlify/functions/podcast.mjs` + `netlify/lib/podcast.mjs`: subscriber-only HTTPS studio gateway; bounded validation/strict JSON; fixed configured upstream; private studio/user headers; no redirect following/cookies; timeout/response-size controls; friendly sanitized errors; immutable per-user ownership index plus global unique ID claims; CAS max-two-active reservations; three daily jobs; indexed GET/list/delete authorization; owner sees all gateway-indexed jobs.
- Direct studio-hosted audio URLs, ten-minute HMAC-SHA256 signing and pure constant-time verify helpers, with a documented/tested deterministic vector. No Netlify MP3 byte proxy.
- Feature flags `subscriptions: true`, `podcast: Boolean(STUDIO_URL)`, existing fields and owner verification retained.
- `docs/SUBSCRIPTIONS.md`: states/API/owner curl examples/env variables/email-squatting decision/operational limitations.
- `docs/PODCAST-GATEWAY.md`: current gateway contract, exact signature format, worker responsibilities, schema gaps, and uncertain-create reconciliation.

Touched only `netlify/`, `tests/functions/`, the two requested docs, and this root receipt. No changes to `native-android/`, `src/`, podcast studio, package manifests/lockfile, or repository security policies.

## Endpoint decisions

| Endpoint | Decision | Subscriber daily cap |
| --- | --- | --- |
| assistant POST | Subscriber or owner | 150 messages |
| rank POST | Subscriber or owner | 300 requests |
| study POST, all tasks/images/motion | Subscriber or owner | 60 tasks |
| study GET polling | Subscriber or owner | No generation charge |
| tts POST | Subscriber or owner | 80 clips |
| groups questions | Existing signed-in membership plus subscriber or owner | Shared study 60 |
| groups other actions | Existing account/group rules unchanged | None |
| voices all operations | OWNER ONLY, unchanged requireOwner | Not subscriber-accessible |
| subscription status/request | Valid session; may be unapproved | request attempts 5/hour/user |
| subscription admin routes | OWNER CODE ONLY, never Bearer alone | None |
| podcast all routes | Subscriber or owner | create: 3/day; 2 active, owner exempt |
| features | Public; owner verification unchanged | None |
| Other public/account/catalogue/download routes | Unchanged | None |

Owner exemption is from subscriber daily/active caps, not existing IP/platform anti-abuse backstops. Daily charges happen before upstream work and are not refunded on uncertain upstream failures. Study's task-specific validation may happen after charging; GET polling does not charge.

Kids paid endpoint result: anonymous → 401 sign_in_required; valid unapproved account → 403 subscription_required; active subscriber → normal allowed request with child-safety rules and normal caps; owner → existing allowed behavior. Existing Kids prompt tests and the new access test pass.

## Real verification output

`npm ci` was run exactly once: 194 packages added, 195 audited; installer reported **3 moderate vulnerabilities**. No dependency/security-policy changes or audit remediation were made.

First targeted run: `node tests/functions/subs.test.mjs` exited 0 and printed **35 subscription and podcast checks passed**. Commit `c1540ba` followed that green run.

An initial full-suite run failed at the Eden ranking test because an inherited `TYPESAFE_API_KEY` selected TypeSafe instead of the fake Eden route. Only its presence was inspected, not its value. The test harness now clears inherited provider-selection settings locally so it cannot accidentally select the wrong upstream. Required changes to historical access expectations: missing owner configuration/no session is now 401, and a signed-in unapproved group member is now 403. Owner success tests remain intact.

A subsequent `npm run test:functions` exited 0 with 135 checks (including the initial 35 new checks). After hardening and adding six more checks, the full suite exited 0 with the following summaries **read from actual command output**, not inferred from test names:

| Suite output | Checks passed |
| --- | ---: |
| endpoint checks | 53 |
| subscription and podcast checks | 41 |
| account and sync checks | 14 |
| group checks | 12 |
| CORS checks | 7 |
| report checks | 6 |
| word builder checks | 8 |
| **Total** | **141** |

New checks cover owner/no-auth/garbage/expired/version-revoked sessions; all subscription states/expiry; account/subscription-store fail-closed paths; owner-only administration; CAS approval/denial and request races; denial cooldown; idempotency; request rate limit; pagination/status privacy; manual onboarding and working one-time credentials/no logs; intended-email squatting; CAS caps/UTC rollover/owner exemption/endpoint enforcement; Kids behavior; subscription cleanup on account deletion; podcast validation/headers/ownership/404 isolation; concurrent active-job limit/daily cap/completed-slot release; audio signature vector/tamper/uid/expiry; timeout/redirect/text/malformed/oversize/decoded-secret error mapping; cross-user duplicate studio ID rejection; cancellation cleanup; CORS/configuration/feature flags.

Local Blobs uses a real server; external studio and AI fetches are mocked, with a separate real fetch only for the local Blobs client. The local SDK server's conditional-write check and file write are non-atomic; the test server serializes PUTs per key to model atomic storage, while application operations remain concurrent. Production CAS has not been proved by these local tests.

`git diff --check` passed during review. `git diff --numstat 7d825d9 -- native-android src` returned no changes. No frontend build/e2e was run because the requested lane excludes frontend/native changes.

## Every relevant environment variable (names only)

### Access, quotas, studio, local test store

- `AUTH_SECRET`: existing account HMAC secret; at least 32 characters; subscriber accounts need it.
- `MAVIS_ACCESS_CODE`: existing owner bypass and sole subscription-admin credential.
- `SUB_CAP_ASSISTANT`: default 150/day UTC.
- `SUB_CAP_TTS`: default 80/day UTC.
- `SUB_CAP_RANK`: default 300/day UTC.
- `SUB_CAP_STUDY`: default 60/day UTC; shared with group questions.
- `SUB_CAP_PODCAST`: default 3/day UTC. Zero cap overrides disable subscriber generation; invalid overrides fall back to defaults.
- `STUDIO_URL`: configured HTTPS studio base, no credentials/query/fragment; required for gateway. Feature flag tests presence only, not reachability.
- `STUDIO_SHARED_SECRET`: server-only upstream authentication and audio HMAC secret, also required on the studio worker.
- `MAVIS_BLOBS_URL`: local test-only Netlify Blobs server URL. Do not set for production.
- `MAVIS_BLOBS_TOKEN`: local test-only token; existing fallback local-token. Production Blobs receives its platform binding from Netlify.

### Existing gated provider/feature settings, unchanged

- `LLM_PROVIDER`, `LLM_API_KEY`, `LLM_MODEL`, `LLM_BASE_URL`, `LLM_REASONING_EFFORT`: assistant/study/group AI configuration.
- `TYPESAFE_API_KEY`, `EDENAI_API_KEY`, `JEV_MODEL`: Jev ranking primary/fallback/model configuration.
- `TTS_PROVIDER`, `FISH_AUDIO_API_KEY`, `FISH_AUDIO_VOICE_ID`, `FISH_AUDIO_MODEL`, `GOOGLE_TTS_API_KEY`, `GOOGLE_TTS_VOICE`: existing cloud TTS and owner voice-management settings.
- `IMAGE_LLM_MODEL`, `IMAGE_QUALITY`, `IMAGE_MODEL`: existing study image settings.
- `FAL_KEY`, `FAL_VIDEO_MODEL`: existing study motion settings.
- `GOOGLE_BOOKS_API_KEY`: existing public features presence flag, unchanged.
- `NODE_VERSION`: existing Netlify build setting 22; no build/config edit. Verification used Node v22.23.3.

No TELEGRAM_* variable/channel is configured or consumed in this tree; report.mjs only stores Blobs reports. No Telegram notification was implemented. The owner can inspect new pending requests through admin/list. The code does not log request-message text; request retries create no extra transition/audit event.

## What is NOT verified / integration blockers

1. **Exact studio contract is unavailable.** The brief's book/chapter/article/topic source rules are implemented. Style/language/engine are bounded strings, and length is a bounded string/numeric passthrough; the studio's accepted enum/default/unit schema must be supplied and reconciled. Studio status vocabulary and JSON DELETE shape also require confirmation. This lane is experimental/integration-incomplete, not a verified match to the iMac studio.
2. No Netlify deploy, deployed wildcard-route smoke test, production Blobs CAS/concurrency test, live subscriber approval, or real owner/Christie onboarding. Christie's exact email is not known or guessed.
3. No actual studio/Funnel reachability, shared-secret setup, podcast generation, worker signature/ownership verification, MP3/Range/CORS playback, frontend integration, or Android test. The app CSP was not changed to permit a studio host; playback integration needs a separately approved frontend/security-policy change.
4. No automatic request notification delivery: no existing channel was available; admin/list is the fallback visibility path.
5. Blobs has no multi-record transaction. A crash between state CAS and audit append may leave a changed state without an audit event; create-account may create an account but fail to activate/deliver credentials. Operator reconciliation is documented, not silently destructive rollback.
6. Timed-out or uncertain studio creates retain quota and active reservations, rather than risking excess real jobs. No automatic idempotency/reconciliation service or operator recovery endpoint was built. J must reconcile studio work before repairing an uncertain reservation. Global ID claims intentionally survive job deletion. Account deletion does not cancel remote studio work or erase podcast history.
7. Already-issued signed audio URLs remain valid capabilities for at most ten minutes. Revocation prevents new gateway access on the next request, not playback via an existing capability.
8. Installer-reported moderate vulnerabilities were not remediated. No live cloud AI billing, provider delivery, email ownership, or physical audio was tested.

## Concrete next tests (not performed)

- Supply the studio input schema and status/delete contract; compare/adjust the gateway validators and fake studio fixtures before treating this as integration-ready.
- In a separately approved staging deploy, verify new `/api/subscription/*` and `/api/podcast/*` routing and production CAS with disposable accounts.
- Create/approve a disposable account, sign in using its returned credentials, verify 403 before approval, AI access after approval, and next-request 403 after revoke.
- Implement the documented HMAC/ownership/expiry checks in the studio, verify the fixed vector, tamper/expiry failures, and direct Range streaming from the real studio host.
- Only then integrate app UI, studio media/CSP/CORS, and actual J/Christie onboarding. No deployment or real-world effects are claimed in this receipt.
