# Subscriber access + podcast gateway receipt

Branch: `devin/subs`. Backend-only implementation; sole writer, no subagents. No push, deploy, production onboarding, payment integration, or production secret printing. This receipt includes the original subscriber work and the 2026-10-12 studio-contract alignment follow-up.

Alignment checkpoint: `d5600bf` — committed immediately after the first full green `npm run test:functions` run (**148 checks**, including 48 subscription/podcast checks). The final commit contains the source-of-truth reconciliation, additional hardening/tests, docs, and this receipt; obtain its hash with `git log -1 --oneline`. Historical first subscriber commit: `c1540ba`, after 35 green targeted checks.

## Studio alignment follow-up (2026-10-12)

- Replaced guessed statuses with exactly queued/sourcing/writing/generating/ready/failed; ready/failed are terminal, unknown status returns 502 `studio_bad_response`.
- Strict style/length/language/engine enums with explicit defaults; book supports text, Gutenberg ID string, or HTTPS URL. Local validation precedes quota/slot reservation and studio dispatch.
- Studio POST must return 202 queued; empty DELETE 204 becomes public 200 `{id,status:"deleted"}`. Public create remains 201. Studio 422 detail becomes bounded, control-stripped friendly 400; 404 remains 404; 429 has a retry hint; timeouts/5xx/bad JSON remain friendly 502 `studio_unavailable`.
- Public jobs allowlist progress/stage/engine/durationSec/error/sources plus other documented public fields; source objects are separately projected. Unknown fields, credentials, and uid/internal fields are excluded. Transcript is only returned on an indexed authorized individual GET, never list. Control stripping cannot reconstruct and expose the shared secret.
- Listing still starts from caller-authorized Blobs rows and merges individual studio answers, never grants access from studio list. The existing owner-code exception can list all gateway-indexed jobs; another subscriber gets local 404 without upstream calls. Upstream ownership 404 also remains 404.
- CAS daily-capacity reservations prevent dispatch over cap without charging usage in advance. Usage increments only on observed studio HTTP 202, including accepted-but-malformed responses; handled non-202 failures release their daily hold. Settlement is idempotent and pinned to the reservation's UTC day. Terminal observations through GET/list/audio/pre-create refresh and confirmed DELETE free active slots. Owner remains daily/active-cap exempt.
- Subscription status now includes `signedIn:true` and `podcastEnabled:Boolean(STUDIO_URL)`, with existing state/plan/usage fields retained. This is configuration presence, not studio health.
- Signed audio payload/HMAC/seconds/600-second expiry/test vector are unchanged. Gateway checks authorized job readiness before issuing a URL (409 otherwise), never fetches/proxies MP3 bytes. The fake studio also returns 409 for signed audio before ready.

### Source-of-truth reconciliation

`/home/hawthornes/podcast-lounge/docs/STUDIO-CONTRACT.md` appeared during this run and was read, along with relevant studio tests, **read-only**. No studio file was edited, no studio tests/service were run, and no service was restarted. Compared its concrete enums, bounds, field types, UID rules, HTTP statuses, and signature constants against the gateway and followed that document where it differed from the brief:

- Trim strings; count input bounds as Unicode characters; allow null/blank optional source strings and irrelevant source fields (still validate any URL/ID).
- Gutenberg nonempty IDs are `[1-9][0-9]{0,6}` (maximum 9,999,999), URL bound is 2,048, public HTTPS DNS hostname/no userinfo/only 443; reject literal IP/local host syntax without fetching/resolving sources in Netlify.
- Check normalized outgoing UTF-8 JSON against 450,000 bytes for chapter/non-null text, otherwise 1,000,000 bytes, before quota or dispatch.
- Forward progress as integer percent 0–100, durationSec as integer seconds, and resolved job engine local/notebooklm (not auto).
- Pure audio verification now validates exact lowercase owner or UUID-v4 identities, preserving identity case in signing; the signature algorithm and published vector did not change.

The studio's newest-first `{items}` list has no cursor; the gateway retains its own index-key public pagination and obtains individual answers to preserve independent authorization and owner-wide listing. Actual direct-audio file availability/Range remains the studio's responsibility; ready metadata is only a gateway precheck. **Live round trip not yet verified**; Hermes acceptance steps are in `docs/PODCAST-GATEWAY.md`.

### Current real verification output

An initial updated targeted run failed at the newly required `signedIn` assertion, reproducing the absent app field. The implemented targeted suite then exited 0 with **48 subscription and podcast checks passed**. First full run exited 0 with **53 endpoint, 48 subscription/podcast, 14 account/sync, 12 group, 7 CORS, 6 report, 8 word-builder checks** (148 total), followed by checkpoint `d5600bf`.

Review reproduced a control-stripping secret-reconstruction issue using only an artificial local test key, then fixed it and added a regression check. A second full run exited 0 with 50 subscription/podcast checks (150 total). After reconciling the newly published studio schema, the latest full `npm run test:functions` exited 0 with these counts **read from the actual command output**:

| Suite output | Checks passed |
| --- | ---: |
| endpoint checks | 53 |
| subscription and podcast checks | 51 |
| account and sync checks | 14 |
| group checks | 12 |
| CORS checks | 7 |
| report checks | 6 |
| word builder checks | 8 |
| **Total** | **151** |

`git diff --check` passed. `git diff 127d9d1 --numstat -- native-android src android` returned no changes. No package/dependency/security-policy edits, frontend/native build, live upstream calls, push, or deploy in this follow-up.

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
- `docs/PODCAST-GATEWAY.md`: final studio-aligned gateway contract, exact signature format, worker responsibilities, live verification boundary, and uncertain-create reconciliation.

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

Owner exemption is from subscriber daily/active caps, not existing IP/platform anti-abuse backstops. AI daily charges happen before upstream work and are not refunded on uncertain upstream failures. Podcast daily charges now happen only on observed studio 202, with separate pre-dispatch capacity reservations. Study's task-specific validation may happen after charging; GET polling does not charge.

Kids paid endpoint result: anonymous → 401 sign_in_required; valid unapproved account → 403 subscription_required; active subscriber → normal allowed request with child-safety rules and normal caps; owner → existing allowed behavior. Existing Kids prompt tests and the new access test pass.

## Historical subscriber-run verification output (before alignment)

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

1. **Contract gap resolved; live round trip not yet verified.** The final contract file was compared and followed as recorded above; fake-studio regression tests cover the actual schema, six statuses, and empty 204 DELETE. This is an offline-tested match, not proof of production reachability, generation, or playback.
2. No Netlify deploy, deployed wildcard-route smoke test, production Blobs CAS/concurrency test, live subscriber approval, or real owner/Christie onboarding. Christie's exact email is not known or guessed.
3. No actual studio/Funnel reachability, shared-secret setup, podcast generation, worker signature/ownership verification, MP3/Range/CORS playback, frontend integration, or Android test. The app CSP was not changed to permit a studio host; playback integration needs a separately approved frontend/security-policy change.
4. No automatic request notification delivery: no existing channel was available; admin/list is the fallback visibility path.
5. Blobs has no multi-record transaction. A crash between state CAS and audit append may leave a changed state without an audit event; create-account may create an account but fail to activate/deliver credentials. Operator reconciliation is documented, not silently destructive rollback.
6. Timed-out or uncertain studio creates retain conservative active reservations, rather than risking excess real jobs, but do not charge daily usage without observed 202. Handled non-202 failures release daily holds; a process crash/store failure can leave unsettled holds or accepted work without a recorded charge. No automatic idempotency/reconciliation service or operator recovery endpoint was built. J must reconcile studio work before repairing uncertain reservations/counters. Global ID claims intentionally survive job deletion. Account deletion does not cancel remote studio work or erase podcast history.
7. Already-issued signed audio URLs remain valid capabilities for at most ten minutes. Revocation prevents new gateway access on the next request, not playback via an existing capability.
8. Installer-reported moderate vulnerabilities were not remediated. No live cloud AI billing, provider delivery, email ownership, or physical audio was tested.

## Concrete next tests (not performed)

- Hermes should execute the live acceptance sequence in PODCAST-GATEWAY.md; the schema/status/delete comparison and fake-studio alignment are complete.
- In a separately approved staging deploy, verify new `/api/subscription/*` and `/api/podcast/*` routing and production CAS with disposable accounts.
- Create/approve a disposable account, sign in using its returned credentials, verify 403 before approval, AI access after approval, and next-request 403 after revoke.
- Verify the studio's deployed HMAC/ownership/expiry checks, fixed vector, tamper/expiry failures, and direct Range streaming from the real studio host; this session did not run or certify the worker's offline suite.
- Only then integrate app UI, studio media/CSP/CORS, and actual J/Christie onboarding. No deployment or real-world effects are claimed in this receipt.
