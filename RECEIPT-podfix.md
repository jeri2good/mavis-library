# Podcast fixes receipt

Work completed locally in `wt-podsub-merge`. No push, deployment, live studio jobs, Android signing changes, or access to `~/.secure`.

## Changes

- `netlify/lib/podcast.mjs`: manual create defaults to `notebooklm`; SHA-256 source fingerprints and a per-user Blobs CAS lease coalesce duplicate requests for 60 seconds before quota reservation/dispatch. Text-less requests also include their URL/query/Gutenberg source in the fingerprint. Added deletion tombstones, last-known summaries, and FIFO queue estimates across indexed active jobs (queued/sourcing/writing/generating). Queue positions are 1-based and ETA is position × 5 minutes; terminal jobs have null estimates. Studio list failure falls back to stored state.
- `netlify/functions/podcast.mjs`: duplicate create returns HTTP 200 with the existing ID; new admission remains 201. Missing job rows and upstream job 404s return a successful `deleted` status. Foreign-owned IDs remain inaccessible. Deletes tolerate upstream 404, remove the visible index, and retain ownership tombstones. Subsequent deleted-job polls do not contact studio. Library lists hide deleted jobs and preserve last-known rows on studio errors; job/list responses include queue estimates.
- `netlify/lib/podsub.mjs`: Today persistently removes upstream-missing rows, saves last-known summaries, and adds queue estimates with outage fallback. Explicit subscription engine settings remain unchanged.
- `native-android/app/src/main/java/com/mavislibrary/app/data/podcast/PodcastModels.kt`: only the manual request engine default changes from `auto` to `notebooklm`.
- `tests/functions/subs.test.mjs`: fake HTTP coverage for duplicate/concurrent taps, quota preservation, terminal deletion, missing rows, ownership, job queue estimates and library outage fallback. Existing quota tests now use distinct requests when they intend distinct jobs.
- `tests/functions/podsub.test.mjs`: 60-second boundary, user/content isolation, NotebookLM default and explicit auto selection, Today deletion persistence, queue ordering/ETA, and last-known outage coverage.

## Verification

`env -u TYPESAFE_API_KEY -u FISH_AUDIO_API_KEY npm run test:functions` passed (exit 0). Final log: `/tmp/podfix-tests-final.log`. Reported totals: 53 endpoint, 54 subscription/podcast, 33 podsub, 14 account/sync, 12 group, 7 CORS, 6 report, 8 word builder checks (187 total).

All three changed server modules and both test files pass `node --check`; `git diff --check` passes. Tests use fake studio HTTP and a temporary local Netlify Blobs server. The sandbox initially refused that server's listening socket; the approved rerun permits the local fixture server. Optional inherited AI provider keys are removed for the runner, as in prior repository verification.

No Android build or live client acceptance was performed. Server responses expose terminal deletion; client handling of that status is outside this server-side verification. Queue ETA is an estimate from indexed jobs and the available studio list, not a guaranteed completion time.

## Rollback

Pre-edit copies are under `/tmp/podfix-backup/` with repository-relative paths. Changes are uncommitted; restore those six files to undo this work, and remove this receipt if desired.
