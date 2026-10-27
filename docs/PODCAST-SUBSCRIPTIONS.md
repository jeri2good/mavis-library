# Podcast Studio subscriptions (backend)

The server owns generation. Both manual `/api/podcast/create` and subscriptions call
`createPodcast` in `netlify/lib/podcast.mjs`: the same validation, CAS quota
reservation, studio POST (`X-Mavis-User`), acceptance settlement and job index.
No account token or owner credential is forwarded to feeds or the studio.

## API

All routes use `requireAccess(req, { feature: 'podcast' })`, the existing
same-origin policy and Android CORS allowlist. Owner access maps to `owner`;
account access maps to the account UUID, including accounts with unlimited access
to other features. Podcast limits apply to everyone.

| Method and path | Result |
| --- | --- |
| GET /api/podsub | Stored document (or disabled defaults), `today: {used,limit,date}`, `lastRun` or null |
| PUT /api/podsub | Full validated document with computed `nextRunAt` and `updatedAt` |
| POST /api/podsub/run-now | Run record; persistent CAS rate limit of 3/hour per user |
| GET /api/podsub/runs | Array of the latest 30 run records, newest first |
| GET /api/podsub/today | Array of today's indexed jobs with refreshed studio status, newest first |
| PUT /api/podsub/limit | Owner only; `{uid,limit}` where limit is an integer 1..50 |

PUT accepts only `version:1`, `sites`, `schedule`, `engine`, `rank`, `keepDays`
(default 7), and `notify`. Do not send GET's server fields back on PUT.
Sites are limited to 12. Site IDs are unique ASCII letters, digits, underscores
and hyphens, maximum 100 characters. URLs must be public HTTP(S), without userinfo
or nonstandard ports. Mode is `off` or `top`; count is 1..5, required for `top`.
Delivery is `briefing` or `separate`; priority is a safe integer (lower first).
Timezone is an IANA zone, days are unique lowercase weekday names, and readyBy
uses HH:MM. An enabled schedule needs at least one day. Engines are `auto` and
`notebooklm`. The backend forwards this policy unchanged: no backend fallback
changes `notebooklm` into another engine. Rank `important` currently falls back
to newest, as allowed by the contract.

## Scheduling and storage

`podsub-tick` runs `*/5 * * * *`. It selects at most ten distinct due users in
oldest-index order, rechecks each document and current account/subscription
access, and uses a ten-minute CAS lease shared with run-now and document PUT.
A released lock is a CAS tombstone, preventing an expired holder from deleting
its successor's lease. Stale due keys are ignored and removed.

Ready-by weekdays refer to the local ready-by date. Start is 60 elapsed minutes
before the resolved ready-by instant. DST folds use the first occurrence;
nonexistent spring-forward times shift forward by the gap. The next start is
strictly in the future. Thus readyBy 00:30 starts the previous calendar day.
Quota usage always belongs to the local date of creation, including such starts.
Without a saved timezone, manual jobs use UTC.

Documents, due indexes, seen IDs (last 300 per site), runs (last 30), limits and
locks use the contract's prefixes in `subStore()`. Additional `podsub-rate/<uid>`
records hold run-now timestamps. Daily usage remains `use/<uid>/<YYYYMMDD>`;
the date is now local for podcasts. Default limit is 1, owner default is 30,
and a valid `podlimit/<uid>` overrides either. A queued 202 acceptance consumes
quota even if its response body is malformed, preserving existing semantics.
Rejected requests release quota. Existing active-job rows remain maintained for
job lifecycle compatibility, but the old two-active-job gate does not prevent
users with higher daily limits from requesting their scheduled batch.

Top N means the newest N feed entries, then unseen filtering. Repeated runs do
not backfill older posts. Successful episodes mark only their included post IDs
seen. All briefing sites share one episode, ordered at the highest-priority
contributing site's position. Separate posts each consume one slot. Limit
exhaustion records every affected site as `skipped_limit`. Empty feeds use no
quota and record `skipped_empty`; unavailable feeds record `feed_failed`, not
an empty-day success. Other skip reasons are `create_failed` and `skipped_budget`.

Feeds use plain fetch, manual redirects, validated public DNS pinned to the
connection, a shared six-second DNS/redirect/body timeout, and a 2 MiB decoded
body cap. RSS/Atom parsing rejects DTD/entity declarations. Feed bodies are
bounded before building the studio text source; chapter/text input combines
briefings without asking the studio to refetch arbitrary URLs.

The tick uses a 25-second work deadline inside Netlify's 30-second scheduled
function budget. Per-user feeds run in parallel; users run concurrently; studio
POSTs are bounded and never wait for audio. Episodes that do not fit the budget
are recorded as skipped_budget and remain unseen. Blobs service latency itself
is controlled by the SDK/platform. A process death after studio acceptance but
before persisting seen IDs cannot be made exactly-once without studio-side
idempotency; the lease and shared quota prevent concurrent duplicate runs and
quota overrun, but do not claim crash-atomic dispatch across services.

## Environment and operation

Existing: `STUDIO_URL` (HTTPS), `STUDIO_SHARED_SECRET`, `AUTH_SECRET`, owner
access configuration (`MAVIS_ACCESS_CODE`, optionally `OWNER_EMAILS`), and Netlify
Blobs runtime credentials. No added dependencies or Netlify settings changes.
`SUB_CAP_PODCAST` no longer controls creation; use the owner limit API.
Optional: `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID_OWNER` or
`TELEGRAM_CHAT_ID_<account UUID>`. Missing notification configuration silently
skips sending. The message is sent once after a run that created episodes;
notification failure does not fail generation.

`keepDays` is saved for app download retention. Today only includes the current
local calendar day, so it does not expose older scheduled episodes. The app
performs download cleanup and migration; this backend does not edit Android or
web UI code. Run-now works even when the recurring schedule is disabled.

Verification: `env -u TYPESAFE_API_KEY -u FISH_AUDIO_API_KEY npm run test:functions`.
All studio/feed tests are mocked and use a temporary local Blobs server. No live
accounts, owner decisions, podcast dispatches, deploys or settings changes are
part of local verification. Hermes owns deployment and live acceptance.
