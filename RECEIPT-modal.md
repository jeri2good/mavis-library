# Modal Podcast Studio receipt

Date: 2026-10-12. Workspace: `jermaine-hawthorne`. Branch: `modal`.

## Bottom line

Deployed and live-verified app: **mavis-podcast-studio**.

Public base: **https://jermaine-hawthorne--mavis-podcast-studio-web.modal.run**

The web endpoint is CPU-only, scales to zero, and never runs inference. Source gathering and audio finishing run on separate Modal CPU functions; script writing and Kokoro speech run on one shared L4. The existing `local` engine label now means this Modal GPU pipeline, not the iMac.

Both required original jobs are ready, signed downloads have been independently inspected with ffprobe, and a default-length cost sample plus a genuinely warm GPU chapter job passed. NotebookLM remains disabled. No Android, Netlify, iMac-service, or pre-existing Modal resources were modified. Gateway cutover and iMac-service shutdown are intentionally left to Hermes; this receipt does not claim a live Netlify round trip.

## Architecture and contract

- `modal_app.py`: app, ASGI endpoint, serialized state class, CPU source/finishing functions, bounded asynchronous pipeline, shared GPU class, hourly maintenance.
- `service.py`: the existing FastAPI routes/security middleware are reused via an async manager adapter. Statuses, response filtering, ownership, schema, byte limits, Bearer/gateway selection, HMAC rules, audio CORS policy, POST 202, and DELETE 204 are preserved.
- `cloud_state.py`: SQLite metadata, durable admission counters, pending jobs, atomic claims, content cache, and recovery. `cloud_budget.py`: cumulative GPU-call watchdog.
- `sources.py`: fetching policy is unchanged. The only change is an explicit import of `AbstractResolver`, fixing a cold-container import-order failure without weakening SSRF checks.
- `engines.py`: unchanged; its Edge renderer, ffmpeg processing, script validation, and retained NotebookLM implementation remain available. Modal's default LLM never calls the Hermes gateway or Devin.

### Why storage is safe within this deployment

SQLite is **not** shared between independent writers. Only `State`, configured with `max_containers=1` and `max_inputs=1`, opens the database. Every operation reloads its dedicated metadata Volume, opens a short-lived SQLite connection with DELETE journaling and FULL synchronization, closes the connection, and explicitly commits the Volume before returning. Admission events and the job insert are one SQLite transaction.

Other containers never open that database. They call the state actor for metadata operations. Audio writers write unique job-ID MP3s to a separate Volume and commit before publishing `ready`. The web reader reloads audio under a local lock, copies it to container-local scratch, then lets the unchanged FileResponse serve full/ranged bytes. No Volume reload occurs while a stream has a Volume file open.

Pending records are the durable queue/outbox; Modal `.spawn()` schedules work, and call IDs support cancellation. Hourly maintenance prunes retention and resubmits unclaimed pending IDs. An atomic claim prevents duplicate generation. Stale executing jobs are failed with `restarted`, not silently regenerated. Web cold starts and a killed verification client do not fail independent generation jobs; Alice finishing during the interruption demonstrated that boundary.

**Operational limitation:** drain jobs and state RPCs before deployment, and do not run overlapping `modal run` apps against these Volumes. Modal caps are per function version, not distributed filesystem locks; this design does not promise rolling, overlapping writers. A Modal Dict was not used for 30-day job records because entries expire after seven days of inactivity.

### Limits and safety controls

- Two CPU pipeline containers globally, one input each; one shared L4 GPU container globally across production script/voice methods, with one input at a time.
- GPU class `max_containers=1`, `min_containers=0`, `scaledown_window=60`, per-input timeout 900s; model startup watchdog 180s.
- Per-job cumulative GPU-call budget: **900 seconds**. Cold start and queue/RPC wait are conservatively included. Exhaustion cancels the call with `terminate_containers=True` and fails clearly; it does not bypass the cap through Edge/API fallback.
- Existing gateway caps: three queued and five nonterminal jobs per UID; six accepted creates/hour for non-owner UIDs; global 60 accepted creates/hour; direct-IP 120 requests/minute and bounded client map.
- Retention: 30 days, hourly maintenance and pre-admission pruning, **50 stored jobs globally**. The contract says globally, despite the brief's “per uid” wording; preserving the stricter global limit also bounds every UID to at most 50.
- Cache: SHA-256 of actual gathered content, style, length, language, title, evidence/note flags, and pipeline version, scoped to the UID. A new contract-compatible queued job reuses the existing MP3 bytes, without inference. The cache test reported zero GPU seconds.
- `STUDIO_ENABLED=0` blocks authenticated creates with 503. This works as a deployment environment override or through the `podcast-studio` Secret. Secret changes require a redeploy/container refresh.

Environment kill/restore commands, with no credentials on the command line:

```bash
STUDIO_ENABLED=0 modal deploy modal_app.py
modal deploy modal_app.py
```

The second command restores the Secret's setting; the currently provisioned setting is enabled.

The deployment override uses an always-present internal environment entry so local and remote Modal dependency graphs have the same shape. An initial conditional-entry bug was found during the real restore test, fixed, and the full disable/restore test then passed. Restore is in the verifier's `finally` block.

## Models, voices, and fallback lanes

**Default:** Qwen/Qwen3-4B-Instruct-2507 through vLLM 0.11.0, on L4. Apache-2.0; pinned revision `cdbee75f17c01a7cc42f958dc650907174af0554`. Transformers is pinned to 4.57.1.

**Default speech:** hexgrad/Kokoro-82M, Apache-2.0, Kokoro 0.9.4. Alex/HOST_A uses `am_michael`; Sam/HOST_B uses `af_heart`. These are podcast hosts, not a replacement for Mavis's ElevenLabs Lizzie voice. Rendered audio is concatenated, normalized with `loudnorm=I=-16:TP=-1.5:LRA=11`, encoded at 44.1kHz/128kbps, and tagged with requested title and artist `Mavis Podcast Lounge`, as before.

**Error-only speech fallback:** Edge TTS, AndrewNeural/AriaNeural, on a Modal CPU function. It was not used by the successful live jobs. Budget exhaustion fails rather than invoking fallback.

**Optional LLM fallback:** explicitly provisioned `STUDIO_FALLBACK_LLM_URL`, `STUDIO_FALLBACK_LLM_KEY`, and `STUDIO_FALLBACK_LLM_MODEL` in the Modal Secret, using an OpenAI-compatible API. None is currently configured. No TypeSafe classifier is misrepresented as a dialogue generator; no paid API fallback was verified or used.

**NotebookLM:** retained in the codebase but disabled in Modal admission. Explicit NotebookLM requests resolve to `local` and receive the existing local-engine transcript note. No Google login/cookies were read, mounted, tested, or used. Datacentre-IP login challenges remain a concern; this session did not start NotebookLM work.

### Corrected real-chapter LLM comparison

Both candidates used the same **11,775-character actual opening chapter** of Gutenberg Alice, the same short/deep-dive prompt and valid two-host JSON checks:

| Candidate | GPU function wall | Model load portion | Spoken words / 1,100 target | Turns | Grounded detail coverage |
|---|---:|---:|---:|---:|---:|
| Qwen3-4B-Instruct-2507 | 134.03s | 64.25s | 1,105 | 14 | 7/8 |
| Qwen3-1.7B | 76.96s | 41.15s | 684 | 28 | 7/8 |

Both models are Apache-2.0; 1.7B revision `70d244cc86ccca08cf5af4e1e306ecf908b1ad5e`. Keep 4B: it hit the requested length within 0.5%, whereas 1.7B was about 38% short. Detail coverage is a lexical diagnostic, not an independent factual-quality audit. An explicit turn-count length hint was added to the cloud prompt.

An earlier comparison accidentally selected a **38-character contents entry**. It is invalid as a real-chapter benchmark and is not used to select the model. The same fixture mistake caused one failed warm probe (`b6b72db0-f2c6-42f0-955b-45e8d1bdda9b`), with malformed JSON after retry. Regression tests now skip contents entries and reject a fixture with no full chapter. The corrected warm probe used real chapter content, not a duplicate of either required original create.

### Speech comparison attempt

On the same two-turn, 156-word excerpt, warm Kokoro produced **55.265s WAV audio** in **0.66849 GPU-function seconds** (2,652,764 bytes). A separate same-script Dia attempt used the shared, capped L4 class, not another GPU pool.

Dia-1.6B-0626 is Apache-2.0 (pinned revision `ef2795fcc29c5abe6ffc91fd33808588b49bbc66`), but AutoProcessor failed with OSError because its required `descript/dac_44khz` decoder checkpoint did not provide the expected Transformers weight files. The failed call was cancelled with container termination. **No Dia audio or audible quality comparison was verified.** Dia is only an SDK-only experimental sample method; it is not a default or fallback production lane. Kokoro remains selected. Human listening/speaker-naturalness assessment is not claimed from ffprobe alone.

## Real verification outputs

Baseline: **213 passed, 1 warning in 5.18s**; first green checkpoint commit `975c352`.

Final full suite: **238 passed, 1 warning in 8.24s**. All 213 original tests remain unchanged and green. Additional tests cover state/retention/rates/cache/ownership/recovery, async routes, kill settings, budget exhaustion, call termination, real-chapter selection, and the resolver's cold import. The source import regression was reproduced failing before the explicit-import fix, then passed.

`pip check`: **No broken requirements found.** `py_compile` and `git diff --check` passed. The existing systemd unit passed `systemd-analyze verify`; it was not installed or restarted. No local test servers were started; the worktree-specific process check found none.

Final deployed app health: HTTP **200**, exactly `{"ok":true}`. Root routes work without `/studio`; signed `/studio` prefix audio was also verified after the final deployment. The first measured cold health request took **6.111s**; a warm health request in the default verifier took **0.243s**.

| Check | Actual result |
|---|---|
| Original topic create | 202; ID `e358aabb-b380-49a4-81fc-64beeba9dfe7`, then ready |
| Original Gutenberg `"11"` book create | 202; ID `0d9707f3-f4ee-4b10-a282-f88c31ecfc5b`, then ready |
| Signed downloads, no Authorization | 200, both MP3s saved and ffprobed |
| Range `bytes=0-1023` | 206, 1,024 bytes, correct Content-Range, private/no-store |
| Tampered signature | 401 for each sample |
| Expired signature | 401 for each sample |
| Changed UID without resigning | 401 for each sample |
| Correctly signed different UID | 404 for audio |
| Different gateway UID | 404 for metadata |
| Missing or wrong gateway key | 401 |
| Bearer operator get/list | 200 after disable/restore and final deploy |
| Content cache | 0 GPU seconds; 15.06s pipeline / 22.37s client elapsed |
| Delete cached test job | 204 with empty body; subsequent GET 404 |
| Disabled authenticated create | 503, `Studio generation is disabled` |
| Disabled missing-key create | 401; health still 200 |
| Restored persisted prefix signed Range | 206 |

Signed capabilities were built with the documented `id.exp.uid` HMAC using the 0600 shared-secret file; no secret value or signed query string is in this receipt/log output. The topic's search returned no live sources: its transcript includes the general-background/no-live-sources note. Alice had a retrieved Gutenberg source. This is not a medical-source-retrieval quality claim.

### Episode timing, media, and cost measurements

All successful samples used Qwen3-4B and the two Kokoro voices, without Edge/API fallback.

| Episode | CPU gather | Writing RPC wall | Voices RPC wall | CPU finish | Pipeline total | Successful GPU-function wall | Audio duration | MP3 bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Topic short | 3.25s | 236.49s | 27.64s | 14.01s | 304.51s | 198.84s | 372.767s | 5,964,819 |
| Alice short | 0.17s | 229.45s | 30.11s | 14.02s | 299.83s | 245.93s | 467.383s | 7,478,650 |
| Alice default | 0.59s | 334.18s | 33.33s | 19.63s | 399.80s | 300.57s | 764.003s | 12,224,593 |
| Corrected warm chapter | <0.001s | 85.95s | 28.24s | 12.59s | 148.91s | 107.01s | 478.616s | 7,658,388 |

Gather/finish columns are wall time inside their CPU functions; writing/voices include their RPC/queue/cold wait. Totals additionally include persistence and orchestration overhead, so columns do not exactly sum. The default sample is **12m44s**, not an invented exact 14 minutes; the unchanged word-target contract promises approximate lengths only.

The warm chapter had one idle GPU runner before admission and **LLM load time 0.0s** in its job telemetry. Its explicit one-time test preparation took 57.37 GPU-function seconds and is separate from episode work. Cold whole-book jobs and a warm chapter are not a controlled same-input full-pipeline speed comparison; the real-chapter benchmark separately measured a 64.25s cold model load.

Original POST response times: topic **6.342s**, Alice **4.645s**; default **2.805s**, corrected warm chapter **9.080s**. Verification resumed the two original IDs after ConnectTimeout/SIGTERM rather than duplicating creates. Failed fixture probe client elapsed was 153.25s; exact GPU/billing duration for that failure was not captured. Its zero returned successful-call measurement must not be read as free GPU work. Future failures retain the conservative budget wall time too.

Modal CLI 1.5.3 exposes runner/backlog stats but no exact billed-dollar figures through its app commands. These are therefore **estimates, not billing receipts**, using Modal's published L4 rate **$0.000222/second** at https://modal.com/pricing:

- Topic: 198.84 × rate = **$0.04414 GPU work**.
- Alice short: 245.93 × rate = **$0.05460 GPU work**.
- Default: 300.56523 × rate = **$0.06673 GPU work**.
- Warm chapter: 107.01 × rate = **$0.02376 GPU work**, excluding its explicit warm-up experiment.
- Conservative default allowance: its 367.51211s cold/queue/RPC budget window plus a full 60s idle tail gives **$0.09491 GPU estimate**. Queue/allocation wait may not be billed, so this is deliberately conservative, not exact usage.

CPU/memory/storage are additional and are not included in those GPU dollars. GPU-function timing counts wall time while a GPU-backed function is executing, including model loading inside it, not CUDA kernel-only seconds; it excludes platform cold scheduling and idle tails.

**Idle:** GPU/CPU web inference compute scales to zero after its tail; verified GPU runners are zero. It is not literally a zero-dollar account: hourly maintenance does small CPU work, and persistent storage remains. The model Volume lists **12,469,201,307 logical bytes (11.61285 GiB)**, approximately **$1.045/month** at the published $0.09/GiB/month rate before credits; actual billed storage may differ. This includes the benchmark candidate cache. MP3/SQLite storage adds a small amount.

Hermes was sent the recommendation: ask J to set a **$10/month workspace spend limit** in the Modal dashboard. It was not set by this agent. Free credits/account billing balance were not verified. Per-job caps do not substitute for a workspace spending limit under sustained demand.

## Secrets and resources

Secret created by this session, by name only: **podcast-studio**.

The shared-secret file is `~/.secure/podcast-studio-shared-secret`, verified mode **0600**, containing the requested 32 random bytes as hex. The owner token was copied from its existing secure file without printing or changing it. CLI provisioning uses a temporary 0600 dotenv file and captures output; values are never placed in command arguments, source, commits, or receipts. Hermes must securely give the same shared key to Netlify.

New Volumes only: `mavis-podcast-studio-state`, `mavis-podcast-studio-audio`, `mavis-podcast-studio-models`. Existing gravity/wakeword/other resources were not changed.

The two required media artifacts are retained as `data/modal-sample-topic.mp3` and `data/modal-sample-alice.mp3`. Default/warm audio, benchmark metrics, HTTP checks, and detailed deployment/test logs are in ignored `data/` artifacts.

## Final state and remaining verification

Final deploy completed successfully (2.824s). `mavis-podcast-studio` remains **deployed** as app `ap-PQX7Blg43xiisac65WlvyP`; it was not stopped, so the endpoint remains usable. The failed initial image-build app `ap-VSznexhIOf8dzaa5IFJggB` is already **stopped, 0 tasks**. No separate `modal run` test app was started.

Actual final GPU stats: **0 runners, 0 running inputs, 0 backlog**. The final `modal app list --json` shows the deployed app at **0 tasks** after its CPU tails scaled down, and the failed build app stopped at 0 tasks. No local test server remains.

Not verified: live Netlify/Android gateway cutover, browser playback/CSP/exact CORS origins, NotebookLM login/generation, 22-minute long episodes, concurrent/load or rolling-deploy behaviour, real active-job deletion races, forced live Edge/API fallback, workspace billing/remaining credits/spend-limit enforcement, and subjective listening quality. Dia did not generate audio and must not be described as verified. Existing short/default jobs, signed isolation, durable records across redeploys, the warm lane, and kill/restore are verified above.

Reproduce with `requirements-modal.txt`, the pytest command in AGENTS.md, `benchmark_modal.py`, and `verify_modal.py`. The verifier has `--resume-topic`/`--resume-alice` for the original IDs without recreating them; `--mode default` creates a separate cost sample, and `--mode warm` explicitly prepares the capped GPU before a distinct real-chapter sample. These provider checks spend compute; run them deliberately, not as routine health probes.

No push was performed. Final implementation/receipt commit is on branch `modal` after the baseline checkpoint.

## NotebookLM primary-engine checkpoint — 2026-10-12

Baseline prerequisite verified: clean branch `modal` at `abf3730`, deployed app `ap-PQX7Blg43xiisac65WlvyP`, live health HTTP 200, pipeline/GPU zero running inputs and backlog. Baseline pytest output: **238 passed, 1 warning in 8.40s**.

First NotebookLM green checkpoint: **311 passed, 1 warning in 9.99s**, read from pytest output. `pip check`, `git diff --check`, and `systemd-analyze verify deploy/podcast-lounge.service` passed. The warning is the pre-existing Starlette HTTPX TestClient deprecation.

Default `auto` tries NotebookLM first; Modal L4/Qwen/Kokoro remains fallback. Explicit NotebookLM auth/challenge failures return a safe job error rather than claiming local audio is NotebookLM; caps, pause, quota, unsupported-source and other failures select local. Local fallback transcripts/audio contain “made with the local engine.” Dedicated single-container/single-input NotebookLM actor uses the core `notebooklm-py==0.8.4` HTTPX/web client, no browser extras or headless reauthentication. Topic source texts are preserved individually (conservative 50-source cap). Audio is converted to MP3 with matching loudnorm and ID3 metadata before notebook cleanup.

Auth is private temporary 0600 storage loaded from `notebooklm-auth`; refreshed storage is copied after client close to a private Volume file with `saved_at`. Volume mtime and saved_at must be newer than the secret timestamp; changed secret fingerprints supersede old persisted sessions. No cookies/email are returned. SDK/HTTP transport logging is disabled in the isolated NotebookLM actor. Global default allowance is six attempted NotebookLM jobs per UTC day, durable reservation before Google calls; optional Secret keys `NOTEBOOKLM_DAILY_MAX`, `NOTEBOOKLM_OWNER_UID` (default `owner`), and `NOTEBOOKLM_OWNER_UNLIMITED=1` configure account policy. Owner is not exempt unless explicitly enabled. Quota pause is durable for 30 minutes.

Open health gains `notebooklm {available, loggedIn, note}` only on Modal, with a five-minute authenticated check. SDK 0.8.4 does **not** accept `list(limit=1)`; the cheapest available `notebooks.list()` is used without returning any notebook data. Heavy use of this **unofficial API can get J’s Google account limited**. Workspace spend cap is J’s stated **$20**, not set or verified by this agent.

Live deployment/proof and created resource names will be recorded below after actual verification; not yet claimed at this checkpoint.
