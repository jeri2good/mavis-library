# Podcast gateway / studio integration contract

The Netlify gateway is the public subscriber-only front door. It is not a podcast generator and never proxies MP3 bytes. The studio runs separately on J's iMac. No studio files, deployment, Funnel configuration, or app UI were changed.

**Integration status: experimental, not live-verified.** The studio's actual validation schema was not supplied and is not present in this repository. The gateway implements the brief's source rules plus bounded passthrough controls below; matching the studio's exact style/length/language/engine schema, status vocabulary, and DELETE result remains a blocking integration check. These are the gateway's current contract, not a claim that the studio already implements it.

## Environment and transport

- `STUDIO_URL`: HTTPS base URL, optionally including a base path. No embedded username/password, query, or fragment. The gateway appends `/v1/podcasts`. Use J's actual approved HTTPS/Funnel base; no public tunnel was created here.
- `STUDIO_SHARED_SECRET`: server-only shared authentication and HMAC key. Configure a strong randomly generated secret on both systems; never put it in the app, repository, response, or logs.
- `AUTH_SECRET`, `MAVIS_ACCESS_CODE`, `SUB_CAP_PODCAST`, and local test Blobs settings are documented in SUBSCRIPTIONS.md.

Absent/invalid studio configuration returns 503 `{code:"studio_not_configured",error:"studio_not_configured",message:...}` after the caller's access check. The public features flag is deliberately `Boolean(STUDIO_URL)` as requested; it is not a health check and does not prove a shared secret is configured.

Every gateway route needs `requireAccess({feature:"podcast"})`: valid owner code or active/unexpired subscriber session. Origin/CORS rules follow the existing account rules; DELETE preflight is supported. IP soft/platform limits remain in place.

Upstream requests send only:

```text
X-Studio-Key: <STUDIO_SHARED_SECRET>
X-Mavis-User: <account UUID, or owner>
Accept: application/json
Content-Type: application/json   (POST only)
```

No inbound authorization header, owner code, Origin, cookie, or arbitrary header is forwarded. Fetch uses `redirect: manual`, `credentials: omit`, and an AbortSignal. Each upstream call is limited to 10 seconds, further constrained by a shared 10-second upstream deadline for the route's multi-call work. Blobs operations have SDK-managed retry behavior and are not covered by that upstream deadline; total function duration is not claimed to be exactly 10 seconds.

Only strict `application/json` object responses are accepted, at most 256,000 bytes read incrementally (including chunked responses); HTML/text/arrays/malformed JSON/redirects/oversize bodies fail. Errors are mapped to friendly 502 `studio_unavailable`, never upstream error text. An upstream 404 is mapped to friendly 404 `not_found`. The gateway exposes only allowlisted job fields `id`, `status`, optional bounded `title`; provider diagnostics, paths, internal ownership fields, and credentials are not forwarded. Responses containing the decoded shared secret are rejected.

## Public API

| Method/path | Result |
| --- | --- |
| POST `/api/podcast/create` | 201 `{id,status}` |
| GET `/api/podcast/list?cursor=...` | `{jobs:[{id,status,title?}],cursor}`; index-key pagination, maximum 100 |
| GET `/api/podcast/job/<id>` | `{id,status,title?}` |
| DELETE `/api/podcast/job/<id>` | `{id,status,title?}` only after terminal cancellation/deletion is confirmed |
| GET `/api/podcast/audio/<id>` | `{url,expiresAt}`; no audio fetch or byte proxy |

Job IDs must match `[A-Za-z0-9_-]{1,100}`. Status values currently accepted: pending, queued, running, processing, completed, ready, failed, cancelled, canceled, deleted. Terminal: completed, ready, failed, cancelled, canceled, deleted. These enums must be checked against the studio before integration.

### Create validation

Accepted JSON object fields only:

```text
{kind,title,text?,url?,gutenbergId?,query?,style?,length?,language?,engine?}
```

| Field/kind | Current gateway rule |
| --- | --- |
| kind | book, chapter, article, topic |
| title | Required nonblank string, maximum 300 characters |
| chapter | Required text, maximum 400,000 characters; text allowed only for chapter |
| article | Required HTTPS URL |
| book | HTTPS URL or positive integer gutenbergId |
| URL | HTTPS only, maximum 2,000 characters, no embedded credentials; only article/book |
| topic | Required nonblank query, maximum 300 characters; query allowed only for topic |
| gutenbergId | Positive safe integer; book only |
| style/engine | Optional nonblank strings, maximum 80 characters; no guessed studio enum |
| language | Optional nonblank string, maximum 40 characters; no guessed studio enum |
| length | Optional bounded string token `[A-Za-z0-9_-]{1,40}` or finite positive numeric value ≤120; numeric units are **not verified** |

NUL/DEL controls are rejected in optional text/control strings. Invalid/unknown fields fail with 400. The request envelope is limited to 1,700,000 JavaScript string characters by the existing readJson helper; its bound is not a UTF-8 byte count. This allows long chapter payloads while bounding gateway memory. Style/engine/language enum/default semantics and length units must be reconciled with the real studio schema before this lane is considered integration-ready. No source content is fetched by Netlify. The studio must implement its own SSRF defenses for URL fetching, including DNS/private-address checks and redirect limits.

POST create forwards validated fields to `STUDIO_URL/v1/podcasts`; the studio must return a unique `{id,status}` JSON object. Subscribers consume the daily podcast quota before upstream creation (default three/day UTC), and reserve an active slot with CAS before dispatch (maximum two). Owner code is exempt from both limits. Validation failures do not create jobs or charge; uncertain upstream failures are conservatively charged.

## Ownership and concurrency

Blobs store `mavis-subs`:

- `pod/<userId>/<id>`: immutable `{id,userId,createdAt}` ownership index.
- `pod-id/<id>`: global create-only ID claim; a duplicate studio ID cannot be rebound to another user. This claim is intentionally retained after deletion to prevent ID reuse.
- `pod-active/<userId>`: CAS-protected `{slots:[...]}`, containing job IDs and temporary `~reserve-<uuid>` reservation tokens. Reservation tokens cannot be mistaken for valid studio IDs.

The caller's user ID is derived only from the validated account, never the request body. Job GET/DELETE/audio checks the gateway index before contacting the studio; another subscriber's job returns 404, not 403, with no upstream request. Owner-code requests may access/list all indexed jobs. An owner viewing/deleting another user's job sends that indexed owner's user ID upstream; an owner-created job sends owner.

Listing walks only authorized index entries and fetches each corresponding studio job using GET `/v1/podcasts/<id>`. It deliberately does not trust a studio-wide listing for ownership. Only jobs created through this gateway are listed, even for the owner. List results are projected to bounded job fields and have a shared upstream deadline; a slow/large listing may return a friendly failure rather than partial success. Cursors are key-based, not snapshot cursors.

Studio GET `/v1/podcasts/<id>` must return `{id,status,title?}`. DELETE to that same path must return the same shape with a terminal status; an empty 204 is not accepted. The gateway retains authorization/slot state until cancellation is confirmed, then removes the per-user index and frees the slot. The studio is responsible for ensuring terminal means work is no longer active.

Terminal jobs observed through GET/list or the pre-create refresh free their slots using CAS. Racing creates reserve at most two slots; racing status updates cannot overwrite a new reservation. Daily counters use the subscription CAS counter helpers.

### Failure reconciliation

Blobs and the studio do not share a transaction. A timed-out POST may have created work despite an absent reply. **Unknown reservations are not automatically expired/refunded**, because doing so would permit more than two real active jobs. Failed/ambiguous creates, duplicate IDs, or a crash between studio creation and index updates can leave conservative reservations needing owner reconciliation. Inspect studio jobs for that user and resolve outstanding work before manually repairing `pod-active/<userId>`/ownership records; do not blindly clear a reservation or retry repeatedly. No automatic reconciliation service or operator recovery endpoint is included. An idempotent create contract from the studio would be the preferred next improvement.

Account deletion removes subscriber entitlement, but does not automatically cancel remote studio work or delete podcast history/ID claims. A deleted account's old token cannot access any route; the owner can reconcile its jobs. Old signed audio URLs remain bearer capabilities for at most ten minutes.

## Direct audio signature — exact worker requirement

The gateway constructs:

```text
<STUDIO_URL>/v1/podcasts/<id>/audio?exp=<exp>&uid=<uid>&sig=<sig>
```

- `exp`: base-10 integer Unix **seconds**, computed as `floor(nowMilliseconds/1000) + 600`.
- `uid`: validated account UUID, or exactly `owner` when owner code authorized the URL.
- Signing payload: UTF-8 bytes of the exact string `${id}.${exp}.${uid}`. No newline, no JSON, no URL encoding inside the signed payload.
- `sig`: lowercase hexadecimal HMAC-SHA256, key is the UTF-8 bytes of `STUDIO_SHARED_SECRET`; 64 hex characters.
- `expiresAt`: response ISO timestamp corresponding to `exp * 1000`.

Pure helpers `signAudio(secret,id,exp,uid)` and `verifyAudio(secret,id,exp,uid,sig,nowMilliseconds)` are exported from netlify/lib/podcast.mjs. Verification requires a valid ID/uid, ten-digit decimal expiration, lowercase 64-character hex signature, `exp > floor(now/1000)`, and `exp <= floor(now/1000)+600`, then uses constant-time comparison. Keep worker/gateway clocks synchronized.

The studio worker must additionally check that the requested job belongs to uid, unless uid is owner; the pure verifier only authenticates the capability. Reject duplicate exp/uid/sig query parameters instead of picking an ambiguous value. Audio must be delivered directly by the studio with Range support and appropriate CORS for the app's origins. Treat signed URLs as temporary credentials, use private caching that does not outlive expiry, and redact query signatures from logs. Possession permits streaming until expiry; revocation stops issuance on the next gateway request, not already-issued URLs.

Deterministic non-production test vector, generated and checked against an independent Node crypto HMAC:

```text
secret: vector-secret
id: job-vector
exp: 1800000600
uid: owner
payload: job-vector.1800000600.owner
sig: 92eff03c6dda51ba238460e571ec6c47632852c48812f32afbc67167c3dde4ce
verification nowMilliseconds: 1800000000000
```

Changing id, exp, uid, secret, or signature must fail; expiration at or before now and a future duration over ten minutes must fail. Never use this test key in production.

## Testing and unverified boundaries

`npm run test:functions` includes the local subscription/podcast suite. Studio calls are answered by fake global fetch; local Blobs uses a separately retained real fetch. Coverage includes validation/ownership/caps/races, signed-URL tamper/expiry, timeouts/redirects/malformed/oversized/secret-containing responses, duplicate IDs, owner exemptions, CORS/configuration, and feature flags.

Not verified: real studio input schema, production Netlify route matching/Blobs CAS, Funnel reachability, shared secret configuration, actual podcast generation, studio signature/ownership implementation, direct MP3 streaming, Range, audio CORS, app CSP compatibility, or UI integration. The site's current static CSP does not add a studio media/connect origin; no security policy was weakened or changed. A separately approved frontend/security-policy integration is required before claiming browser playback. No push or deploy was performed.
