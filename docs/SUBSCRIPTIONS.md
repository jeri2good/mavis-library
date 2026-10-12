# Subscriber access

The app, catalogue, books, and ordinary account/group functions remain reachable on the open internet. Cloud AI usage requires an approved subscriber session or the existing owner code. This is a manual approval system, not a payment implementation.

## Access decision table

| Endpoint | Access | Daily subscriber charge |
| --- | --- | --- |
| `/api/assistant` POST | Active subscriber or owner code | assistant: 150 messages |
| `/api/rank` POST | Active subscriber or owner code | rank: 300 requests |
| `/api/study` POST, all tasks including images/motion | Active subscriber or owner code | study: 60 tasks |
| `/api/study` GET, image/motion polling | Active subscriber or owner code | None; polling must not consume generation quota |
| `/api/tts` POST | Active subscriber or owner code | tts: 80 clips |
| `/api/groups`, action `questions` | Signed-in group member AND active subscriber or owner code | Shared study bucket: 60 |
| `/api/groups`, other actions | Existing signed-in/group-membership rules unchanged | None |
| `/api/voices`, every operation | Owner code only | None; subscribers cannot manage or clone voices |
| `/api/subscription/status`, `/request` | Valid account session, subscription not required | Request action: five attempts per rolling hour per user |
| `/api/subscription/admin/*` | Owner code only; a Bearer token cannot authorize administration | None |
| `/api/podcast/*` | Active subscriber or owner code | create only: 3 jobs, maximum 2 active jobs |
| `/api/features` | Public, existing owner-code verification unchanged | None |
| Catalogue/downloads and other existing public endpoints | Unchanged | None |

The owner code bypasses subscriber quotas and podcast active-job limits; existing IP soft/platform backstops still apply. `requireOwner` itself is unchanged, including its historical missing-configuration/wrong-code responses. Some existing source comments still describe the old owner-only policy; this table describes the implemented policy.

`requireAccess` first checks the owner code using the existing constant-time hash comparison, then validates the account session HMAC, expiration, user existence, and current `tokenVersion`. It reads the subscription afresh on every request. Invalid/missing sessions return 401 `code: sign_in_required`. Non-active subscriptions return 403 `code: subscription_required`, `state: none|pending|denied|revoked`, and a truthful message. An expired stored active subscription has no current entitlement: access errors use `state: none, expired: true`; status still reports the stored active state and its expiry. Store/configuration errors fail closed with 503. New errors include both `error` and `code` for compatibility with existing clients.

Kids mode is neither a credential nor an exemption. `x-mavis-kids: 1` keeps the existing child-safety prompts; anonymous paid requests get 401, unapproved signed-in requests get 403, approved requests work and consume the same quota. Public Kids features and owner-code behavior are unchanged. No frontend/native UI was changed.

## Records and transitions

Netlify Blobs store: `mavis-subs`, strong consistency.

- `s/<userId>`: `{userId,email,state,plan,source,requestedAt,message,decidedAt,decidedBy,note,expiresAt,updatedAt}`.
- State: pending, active, denied, revoked. No record means none.
- Plan: family or member. Source: manual or request; source is a string field that can accommodate future billing providers, but no Stripe/webhook/payment code exists.
- Timestamps in records are Unix milliseconds. `expiresAt` is null for manual grants; a future billing adapter may write a numeric expiration using CAS. Invalid expirations fail closed.
- Messages/owner notes are limited to 200 characters. Requests preserve no arbitrary extra fields.
- `use/<userId>/<yyyymmdd>`: shared CAS counters for each daily UTC feature.
- `requests/<userId>`: CAS-protected timestamps for the five-per-hour request rate limit.
- `log/<ts>-<random>`: append-only `{at,action,userId,by}`; ISO `at`, owner decisions have `by: owner`, user requests/deletion have the user's ID. No request text, note, credential, or IP is included.

Legal transitions, re-evaluated after every CAS conflict:

- none → pending via request (201).
- pending → pending via request (200, unchanged); pending → active/denied via owner decision.
- active → active via request (200, unchanged); active → revoked via owner decision.
- denied → pending after seven days from `decidedAt`; before then 429 `request_cooldown` with `retryAfter` in seconds.
- revoked → pending via request, or active via approve.
- Any state → active via owner add, resetting expiration to null.

Invalid owner transitions return 409. All state changes and counters use `readForUpdate`/`writeIfUnchanged` retry loops. Parallel approve/deny has one winner. Revocation takes effect on the next request, not after a cache TTL. Account-wide sign-out invalidates tokens via the existing version bump. Account deletion removes `s/<userId>` and retains the audit history.

The request rate limit counts attempts, including idempotent calls, so clients should use GET status rather than repeatedly POSTing request. AI quotas are consumed after basic validation, before upstream generation, and are not refunded on upstream failure. Podcast is the exception: daily capacity is CAS-reserved before dispatch but usage is charged only on studio HTTP 202; handled non-202 failures release the daily hold. Internal `podcastReserved` tokens are not public usage. Study task-internal validation may happen after charging. Daily-limit responses are 429 `daily_limit` with an ISO UTC `resetsAt`.

## API

Bearer headers use the same token returned by `/api/account/signin`.

| Method/path | Body/result |
| --- | --- |
| GET `/api/subscription/status` | `{state,plan,signedIn:true,podcastEnabled,since,expiresAt,usage:{assistant:{used,cap},tts:...,rank:...,study:...,podcast:...}}`; `since` is latest owner decision time; `podcastEnabled = Boolean(STUDIO_URL)` lets the app hide the Lounge but is not a studio health check |
| POST `/api/subscription/request` | `{message?}` → `{state,plan,requestedAt}`; new none → pending is 201, other successful/idempotent requests are 200 |
| GET `/api/subscription/admin/list?state=pending|active|all&limit=200&cursor=...` | `{items:[{userId,email,state,plan,requestedAt,message}],cursor}`; default pending, maximum 200, pass returned cursor until null |
| POST `/api/subscription/admin/approve` | `{userId|email}` → `{state,userId,plan}` |
| POST `/api/subscription/admin/deny` | `{userId|email,note?}` |
| POST `/api/subscription/admin/revoke` | `{userId|email}` |
| POST `/api/subscription/admin/add` | `{email,plan?}`; existing account only, 404 `account_not_found` with instructions otherwise |
| POST `/api/subscription/admin/create-account` | `{email,plan?}` → 201 `{email,tempPassword,recoveryCode}`; new account only, 409 if it already exists |

Same-origin checks accept the site's origin, no Origin header for API clients, and the existing two allowed app origins. CORS preflight is supported. Subscription requests also have per-IP soft/platform limits. Listing cursors are key-based and not transactional snapshots; concurrent additions behind a cursor may appear on a fresh listing.

## Manual onboarding and the email-squatting decision

**There is deliberately no email pre-approval whitelist.** Signup does not verify email ownership. A whitelist would let the first person registering a listed address inherit access. The owner must either:

1. Confirm an existing account belongs to the intended person out-of-band, then use admin/add; or
2. Create a new account with admin/create-account and privately hand over the generated temporary password and recovery code.

The credentials are returned once with no-store headers, stored only as scrypt hashes, and never logged by this implementation. J must transfer them privately; do not paste them into shared chat/logs or store them in shell history. The user signs in using the normal signin route and changes the password via POST `/api/account/password` with `{currentPassword,password}` and their Bearer token. Password change rotates the token version; the new token is returned. The recovery code follows the existing account recovery contract.

J's intended email is `jermaine.hawthorne@gmail.com`. It is not automatically trusted or seeded. Christie must provide her exact email at seed time; no address is invented or seeded. A fresh signup with J's intended address is explicitly tested to have no subscriber entitlement.

Owner examples (replace the site placeholder and owner-code placeholder locally; never commit a real code):

```sh
curl "$SITE/api/subscription/admin/list?state=pending" \
  -H 'x-mavis-access: <OWNER_CODE>'

curl "$SITE/api/subscription/admin/approve" \
  -H 'x-mavis-access: <OWNER_CODE>' -H 'Content-Type: application/json' \
  --data '{"userId":"<USER_ID>"}'

curl "$SITE/api/subscription/admin/add" \
  -H 'x-mavis-access: <OWNER_CODE>' -H 'Content-Type: application/json' \
  --data '{"email":"<EXISTING_ACCOUNT_EMAIL>","plan":"family"}'

curl "$SITE/api/subscription/admin/create-account" \
  -H 'x-mavis-access: <OWNER_CODE>' -H 'Content-Type: application/json' \
  --data '{"email":"<CONFIRMED_NEW_EMAIL>","plan":"family"}'
```

These commands are documentation only; no production onboarding was performed.

## Notifications

This tree contains no TELEGRAM_* configuration or Telegram delivery code; report.mjs only stores reports in Blobs. No notification channel was invented. J must poll the pending admin list. New requests produce one transition/audit event, not repeated events on idempotent retries.

## Environment

New/required access settings:

| Variable | Purpose/default |
| --- | --- |
| `AUTH_SECRET` | Existing session signing secret, at least 32 characters; required for accounts/subscribers |
| `MAVIS_ACCESS_CODE` | Existing owner bypass/admin credential; kept private |
| `SUB_CAP_ASSISTANT` | Daily messages, default 150 |
| `SUB_CAP_TTS` | Daily clips, default 80 |
| `SUB_CAP_RANK` | Daily ranking requests, default 300 |
| `SUB_CAP_STUDY` | Daily tasks/questions shared, default 60 |
| `SUB_CAP_PODCAST` | Daily studio-accepted (202) jobs, default 3; in-flight capacity reserved before dispatch |
| `STUDIO_URL` | HTTPS studio base, see PODCAST-GATEWAY.md; absent disables gateway |
| `STUDIO_SHARED_SECRET` | Private studio authentication/HMAC secret; required for gateway |
| `MAVIS_BLOBS_URL` | Local test server only; do not set in production |
| `MAVIS_BLOBS_TOKEN` | Local Blobs token; local-only default is local-token |

Cap overrides must be safe, nonnegative integers (zero disables subscriber generation); invalid values fall back to defaults. Owner usage is exempt. Blobs production binding is supplied by Netlify, not an additional application secret.

Existing provider settings remain unchanged: `LLM_PROVIDER`, `LLM_API_KEY`, `LLM_MODEL`, `LLM_BASE_URL`, `LLM_REASONING_EFFORT`; `TYPESAFE_API_KEY`, `EDENAI_API_KEY`, `JEV_MODEL`; `TTS_PROVIDER`, `FISH_AUDIO_API_KEY`, `FISH_AUDIO_VOICE_ID`, `FISH_AUDIO_MODEL`, `GOOGLE_TTS_API_KEY`, `GOOGLE_TTS_VOICE`; `IMAGE_LLM_MODEL`, `IMAGE_QUALITY`, `IMAGE_MODEL`; `FAL_KEY`, `FAL_VIDEO_MODEL`. Features also reports existing `GOOGLE_BOOKS_API_KEY` presence. No values are returned in subscription/feature responses.

## Verification and limitations

Run `npm ci` once, then `npm run test:functions`. The new suite is imported by the existing functions.test.mjs runner, so no package-script edit is required. It uses a real local Blobs server and mocked studio/AI fetches, never external network calls. The SDK's local server has a non-atomic check/write implementation; the test server serializes PUTs per key to model production atomic If-Match/If-None-Match while client operations remain concurrent. This proves the application CAS algorithm under modeled atomic writes, not the deployed Netlify service.

Blobs does not provide multi-key transactions. A process crash/store failure between a successful state CAS and the audit append can leave the state changed without an audit event. Similarly, create-account can create the account but fail to activate it or deliver credentials; it does not roll back/delete the account silently. These partial failures require owner reconciliation; do not blindly repeat create-account and assume a new credential will be returned. Existing account recovery applies if the recipient already received a recovery code; otherwise operator intervention is needed. No production concurrency, email identity, live AI delivery, or deployed route matching has been verified here.
