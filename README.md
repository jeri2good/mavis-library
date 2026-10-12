# Mavis Podcast Lounge

Standalone podcast-studio HTTP backend for Mavis Library. Production port is **8140**; developer verification uses **127.0.0.1:8141**. This service does not modify or route through Android, LiveKit, or a Devin model lane.

## Engine lanes

- **Default (`auto`):** NotebookLM when a read-only NotebookLM API check verifies a stored login; otherwise the local engine.
- **Local:** source gathering → chunked map/reduce → Alex/Sam JSON dialogue → Andrew/Aria Edge TTS → short inter-turn pauses → loudness-normalized 128 kbps MP3 with ID3 title and artist `Mavis Podcast Lounge`. Length targets are approximately 1,100 / 2,000 / 3,200 spoken words, not guaranteed wall-clock durations.
- **Edge compatibility:** edge-tts 7.2.8's legacy synthesis endpoint started rejecting requests with HTTP 403 in October 2026. A narrow in-process override uses Microsoft's current `api.msedgeservices.com` synthesis host, following the endpoint-only change reviewed in upstream PR https://github.com/rany2/edge-tts/pull/491 (open/unmerged at delivery). The installed package is not edited, no fork is installed, and TLS verification/voices remain unchanged. The override leaves any future non-legacy package endpoint alone. This compatibility lane has a real synthesis probe and dedicated tests; remove it after adopting a verified upstream release with the fix.
- **LLM fallback:** the existing Hermes gateway at `http://127.0.0.1:8000/v1`, model `mavis-gravity`. TypeSafe System One is a decision/classification API, **not a text-generating LLM**. Its documented endpoint rejects chat payloads. No requests are sent there by default. An actual compatible future TypeSafe chat endpoint can be opted into with `TYPESAFE_CHAT_URL`; rejection falls back to Hermes.
- **NotebookLM:** a new notebook per job, server-fetched text source, format/length enums, generation timeout of 20 minutes, quota retry/backoff, download and conversion to real MP3. The job-owned notebook is deleted in `finally` unless `KEEP_NOTEBOOKS=1`. Cleanup failures are reported in structured logs; they may require manually removing that job's notebook.
- **Automatic runtime fallback:** only NotebookLM authentication and quota/rate-limit errors trigger a local rerun for `engine=auto`. Its spoken transcript explicitly says it was made with the local engine. Explicit `engine=notebooklm` fails instead.
- **Not verified live:** NotebookLM generation/download with Google credentials. The entire integration is covered with fake clients and a real ffmpeg conversion test.

“Local” means this service runs locally and uses no paid AI-caller service. It still needs network access for Edge TTS, search, and the upstream provider behind the existing Hermes gateway. This project does not alter that gateway's provider, subscription, billing, or security configuration.

## Setup and verification

Python 3.12, ffmpeg and ffprobe are required. The supplied `.venv` is already populated; `requirements.txt` records its direct dependencies with exact versions.

```bash
cd /home/hawthornes/podcast-lounge
.venv/bin/pip check
.venv/bin/python -m pytest -q
systemd-analyze verify deploy/podcast-lounge.service
.venv/bin/uvicorn main:app --host 127.0.0.1 --port 8141 --workers 1 --no-access-log --no-proxy-headers
```

In another terminal, `.venv/bin/python sample_run.py` performs two real HTTP jobs (Alice, Gutenberg ID 11, and a vaccines topic), polls them, verifies byte-range delivery, and preserves `data/sample-alice.mp3`, `data/sample-vaccines.mp3`, transcripts and `data/sample-results.json`. It exits with an error on any failed job; it never reports a fabricated successful sample. Each rerun creates new jobs and replaces only these designated sample outputs. The receipt records the verified run, not estimates. Sample copies are verification artifacts outside job retention.

The upstream Starlette TestClient currently emits a deprecation warning about httpx. The suite uses the already-installed httpx; no extra dependency is required to pass.

## Token and network boundary

At first app initialization a 48-byte random token is created once at `~/.secure/podcast-lounge-token`, with permissions **0600**. Existing tokens are reused and permissions corrected; symlinks and weak token files are refused. The token is never printed or logged. Provision it to the Android app out of band using Hermes' secure handling.

Every HTTP path, including missing routes, requires an exact `Authorization: Bearer <token>` header except **GET /v1/health**. API documentation routes are disabled. Constant-time byte comparison is used. The service limits each direct client IP to 120 requests per minute, including health and failed authentication, and caps request bodies at 3 MiB. Reverse-proxy headers are deliberately ignored to prevent spoofing the IP limiter.

Production binds `0.0.0.0` as requested. Use a trusted LAN, firewall or TLS reverse proxy; bearer tokens over plain HTTP are not safe on untrusted networks. If a proxy is introduced, direct-IP limiting will see the proxy address until a separately reviewed trusted-proxy policy is implemented.

An authenticated smoke check without putting a token in command arguments or output:

```bash
.venv/bin/python - <<'PY'
from pathlib import Path
import httpx
headers = {"Authorization": "Bearer " + (Path.home()/".secure/podcast-lounge-token").read_text().strip()}
with httpx.Client(base_url="http://127.0.0.1:8141", headers=headers) as client:
    print(client.get("/v1/health").json())
    result = client.post("/v1/podcasts", json={"kind":"chapter", "title":"A chapter", "text":"Supply your source text here.", "engine":"local", "length":"short"})
    print(result.status_code, result.json())
PY
```

## API contract

- `GET /v1/health`: `{ok, engines:{notebooklm:{available,loggedIn,note},local:{available,note}}}`. NotebookLM `available` means the library is installed; `loggedIn` requires a real successful check, cached for 60 seconds. No credential file means false. Local availability means ffmpeg/ffprobe are installed; its note makes network requirements explicit.
- `POST /v1/podcasts`: 202 `{id,status:"queued"}`. `kind` is `book|chapter|article|topic`; `title` is required. Source fields: `text`, `url`, `gutenbergId`, `query`. Defaults: `style=deep-dive`, `length=default`, `language=en`, `engine=auto`. Styles: `deep-dive|brief|debate|critique`; lengths: `short|default|long`; engines: `auto|notebooklm|local`.
- Validation: chapter requires trimmed nonempty text (≤400,000 characters); article requires a public HTTPS URL; book requires text, Gutenberg ID or HTTPS URL; topic requires a trimmed query (≤300 characters). Invalid fields return 422 with a human-readable `detail`, never an echo of source text. Extra fields are rejected.
- `GET /v1/podcasts/{id}`: `{id,status,progress,stage,engine,title,kind,style,createdAt,sources}`; `durationSec` appears when ready, `error` on failure, `transcript` for local output. Status is `queued|sourcing|writing|generating|ready|failed`.
- `GET /v1/podcasts`: `{items:[...]}`, newest first, up to 100, without transcripts. The current retention cap means at most 50 jobs exist.
- `GET /v1/podcasts/{id}/audio`: `audio/mpeg`, Content-Length, HTTP byte ranges (206; invalid ranges 416). Not-ready audio returns 409; unknown IDs return 404.
- `DELETE /v1/podcasts/{id}`: 204; cancels an active job before removing its stored job and audio files.

## Sources and persistence

URL fetching accepts HTTPS DNS hostnames only, with no userinfo or non-443 port. Private, loopback, link-local, CGNAT, reserved and multicast addresses, bare IP forms, and local/internal suffixes are blocked. All DNS answers are checked inside the connector resolver used for the actual connection, preventing a separate-check DNS-rebinding gap. Redirects are revalidated (maximum three), proxy environment variables and cookies are disabled, timeout is 15 seconds per fetch including redirects, and content is capped at 3 MiB. Identity encoding is requested; compressed responses that ignore it are rejected without decompression, preventing compression bombs. TLS validation stays enabled. Feed-reader UA: `Mozilla/5.0 (compatible; Feedbot/1.0)`.

The stdlib article extractor prioritizes main/article content and removes navigation, scripts, ads and cookie banners; it is not a full browser and does not execute JavaScript. Gutenberg sources have license markers stripped, chapter headings identified, and a whole-book outline/opening/key-passage sample capped at 120,000 characters. Gutenberg main-site failures fall back to public mirrors with the same URL policy.

Topics use keyless DDGS, then DuckDuckGo HTML if DDGS has no results, aiming for 3–6 successfully fetched public sources. Fewer accessible results can occur; only real fetched sources are returned. If none are available, `sources` stays empty and the spoken intro explicitly says it uses general background knowledge with no live sources. Source grounding and quote discipline are enforced by prompts, not an independent factual-certification system.

SQLite in `data/jobs.sqlite` uses WAL and synchronous FULL writes. Two queue workers enforce the generation cap. A process lock refuses multiple service instances using the same data directory; use one uvicorn worker. Startup marks interrupted/queued jobs failed with `error:"restarted"`, rather than silently re-running them. Completed jobs survive restart. Jobs older than 30 days are removed on startup, before submissions, and hourly. At 50 jobs the oldest completed/failed job is evicted; if all 50 are in flight, new submissions get 429. Structured logs contain job IDs/stages, not complete source text or credentials. The data directory is private (0700) and ignored by git.

## NotebookLM one-time sign-in

J runs the following interactively, outside systemd:

```bash
.venv/bin/notebooklm login
```

If the browser needs an alternative, consult `.venv/bin/notebooklm login --help` and use `--browser chrome`. Never paste cookies into logs or chat. Default storage is `~/.notebooklm/profiles/default/storage_state.json`; use `NOTEBOOKLM_STORAGE` for a different path and adjust the unit's writable paths if necessary. Changed credential files clear the service's expired-auth marker automatically. Verify `GET /v1/health` reports `loggedIn:true`, submit an explicit NotebookLM job, wait for ready, check MP3 playback/ranges, then confirm its temporary notebook was removed. Fake-client tests do not replace this live acceptance check.

## Systemd installation (Hermes/operator only)

The agent does not run sudo or install the system unit. Prepare writable directories before starting the hardened service:

```bash
mkdir -p /home/hawthornes/podcast-lounge/data /home/hawthornes/.secure /home/hawthornes/.notebooklm
chmod 700 /home/hawthornes/podcast-lounge/data
sudo install -m 644 deploy/podcast-lounge.service /etc/systemd/system/podcast-lounge.service
sudo systemctl daemon-reload
sudo systemctl enable --now podcast-lounge.service
systemctl status podcast-lounge.service --no-pager
journalctl -u podcast-lounge.service -n 50 --no-pager
```

Check health on port 8140 and make an authenticated submission after installation. A verification server on 8141 must be stopped first because the process lock protects the same data directory.

The SYSTEM unit runs as `hawthornes`, restarts automatically, reads optional `~/.secure/podcast-lounge.env`, and sets NoNewPrivileges, PrivateTmp, ProtectHome=read-only, ProtectSystem=strict, restrictive address families, no capabilities, and explicit writable data/secure/NotebookLM paths. Its syntax and runtime-equivalent sandbox are checked during delivery; final system installation/start is an operator step.

Optional environment settings (keep secrets in a 0600 file, never commit them):

```text
HERMES_LLM_BASE_URL=http://127.0.0.1:8000/v1
HERMES_LLM_MODEL=mavis-gravity
HERMES_LLM_API_KEY=<only if your gateway requires one>
KEEP_NOTEBOOKS=0
NOTEBOOKLM_STORAGE=/home/hawthornes/.notebooklm/profiles/default/storage_state.json
```

Additional overrides: `PODCAST_DATA_DIR`, `PODCAST_TOKEN_PATH`, `TYPESAFE_CHAT_URL`, `TYPESAFE_API_KEY`, `TYPESAFE_MODEL`. Alternate writable paths require corresponding systemd sandbox changes. No provider swaps or outside-service configuration changes are performed by this project.
