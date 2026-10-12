import asyncio
from datetime import datetime, timedelta, timezone
from unittest.mock import AsyncMock

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

from engines import NotebookAuthError, NotebookQuotaError
from service import JobManager, PodcastRequest, create_app, load_token, public_job
from sources import Gathered

TOKEN = "test-token-with-at-least-thirty-two-characters"
HEADERS = {"Authorization": "Bearer " + TOKEN}
BODY = {"kind": "chapter", "title": "A Chapter", "text": "Source material", "engine": "local", "length": "short"}


class FakeNotebook:
    def __init__(self, logged=False, error=None):
        self.logged_in = AsyncMock(return_value=logged)
        self.generate = AsyncMock(side_effect=error)


class FakeLocal:
    async def generate(self, request, gathered, output, stage, fallback=False):
        await stage("writing", 25, "Writing")
        await stage("generating", 60, "Generating")
        output.write_bytes(b"ID3" + b"0" * 1000)
        return "Alex: made with the local engine\nSam: hello" if fallback else "Alex: hello\nSam: hello"


def make_app(tmp_path, **kwargs):
    return create_app(data_dir=tmp_path, token=TOKEN, notebook=kwargs.pop("notebook", FakeNotebook()), local=kwargs.pop("local", FakeLocal()), gatherer=kwargs.pop("gatherer", AsyncMock(return_value=Gathered("Source", [{"title": "Source"}]))), probe=AsyncMock(return_value=8), **kwargs)


def wait(client):
    client.portal.call(client.app.state.manager.queue.join)


def test_health_open_and_other_routes_auth(tmp_path):
    with TestClient(make_app(tmp_path)) as client:
        health = client.get("/v1/health")
        assert health.status_code == 200
        assert health.json()["engines"]["notebooklm"]["loggedIn"] is False
        for method, path in [("GET", "/v1/podcasts"), ("POST", "/v1/podcasts"), ("GET", "/docs"), ("GET", "/openapi.json"), ("HEAD", "/v1/health"), ("GET", "/v1/podcasts/unknown/audio"), ("DELETE", "/v1/podcasts/unknown")]:
            assert client.request(method, path).status_code == 401
            assert client.request(method, path, headers={"Authorization": "Bearer wrong"}).status_code == 401
        assert client.get("/v1/podcasts", headers=HEADERS).status_code == 200


@pytest.mark.parametrize("body,message", [
    ({"kind": "chapter", "title": "T"}, "chapter needs"),
    ({"kind": "chapter", "title": "T", "text": "  "}, "chapter needs"),
    ({"kind": "chapter", "title": "T", "text": "x" * 400001}, "400000"),
    ({"kind": "article", "title": "T", "url": "http://example.com"}, "public HTTPS"),
    ({"kind": "article", "title": "T"}, "article needs"),
    ({"kind": "book", "title": "T"}, "book needs"),
    ({"kind": "topic", "title": "T", "query": "x" * 301}, "300"),
    ({"kind": "topic", "title": "T", "query": " "}, "topic needs"),
    ({"kind": "wrong", "title": "T"}, "kind"),
    (dict(BODY, engine="wrong"), "engine"),
    (dict(BODY, style="wrong"), "style"),
    (dict(BODY, length="wrong"), "length"),
    (dict(BODY, language="es"), "language"),
    (dict(BODY, gutenbergId="../11"), "numeric"),
    (dict(BODY, title="  "), "title"),
])
def test_validation_human_messages(tmp_path, body, message):
    with TestClient(make_app(tmp_path)) as client:
        response = client.post("/v1/podcasts", json=body, headers=HEADERS)
        assert response.status_code == 422
        assert message in response.json()["detail"]
        assert "input" not in response.json()


def test_full_api_and_range(tmp_path):
    with TestClient(make_app(tmp_path)) as client:
        response = client.post("/v1/podcasts", json=BODY, headers=HEADERS)
        assert response.status_code == 202
        assert response.json()["status"] == "queued"
        job_id = response.json()["id"]
        wait(client)
        job = client.get("/v1/podcasts/" + job_id, headers=HEADERS).json()
        assert job["status"] == "ready" and job["progress"] == 100 and job["durationSec"] == 8
        assert "transcript" in job and "_request" not in job
        assert set(job) == {"id", "status", "progress", "stage", "engine", "title", "kind", "style", "durationSec", "createdAt", "transcript", "sources"}
        assert "transcript" not in client.get("/v1/podcasts", headers=HEADERS).json()["items"][0]
        base = "/v1/podcasts/" + job_id + "/audio"
        whole = client.get(base, headers=HEADERS)
        assert whole.status_code == 200 and whole.headers["content-type"] == "audio/mpeg"
        assert int(whole.headers["content-length"]) == 1003
        for value, expected in [("bytes=0-9", whole.content[:10]), ("bytes=-10", whole.content[-10:]), ("bytes=1000-", whole.content[1000:])]:
            part = client.get(base, headers=dict(HEADERS, Range=value))
            assert part.status_code == 206 and part.content == expected
            assert int(part.headers["content-length"]) == len(expected)
            assert part.headers["content-range"].startswith("bytes ")
        assert client.get(base, headers=dict(HEADERS, Range="bytes=9999-10000")).status_code == 416
        assert client.delete("/v1/podcasts/" + job_id, headers=HEADERS).status_code == 204
        assert not (tmp_path / (job_id + ".mp3")).exists()
        assert client.get("/v1/podcasts/" + job_id, headers=HEADERS).status_code == 404


@pytest.mark.parametrize("error", [NotebookAuthError("NotebookLM needs a fresh sign-in"), NotebookQuotaError("quota")])
def test_auto_fallback(tmp_path, error):
    with TestClient(make_app(tmp_path, notebook=FakeNotebook(logged=True, error=error))) as client:
        job_id = client.post("/v1/podcasts", json=dict(BODY, engine="auto"), headers=HEADERS).json()["id"]
        wait(client)
        job = client.get("/v1/podcasts/" + job_id, headers=HEADERS).json()
        assert job["status"] == "ready" and job["engine"] == "local"
        assert "made with the local engine" in job["transcript"]


def test_explicit_notebook_auth_failure(tmp_path):
    with TestClient(make_app(tmp_path, notebook=FakeNotebook(error=NotebookAuthError("NotebookLM needs a fresh sign-in")))) as client:
        job_id = client.post("/v1/podcasts", json=dict(BODY, engine="notebooklm"), headers=HEADERS).json()["id"]
        wait(client)
        job = client.get("/v1/podcasts/" + job_id, headers=HEADERS).json()
        assert job["status"] == "failed" and job["error"] == "NotebookLM needs a fresh sign-in"
        assert client.get("/v1/podcasts/" + job_id + "/audio", headers=HEADERS).status_code == 409


def test_auto_logged_out_uses_local(tmp_path):
    nb = FakeNotebook()
    with TestClient(make_app(tmp_path, notebook=nb)) as client:
        job_id = client.post("/v1/podcasts", json=dict(BODY, engine="auto"), headers=HEADERS).json()["id"]
        wait(client)
        assert client.get("/v1/podcasts/" + job_id, headers=HEADERS).json()["engine"] == "local"
        nb.generate.assert_not_called()


def test_notebook_omits_transcript(tmp_path):
    nb = FakeNotebook(logged=True)
    async def generate(*args):
        args[-1].write_bytes(b"ID3")
    nb.generate.side_effect = generate
    with TestClient(make_app(tmp_path, notebook=nb)) as client:
        job_id = client.post("/v1/podcasts", json=dict(BODY, engine="notebooklm"), headers=HEADERS).json()["id"]
        wait(client)
        job = client.get("/v1/podcasts/" + job_id, headers=HEADERS).json()
        assert job["status"] == "ready" and "transcript" not in job


def test_rate_limit(tmp_path):
    with TestClient(make_app(tmp_path, rate_limit=3)) as client:
        assert [client.get("/v1/health").status_code for _ in range(4)] == [200, 200, 200, 429]


def test_body_cap(tmp_path):
    with TestClient(make_app(tmp_path)) as client:
        assert client.post("/v1/podcasts", content=b"x" * (3 * 1024 * 1024 + 1), headers=HEADERS).status_code == 413


def test_token_stable_private(tmp_path):
    path = tmp_path / "secure/token"
    first = load_token(path)
    assert first == load_token(path) and len(first) >= 32
    assert path.stat().st_mode & 0o777 == 0o600


def test_token_symlink_refused(tmp_path):
    target = tmp_path / "target"
    target.write_text(TOKEN)
    path = tmp_path / "symlink"
    path.symlink_to(target)
    with pytest.raises(OSError):
        load_token(path)


def make_manager(tmp_path, **kwargs):
    return JobManager(tmp_path, notebook=FakeNotebook(), local=kwargs.pop("local", FakeLocal()), gatherer=AsyncMock(return_value=Gathered("source", [])), probe=AsyncMock(return_value=1), **kwargs)


def test_concurrency_two_and_queue(tmp_path):
    async def run():
        gate = asyncio.Event()
        count, maximum = 0, 0
        class SlowLocal(FakeLocal):
            async def generate(self, *args, **kwargs):
                nonlocal count, maximum
                count += 1
                maximum = max(maximum, count)
                await gate.wait()
                result = await super().generate(*args, **kwargs)
                count -= 1
                return result
        manager = make_manager(tmp_path, local=SlowLocal())
        await manager.start()
        try:
            jobs = [await manager.enqueue(PodcastRequest(**BODY)) for _ in range(5)]
            for _ in range(100):
                if count == 2:
                    break
                await asyncio.sleep(.001)
            assert count == 2
            assert sum(job["status"] == "queued" for job in manager.all()) == 3
            assert manager.get(jobs[-1]["id"])["status"] == "queued"
            gate.set()
            await asyncio.wait_for(manager.queue.join(), 2)
            assert maximum == 2 and all(job["status"] == "ready" for job in manager.all())
        finally:
            await manager.close()
    asyncio.run(run())


def test_restart_recovery(tmp_path):
    async def run():
        manager = make_manager(tmp_path)
        job = await manager.enqueue(PodcastRequest(**BODY))
        manager.update(job["id"], "sourcing", progress=10)
        manager.audio_path(job["id"], True).write_bytes(b"partial")
        manager.audio_path(job["id"]).write_bytes(b"renamed before crash")
        manager.db.close()
        restarted = make_manager(tmp_path)
        await restarted.start()
        try:
            recovered = restarted.get(job["id"])
            assert recovered["status"] == "failed" and recovered["error"] == "restarted"
            assert "_request" not in recovered
            assert not restarted.audio_path(job["id"], True).exists()
            assert not restarted.audio_path(job["id"]).exists()
            with pytest.raises(ValueError):
                restarted.update(job["id"], "ready")
        finally:
            await restarted.close()
    asyncio.run(run())


def test_retention_and_cap(tmp_path):
    async def run():
        manager = make_manager(tmp_path)
        await manager.start()
        try:
            old = await manager.enqueue(PodcastRequest(**BODY))
            manager.update(old["id"], "failed", error="old", createdAt=(datetime.now(timezone.utc) - timedelta(days=31)).isoformat())
            manager.audio_path(old["id"]).write_bytes(b"audio")
            await manager.prune()
            with pytest.raises(HTTPException):
                manager.get(old["id"])
            assert not manager.audio_path(old["id"]).exists()
            for _ in range(51):
                job = await manager.enqueue(PodcastRequest(**BODY))
                manager.update(job["id"], "failed", error="test")
            assert len(manager.all()) == 50
            assert manager.all()[0]["id"] == job["id"]
        finally:
            await manager.close()
    asyncio.run(run())


def test_full_active_queue_rejected(tmp_path):
    async def run():
        manager = make_manager(tmp_path)
        for _ in range(50):
            await manager.enqueue(PodcastRequest(**BODY))
        with pytest.raises(HTTPException) as exc:
            await manager.enqueue(PodcastRequest(**BODY))
        assert exc.value.status_code == 429 and len(manager.all()) == 50
        manager.db.close()
    asyncio.run(run())


def test_delete_active_keeps_workers_alive(tmp_path):
    async def run():
        gate = asyncio.Event()
        class SlowLocal(FakeLocal):
            async def generate(self, *args, **kwargs):
                await gate.wait()
                return await super().generate(*args, **kwargs)
        manager = make_manager(tmp_path, local=SlowLocal())
        await manager.start()
        try:
            job = await manager.enqueue(PodcastRequest(**BODY))
            while job["id"] not in manager.active:
                await asyncio.sleep(.001)
            await manager.delete(job["id"])
            gate.set()
            next_job = await manager.enqueue(PodcastRequest(**BODY))
            await asyncio.wait_for(manager.queue.join(), 2)
            assert manager.get(next_job["id"])["status"] == "ready"
            assert all(not worker.done() for worker in manager.workers)
        finally:
            await manager.close()
    asyncio.run(run())


def test_unknown_exception_never_exposes_secrets(tmp_path):
    gatherer = AsyncMock(side_effect=RuntimeError("private-cookie-and-source-must-not-leak"))
    with TestClient(make_app(tmp_path, gatherer=gatherer)) as client:
        job_id = client.post("/v1/podcasts", json=BODY, headers=HEADERS).json()["id"]
        wait(client)
        job = client.get("/v1/podcasts/" + job_id, headers=HEADERS).json()
        assert job["status"] == "failed"
        assert "private-cookie" not in job["error"]


def test_parallel_submissions_keep_cap(tmp_path):
    async def run():
        manager = make_manager(tmp_path)
        try:
            results = await asyncio.gather(*(manager.enqueue(PodcastRequest(**BODY)) for _ in range(60)), return_exceptions=True)
            assert len(manager.all()) == 50
            assert sum(isinstance(result, HTTPException) for result in results) == 10
        finally:
            manager.db.close()
    asyncio.run(run())


def test_single_process_lock(tmp_path):
    async def run():
        first, second = make_manager(tmp_path), make_manager(tmp_path)
        await first.start()
        try:
            with pytest.raises(RuntimeError, match="Only one"):
                await second.start()
        finally:
            second.db.close()
            await first.close()
    asyncio.run(run())
