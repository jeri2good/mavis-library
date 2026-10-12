import asyncio
import fcntl
import hashlib
import hmac
import inspect
import json
import logging
import os
import re
import secrets
import sqlite3
import time
import uuid
from collections import defaultdict, deque
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Literal
from urllib.parse import urlsplit

from fastapi import FastAPI, HTTPException, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, ConfigDict, Field, model_validator
from starlette.datastructures import MutableHeaders, QueryParams
from starlette.middleware.cors import CORSMiddleware

from engines import LocalEngine, NotebookAuthError, NotebookEngine, NotebookQuotaError, duration_seconds
from sources import gather, validate_url

ROOT = Path(__file__).resolve().parent
LOG = logging.getLogger("podcast")
TERMINAL = {"ready", "failed"}
TRANSITIONS = {"queued": {"sourcing", "failed"}, "sourcing": {"writing", "generating", "failed"}, "writing": {"generating", "failed"}, "generating": {"writing", "ready", "failed"}, "ready": set(), "failed": set()}


def timestamp():
    return datetime.now(timezone.utc).isoformat()


def studio_enabled():
    return (os.getenv("STUDIO_DEPLOY_ENABLED") or os.getenv("STUDIO_ENABLED", "1")) != "0"


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


def load_shared_secret(path=None):
    secret = os.getenv("STUDIO_SHARED_SECRET")
    if secret is not None:
        if not secret:
            raise RuntimeError("STUDIO_SHARED_SECRET must not be empty")
        return secret
    return load_token(path or Path.home() / ".secure/podcast-lounge-shared-secret")


def valid_uid(uid):
    return isinstance(uid, str) and (uid == "owner" or re.fullmatch(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}", uid) is not None)


def uid_hash(uid, length=8):
    return hashlib.sha256(uid.encode("utf-8")).hexdigest()[:length]


def verify_audio(secret, job_id, exp, uid, sig, now_milliseconds=None):
    if not (re.fullmatch(r"[A-Za-z0-9_-]{1,100}", job_id) and valid_uid(uid)
            and re.fullmatch(r"[0-9]{10}", exp) and re.fullmatch(r"[0-9a-f]{64}", sig)):
        return False
    now = int((time.time() * 1000 if now_milliseconds is None else now_milliseconds) // 1000)
    if not now < int(exp) <= now + 600:
        return False
    expected = hmac.new(secret.encode("utf-8"), f"{job_id}.{exp}.{uid}".encode("utf-8"), hashlib.sha256).hexdigest()
    return hmac.compare_digest(sig, expected)


class SecurityMiddleware:
    def __init__(self, app, token, shared_secret, rate_limit=120, path_prefix="/studio", audio_origins=()):
        self.app, self.token, self.shared_secret, self.rate_limit = app, token, shared_secret, rate_limit
        self.path_prefix = path_prefix.rstrip("/")
        for origin in audio_origins:
            parsed = urlsplit(origin)
            if (parsed.scheme not in ("http", "https") or not parsed.netloc or parsed.username is not None
                    or parsed.password is not None or parsed.path or parsed.query or parsed.fragment
                    or re.search(r"[\s\\\x00-\x1f\x7f*]", origin)):
                raise RuntimeError("STUDIO_AUDIO_ORIGINS requires exact HTTP(S) origins without paths or credentials")
        self.audio_cors = CORSMiddleware(app, allow_origins=list(audio_origins), allow_methods=["GET"], allow_headers=["Range"], expose_headers=["Accept-Ranges", "Content-Length", "Content-Range"], max_age=600) if audio_origins else None
        self.clients = defaultdict(deque)

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        scope = dict(scope)
        path = scope["path"]
        if self.path_prefix and path.startswith(self.path_prefix + "/"):
            scope["path"] = path[len(self.path_prefix):]
            scope["root_path"] = self.path_prefix
        path = scope["path"]
        audio_match = re.fullmatch(r"/v1/podcasts/([A-Za-z0-9_-]{1,100})/audio", path)
        state = scope.setdefault("state", {})
        headers = defaultdict(list)
        for name, value in scope["headers"]:
            headers[name.lower()].append(value)
        status = 500

        async def logged_send(message):
            nonlocal status
            if message["type"] == "http.response.start":
                status = message["status"]
                if audio_match:
                    MutableHeaders(scope=message)["Cache-Control"] = "private, no-store"
            await send(message)

        async def dispatch():
            ip = (scope.get("client") or ("unknown",))[0]
            now = time.monotonic()
            if ip not in self.clients and len(self.clients) >= 4096:
                self.clients = defaultdict(deque, {key: hits for key, hits in self.clients.items() if hits and hits[-1] > now - 60})
                if len(self.clients) >= 4096:
                    return await JSONResponse({"detail": "Too many clients"}, 429)(scope, receive, logged_send)
            hits = self.clients[ip]
            while hits and hits[0] <= now - 60:
                hits.popleft()
            if len(hits) >= self.rate_limit:
                return await JSONResponse({"detail": "Rate limit exceeded; retry in a minute"}, 429, headers={"Retry-After": "60"})(scope, receive, logged_send)
            hits.append(now)
            uid, mode = None, None
            params = QueryParams(scope.get("query_string", b""))
            if audio_match and self.audio_cors and scope["method"] == "OPTIONS" and headers[b"access-control-request-method"]:
                return await self.audio_cors(scope, receive, logged_send)
            signed = scope["method"] == "GET" and audio_match and any(key in params for key in ("exp", "uid", "sig"))
            health = path == "/v1/health" and scope["method"] == "GET"
            if signed:
                if all(len(params.getlist(key)) == 1 for key in ("exp", "uid", "sig")) and verify_audio(self.shared_secret, audio_match[1], params["exp"], params["uid"], params["sig"]):
                    uid, mode = params["uid"], "signed"
            elif headers[b"x-studio-key"]:
                if (len(headers[b"x-studio-key"]) == len(headers[b"x-mavis-user"]) == 1
                        and secrets.compare_digest(headers[b"x-studio-key"][0], self.shared_secret.encode("utf-8"))):
                    candidate = headers[b"x-mavis-user"][0].decode("latin-1")
                    if valid_uid(candidate):
                        uid, mode = candidate, "gateway"
            elif len(headers[b"authorization"]) == 1 and secrets.compare_digest(headers[b"authorization"][0], ("Bearer " + self.token).encode()):
                uid, mode = "owner", "bearer"
            if not health and uid is None:
                return await JSONResponse({"detail": "Valid studio authorization required"}, 401, headers={"WWW-Authenticate": "Bearer"})(scope, receive, logged_send)
            state.update(uid=uid, auth_mode=mode)
            create = path == "/v1/podcasts" and scope["method"] == "POST"
            cap = 1_000_000 if create else 3 * 1024 * 1024
            body = bytearray()
            while True:
                message = await receive()
                if message["type"] == "http.disconnect":
                    return
                body.extend(message.get("body", b""))
                if len(body) > cap:
                    return await JSONResponse({"detail": "Request body exceeds byte limit"}, 413)(scope, receive, logged_send)
                if not message.get("more_body", False):
                    break
            if create and len(body) > 450_000:
                try:
                    payload = json.loads(body)
                except (ValueError, UnicodeError):
                    payload = None
                if isinstance(payload, dict) and (payload.get("kind") == "chapter" or payload.get("text") is not None):
                    return await JSONResponse({"detail": "Text request JSON exceeds 450,000 bytes"}, 413)(scope, receive, logged_send)
            delivered = False

            async def replay():
                nonlocal delivered
                if not delivered:
                    delivered = True
                    return {"type": "http.request", "body": bytes(body), "more_body": False}
                return await receive()

            target = self.audio_cors if audio_match and self.audio_cors else self.app
            await target(scope, replay, logged_send)

        try:
            await dispatch()
        finally:
            record = {"event": "request", "method": scope["method"], "status": status}
            if state.get("uid"):
                record.update(uidHash=uid_hash(state["uid"]), authMode=state["auth_mode"])
            forwarded_proto = headers[b"x-forwarded-proto"]
            if len(forwarded_proto) == 1 and forwarded_proto[0] in (b"http", b"https"):
                record["forwardedProto"] = forwarded_proto[0].decode()
            LOG.info(json.dumps(record))


class JobManager:
    def __init__(self, data_dir, notebook=None, local=None, gatherer=gather, probe=duration_seconds, global_create_limit=60):
        self.data_dir = Path(data_dir)
        self.data_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.data_dir.chmod(0o700)
        self.db = sqlite3.connect(self.data_dir / "jobs.sqlite")
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA synchronous=FULL")
        self.db.execute("CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, record TEXT NOT NULL)")
        self.db.execute("CREATE TABLE IF NOT EXISTS create_events (uid_hash TEXT NOT NULL, created REAL NOT NULL)")
        self.db.execute("CREATE INDEX IF NOT EXISTS create_events_time ON create_events (created)")
        for job in self.all():
            if "owner_uid" not in job:
                job["owner_uid"] = "owner"
                self.db.execute("UPDATE jobs SET record=? WHERE id=?", (json.dumps(job), job["id"]))
        self.db.commit()
        self.global_create_limit = global_create_limit
        self.notebook, self.local = notebook or NotebookEngine(), local or LocalEngine()
        self.gatherer, self.probe = gatherer, probe
        self.queue = asyncio.Queue()
        self.workers, self.active = [], {}
        self.lock_file = None
        self.lifecycle_lock = asyncio.Lock()

    def all(self):
        return sorted((json.loads(row[0]) for row in self.db.execute("SELECT record FROM jobs")), key=lambda job: (job["createdAt"], job["id"]), reverse=True)

    def get(self, job_id, uid=None):
        row = self.db.execute("SELECT record FROM jobs WHERE id=?", (job_id,)).fetchone()
        if not row:
            raise HTTPException(404, "Podcast not found")
        job = json.loads(row[0])
        if uid is not None and uid != "owner" and job["owner_uid"] != uid:
            raise HTTPException(404, "Podcast not found")
        return job

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
        LOG.info(json.dumps({"jobId": job_id, "uidHash": uid_hash(job["owner_uid"]), "stage": job["status"], "progress": job["progress"]}))
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
                self.audio_path(job["id"]).unlink(missing_ok=True)
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

    async def delete(self, job_id, uid=None):
        async with self.lifecycle_lock:
            self.get(job_id, uid)
            await self._delete(job_id)

    async def _delete(self, job_id):
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
        async with self.lifecycle_lock:
            await self._prune(now)

    async def _prune(self, now=None):
        cutoff = (now or datetime.now(timezone.utc)) - timedelta(days=30)
        for job in self.all():
            if datetime.fromisoformat(job["createdAt"]) < cutoff:
                await self._delete(job["id"])
        jobs = self.all()
        for job in reversed(jobs):
            if len(jobs) <= 50:
                break
            if job["status"] in TERMINAL:
                await self._delete(job["id"])
                jobs.remove(job)

    def check_create_limits(self, uid, public):
        cutoff = time.time() - 3600
        global_count = self.db.execute("SELECT COUNT(*) FROM create_events WHERE created>?", (cutoff,)).fetchone()[0]
        if global_count >= self.global_create_limit:
            raise HTTPException(429, "Global create rate limit exceeded; retry in an hour", headers={"Retry-After": "3600"})
        if uid != "owner":
            count = self.db.execute("SELECT COUNT(*) FROM create_events WHERE uid_hash=? AND created>?", (uid_hash(uid, 64), cutoff)).fetchone()[0]
            if count >= 6:
                raise HTTPException(429, "User create rate limit exceeded; retry in an hour", headers={"Retry-After": "3600"})
        if public or uid != "owner":
            jobs = [job for job in self.all() if job["owner_uid"] == uid]
            if sum(job["status"] == "queued" for job in jobs) >= 3:
                raise HTTPException(429, "User queue is full (3 queued jobs)", headers={"Retry-After": "60"})
            if sum(job["status"] not in TERMINAL for job in jobs) >= 5:
                raise HTTPException(429, "User capacity is full (2 concurrent plus 3 queued jobs)", headers={"Retry-After": "60"})

    async def enqueue(self, request, uid="owner", public=False):
        self.check_create_limits(uid, public)
        engine = request.engine
        if engine == "auto":
            engine = "notebooklm" if await self.notebook.logged_in() else "local"
        async with self.lifecycle_lock:
            await self._prune()
            self.check_create_limits(uid, public)
            jobs = self.all()
            if len(jobs) >= 50:
                victim = next((job for job in reversed(jobs) if job["status"] in TERMINAL), None)
                if not victim:
                    raise HTTPException(429, "Podcast queue is full (50 jobs); wait or delete a job")
                await self._delete(victim["id"])
            job_id = str(uuid.uuid4())
            job = {"id": job_id, "owner_uid": uid, "status": "queued", "progress": 0, "stage": "Queued", "engine": engine, "title": request.title, "kind": request.kind, "style": request.style, "createdAt": timestamp(), "sources": [], "_request": request.model_dump()}
            now = time.time()
            self.db.execute("DELETE FROM create_events WHERE created<=?", (now - 3600,))
            self.db.execute("INSERT INTO create_events VALUES (?, ?)", (uid_hash(uid, 64), now))
            self.save(job)
            self.queue.put_nowait(job_id)
            LOG.info(json.dumps({"event": "created", "jobId": job_id, "uidHash": uid_hash(uid)}))
            return job

    async def worker(self):
        while True:
            job_id = await self.queue.get()
            try:
                try:
                    if self.get(job_id)["status"] != "queued":
                        continue
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
                    LOG.info(json.dumps({"jobId": job_id, "uidHash": uid_hash(job["owner_uid"]), "stage": "local_engine", "llm": used}))
            if not output.is_file() or output.stat().st_size == 0:
                raise RuntimeError("Engine produced no audio")
            duration = await self.probe(output)
            with output.open("rb") as stream:
                os.fsync(stream.fileno())
            output.replace(self.audio_path(job_id))
            directory_fd = os.open(self.data_dir, os.O_DIRECTORY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
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
            elif isinstance(exc, (ValueError, RuntimeError)) and str(exc) in {
                "Only public HTTPS hostnames are allowed", "URL resolves to a non-public address", "Redirect without location", "Too many redirects", "Source exceeds 3 MB", "Source uses unsupported content encoding", "Could not retrieve Gutenberg book", "Source contains no readable text", "LLM returned invalid script JSON after retry", "ffmpeg is required to render audio", "Edge TTS synthesis failed", "Audio processing failed", "Engine produced no audio", "NotebookLM audio generation failed", "No LLM endpoint available; check TypeSafe chat support or HERMES_LLM_BASE_URL",
            }:
                error = str(exc)
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
    return {key: value for key, value in job.items() if not key.startswith("_") and key != "owner_uid" and (transcript or key != "transcript")}


async def resolved(value):
    return await value if inspect.isawaitable(value) else value


def create_app(data_dir=None, token=None, notebook=None, local=None, gatherer=gather, probe=duration_seconds, rate_limit=120, shared_secret=None, global_create_limit=60, path_prefix="/studio", audio_origins=None, manager_factory=None):
    data_dir = Path(data_dir or os.getenv("PODCAST_DATA_DIR", str(ROOT / "data")))
    audio_origins = tuple(origin.strip() for origin in os.getenv("STUDIO_AUDIO_ORIGINS", "").split(",") if origin.strip()) if audio_origins is None else tuple(audio_origins)

    @asynccontextmanager
    async def lifespan(app):
        manager = manager_factory() if manager_factory else JobManager(data_dir, notebook, local, gatherer, probe, global_create_limit=global_create_limit)
        app.state.manager = manager
        await manager.start()
        try:
            yield
        finally:
            await manager.close()

    app = FastAPI(title="Mavis Podcast Lounge", lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None, redirect_slashes=False)
    app.add_middleware(SecurityMiddleware, token=token or load_token(os.getenv("PODCAST_TOKEN_PATH", str(Path.home() / ".secure/podcast-lounge-token"))), shared_secret=shared_secret if shared_secret is not None else load_shared_secret(), rate_limit=rate_limit, path_prefix=path_prefix, audio_origins=audio_origins)

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request, exc):
        details = []
        for error in exc.errors():
            field = error["loc"][1] if len(error["loc"]) > 1 else "body"
            field = field if field in PodcastRequest.model_fields else "body"
            details.append(field + ": " + error["msg"])
        return JSONResponse({"detail": "; ".join(details)}, 422)

    @app.get("/v1/health")
    async def health():
        return {"ok": True}

    @app.post("/v1/podcasts", status_code=202)
    async def create(body: PodcastRequest, request: Request):
        if not studio_enabled():
            raise HTTPException(503, "Studio generation is disabled")
        job = await request.app.state.manager.enqueue(body, request.state.uid, public=request.state.auth_mode == "gateway")
        return {"id": job["id"], "status": "queued"}

    @app.get("/v1/podcasts")
    async def list_jobs(request: Request):
        jobs = await resolved(request.app.state.manager.all())
        if request.state.auth_mode != "bearer":
            jobs = [job for job in jobs if job["owner_uid"] == request.state.uid]
        return {"items": [public_job(job, transcript=False) for job in jobs[:100]]}

    @app.get("/v1/podcasts/{job_id}")
    async def get_job(job_id: str, request: Request):
        return public_job(await resolved(request.app.state.manager.get(job_id, request.state.uid)))

    @app.get("/v1/podcasts/{job_id}/audio")
    async def audio(job_id: str, request: Request):
        manager = request.app.state.manager
        job = await resolved(manager.get(job_id, request.state.uid))
        if job["status"] != "ready":
            raise HTTPException(409, "Podcast audio is not ready")
        path = await resolved(manager.audio_path(job_id))
        if not path.is_file():
            raise HTTPException(409, "Podcast audio is missing")
        return FileResponse(path, media_type="audio/mpeg", filename="podcast.mp3", headers={"Cache-Control": "private, no-store", "Accept-Ranges": "bytes"})

    @app.delete("/v1/podcasts/{job_id}", status_code=204)
    async def delete(job_id: str, request: Request):
        await request.app.state.manager.delete(job_id, request.state.uid)
        return Response(status_code=204)

    return app
