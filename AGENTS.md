# Podcast Lounge project rules

- Scope: this backend only; do not modify Android or other Mavis services.
- Use `.venv/bin/python -m pytest -q` and `.venv/bin/pip check` for verification.
- Validate deployment with `systemd-analyze verify deploy/podcast-lounge.service`.
- Ad-hoc testing binds 127.0.0.1:8141, never the operator's production port 8140.
- Real samples: start the server, run `.venv/bin/python sample_run.py`, inspect `data/sample-results.json`, and independently verify samples with ffprobe. Logs and generated data are ignored by git.
- Never print the token at ~/.secure/podcast-lounge-token, provider keys, Google cookies, or source text. Use fake credentials/clients in unit tests.
- TypeSafe System One only classifies typed questions; it cannot generate dialogue. Default dialogue uses the existing Hermes OpenAI-compatible gateway, not Devin. Future TypeSafe chat support is explicit opt-in.
- One service process/uvicorn worker per data directory; two internal workers cap concurrent generations. No sudo or outside-service installation by the coding agent.
- Gateway source of truth: `docs/STUDIO-CONTRACT.md`. Preserve existing statuses and DELETE=204; Gutenberg IDs are JSON strings, signature expiry is Unix seconds, and UID ownership stays out of response payloads.
- Gateway tests inject a fake `STUDIO_SHARED_SECRET` automatically. For workspace-contained scratch data, use `TMPDIR="$PWD/data" .venv/bin/python -m pytest -q --basetemp=data/<unique-run>-tmp`; run long commands in the background with ignored logs under `data/`.
- Never log raw UIDs or signed URL query strings; keep uvicorn access logs/proxy-header trust disabled. Shared-secret file is `~/.secure/podcast-lounge-shared-secret`, mode 0600, provisioned only by runtime/operator outside test runs. Audio CORS is exact-origin opt-in via `STUDIO_AUDIO_ORIGINS`, not a global API grant.
