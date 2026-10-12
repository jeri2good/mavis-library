# Podcast Lounge delivery receipt

Date: 2026-10-12. Workspace: `/home/hawthornes/podcast-lounge`.

## Built and verified

- Reviewed the unverified WIP checkpoint critically; implemented the missing authenticated FastAPI service, durable SQLite job lifecycle, recovery, bounded queue, retention, deployment unit, tests and operator instructions.
- All requested `/v1` routes and response shapes; human validation errors; health open, every other HTTP path bearer-protected; constant-time token comparison; per-direct-IP rate limiting; bounded request bodies.
- Token generated/reused without printing it at `~/.secure/podcast-lounge-token`. Actual permissions verified: **0600**. Data directory: **0700**. Credentials and generated job data are not tracked by git.
- Public-HTTPS-only article/book fetching with actual-connection DNS filtering, redirect revalidation, no proxies/cookies, feed-reader UA, TLS verification, 15-second fetch timeout and 3 MiB cap. Identity encoding is requested; unexpectedly compressed responses are rejected to avoid decompression bombs.
- Stdlib main-text extraction; Gutenberg license stripping, chapter identification and bounded whole-book sampling; public mirror fallback; keyless DDGS source gathering and DuckDuckGo HTML fallback; explicit spoken no-live-source disclosure when no pages can be fetched.
- Local two-host grounded-script prompts, chunked map/reduce, strict JSON validation and one repair retry, distinct neural voices, per-turn synthesis/pauses, ffmpeg loudness normalization, ID3 title/artist, real MP3 duration probing.
- NotebookLM integration uses installed notebooklm-py 0.8.4's async client/context-manager API, text ingestion, format/length enums, a 20-minute generation timeout, quota retry/backoff, correct `task_id` artifact selection, real MP3 conversion and job-notebook cleanup. Fake-client coverage includes auth, rate limits, timeout, cleanup and every style/length pair.
- `auto` selects verified NotebookLM login or local. Runtime NotebookLM auth/quota failures fall back only for auto jobs, with a spoken/transcript local-engine disclosure. Explicit NotebookLM requests fail honestly with `NotebookLM needs a fresh sign-in` when unauthenticated.
- Exactly two internal generation workers, maximum 50 stored jobs, 30-day retention, active-job cancellation, single-process data lock and restart recovery (`error: restarted`). Terminal jobs drop the original request/source payload. Private engine metadata stays out of API responses.

## Test results — read from actual pytest output

- First green run: **137 passed, 1 warning in 2.91s**, `data/pytest-first-green.log`.
- Final run: **150 passed, 1 warning in 4.72s**, `data/pytest-final.log`.
- The warning comes from the installed Starlette TestClient deprecating its httpx integration; no test failures were hidden or ignored.
- Coverage: URL-policy rejection table (private/loopback/link-local/CGNAT/IPv6/IP encodings/userinfo), mixed private DNS answers, mapped/NAT64/6to4 private-address encodings and actual resolver behavior, redirect policy and cap, size/encoding limits, article/Gutenberg fixtures, mirror fallback, book sampling, source-less topics, script JSON/repair/map-reduce, real ffmpeg conversion, auth/validation, full and ranged audio, fallback, concurrency, retention/cap, active deletion, restart recovery, provider fallback, secret-safe errors and process locking.
- `.venv/bin/pip check`: **No broken requirements found**.
- `git diff --check`: passed.
- `systemd-analyze verify deploy/podcast-lounge.service`: passed.

## Real end-to-end MP3 results

Both were submitted through HTTP on **127.0.0.1:8141**, went through real source gathering, real LLM dialogue, real two-voice speech synthesis, real ffmpeg normalization/metadata, and reached `ready`. `sample_run.py` independently received **HTTP 206 with 100 bytes** for each job's first audio range. Independent ffprobe output supplied the exact durations/sizes below.

| Sample | Preserved path | ffprobe duration | File size |
| --- | --- | ---: | ---: |
| Alice, Gutenberg ID 11, short/deep-dive | `./data/sample-alice.mp3` | **474.279184 s** (7:54) | **7,589,018 bytes** |
| How vaccines train the immune system, short/deep-dive | `./data/sample-vaccines.mp3` | **443.559184 s** (7:24) | **7,097,488 bytes** |

- Voices actually used for both: Alex/HOST_A **en-US-AndrewNeural**; Sam/HOST_B **en-US-AriaNeural**. Both were confirmed in the live voice list.
- LLM actually used for both: **Hermes local gateway (`mavis-gravity`)**, `http://127.0.0.1:8000/v1`. The job-specific `local_engine` log entries confirm this. The backend does not claim to know which upstream model the preexisting gateway selected internally.
- Alice job: `be4fa248-f8f3-4673-8c41-dbf9a11f7497`. Fetched source: `https://gutenberg.pglaf.org/1/11/11-0.txt`.
- Vaccines job: `aa25baca-ef8d-4bd2-b016-4ce6b0146747`. Three actual fetched sources:
  1. CDC: `https://www.cdc.gov/vaccines/basics/explaining-how-vaccines-work.html`
  2. University of Florida: `https://distance.physiology.med.ufl.edu/about/articles/pharmacology/how-do-vaccines-work-a-simple-guide-to-the-immune-system-and-immunization/`
  3. Science Times: `https://www.sciencetimes.com/articles/60695/20251105/how-vaccines-work-exploring-immune-system-breakthroughs-vaccine-science.htm`
- SHA-256 Alice: `1a798a8bd44b4d628f12bbd69f58bb69b4c9c96a0bf47b138af9b8d61077c6b5`.
- SHA-256 vaccines: `a54ab35c7e154ad1d4c0068307800eb55c314bc2c7051e422ca809c7dcfed758`.
- ffprobe verified both ID3 titles and artist **Mavis Podcast Lounge**.
- Independent loudness measurements of the final files: Alice **-16.83 LUFS / -1.82 dBTP**, vaccines **-16.79 LUFS / -1.85 dBTP**. Rendering used `loudnorm=I=-16:TP=-1.5:LRA=11`; measurements are reported as measured, not rounded to the requested target.
- Evidence retained locally: `data/sample-results.json`, `data/sample-run.log`, `data/server-test.log`, `data/sample-*-transcript.txt`, `data/sample-*-loudness.log`. These data artifacts are intentionally git-ignored; the preserved MP3s are present on disk, not committed as binaries.

## Necessary, evidence-backed adjustments

### TypeSafe is not a text-generation endpoint

The configured variable name was confirmed as `TYPESAFE_API_KEY` without revealing its value. A chat payload to the stated System One endpoint returned **400**, and its assumed `/chat/completions` child returned **404**. TypeSafe's own documentation explicitly says System One models return typed decisions, not generated replies or explanations: `https://docs.typesafe.ai/concepts/system-one`.

Therefore the dialogue path uses the brief's existing-Hermes-gateway fallback. The default does not repeatedly send unsupported chat payloads to TypeSafe. A future genuinely compatible endpoint can be explicitly supplied with `TYPESAFE_CHAT_URL`; rejection still falls back. No other service/provider configuration was changed.

### Edge TTS legacy endpoint broke during this task's date

Initial full Alice synthesis and an isolated one-line probe failed with **WebSocket HTTP 403**, despite a working voice list and synchronized system clock. Upstream issue `https://github.com/rany2/edge-tts/issues/490` reports the same October 2026 failure. The latest released package remains 7.2.8.

Reviewed upstream PR `https://github.com/rany2/edge-tts/pull/491`, head `8a5844c3a1d33e4e8f2f3e14535542c0d75d4248`, and adapted only its small endpoint migration: a guarded in-process override of the legacy synthesis host to Microsoft's current `api.msedgeservices.com` host. No package files were changed, no unreviewed fork/new dependency was installed, no personal key was added, and TLS verification was not disabled. The PR is **open/unmerged**, so this is a documented compatibility override rather than a claimed official release.

A real modern-endpoint one-line speech probe succeeded, then both requested full podcasts succeeded. Tests cover the override and preservation of future non-legacy endpoints.

### Gutenberg main-site availability

Initial Alice sourcing failed against the main site with 503/504 responses. An initially investigated mirror also had a hostname/certificate mismatch and was not accepted; TLS verification stayed on. The final implementation uses validated public mirror fallbacks, and the successful real Alice job used `gutenberg.pglaf.org`.

## Dependencies

**No packages were installed or upgraded during this resumed session.** The first audit already found all required packages in the provided/WIP venv. `requirements.txt` pins the direct dependencies actually used:

| Dependency | Version | Role |
| --- | --- | --- |
| notebooklm-py[cookies] | 0.8.4 | NotebookLM async integration and interactive login CLI |
| fastapi | 0.143.0 | HTTP service and validation integration |
| uvicorn | 0.54.0 | ASGI server |
| edge-tts | 7.2.8 | Neural speech synthesis |
| aiohttp | 3.14.5 | SSRF-safe connector/resolver and bounded fetching |
| httpx | 0.28.1 | Gateway client, HTTP tests and real sample runner |
| python-dotenv | 1.2.4 | Read configured TypeSafe key without outputting it |
| ddgs | 9.16.0 | Keyless public search |
| pytest | 9.1.1 | Unit/integration tests |

The additional direct requirements beyond Hermes' four explicitly named setup packages are aiohttp, httpx, python-dotenv, ddgs and pytest; they were already installed when the resumed audit began. No readability/trafilatura dependency was needed: extraction is stdlib. Existing `/usr/bin/ffmpeg` and `/usr/bin/ffprobe` were used; nothing was installed with sudo.

## Deployment verification and boundaries

- Wrote `deploy/podcast-lounge.service`: SYSTEM unit, User/Group hawthornes, production bind 0.0.0.0:8140, Restart=always, optional secure EnvironmentFile, NoNewPrivileges, PrivateTmp, ProtectSystem=strict, ProtectHome=read-only, writable data/secure/NotebookLM paths, empty capabilities and restricted address families.
- Static unit validation passed. Offline systemd security inspection reported **4.7 / OK**; this is a systemd heuristic, not a complete security audit.
- Started the actual final app in a **transient user unit** as hawthornes with matching sandbox properties, on **127.0.0.1:8141**. Verified it was active/running, health 200 with `loggedIn:false`, unauthenticated list 401, authenticated list 200, and the preserved Alice MP3 range 206 after process restart. `systemctl show` confirmed the active sandbox properties and empty capability set.
- A separate transient unit using the same sandbox successfully performed **real two-voice Edge TTS and ffmpeg**: `data/sandbox-voice-probe.mp3`, **150,154 bytes / 9 seconds** (rounded ffprobe duration). This verifies network/TTS/subprocess/data-write behavior inside the hardened runtime, not just a successful health handler.
- Verification services were stopped after checks so the data-directory lock does not block Hermes' production installation. No production port 8140 service was started by this agent.
- **SYSTEM installation and start on 8140 remain Hermes/operator steps.** No sudo was run, no unit was installed in `/etc/systemd/system`, and transient-user-unit verification is not misrepresented as a production SYSTEM-unit installation.

## What is not verified

- **NotebookLM live generation/download/cleanup with J's Google account:** no login exists. Health honestly reports `loggedIn:false`. All NotebookLM tests use fake clients; J's one-time login and an explicit NotebookLM live job are still required.
- Android app integration/playback/UI: Android was not touched. HTTP contracts, MP3 files and ranges were verified server-side, not on the phone.
- Human listening quality and independent fact-checking of every spoken claim: neural audio was generated and analyzed, not manually auditioned or certified. Grounding is prompt-based.
- Real long/default-duration podcasts and live alternate styles: word targets/style prompts and NotebookLM enum mappings are tested, but the two full live jobs used short/deep-dive.
- Future Microsoft endpoint stability or a merged/released Edge TTS fix: the documented compatibility shim is currently working, not a permanence guarantee.
- Upstream gateway billing/provider identity: existing configuration was left unchanged; no guarantee of permanently free upstream inference is fabricated.

## Git and handoff

- WIP preserved in history; first verified checkpoint committed as **01cc2db** after the first green run.
- Final delivery commit follows this receipt and final checks; its hash is reported in the handoff.
- Git had no author identity configured. Commits used explicit process-environment Devin author/committer identity; git configuration was not changed. Nothing was pushed.
- Installation, secure token handling, sign-in procedure, smoke checks and limits are in `README.md`; project-specific verification rules are in `AGENTS.md`.
