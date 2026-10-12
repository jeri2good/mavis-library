import asyncio
import hashlib
import hmac
import json
import logging
import sqlite3
from urllib.parse import urlencode

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

import service
from test_service import BODY, HEADERS, TOKEN, FakeLocal, FakeNotebook, make_app, make_manager, wait

SECRET = "test-shared-secret-not-for-production-123456789"
USER_A = "11111111-1111-4111-8111-111111111111"
USER_B = "22222222-2222-4222-9222-222222222222"
NOW = 1800000000


def gateway(uid=USER_A, secret=SECRET):
    return {"X-Studio-Key": secret, "X-Mavis-User": uid}


def signature(job_id, exp, uid, secret=SECRET):
    return hmac.new(secret.encode(), f"{job_id}.{exp}.{uid}".encode(), hashlib.sha256).hexdigest()


def signed_url(job_id, uid=USER_A, exp=None, secret=SECRET):
    exp = str(exp or int(service.time.time()) + 600)
    return f"/v1/podcasts/{job_id}/audio?" + urlencode({"exp": exp, "uid": uid, "sig": signature(job_id, exp, uid, secret)})


def ready_job(client, headers=None):
    response = client.post("/v1/podcasts", json=BODY, headers=headers or gateway())
    assert response.status_code == 202
    wait(client)
    return response.json()["id"]


def test_gateway_auth_and_health(tmp_path):
    with TestClient(make_app(tmp_path)) as client:
        assert client.get("/v1/health").json() == {"ok": True}
        for headers in [{}, {"X-Mavis-User": USER_A}, gateway(secret="wrong"), {"Authorization": "Bearer wrong"}]:
            assert client.get("/v1/podcasts", headers=headers).status_code == 401
        assert client.get("/v1/podcasts", headers=gateway()).status_code == 200
        assert client.get("/v1/podcasts", headers=HEADERS).status_code == 200
        assert client.get("/v1/podcasts", headers=dict(HEADERS, **gateway(secret="wrong"))).status_code == 401


@pytest.mark.parametrize("uid", ["", "OWNER", "user", "../owner", USER_A + " ", "11111111-1111-1111-8111-111111111111", "11111111-1111-4111-7111-111111111111"])
def test_invalid_gateway_uid(tmp_path, uid):
    with TestClient(make_app(tmp_path)) as client:
        assert client.get("/v1/podcasts", headers=gateway(uid)).status_code == 401
        assert client.post("/v1/podcasts", json=BODY, headers=gateway(uid)).status_code == 401


def test_missing_or_duplicate_identity_headers(tmp_path):
    with TestClient(make_app(tmp_path)) as client:
        assert client.get("/v1/podcasts", headers={"X-Studio-Key": SECRET}).status_code == 401
        for name, value in [("X-Studio-Key", SECRET), ("X-Mavis-User", USER_A), ("Authorization", "Bearer " + TOKEN)]:
            headers = list((HEADERS if name == "Authorization" else gateway()).items()) + [(name, value)]
            assert client.get("/v1/podcasts", headers=headers).status_code == 401


def test_ownership_isolation_and_owner_access(tmp_path):
    with TestClient(make_app(tmp_path)) as client:
        a = ready_job(client)
        b = ready_job(client, gateway(USER_B))
        owner = ready_job(client, HEADERS)
        assert client.portal.call(client.app.state.manager.get, a)["owner_uid"] == USER_A
        assert client.portal.call(client.app.state.manager.get, owner)["owner_uid"] == "owner"
        assert [j["id"] for j in client.get("/v1/podcasts", headers=gateway()).json()["items"]] == [a]
        assert [j["id"] for j in client.get("/v1/podcasts", headers=gateway(USER_B)).json()["items"]] == [b]
        assert {j["id"] for j in client.get("/v1/podcasts", headers=HEADERS).json()["items"]} == {a, b, owner}
        assert [j["id"] for j in client.get("/v1/podcasts", headers=gateway("owner")).json()["items"]] == [owner]
        for method, suffix in [("GET", ""), ("DELETE", ""), ("GET", "/audio")]:
            assert client.request(method, f"/v1/podcasts/{a}{suffix}", headers=gateway(USER_B)).status_code == 404
        assert client.get(f"/v1/podcasts/{a}", headers=HEADERS).status_code == 200
        assert client.get(f"/v1/podcasts/{a}/audio", headers=HEADERS).status_code == 200
        response = client.delete(f"/v1/podcasts/{a}", headers=HEADERS)
        assert response.status_code == 204 and response.content == b""
        assert client.get(signed_url(a)).status_code == 404


def test_published_signature_vector(monkeypatch):
    exp = "1800000600"
    expected = "92eff03c6dda51ba238460e571ec6c47632852c48812f32afbc67167c3dde4ce"
    assert signature("job-vector", exp, "owner", "vector-secret") == expected
    assert service.verify_audio("vector-secret", "job-vector", exp, "owner", expected, 1800000000000)
    for args in [
        ("wrong", "job-vector", exp, "owner", expected),
        ("vector-secret", "other-id", exp, "owner", expected),
        ("vector-secret", "job-vector", "1800000599", "owner", expected),
        ("vector-secret", "job-vector", exp, USER_A, expected),
        ("vector-secret", "job-vector", exp, "owner", "0" * 64),
    ]:
        assert not service.verify_audio(*args, 1800000000000)


@pytest.mark.parametrize("exp", [NOW, NOW - 1, NOW + 601, NOW * 1000, "01800000600", "+1800000600", "1800000600.0", "1800000600e0", "١٨٠٠٠٠٠٦٠٠"])
def test_signature_expiration_rules(exp):
    sig = signature("job-vector", exp, "owner")
    assert not service.verify_audio(SECRET, "job-vector", str(exp), "owner", sig, NOW * 1000)


@pytest.mark.parametrize("change", ["id", "exp", "uid", "secret", "sig", "uppercase", "expired", "too-far", "wrong-owner", "invalid-uid", "duplicate-exp", "duplicate-uid", "duplicate-sig"])
def test_signed_audio_rejections(tmp_path, monkeypatch, change):
    monkeypatch.setattr(service.time, "time", lambda: NOW)
    with TestClient(make_app(tmp_path)) as client:
        job_id = ready_job(client)
        exp, uid, secret = str(NOW + 600), USER_A, SECRET
        if change == "expired":
            exp = str(NOW)
        if change == "too-far":
            exp = str(NOW + 601)
        if change == "wrong-owner":
            uid = USER_B
        if change == "invalid-uid":
            uid = "not-a-user"
        if change == "secret":
            secret = "wrong"
        sig = signature(job_id, exp, uid, secret)
        if change == "id":
            job_id = "other-id"
        if change == "exp":
            exp = str(NOW + 599)
        if change == "uid":
            uid = USER_B
        if change == "sig":
            sig = "0" * 64
        if change == "uppercase":
            sig = sig.upper()
        params = [("exp", exp), ("uid", uid), ("sig", sig)]
        if change.startswith("duplicate-"):
            key = change.removeprefix("duplicate-")
            params.append((key, dict(params)[key]))
        url = f"/v1/podcasts/{job_id}/audio?" + urlencode(params)
        assert client.get(url).status_code == (404 if change == "wrong-owner" else 401)
        assert client.get(url, headers=HEADERS).status_code == (404 if change == "wrong-owner" else 401)


def test_signed_audio_only_range_and_owner_capability(tmp_path, monkeypatch):
    monkeypatch.setattr(service.time, "time", lambda: NOW)
    with TestClient(make_app(tmp_path)) as client:
        job_id = ready_job(client)
        url = signed_url(job_id)
        whole = client.get(url)
        assert whole.status_code == 200
        assert whole.headers["cache-control"] == "private, no-store"
        assert whole.headers["accept-ranges"] == "bytes"
        assert int(whole.headers["content-length"]) == 1003
        part = client.get(url, headers={"Range": "bytes=0-9"})
        assert part.status_code == 206 and part.content == whole.content[:10]
        assert part.headers["content-length"] == "10"
        assert part.headers["cache-control"] == "private, no-store"
        invalid = client.get(url, headers={"Range": "bytes=9999-"})
        assert invalid.status_code == 416 and invalid.headers["cache-control"] == "private, no-store"
        assert invalid.headers["content-range"] == "bytes */1003"
        invalid = client.get(url, headers={"Range": "nonsense"})
        assert invalid.status_code == 400 and invalid.headers["cache-control"] == "private, no-store"
        multipart = client.get(url, headers={"Range": "bytes=0-2,10-12"})
        assert multipart.status_code == 206
        assert multipart.headers["content-type"].startswith("multipart/byteranges;")
        assert len(multipart.content) == int(multipart.headers["content-length"])
        assert multipart.headers["cache-control"] == "private, no-store"
        assert client.get(signed_url(job_id, "owner")).status_code == 200
        query = url.split("?", 1)[1]
        assert client.get(f"/v1/podcasts/{job_id}?{query}").status_code == 401
        assert client.delete(f"/v1/podcasts/{job_id}?{query}").status_code == 401
        for param in ["exp", "uid", "sig"]:
            params = dict(exp=str(NOW + 600), uid=USER_A, sig=signature(job_id, NOW + 600, USER_A))
            del params[param]
            assert client.get(f"/v1/podcasts/{job_id}/audio?" + urlencode(params)).status_code == 401


def test_hourly_uid_limit_survives_deletion_and_restart(tmp_path):
    app = make_app(tmp_path)
    with TestClient(app) as client:
        for _ in range(6):
            job_id = ready_job(client)
            assert client.delete(f"/v1/podcasts/{job_id}", headers=gateway()).status_code == 204
        response = client.post("/v1/podcasts", json=BODY, headers=gateway())
        assert response.status_code == 429 and response.headers["retry-after"]
        assert client.post("/v1/podcasts", json=BODY, headers=gateway(USER_B)).status_code == 202
        for _ in range(7):
            ready_job(client, HEADERS)
    with TestClient(make_app(tmp_path)) as client:
        assert client.post("/v1/podcasts", json=BODY, headers=gateway()).status_code == 429


def test_global_create_limit_and_recovery(tmp_path, monkeypatch):
    clock = [NOW]
    monkeypatch.setattr(service.time, "time", lambda: clock[0])
    with TestClient(make_app(tmp_path, global_create_limit=2)) as client:
        ready_job(client)
        ready_job(client, HEADERS)
        assert client.post("/v1/podcasts", json=BODY, headers=gateway(USER_B)).status_code == 429
        clock[0] += 3600
        assert client.post("/v1/podcasts", json=BODY, headers=gateway()).status_code == 202


def test_per_uid_queue_concurrency_and_racing_creates(tmp_path):
    async def run():
        gate = asyncio.Event()
        class SlowLocal(FakeLocal):
            async def generate(self, *args, **kwargs):
                await gate.wait()
                return await super().generate(*args, **kwargs)
        manager = make_manager(tmp_path, local=SlowLocal())
        await manager.start()
        try:
            for _ in range(2):
                await manager.enqueue(service.PodcastRequest(**BODY), USER_A)
            for _ in range(100):
                if len(manager.active) == 2:
                    break
                await asyncio.sleep(.001)
            assert len(manager.active) == 2
            results = await asyncio.gather(*(manager.enqueue(service.PodcastRequest(**BODY), USER_A) for _ in range(8)), return_exceptions=True)
            assert sum(isinstance(r, HTTPException) for r in results) == 5
            assert sum(j["status"] == "queued" for j in manager.all() if j["owner_uid"] == USER_A) == 3
            assert len(manager.active) == 2
            await manager.enqueue(service.PodcastRequest(**BODY), USER_B)
            gate.set()
            await asyncio.wait_for(manager.queue.join(), 2)
            assert all(j["status"] == "ready" for j in manager.all())
            await manager.enqueue(service.PodcastRequest(**BODY), USER_A)
            await asyncio.wait_for(manager.queue.join(), 2)
            with pytest.raises(HTTPException) as exc:
                await manager.enqueue(service.PodcastRequest(**BODY), USER_A)
            assert exc.value.status_code == 429
        finally:
            await manager.close()
    asyncio.run(run())


@pytest.mark.parametrize("body,size", [(dict(BODY, text="x" * 400000), 450_000), ({"kind": "topic", "title": "T", "query": "x"}, 1_000_000)])
def test_create_byte_caps(tmp_path, body, size):
    with TestClient(make_app(tmp_path)) as client:
        raw = json.dumps(body).encode()
        raw += b" " * (size + 1 - len(raw))
        assert client.post("/v1/podcasts", content=raw, headers=dict(gateway(), **{"Content-Type": "application/json"})).status_code == 413
        streamed = (raw[i:i + 8192] for i in range(0, len(raw), 8192))
        assert client.post("/v1/podcasts", content=streamed, headers=dict(gateway(), **{"Content-Type": "application/json"})).status_code == 413
        assert not client.portal.call(client.app.state.manager.all)


def test_old_job_migration_persists(tmp_path):
    manager = make_manager(tmp_path)
    job = asyncio.run(manager.enqueue(service.PodcastRequest(**BODY)))
    del job["owner_uid"]
    manager.save(job)
    manager.db.close()
    with TestClient(make_app(tmp_path)) as client:
        assert client.portal.call(client.app.state.manager.get, job["id"])["owner_uid"] == "owner"
        assert client.get(f"/v1/podcasts/{job['id']}", headers=gateway()).status_code == 404
        assert client.get(f"/v1/podcasts/{job['id']}", headers=HEADERS).status_code == 200
    with sqlite3.connect(tmp_path / "jobs.sqlite") as db:
        assert json.loads(db.execute("SELECT record FROM jobs").fetchone()[0])["owner_uid"] == "owner"


def test_secret_loader_env_file_and_permissions(tmp_path, monkeypatch):
    path = tmp_path / "secure/shared-secret"
    assert service.load_shared_secret(path) == SECRET
    assert not path.exists()
    monkeypatch.delenv("STUDIO_SHARED_SECRET")
    first = service.load_shared_secret(path)
    assert len(first) >= 32 and service.load_shared_secret(path) == first
    assert path.stat().st_mode & 0o777 == 0o600
    path.chmod(0o644)
    assert service.load_shared_secret(path) == first
    assert path.stat().st_mode & 0o777 == 0o600
    link = tmp_path / "link"
    link.symlink_to(path)
    with pytest.raises(OSError):
        service.load_shared_secret(link)


def test_path_prefix_and_forwarded_headers_do_not_authorize(tmp_path):
    with TestClient(make_app(tmp_path), root_path="/studio") as client:
        assert client.get("/studio/v1/health").json() == {"ok": True}
        job_id = client.post("/studio/v1/podcasts", json=BODY, headers=gateway()).json()["id"]
        wait(client)
        assert client.get("/studio/v1/podcasts", headers=gateway()).status_code == 200
        assert client.get("/studio" + signed_url(job_id), headers={"X-Forwarded-Host": "untrusted.example", "X-Forwarded-Proto": "https"}).status_code == 200
        assert client.get("/studio/v1/podcasts", headers={"X-Forwarded-User": "owner", "X-Forwarded-Prefix": "/studio"}).status_code == 401
    with TestClient(make_app(tmp_path)) as client:
        assert client.get("/studio/v1/health").status_code == 200
        assert client.get("/studio/v1/podcasts", headers=gateway()).status_code == 200


def test_secret_and_uid_never_logged_or_credential_exposed(tmp_path, caplog):
    caplog.set_level(logging.INFO, logger="podcast")
    with TestClient(make_app(tmp_path)) as client:
        job_id = ready_job(client)
        responses = [client.get("/v1/health"), client.get("/v1/podcasts", headers=gateway()), client.get(f"/v1/podcasts/{job_id}", headers=gateway()), client.get(signed_url(job_id)), client.get("/v1/podcasts", headers=gateway(secret="wrong")), client.post("/v1/podcasts", json={SECRET: TOKEN}, headers=gateway())]
        for response in responses:
            assert SECRET not in response.text and TOKEN not in response.text
        assert "owner_uid" not in responses[1].text
    assert SECRET not in caplog.text and TOKEN not in caplog.text
    records = [r.getMessage() for r in caplog.records if r.name == "podcast"]
    assert records
    assert SECRET not in "\n".join(records) and TOKEN not in "\n".join(records)
    assert USER_A not in "\n".join(records)
    assert all("sig=" not in message for message in records)
    assert any(json.loads(message).get("uidHash") == hashlib.sha256(USER_A.encode()).hexdigest()[:8] for message in records)


def test_published_vector_on_audio_route(tmp_path, monkeypatch):
    monkeypatch.setattr(service.time, "time", lambda: NOW)
    with TestClient(make_app(tmp_path, shared_secret="vector-secret")) as client:
        original_id = ready_job(client, HEADERS)
        job = client.portal.call(client.app.state.manager.get, original_id)
        job["id"] = "job-vector"
        client.portal.call(client.app.state.manager.save, job)
        (tmp_path / "job-vector.mp3").write_bytes(b"ID3-vector-audio")
        url = "/v1/podcasts/job-vector/audio?exp=1800000600&uid=owner&sig=92eff03c6dda51ba238460e571ec6c47632852c48812f32afbc67167c3dde4ce"
        response = client.get(url, headers={"Range": "bytes=0-2"})
        assert response.status_code == 206 and response.content == b"ID3"


@pytest.mark.parametrize("body,size", [(dict(BODY, text="x" * 400000), 450_000), ({"kind": "topic", "title": "T", "query": "x", "engine": "local"}, 1_000_000)])
def test_create_exact_byte_cap_accepted(tmp_path, body, size):
    with TestClient(make_app(tmp_path)) as client:
        raw = json.dumps(body).encode()
        raw += b" " * (size - len(raw))
        assert len(raw) == size
        assert client.post("/v1/podcasts", content=raw, headers=dict(gateway(), **{"Content-Type": "application/json"})).status_code == 202
        wait(client)


def test_multibyte_text_byte_cap(tmp_path):
    with TestClient(make_app(tmp_path)) as client:
        raw = json.dumps(dict(BODY, text="é" * 300000), ensure_ascii=False).encode()
        assert len(raw) > 450_000
        assert client.post("/v1/podcasts", content=raw, headers=dict(gateway(), **{"Content-Type": "application/json"})).status_code == 413


def test_hourly_boundary_and_invalid_requests_not_charged(tmp_path, monkeypatch):
    clock = [NOW]
    monkeypatch.setattr(service.time, "time", lambda: clock[0])
    with TestClient(make_app(tmp_path)) as client:
        for _ in range(6):
            assert client.post("/v1/podcasts", json=dict(BODY, owner_uid=USER_B), headers=gateway()).status_code == 422
            ready_job(client)
        assert client.post("/v1/podcasts", json=BODY, headers=gateway()).status_code == 429
        clock[0] += 3599
        assert client.post("/v1/podcasts", json=BODY, headers=gateway()).status_code == 429
        clock[0] += 1
        ready_job(client)
        events = client.portal.call(lambda: list(client.app.state.manager.db.execute("SELECT uid_hash FROM create_events")))
        assert events == [(hashlib.sha256(USER_A.encode()).hexdigest(),)]


def test_public_owner_queue_is_bounded_but_bearer_legacy_queue_remains(tmp_path):
    async def run():
        manager = make_manager(tmp_path)
        try:
            for _ in range(3):
                await manager.enqueue(service.PodcastRequest(**BODY), "owner", public=True)
            with pytest.raises(HTTPException) as exc:
                await manager.enqueue(service.PodcastRequest(**BODY), "owner", public=True)
            assert exc.value.status_code == 429
            await manager.enqueue(service.PodcastRequest(**BODY))
            assert len(manager.all()) == 4
        finally:
            manager.db.close()
    asyncio.run(run())


def test_global_limit_atomic_and_durable(tmp_path):
    async def run():
        manager = make_manager(tmp_path, global_create_limit=2)
        try:
            results = await asyncio.gather(*(manager.enqueue(service.PodcastRequest(**BODY)) for _ in range(10)), return_exceptions=True)
            assert sum(isinstance(r, HTTPException) for r in results) == 8
            assert len(manager.all()) == 2
        finally:
            manager.db.close()
        restarted = make_manager(tmp_path, global_create_limit=2)
        try:
            with pytest.raises(HTTPException) as exc:
                await restarted.enqueue(service.PodcastRequest(**BODY), USER_B)
            assert exc.value.status_code == 429
        finally:
            restarted.db.close()
    asyncio.run(run())


def test_signed_audio_not_ready_still_checks_ownership(tmp_path):
    async def blocked(request):
        await asyncio.Event().wait()
    with TestClient(make_app(tmp_path, gatherer=blocked)) as client:
        response = client.post("/v1/podcasts", json=BODY, headers=gateway())
        job_id = response.json()["id"]
        assert client.get(signed_url(job_id)).status_code == 409
        assert client.get(signed_url(job_id, USER_B)).status_code == 404
        assert client.delete(f"/v1/podcasts/{job_id}", headers=gateway()).status_code == 204
        assert all(not task.done() for task in client.app.state.manager.workers)


def test_audio_cors_exact_origin_and_preflight(tmp_path, monkeypatch):
    monkeypatch.setenv("STUDIO_AUDIO_ORIGINS", "https://player.example, https://owner.example")
    with TestClient(make_app(tmp_path)) as client:
        job_id = ready_job(client)
        url = signed_url(job_id)
        headers = {"Origin": "https://player.example", "Range": "bytes=0-2"}
        response = client.get(url, headers=headers)
        assert response.status_code == 206
        assert response.headers["access-control-allow-origin"] == "https://player.example"
        assert "Origin" in response.headers["vary"]
        assert "Content-Range" in response.headers["access-control-expose-headers"]
        assert "access-control-allow-credentials" not in response.headers
        assert "access-control-allow-origin" not in client.get(url, headers={"Origin": "https://evil.example"}).headers
        preflight = {"Origin": "https://player.example", "Access-Control-Request-Method": "GET", "Access-Control-Request-Headers": "Range"}
        response = client.options(url, headers=preflight)
        assert response.status_code == 200 and response.content == b"OK"
        assert response.headers["access-control-allow-origin"] == "https://player.example"
        assert client.options(url, headers=dict(preflight, **{"Origin": "https://evil.example"})).status_code == 400
        assert client.options(url, headers=dict(preflight, **{"Access-Control-Request-Method": "DELETE"})).status_code == 400
        assert client.options(url, headers=dict(preflight, **{"Access-Control-Request-Headers": "Authorization"})).status_code == 400
        assert client.options("/v1/podcasts", headers=preflight).status_code == 401
        assert "access-control-allow-origin" not in client.get("/v1/podcasts", headers=dict(gateway(), Origin="https://player.example")).headers
    with TestClient(make_app(tmp_path, audio_origins=())) as client:
        assert client.options(url, headers=preflight).status_code == 401


@pytest.mark.parametrize("origin", ["*", "https://*.example", "https://player.example/", "https://user:password@player.example", "https://player.example?query=x", "https://player.example\n"])
def test_invalid_audio_origin_configuration(tmp_path, origin):
    with pytest.raises(RuntimeError, match="exact HTTP"):
        with TestClient(make_app(tmp_path, audio_origins=[origin])):
            pass


def test_no_absolute_redirects_for_prefix_or_trailing_slash(tmp_path):
    with TestClient(make_app(tmp_path)) as client:
        response = client.get("/studio/v1/podcasts/", headers=dict(gateway(), **{"X-Forwarded-Host": "spoofed.example", "X-Forwarded-Proto": "https"}), follow_redirects=False)
        assert response.status_code == 404 and "location" not in response.headers


def test_bearer_uid_header_cannot_change_owner(tmp_path):
    with TestClient(make_app(tmp_path)) as client:
        job_id = ready_job(client, dict(HEADERS, **{"X-Mavis-User": USER_A}))
        assert client.portal.call(client.app.state.manager.get, job_id)["owner_uid"] == "owner"
        assert client.get(f"/v1/podcasts/{job_id}", headers=gateway()).status_code == 404


def test_health_never_contacts_provider_or_reveals_login(tmp_path):
    notebook = FakeNotebook(logged=True)
    with TestClient(make_app(tmp_path, notebook=notebook)) as client:
        assert client.get("/v1/health").json() == {"ok": True}
        notebook.logged_in.assert_not_called()


def test_empty_secret_env_refused_without_file_creation(tmp_path, monkeypatch):
    monkeypatch.setenv("STUDIO_SHARED_SECRET", "")
    path = tmp_path / "missing-secret"
    with pytest.raises(RuntimeError, match="must not be empty"):
        service.load_shared_secret(path)
    assert not path.exists()


def test_generation_error_with_credentials_is_sanitized(tmp_path, caplog):
    async def fail(request):
        raise RuntimeError(SECRET + TOKEN + USER_A)
    caplog.set_level(logging.INFO, logger="podcast")
    with TestClient(make_app(tmp_path, gatherer=fail)) as client:
        job_id = ready_job(client)
        response = client.get(f"/v1/podcasts/{job_id}", headers=gateway())
        assert response.json()["status"] == "failed"
        assert SECRET not in response.text and TOKEN not in response.text and USER_A not in response.text
    assert SECRET not in caplog.text and TOKEN not in caplog.text
    assert USER_A not in "\n".join(r.getMessage() for r in caplog.records if r.name == "podcast")
