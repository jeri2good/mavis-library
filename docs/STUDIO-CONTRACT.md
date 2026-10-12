# Studio HTTP contract — gateway source of truth

Date: 2026-10-12. Implementation: `service.py` in `/home/hawthornes/podcast-lounge`.

This is the studio's actual contract, not the public Netlify API. The gateway must adapt its own validation, status allowlist, pagination, and DELETE handling to this document. The gateway lane is implemented and offline-tested; the live Netlify → Funnel → studio round trip is **not verified**. Existing generation engines, statuses, and owner Bearer mode remain intact.

## Transport and authentication

Paths below are relative to the studio base. A gateway base ending in `/studio` appends `/v1/podcasts` (not an absolute `/v1/...` URL that discards the prefix). Both stripped-prefix upstream requests `/v1/...` and intact-prefix requests `/studio/v1/...` work. `/studio` is the default explicit prefix; `create_app(path_prefix=...)` can override it. ASGI `root_path` matching the configured prefix is also supported. No response contains an absolute audio URL. Trailing-slash redirects are disabled; use exact paths.

Run one uvicorn process/worker, with `--no-access-log --no-proxy-headers`, as already specified in the deployment unit. Forwarded host/IP/prefix headers do not affect routing, authorization, ownership, signature validation, or rate-limit identity. A valid `X-Forwarded-Proto: http|https` may appear as `forwardedProto` in structured logs only. The direct-IP limiter sees the proxy's socket IP; it does not trust `X-Forwarded-For`.

Supported auth modes:

| Mode | Required headers/capability | Identity and access |
| --- | --- | --- |
| Owner tools (existing default) | Exact `Authorization: Bearer <podcast token>` | UID `owner`; list/get/delete/audio can access all jobs |
| Gateway (additional public integration lane) | Exact `X-Studio-Key: <shared secret>` and `X-Mavis-User: <uid>` | Jobs created for that UID; list includes only that UID's jobs; non-owner GET/DELETE/audio cannot access other UIDs |
| Signed audio | GET audio route with valid `exp`, `uid`, `sig` | Audio only, with the signed UID's ownership check; signed UID `owner` may stream any job |

UID must be exactly `owner`, or match the UUID-v4 pattern:

```text
[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}
```

No whitespace trimming or case normalization of UIDs is performed. Use the same canonical account string everywhere. Missing/invalid UID or key returns 401. Duplicate gateway key/user headers or duplicate Bearer authorization headers are rejected. When `X-Studio-Key` is present it selects gateway mode; invalid gateway credentials do not fall back to a valid Bearer header. Bearer mode ignores a supplied end-user header and always creates as `owner`.

Gateway UID `owner` may GET/DELETE/stream any job, but its **list** is only owner-created jobs; Bearer list is the all-jobs operator view. The gateway's own ownership index remains responsible for its public owner-wide listing.

- Bearer token: generated once/reused at `~/.secure/podcast-lounge-token`, file mode 0600.
- Shared key/HMAC secret: nonempty `STUDIO_SHARED_SECRET` environment value takes precedence, including in tests. Otherwise generated once/reused at `~/.secure/podcast-lounge-shared-secret`, file mode corrected to 0600. File-backed secrets require at least 32 ASCII characters and refuse final-path symlinks; generation uses 48 random bytes encoded as URL-safe text. Provision a strong random environment secret too; never use the test vector key in production.
- Neither secret is returned or logged. Headers and query strings are not included in application request logs. Keep any proxy/platform logs from recording signed URLs too.
- Persisted job records contain internal `owner_uid`; older records missing the field are durably migrated to `owner` at initialization. `owner_uid` and underscore-prefixed internals are excluded from all job/list responses.

## Routes and HTTP results

| Method/path | Success response |
| --- | --- |
| GET `/v1/health` | 200 JSON `{"ok":true}` in legacy mode; Modal adds `notebooklm {available,loggedIn,note}` with a five-minute authenticated cache; open, no credentials/UIDs/paths/account email/notebook titles |
| POST `/v1/podcasts` | **202** JSON `{"id":"<uuid-v4>","status":"queued"}` |
| GET `/v1/podcasts` | 200 JSON `{"items":[<job without transcript>,...]}` |
| GET `/v1/podcasts/{id}` | 200 JSON job object below |
| DELETE `/v1/podcasts/{id}` | **204, no body**, no JSON object, no cancellation/deletion status |
| GET `/v1/podcasts/{id}/audio` | 200 MP3 bytes, or 206 for a satisfiable byte range |

There is no create/list envelope named `jobs`, no cursor parameter or pagination token, and no `audioUrl` field. Unknown list query parameters are ignored. List is sorted descending by `(createdAt,id)`; it emits up to 100 entries, but storage retention currently allows at most 50. API documentation/OpenAPI routes are disabled. HEAD is not a supported audio method; the signed capability authorizes GET only.

DELETE waits for the active local generation task to cancel, then removes the database record and job audio/temporary files. A queued ID left in the internal queue is skipped by workers after deletion. Subsequent GET/DELETE/audio returns 404. Successful DELETE=204 is the gateway's confirmation; do not require a terminal-status JSON response.

## Create JSON schema

Required JSON object; extra fields are rejected. Free-form string fields (`title`, `text`, `url`, `gutenbergId`, `query`) are whitespace-trimmed by validation; literal enum fields must match exactly, without surrounding whitespace. Length bounds below are Python Unicode character counts, not bytes; byte-envelope limits independently apply.

| Field | JSON type | Exact bounds/enums/default |
| --- | --- | --- |
| `kind` | string, required | `book`, `chapter`, `article`, `topic` |
| `title` | string, required | 1–300 characters after trimming |
| `text` | string or null, optional | ≤400,000 characters; default null |
| `url` | string or null, optional | ≤2,048 characters; default null; when nonempty, public HTTPS DNS hostname only, no userinfo, no port except 443 |
| `gutenbergId` | **string** or null, optional | default null; a nonempty value must match `[1-9][0-9]{0,6}` (1–9,999,999 as decimal text); JSON integers are not accepted |
| `query` | string or null, optional | ≤300 characters; default null |
| `style` | string, optional | `deep-dive`, `brief`, `debate`, `critique`; default `deep-dive` |
| `length` | string, optional | `short`, `default`, `long`; default `default`; **not a numeric minute/second count** |
| `language` | string, optional | only `en`; default `en` |
| `engine` | string, optional | `auto`, `notebooklm`, `local`; default `auto` |

Kind-specific requirements:

- `chapter`: nonempty trimmed `text`.
- `article`: nonempty public HTTPS `url`.
- `book`: at least one nonempty `text`, `gutenbergId`, or public HTTPS `url`. Retrieval precedence is text → Gutenberg ID → URL.
- `topic`: nonempty trimmed `query`.

The studio does not forbid source fields solely because they are irrelevant to a given kind; any provided URL or Gutenberg ID is still validated. `owner_uid`/`uid` are not accepted create fields; ownership derives only from authentication. No content is fetched by the gateway/studio create validator itself; source fetching happens asynchronously in generation with DNS/public-address checks, redirect revalidation, three redirects maximum, TLS verification, and a 3 MiB fetch cap.

`length` maps to approximate local dialogue word targets: short=1,100, default=2,000, long=3,200. NotebookLM uses its SHORT/DEFAULT/LONG audio-length enums. Neither lane promises an exact wall-clock duration. Modal `auto` tries NotebookLM first and falls back to local on any NotebookLM failure. Explicit `notebooklm` auth/challenge failures produce safe job errors; account caps, quota pause, unsupported sources, or other failures select local with the existing transcript note. Default account allowance is six attempted jobs per UTC day globally, one NotebookLM generation at a time, with a durable 30-minute quota pause. Owner exemption requires `NOTEBOOKLM_OWNER_UNLIMITED=1`. Heavy use of this unofficial API can get the Google account limited. Legacy engine routing remains unchanged.

Example accepted create body:

```json
{"kind":"book","title":"Alice","gutenbergId":"11","style":"deep-dive","length":"short","language":"en","engine":"local"}
```

Gateway alignment differences to address: `gutenbergId` must be forwarded as decimal **text** rather than a JSON integer; only the concrete style/length/language/engine enums above are accepted; POST success is 202. Validation failures are 422, not 400.

## Job and list item fields

A job response contains these exact fields; optional fields are omitted until available, not synthesized as nulls. A list item has the same shape except `transcript` is always omitted.

| Field | JSON type | Semantics |
| --- | --- | --- |
| `id` | string | generated UUID v4; gateway/audio helper ID syntax is `[A-Za-z0-9_-]{1,100}` |
| `status` | string enum | **`queued`, `sourcing`, `writing`, `generating`, `ready`, `failed`** |
| `progress` | integer | percent, 0–100; advisory stage milestones, not elapsed-time percentage |
| `stage` | string | human-readable explanation; not an enum or stable machine status |
| `engine` | string enum | resolved/current `notebooklm` or `local`; never `auto` |
| `title` | string | trimmed requested title |
| `kind` | string enum | create kind |
| `style` | string enum | create style |
| `createdAt` | string | UTC ISO-8601 timestamp with timezone, e.g. `2026-10-12T08:00:00+00:00` |
| `sources` | array of objects | empty until sourced; each has `title:string`, optionally `url:string`; no fabricated live URL |
| `durationSec` | integer, optional | measured audio duration in **seconds**, rounded to nearest whole second; present for ready audio |
| `transcript` | string, optional | local engine dialogue; job GET only; NotebookLM output normally omits it |
| `error` | string, optional | sanitized failure description; restart interruption uses literal `restarted` |

Terminal statuses are **only `ready` and `failed`**. All four others are nonterminal and keep an active gateway slot occupied. Status polling can skip intermediate stages. NotebookLM may skip writing; auto fallback can move generating → writing → generating. Do not translate or require `pending`, `running`, `processing`, `completed`, `cancelled`, `canceled`, or `deleted`. Deletion removes the job rather than emitting another status. `length`, `language`, the submitted source text/query, provider metadata, credentials, filesystem paths, and `owner_uid` are not job response fields.

## Errors

Unless noted otherwise, application errors are JSON objects with one field, `{"detail":"human-readable string"}`. They contain no validation input/source echo or credentials. No gateway-style `code`, `message`, or `success` fields are added.

| HTTP status | Conditions/shape |
| --- | --- |
| 401 | absent/invalid/duplicate credentials or invalid signed capability; `{"detail":"Valid studio authorization required"}`, `WWW-Authenticate: Bearer` |
| 404 | nonexistent job or another UID's job; both use `{"detail":"Podcast not found"}` for job routes; other missing paths use FastAPI `{"detail":"Not Found"}` |
| 409 | owned job audio is not ready or its audio file is missing; `{"detail":"Podcast audio is not ready"}` or `{"detail":"Podcast audio is missing"}` |
| 413 | byte envelope limit exceeded; JSON `detail` string |
| 422 | malformed JSON, schema/type/enum/source validation failure; JSON `detail` is a semicolon-joined human-readable string; unknown field names are reported as `body`, not reflected |
| 429 | direct-IP, per-UID hourly, per-UID queue/capacity, global hourly create, or global stored-job capacity limit; JSON `detail` string; rate/UID queue limits include `Retry-After` seconds (60 or 3600); full 50-job storage and exhausted client-map errors have no retry header |
| 503 | no explicit application 503 branch; an unavailable process/proxy is outside this JSON contract |
| 400 / 416 | malformed/unsatisfiable Range: Starlette text response, **not JSON**; 416 includes `Content-Range: bytes */<full-size>` |
| 405 | authenticated unsupported method on a known path, FastAPI JSON `detail` |
| 500 | unexpected infrastructure failure, not a promised JSON API error; gateway must fail closed |

Authorization/ownership is checked before exposing audio readiness. Signed capability expiry/failure is 401 even when a Bearer header is also present; a valid capability for the wrong UID's job is 404. A supplied audio signing parameter selects signed mode, so malformed/partial/duplicate signing parameters never fall back to header auth.

## Abuse and retention limits

- Create body: **1,000,000 bytes (decimal 1 MB)** maximum, including whitespace/JSON envelope. If kind is chapter or a non-null `text` field is supplied, the entire JSON body is instead capped at **450,000 bytes (decimal 450 KB)**. Actual streamed bytes are counted; Content-Length is not trusted. UTF-8 multibyte characters and JSON escape sequences consume bytes. A valid ≤400,000-character chapter can still exceed the byte cap if encoded with many escapes/multibyte characters.
- Other requests retain the existing 3 MiB body ceiling (3,145,728 bytes).
- One process, **two global generation workers**: consequently no UID can have more than two concurrently executing jobs.
- Every gateway UID (including gateway `owner`): maximum **three persisted queued jobs**, maximum **five nonterminal jobs** (two executing plus three queued). Three queued jobs already fill the UID queue even if that UID currently has fewer than two workers. Admission is serialized and rechecked after awaited engine selection.
- Subscriber UID: **six accepted creates per rolling 3,600 seconds**, independent of deletion, generation success/failure, or restart. UID `owner` is exempt from this per-UID hourly counter.
- Bearer owner tools retain the legacy queue allowance (no per-UID queue restriction); they still obey two global workers, retention/storage, global create, and direct-IP limits.
- **Global create limit: 60 accepted creates per rolling 3,600 seconds**, including owner, across both header auth modes. Configurable via `create_app(global_create_limit=...)` for tests/embedding; production default is 60. Per-UID/global accepted-create events are committed with the new job in SQLite; events survive restart and job deletion. Full SHA-256 UID digests identify rate counters; only the first eight hex characters are emitted as log `uidHash`. Invalid/auth-rejected/cap-rejected requests do not charge a create event.
- Existing direct-socket-IP cap: **120 requests per rolling 60 seconds** (all requests, including health, invalid auth, audio and configured preflight). In-memory client map capped at 4,096 entries. A proxy shares its direct-IP budget; this is not a forwarded-client-IP limiter.
- At most **50 stored jobs globally**, 30-day retention, hourly cleanup/startup/pre-create pruning; terminal oldest jobs can be evicted to admit new work. A full all-nonterminal store rejects creates with 429.
- Restart marks interrupted/nonterminal jobs `failed` with `error:"restarted"`; ready/failed records and subscriber ownership survive. No automatic rerun, purchase, provider change, deployment, or idempotent-create guarantee is provided.

## Direct audio signature — exact rules

```text
GET <studio-base>/v1/podcasts/<id>/audio?exp=<exp>&uid=<uid>&sig=<sig>
payload = UTF-8 bytes of the exact string `${id}.${exp}.${uid}`
sig = lowercase hexadecimal HMAC-SHA256(UTF-8 shared secret, payload)
```

- ID must match `[A-Za-z0-9_-]{1,100}`; UID must match the authentication UID rules above.
- `exp` must match ASCII `[0-9]{10}`: ten decimal digits of Unix **SECONDS**, not milliseconds, signed decimal, exponent notation, or a float.
- Compute gateway expiry as `floor(nowMilliseconds/1000) + 600`. Studio requires `exp > floor(nowMilliseconds/1000)` and `exp <= floor(nowMilliseconds/1000)+600`.
- `sig` must match `[0-9a-f]{64}`. Studio uses constant-time digest comparison after format/time validation.
- No newline, JSON, whitespace normalization, or URL encoding inside the signed payload. Query decoding happens before validation/signature computation; sign the unencoded values.
- Exactly one each of `exp`, `uid`, `sig`; duplicates (including identical duplicates and encoded parameter names) are rejected with 401. Missing members of a supplied signature are rejected. Other query parameters do not contribute to the payload.
- Valid signature bypasses header auth **only for GET audio**, never metadata/list/create/delete/health alternatives. Verify the job belongs to signed UID after signature validation; `uid=owner` is the deliberate all-jobs exception.
- Direct streaming preserves Range/206, Content-Length, Accept-Ranges: bytes, and **Cache-Control: private, no-store**. Full/single-range responses are audio/mpeg; multiple ranges use multipart/byteranges with an exact Content-Length. Full/range responses, auth/readiness failures, and Range errors have no-store. A stream authorized before expiry may finish after expiry; a subsequent request must be validated afresh.
- Deletion/retention removes access immediately through 404. Already-issued capabilities otherwise remain usable until expiration; do not claim immediate subscription revocation invalidates an issued URL. Keep gateway/studio clocks synchronized.
- Treat full signed URLs as temporary credentials and never log their query strings. Netlify should return URLs without fetching or proxying MP3 bytes.

### Deterministic non-production test vector

```text
secret: vector-secret
id: job-vector
exp: 1800000600
uid: owner
payload: job-vector.1800000600.owner
sig: 92eff03c6dda51ba238460e571ec6c47632852c48812f32afbc67167c3dde4ce
verification nowMilliseconds: 1800000000000
```

The suite independently computes this with stdlib HMAC, verifies the literal published digest, and streams a ranged audio response using the literal vector. It rejects tampering with ID/exp/UID/secret/signature, uppercase hex, expiration at/before now, >600-second expiry, wrong ownership, partial signatures, and duplicate parameters.

## Optional browser audio CORS

Default: no origins configured and no cross-origin CORS grant. Operator may set `STUDIO_AUDIO_ORIGINS` to a comma-separated list of the **actual approved exact HTTP(S) origins**, without trailing slash, path, wildcard, credentials, query or fragment. Do not infer app origins or change its CSP here.

Only the audio route receives CORS handling. Allowed origin audio GET responses include the matching `Access-Control-Allow-Origin` and `Vary: Origin`; exposed headers are Accept-Ranges, Content-Length, Content-Range. No cookie credentials are allowed. Configured audio OPTIONS preflights are public (no bytes, no job lookup), allow only GET and the Range request header plus CORS-standard safelisted headers, cache preflight for 600 seconds, and return 200 text `OK` or 400 text `Disallowed CORS ...`. They do not authorize a subsequent GET; a valid signature or header auth remains required. Other studio routes do not gain public preflight or CORS access. With no configured origins, OPTIONS requires normal header auth and is not a supported route method.

## Verification and operator handoff

Offline full suite (test secret is injected; no real shared-key file is touched):

```bash
TMPDIR="$PWD/data" .venv/bin/python -m pytest -q --basetemp=data/pytest-gateway-operator-tmp
.venv/bin/pip check
systemd-analyze verify deploy/podcast-lounge.service
```

For a live integration check, Hermes/operator must provision the same strong key into the gateway and studio without printing it, activate/restart the studio, configure the approved Funnel `/studio` path and exact audio CORS origins, and align the gateway enums/fields/202/204 handling above. Then verify subscriber A create/list/get/audio, subscriber B 404 isolation, owner access, a signed Range request with no Authorization, duplicate/tampered/expired URL failures, confirmed DELETE=204 and post-delete 404. Verify app CSP/browser playback independently. **No unit installation, restart, Funnel configuration, gateway/Android changes, live provider generation, or live gateway round trip was performed for this change.**
