# Podcast Lounge project rules

- Scope: this backend only; do not modify Android or other Mavis services.
- Use `.venv/bin/python -m pytest -q` and `.venv/bin/pip check` for verification.
- Validate deployment with `systemd-analyze verify deploy/podcast-lounge.service`.
- Ad-hoc testing binds 127.0.0.1:8141, never the operator's production port 8140.
- Real samples: start the server, run `.venv/bin/python sample_run.py`, inspect `data/sample-results.json`, and independently verify samples with ffprobe. Logs and generated data are ignored by git.
- Never print the token at ~/.secure/podcast-lounge-token, provider keys, Google cookies, or source text. Use fake credentials/clients in unit tests.
- TypeSafe System One only classifies typed questions; it cannot generate dialogue. Default dialogue uses the existing Hermes OpenAI-compatible gateway, not Devin. Future TypeSafe chat support is explicit opt-in.
- One service process/uvicorn worker per data directory; two internal workers cap concurrent generations. No sudo or outside-service installation by the coding agent.
