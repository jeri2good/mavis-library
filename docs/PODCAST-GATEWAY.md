# Podcast gateway / studio integration contract

The Netlify gateway is the public subscriber-only front door. It is not a podcast generator and never proxies MP3 bytes. The studio runs separately on J's iMac. No studio files, deployment, Funnel configuration, or app UI were changed.

**Contract aligned and fake-studio verified; live round trip not yet verified.** Source of truth: `/home/hawthornes/podcast-lounge/docs/STUDIO-CONTRACT.md` (2026-10-12), read-only. It landed during alignment and supersedes the brief where details differ: trimmed/nullable source strings, irrelevant source fields accepted, Gutenberg IDs limited to seven digits, 2,048-character URLs, encoded body limits, progress as integer percent, resolved job engine, and UUID-v4 audio identities. Hermes still needs to verify the actual Netlify → studio → audio round trip. This is the subscriber gateway lane, not a replacement for the default Mavis voice architecture or an experimental voice-provider lane.

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

JSON endpoints require strict `application/json` objects, at most 256,000 bytes read incrementally (including chunked responses); HTML/text/arrays/malformed JSON/redirects/oversize bodies fail. DELETE is the exception: an empty `204 No Content` is accepted without JSON parsing. POST must return `202`, job GET must return `200`, and DELETE must return `204`.

Error mappings:

| Upstream result | Public response |
| --- | --- |
| 422 `{detail:string}` | 400 `bad_request`; detail stripped of C0/C1 controls, capped at 300 characters; blank detail uses a friendly fallback |
| 404 | 404 `not_found` (including upstream ownership rejection) |
| 429 | 429 `studio_busy`, friendly message and `retryAfter` seconds; positive integer Retry-After capped at 3,600, otherwise 60 |
| 5xx, timeout, network failure, bad JSON/content type, unexpected HTTP response | 502 `studio_unavailable`; no upstream diagnostics echoed |
| Unknown job status or mismatched/invalid job ID | 502 `studio_bad_response` |

Responses containing the decoded shared secret, including strings that would reconstruct it after control stripping, are rejected rather than forwarded. No unknown fields or internal ownership/credential fields are passed through. Allowlisted human-readable `error` is exposed as job metadata, not an unrestricted provider object.

## Public API

| Method/path | Result |
| --- | --- |
| POST `/api/podcast/create` | 201 `{id,status:"queued"}` after studio 202 |
| GET `/api/podcast/list?cursor=...` | `{jobs:[PublicJob],cursor}`; index-key pagination, maximum 100; never includes transcript |
| GET `/api/podcast/job/<id>` | `PublicJob`, optionally including owner-authorized `transcript` |
| DELETE `/api/podcast/job/<id>` | 200 `{id,status:"deleted"}` after studio 204 |
| GET `/api/podcast/audio/<id>` | `{url,expiresAt}` only after an authorized job GET confirms ready; otherwise 409 `audio_not_ready`; no audio byte proxy |

Job IDs must match `[A-Za-z0-9_-]{1,100}`. Studio statuses are **exactly** `queued | sourcing | writing | generating | ready | failed`. Only `ready` and `failed` are terminal. `deleted` is a gateway DELETE acknowledgment, never an accepted studio job status.

`PublicJob` allowlists `id`, `status`, `progress`, `stage`, `engine`, `title`, `kind`, `style`, `durationSec`, `createdAt`, `error`, and `sources`. Optional fields are exposed only with suitable types: integer progress 0–100 (**percent**, not fractional progress), nonnegative safe integer durationSec (**seconds**), resolved engine `notebooklm|local` (never auto), recognized kind/style, string title/stage/error/createdAt. Title/stage/source titles are capped at 300 characters, error at 1,000, createdAt at 100; controls are stripped. Sources are capped at 100 and each source projects only `{title,url?}`; URL is at most 2,048 characters, HTTP(S), without embedded credentials. A string transcript is exposed only on the authorized individual job GET and remains subject to the overall 256,000-byte upstream response limit. Polling may skip stages or revisit writing/generating during auto fallback; only the status vocabulary, not a monotonic sequence, is enforced.

### Create validation

Accepted JSON object fields only:

```text
{kind,title,text?,url?,gutenbergId?,query?,style?,length?,language?,engine?}
```

| Field/kind | Gateway rule |
| --- | --- |
| kind | book, chapter, article, topic |
| title | Required nonblank trimmed string, maximum 300 Unicode characters |
| chapter | Required nonempty trimmed text, maximum 400,000 Unicode characters |
| article | Required public HTTPS URL |
| book | Nonempty gutenbergId OR text OR public HTTPS URL; text also bounded at 400,000 characters; studio precedence text → Gutenberg → URL |
| URL | Public HTTPS DNS hostname, maximum 2,048 characters, no userinfo, only default/443 port; literal IP, localhost/local/internal/lan names, malformed DNS labels, backslash and controls rejected |
| topic | Required nonblank trimmed query, maximum 300 Unicode characters |
| gutenbergId | Nonempty value must be decimal text `[1-9][0-9]{0,6}` (1–9,999,999); never a JSON number |
| style | `deep-dive`, `brief`, `debate`, `critique`; default `deep-dive` |
| length | `short`, `default`, `long`; default `default` |
| language | Only `en`; default `en` |
| engine | `auto`, `notebooklm`, `local`; default `auto` |

All strings are trimmed before validation, with bounds counted in Unicode characters rather than UTF-16 units. Optional text/url/query/gutenbergId may be null or blank; kind-specific required sources must still be nonempty. **Source fields are not forbidden merely because they are irrelevant to a kind**, but any supplied URL/ID is validated. NUL/DEL controls are rejected in source text/url/query. Unknown fields, unknown enum values, null controls, numeric length, and invalid sources fail with a friendly 400 **before reserving capacity or dispatching**. Defaults are sent explicitly to the studio.

The inbound readJson envelope remains limited to 1,700,000 JavaScript string characters. The actual normalized outgoing JSON is additionally measured in UTF-8 bytes before dispatch: **450,000 bytes** when kind is chapter or non-null text is supplied, otherwise **1,000,000 bytes**, matching studio admission. Multibyte characters/JSON escaping can exceed that cap despite valid character counts and produce friendly 400 without charging. No source content is fetched by Netlify; the studio handles DNS/public-address resolution, redirect revalidation, and source fetching. URL syntax checks in the gateway are not a substitute for the studio's SSRF defenses.

POST create forwards validated fields to `STUDIO_URL/v1/podcasts`; the studio returns `202 {id,status:"queued"}` with a unique ID. The gateway keeps its app-facing success HTTP status of 201. Subscribers reserve an active slot (maximum two) and daily capacity (default three/day UTC) with CAS before dispatch. **Daily usage is charged only when the studio returns HTTP 202**, not on local/studio validation errors, 429, 5xx, timeout, or other non-202 responses. A 202 with malformed JSON or a duplicate ID is still accepted work for accounting and is charged, though the app gets an error. Owner code is exempt from both limits.

## Ownership and concurrency

Blobs store `mavis-subs`:

- `pod/<userId>/<id>`: immutable `{id,userId,createdAt}` ownership index.
- `pod-id/<id>`: global create-only ID claim; a duplicate studio ID cannot be rebound to another user. This claim is intentionally retained after deletion to prevent ID reuse.
- `pod-active/<userId>`: CAS-protected `{slots:[...]}`, containing job IDs and temporary `~reserve-<uuid>` reservation tokens. Reservation tokens cannot be mistaken for valid studio IDs.
- `use/<userId>/<yyyymmdd>`: existing feature counters plus internal `podcastReserved:[uuid,...]` daily-capacity holds. Public usage shows only accepted `podcast` count, never reservation IDs. Accepted count plus holds cannot exceed the cap. Settlement is idempotent and tied to the UTC day of reservation, even if the call crosses midnight.

The caller's user ID is derived only from the validated account, never the request body. Job GET/DELETE/audio checks the gateway index before contacting the studio; another subscriber's job returns 404, not 403, with no upstream request. Owner-code requests may access/list all indexed jobs. An owner viewing/deleting another user's job sends that indexed owner's user ID upstream; an owner-created job sends owner.

Listing walks only authorized index entries and fetches each corresponding studio job using GET `/v1/podcasts/<id>`. It deliberately does not trust a studio-wide listing for ownership. Only jobs created through this gateway are listed, even for the owner. List results are projected to bounded job fields and have a shared upstream deadline; a slow/large listing may return a friendly failure rather than partial success. Cursors are key-based, not snapshot cursors.

Studio GET `/v1/podcasts` returns `{items:[Job]}` newest first, maximum 100, without transcripts. GET `/v1/podcasts/<id>` returns the same Job object plus an optional transcript. The gateway merges its authorized index rows with individual studio answers rather than trusting upstream listing for authorization; its public list retains index-key pagination, not the studio's newest-first ordering. Studio ownership is also enforced with `X-Mavis-User`; another uid's job returns 404.

DELETE `/v1/podcasts/<id>` returns **204 No Content with an empty body**, or 404 when unknown. After 204, the gateway frees the indexed user's slot, removes the per-user index, and returns its own JSON `{id,status:"deleted"}`. The global ID claim remains. Failed deletion preserves authorization/slot state for retry/reconciliation.

Terminal jobs observed through GET/list/audio readiness checks or pre-create refresh free their slots using CAS. Racing creates reserve at most two slots; racing status updates cannot overwrite a new reservation. Definitive non-202 responses below 500 (except ambiguous 408) release the temporary active reservation; 5xx/network/timeout retain it conservatively. Daily holds are released on every handled non-202 failure and become usage only on 202.

### Failure reconciliation

Blobs and the studio do not share a transaction. A timed-out POST may have created work despite an absent reply. **Unknown active-slot reservations are not automatically expired**, because doing so would permit more than two real active jobs; daily usage is nevertheless not charged without observed 202. Failed/ambiguous creates, duplicate IDs, or a crash between studio creation and index updates can leave conservative active reservations needing owner reconciliation. A process crash/store failure can also leave an unsettled daily-capacity hold or accepted work without a recorded usage increment. Inspect studio jobs for that user and resolve outstanding work before manually repairing `pod-active/<userId>`, ownership records, or daily holds/counters; do not blindly clear reservations or retry repeatedly. No automatic reconciliation service or operator recovery endpoint is included. An idempotent create contract from the studio would be the preferred next improvement.

Account deletion removes subscriber entitlement, but does not automatically cancel remote studio work or delete podcast history/ID claims. A deleted account's old token cannot access any route; the owner can reconcile its jobs. Old signed audio URLs remain bearer capabilities for at most ten minutes.

## Direct audio signature — exact worker requirement

The gateway constructs:

```text
<STUDIO_URL>/v1/podcasts/<id>/audio?exp=<exp>&uid=<uid>&sig=<sig>
```

- `exp`: base-10 integer Unix **seconds**, computed as `floor(nowMilliseconds/1000) + 600`.
- `uid`: validated canonical account UUID v4, or exactly lowercase `owner` when owner code authorized the URL. The studio/verifier accepts UUID-v4 hex in either case without normalization, but signing always uses the exact original identity string; `OWNER` and non-v4/malformed UUIDs are rejected.
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

`npm run test:functions` includes the local subscription/podcast suite; `node tests/functions/subs.test.mjs` runs that suite alone. Studio calls are answered by fake global fetch; local Blobs uses a separately retained real fetch. Coverage includes the exact queued → sourcing → writing → generating → ready sequence, failed-slot release, allowlisted metadata/transcript privacy, sanitized 422, 429 retry hint, empty 204 deletion, 409 audio readiness, unknown-status 502, local/upstream ownership 404, accepted-only charge accounting, active/daily races, UTC/idempotent quota settlement, signed-URL tamper/expiry, timeouts/redirects/malformed/oversized/secret-containing responses, duplicate IDs, owner exemptions, CORS/configuration, and feature flags.

**Live round trip not yet verified.** The input/status/delete contract is now specified and tested with a fake studio, but production Netlify route matching/Blobs CAS, Funnel reachability, shared secret configuration, actual podcast generation, studio signature/ownership enforcement, direct MP3 streaming, Range, audio CORS, app CSP compatibility, and UI integration remain unverified. The site's current static CSP does not add a studio media/connect origin; no security policy was weakened or changed. A separately approved frontend/security-policy integration is required before claiming browser playback. No push or deploy was performed.

Hermes live acceptance sequence (not performed here):

1. In an approved staging environment, use two disposable signed-in subscribers; verify subscription status includes `signedIn:true`, usage, and configuration-derived `podcastEnabled`.
2. POST a topic with omitted controls; verify studio receives the explicit defaults, public create returns queued, and daily usage increases once. Invalid style/source and studio rejection must leave usage unchanged.
3. Poll the authorized job through the six-status vocabulary; verify progress/stage/engine/sources/duration/error and owner-only transcript. A second subscriber must get 404 without upstream access; list must contain no unindexed jobs or transcript.
4. Before ready, gateway audio returns 409. Once ready, obtain a signed URL and privately fetch directly from studio with `Range: bytes=0-1023`; verify audio/mpeg, a valid partial response, ownership, the fixed signature vector, tamper/expiry rejection, and app-origin CORS. Do not publish/log the signed URL.
5. DELETE an active disposable job; verify upstream empty 204 becomes public deleted JSON, the slot is free, its per-user index is gone, and prior daily usage is not refunded. Also verify failed/ready observation frees a slot and parallel dispatch respects both caps.
