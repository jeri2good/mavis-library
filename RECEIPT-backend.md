# Podcast Studio subscriptions — backend receipt

Branch: `podsub-api`. Backend implementation only. No deploy, push, Netlify settings
change, live account creation, live owner decision, or live podcast dispatch.
Android and web UI source were not touched.

## Implementation

- Added `/api/podsub`, `/run-now`, `/runs`, `/today`, and owner-only `/limit` with
  existing access checks, same-origin policy, and Android CORS.
- Added subscription documents, due indexes, 300-ID seen histories, 30-run
  histories, CAS leases, and persistent three-per-hour run-now limiting.
- Extracted shared manual/scheduled studio creation and job indexing. Per-user
  local-day quota defaults to 1, owner to 30; owner-set overrides are 1..50.
  Subscription usage responses now report the same local-day counter and cap.
- Added five-minute tick, at most ten oldest due users, account-access rechecks,
  timezone/DST-aware scheduling, priority filling, separate/briefing grouping,
  engine pass-through, empty-day handling, and optional Telegram notification.
- Added dependency-free RSS/Atom parsing and bounded fetch with DNS-pinned public
  addresses, redirect validation, six-second timeout, and 2 MiB decoded cap.
- Preserved every selected post in the bounded briefing source. No feed bodies,
  account tokens, cookies, emails, or secrets are logged.

## Changed files

- `netlify/functions/podcast.mjs` — manual endpoint calls shared creation.
- `netlify/functions/podsub.mjs` — authenticated subscription HTTP API.
- `netlify/functions/podsub-tick.mjs` — scheduled function.
- `netlify/lib/podcast.mjs` — shared creation and per-user local-day quotas.
- `netlify/lib/subs.mjs` — matching podcast usage reporting/default cap.
- `netlify/lib/podsub.mjs` — validation, storage, leases, runs and scheduling.
- `netlify/lib/podsub-time.mjs` — local dates and DST-aware next-run computation.
- `netlify/lib/podsub-feed.mjs` — safe feed fetch, pinned transport and parser.
- `tests/functions/podsub.test.mjs` — focused mocked subscription checks.
- `tests/functions/subs.test.mjs` — existing checks updated for new quota policy.
- `tests/functions/functions.test.mjs` — includes podsub tests in existing runner.
- `tests/functions/cors.test.mjs` — recognizes scheduled functions and route arrays.
- `docs/PODCAST-SUBSCRIPTIONS.md` — API/behavior/operations handoff.
- `PROGRESS.md`, `RECEIPT.md` — milestone and verification records.

No dependencies were added. `package.json`, lockfile and `netlify.toml` are unchanged.
`npm ci --ignore-scripts --no-audit --no-fund` installed the existing locked
packages because this worktree initially lacked them.

## Verification

First full green run: **168 checks**, committed immediately as `1d1b34a`.
Expanded focused suite: **28 podcast subscription checks passed**.
Final full runner: pending completion; results will be recorded before final commit.

Command: `env -u TYPESAFE_API_KEY -u FISH_AUDIO_API_KEY npm run test:functions`.
The shell exports those two provider variables; the repository's documented test
command unsets them. The sandbox initially denied binding the local fixture
server; the suite then ran with approved permission for the local Blobs server.
All provider, studio, feed and Telegram behavior was mocked. The pinned transport
check uses the real built-in fetch with a fake HTTPS socket/request implementation.

## Environment

Existing production configuration:

- `STUDIO_URL` — HTTPS studio base URL.
- `STUDIO_SHARED_SECRET` — private studio authentication/signing secret.
- `AUTH_SECRET` — account session signing.
- `MAVIS_ACCESS_CODE` — existing owner credential.
- `OWNER_EMAILS` — optional existing account access exemption; only `access.owner`
  maps to uid `owner`, per the contract.
- Netlify Blobs runtime context/credentials, supplied by Netlify.

Optional notifications: `TELEGRAM_BOT_TOKEN` plus `TELEGRAM_CHAT_ID_OWNER` or
`TELEGRAM_CHAT_ID_<account UUID>`. Missing configuration silently skips notification.
No new required environment variable. `SUB_CAP_PODCAST` is superseded for podcast
creation by `podlimit/<uid>`. `MAVIS_BLOBS_URL` and `MAVIS_BLOBS_TOKEN` are local-test
configuration, not production requirements.

## Limits and handoff

- Hermes must deploy and perform live acceptance. Scheduled functions run on the
  published deploy; this worktree has not been deployed or pushed.
- Rank `important` falls back to newest because no relevance ranker is connected,
  as the contract allows. `keepDays` is persisted; Android owns download cleanup.
- A 25-second work deadline leaves room inside the scheduled function's 30-second
  budget. If the studio is slow, remaining episodes record `skipped_budget` and
  stay unseen. This does not guarantee audio completion by readyBy; it requests
  jobs 60 minutes beforehand. Netlify Blobs latency remains platform-controlled.
- Leases prevent concurrent runs. No cross-service transaction can atomically
  commit studio acceptance and seen IDs: a process death in that interval can
  leave an accepted job without seen tracking. Shared quota still bounds
  creation; studio-side idempotency would be needed for crash-exactly-once delivery.
- Android migration, queue/playback/downloads and removal of phone generation are
  assigned to the other builder and deliberately outside this backend change.

Rollback: revert the podsub-api implementation commits. Existing stored subscription
blobs may remain inert after reverting the scheduled function; no production
storage was changed during this work.
