import asyncio
import fcntl
import json
import logging
import os
import secrets
import shutil
import sqlite3
import time
import uuid
from collections import defaultdict, deque
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Literal

from fastapi import FastAPI, HTTPException, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, ConfigDict, Field, model_validator

from engines import LLM, LocalEngine, NotebookAuthError, NotebookEngine, NotebookQuotaError, duration_seconds
from sources import gather, validate_url

ROOT = Path(__file__).resolve().parent
LOG = logging.getLogger("podcast")
TERMINAL = {"ready", "failed"}
TRANSITIONS = {"queued": {"sourcing", "failed"}, "sourcing": {"writing", "generating", "failed"}, "writing": {"generating", "failed"}, "generating": {"writing", "ready", "failed"}, "ready": set(), "failed": set()}


def timestamp():
    return datetime.now(timezone.utc).isoformat()


class PodcastRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)
    kind: Literal["book", "chapter", "article", "topic"]
    title: str = Field(min_length=1, max_length=300)
    text: str | None = Field(default=None, max_length=400000)
    url: str | None = Field(default=None, max_length=2048)
    gutenbergId: str | None = None
    query: str | None = Field(default=None, max_length=300)
    style: Literal["deep-dive", "brief", "debate", "critique"] = "deep-dive"
    length: Literal["short", "default", "long"] = "default"
    language: Literal["en"] = "en"
    engine: Literal["auto", "notebooklm", "local"] = "auto"

    @model_validator(mode="after")
    def check_source(self):
        if self.url:
            validate_url(self.url)
        if self.gutenbergId:
            import re
            if not re.fullmatch(r"[1-9][0-9]{0,6}", self.gutenbergId):
                raise ValueError("gutenbergId must be a positive numeric Project Gutenberg ID")
        required = {"chapter": (bool(self.text), "A chapter needs non-empty text (at most 400,000 characters)"), "article": (bool(self.url), "An article needs a public HTTPS URL"), "book": (bool(self.gutenbergId or self.text or self.url), "A book needs gutenbergId, text, or a public HTTPS URL"), "topic": (bool(self.query), "A topic needs a non-empty query (at most 300 characters)")}
        valid, message = required[self.kind]
        if not valid:
            raise ValueError(message)
        return self


def load_token(path):
    path = Path(path)
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    except FileExistsError:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
        with os.fdopen(fd) as stream:
            os.fchmod(stream.fileno(), 0o600)
            token = stream.read().strip()
    else:
        token = secrets.token_urlsafe(48)
        with os.fdopen(fd, "w") as stream:
            stream.write(token + "\n")
            stream.flush()
            os.fsync(stream.fileno())
    if len(token) < 32 or not token.isascii():
        raise RuntimeError("Invalid podcast token file; provision a secure token")
    return token


class SecurityMiddleware:
    def __init__(self, app, token, rate_limit=120):
        self.app, self.token, self.rate_limit = app, token, rate_limit
        self.clients = defaultdict(deque)

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        ip = scope.get("client", ("unknown",))[0]
        now = time.monotonic()
        if ip not in self.clients and len(self.clients) >= 4096:
            self.clients = defaultdict(deque, {key: hits for key, hits in self.clients.items() if hits and hits[-1] > now - 60})
            if len(self.clients) >= 4096:
                return await JSONResponse({"detail": "Too many clients"}, 429)(scope, receive, send)
        hits = self.clients[ip]
        while hits and hits[0] <= now - 60:
            hits.popleft()
        if len(hits) >= self.rate_limit:
            return await JSONResponse({"detail": "Rate limit exceeded; retry in a minute"}, 429, headers={"Retry-After": "60"})(scope, receive, send)
        hits.append(now)
        headers = dict(scope["headers"])
        authorization = headers.get(b"authorization", b"")
        expected = ("Bearer " + self.token).encode()
        if not (scope["path"] == "/v1/health" and scope["method"] == "GET") and not secrets.compare_digest(authorization, expected):
            return await JSONResponse({"detail": "Bearer authorization required"}, 401, headers={"WWW-Authenticate": "Bearer"})(scope, receive, send)
        body = bytearray()
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                return
            body.extend(message.get("body", b""))
            if len(body) > 3 * 1024 * 1024:
                return await JSONResponse({"detail": "Request body exceeds 3 MB"}, 413)(scope, receive, send)
            if not message.get("more_body", False):
                break
        delivered = False

        async def replay():
            nonlocal delivered
            if not delivered:
                delivered = True
                return {"type": "http.request", "body": bytes(body), "more_body": False}
            return await receive()

        await self.app(scope, replay, send)


class JobManager:
    def __init__(self, data_dir, notebook=None, local=None, gatherer=gather, probe=duration_seconds):
        self.data_dir = Path(data_dir)
        self.data_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.data_dir.chmod(0o700)
        self.db = sqlite3.connect(self.data_dir / "jobs.sqlite")
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA synchronous=FULL")
        self.db.execute("CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, record TEXT NOT NULL)")
        self.db.commit()
        self.notebook, self.local = notebook or NotebookEngine(), local or LocalEngine()
        self.gatherer, self.probe = gatherer, probe
        self.queue = asyncio.Queue()
        self.workers, self.active = [], {}
        self.lock_file = None

    def all(self):
        return sorted((json.loads(row[0]) for row in self.db.execute("SELECT record FROM jobs")), key=lambda job: (job["createdAt"], job["id"]), reverse=True)

    def get(self, job_id):
        row = self.db.execute("SELECT record FROM jobs WHERE id=?", (job_id,)).fetchone()
        if not row:
            raise HTTPException(404, "Podcast not found")
        return json.loads(row[0])

    def save(self, job):
        self.db.execute("INSERT OR REPLACE INTO jobs VALUES (?, ?)", (job["id"], json.dumps(job)))
        self.db.commit()

    def update(self, job_id, status=None, **changes):
        job = self.get(job_id)
        if status and status != job["status"] and status not in TRANSITIONS[job["status"]]:
            raise ValueError("Invalid job state transition")
        if status:
            job["status"] = status
        job.update(changes)
        if job["status"] in TERMINAL:
            job.pop("_request", None)
        self.save(job)
        LOG.info(json.dumps({"jobId": job_id, "stage": job["status"], "progress": job["progress"]}))
        return job

    async def start(self):
        self.lock_file = open(self.data_dir / "service.lock", "a")
        try:
            fcntl.flock(self.lock_file, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            self.lock_file.close()
            self.lock_file = None
            raise RuntimeError("Only one Podcast Lounge process may use this data directory") from None
        for job in self.all():
            if job["status"] not in TERMINAL:
                self.update(job["id"], "failed", error="restarted", stage="Interrupted by service restart")
                self.audio_path(job["id"], temporary=True).unlink(missing_ok=True)
        await self.prune()
        self.workers = [asyncio.create_task(self.worker()) for _ in range(2)]
        self.cleaner = asyncio.create_task(self.cleanup_loop())

    async def close(self):
        for task in self.workers + [self.cleaner] + list(self.active.values()):
            task.cancel()
        await asyncio.gather(*self.workers, self.cleaner, *self.active.values(), return_exceptions=True)
        self.db.close()
        if self.lock_file:
            self.lock_file.close()

    def audio_path(self, job_id, temporary=False):
        return self.data_dir / (job_id + (".mp3.tmp" if temporary else ".mp3"))

    async def delete(self, job_id):
        self.get(job_id)
        task = self.active.get(job_id)
        if task:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
        self.db.execute("DELETE FROM jobs WHERE id=?", (job_id,))
        self.db.commit()
        self.audio_path(job_id).unlink(missing_ok=True)
        self.audio_path(job_id, temporary=True).unlink(missing_ok=True)

    async def prune(self, now=None):
        cutoff = (now or datetime.now(timezone.utc)) - timedelta(days=30)
        for job in self.all():
            if datetime.fromisoformat(job["createdAt"]) < cutoff:
                await self.delete(job["id"])
        jobs = self.all()
        for job in reversed(jobs):
            if len(jobs) <= 50:
                break
            if job["status"] in TERMINAL:
                await self.delete(job["id"])
                jobs.remove(job)

    async def enqueue(self, request):
        engine = request.engine
        if engine == "auto":
            engine = "notebooklm" if await self.notebook.logged_in() else "local"
        await self.prune()
        jobs = self.all()
        if len(jobs) >= 50:
            victim = next((job for job in reversed(jobs) if job["status"] in TERMINAL), None)
            if not victim:
                raise HTTPException(429, "Podcast queue is full (50 jobs); wait or delete a job")
            await self.delete(victim["id"])
        job_id = str(uuid.uuid4())
        job = {"id": job_id, "status": "queued", "progress": 0, "stage": "Queued", "engine": engine, "title": request.title, "kind": request.kind, "style": request.style, "createdAt": timestamp(), "sources": [], "_request": request.model_dump()}
        self.save(job)
        self.queue.put_nowait(job_id)
        return job

    async def worker(self):
        while True:
            job_id = await self.queue.get()
            try:
                try:
                    self.get(job_id)
                except HTTPException:
                    continue
                task = asyncio.create_task(self.generate(job_id))
                self.active[job_id] = task
                try:
                    await asyncio.shield(task)
                except asyncio.CancelledError:
                    if asyncio.current_task().cancelling():
                        raise
                finally:
                    self.active.pop(job_id, None)
            finally:
                self.queue.task_done()

    async def generate(self, job_id):
        output = self.audio_path(job_id, temporary=True)
        try:
            job = self.get(job_id)
            request = PodcastRequest(**job["_request"])

            async def stage(status, progress, description):
                self.update(job_id, status, progress=progress, stage=description)

            await stage("sourcing", 10, "Gathering source material")
            gathered = await self.gatherer(request)
            self.update(job_id, sources=gathered.sources)
            transcript, fallback = None, False
            if job["engine"] == "notebooklm":
                await stage("generating", 35, "Generating NotebookLM audio")
                try:
                    await self.notebook.generate(request.title, gathered.text, request.style, request.length, request.language, output)
                except (NotebookAuthError, NotebookQuotaError):
                    if request.engine != "auto":
                        raise
                    self.update(job_id, engine="local")
                    fallback = True
            if job["engine"] == "local" or fallback:
                transcript = await self.local.generate(request, gathered, output, stage, fallback=fallback)
                used = getattr(getattr(self.local, "llm", None), "used", None)
                if used:
                    self.update(job_id, _llmUsed=used)
                    LOG.info(json.dumps({"jobId": job_id, "stage": "local_engine", "llm": used}))
            if not output.is_file() or output.stat().st_size == 0:
                raise RuntimeError("Engine produced no audio")
            duration = await self.probe(output)
            output.replace(self.audio_path(job_id))
            extra = {"durationSec": duration}
            if transcript is not None:
                extra["transcript"] = transcript
            self.update(job_id, "ready", progress=100, stage="Ready", **extra)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            if isinstance(exc, NotebookAuthError):
                error = "NotebookLM needs a fresh sign-in"
            elif isinstance(exc, NotebookQuotaError):
                error = "NotebookLM quota or rate limit reached"
            elif isinstance(exc, (ValueError, RuntimeError)):
                error = str(exc)[:300]
            else:
                error = "Generation failed (" + type(exc).__name__ + "); check source or engine connectivity"
            self.update(job_id, "failed", error=error, stage="Failed")
        finally:
            output.unlink(missing_ok=True)

    async def cleanup_loop(self):
        while True:
            await asyncio.sleep(3600)
            await self.prune()


def public_job(job, transcript=True):
    return {key: value for key, value in job.items() if not key.startswith("_") and (transcript or key != "transcript")}


def create_app(data_dir=None, token=None, notebook=None, local=None, gatherer=gather, probe=duration_seconds, rate_limit=120):
    data_dir = Path(data_dir or os.getenv("PODCAST_DATA_DIR", str(ROOT / "data")))

    @asynccontextmanager
    async def lifespan(app):
        manager = JobManager(data_dir, notebook, local, gatherer, probe)
        app.state.manager = manager
        await manager.start()
        try:
            yield
        finally:
            await manager.close()

    app = FastAPI(title="Mavis Podcast Lounge", lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)
    app.add_middleware(SecurityMiddleware, token=token or load_token(os.getenv("PODCAST_TOKEN_PATH", str(Path.home() / ".secure/podcast-lounge-token"))), rate_limit=rate_limit)

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request, exc):
        return JSONResponse({"detail": "; ".join(".".join(str(part) for part in error["loc"][1:]) + ": " + error["msg"] for error in exc.errors())}, 422)

    @app.get("/v1/health")
    async def health(request: Request):
        manager = request.app.state.manager
        logged_in = await manager.notebook.logged_in()
        available = bool(shutil.which("ffmpeg") and shutil.which("ffprobe"))
        return {"ok": True, "engines": {"notebooklm": {"available": True, "loggedIn": logged_in, "note": "Signed in (verified with NotebookLM)" if logged_in else "NotebookLM needs a fresh sign-in"}, "local": {"available": available, "note": "Two-host Edge TTS; TypeSafe chat if supported, otherwise Hermes gateway. Requires network access." if available else "ffmpeg and ffprobe are required"}}}

    @app.post("/v1/podcasts", status_code=202)
    async def create(body: PodcastRequest, request: Request):
        job = await request.app.state.manager.enqueue(body)
        return {"id": job["id"], "status": "queued"}

    @app.get("/v1/podcasts")
    async def list_jobs(request: Request):
        return {"items": [public_job(job, transcript=False) for job in request.app.state.manager.all()[:100]]}

    @app.get("/v1/podcasts/{job_id}")
    async def get_job(job_id: str, request: Request):
        return public_job(request.app.state.manager.get(job_id))

    @app.get("/v1/podcasts/{job_id}/audio")
    async def audio(job_id: str, request: Request):
        manager = request.app.state.manager
        job = manager.get(job_id)
        if job["status"] != "ready":
            raise HTTPException(409, "Podcast audio is not ready")
        path = manager.audio_path(job_id)
        if not path.is_file():
            raise HTTPException(409, "Podcast audio is missing")
        return FileResponse(path, media_type="audio/mpeg", filename="podcast.mp3")

    @app.delete("/v1/podcasts/{job_id}", status_code=204)
    async def delete(job_id: str, request: Request):
        await request.app.state.manager.delete(job_id)
        return Response(status_code=204)

    return app
