import asyncio
import uuid

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

from cloud_state import StateStore, content_hash
from service import PodcastRequest, create_app
from sources import Gathered


@pytest.fixture
def store(tmp_path):
    audio = tmp_path / 'audio'
    audio.mkdir()
    instance = StateStore(tmp_path / 'state', audio)
    yield instance
    instance.db.close()


def payload(**changes):
    return {'kind': 'chapter', 'title': 'Chapter', 'text': 'Evidence', **changes}


def test_state_owner_and_public_contract(store):
    uid = str(uuid.uuid4())
    job = store.admit(payload(engine='notebooklm'), uid, True)
    assert job['status'] == 'queued'
    assert job['engine'] == 'local'
    assert store.get(job['id'], uid)['owner_uid'] == uid
    with pytest.raises(HTTPException) as exc:
        store.get(job['id'], str(uuid.uuid4()))
    assert exc.value.status_code == 404
    assert store.claim(job['id'])['status'] == 'sourcing'
    assert store.claim(job['id']) is None


def test_state_admission_and_events_survive_reopen(store):
    uid = str(uuid.uuid4())
    jobs = [store.admit(payload(), uid, True) for _ in range(3)]
    with pytest.raises(HTTPException) as exc:
        store.admit(payload(), uid, True)
    assert exc.value.status_code == 429
    for job in jobs:
        asyncio.run(store._delete(job['id']))
    reopened = StateStore(store.data_dir, store.audio_dir)
    try:
        assert reopened.db.execute('SELECT COUNT(*) FROM create_events').fetchone()[0] == 3
        assert reopened.all() == []
    finally:
        reopened.db.close()


def test_state_cache_scoped_to_uid_and_content(store):
    uid = str(uuid.uuid4())
    first = store.admit(payload(), uid, True)
    for status in ('sourcing', 'writing', 'generating', 'ready'):
        store.update(first['id'], status)
    store.update(first['id'], _contentHash='digest')
    (store.audio_dir / (first['id'] + '.mp3')).write_bytes(b'audio')
    same = store.admit(payload(), uid, True)
    assert store.cache(same['id'], 'digest')['id'] == first['id']
    other = store.admit(payload(), str(uuid.uuid4()), True)
    assert store.cache(other['id'], 'digest') is None
    assert store.cache(same['id'], 'different') is None


def test_state_kill_switch_and_terminal_guard(store, monkeypatch):
    monkeypatch.setenv('STUDIO_ENABLED', '0')
    with pytest.raises(HTTPException) as exc:
        store.admit(payload(), 'owner', False)
    assert exc.value.status_code == 503
    assert store.all() == []
    monkeypatch.setenv('STUDIO_ENABLED', '1')
    job = store.admit(payload(), 'owner', False)
    store.update(job['id'], 'failed')
    assert store.dispatch('update', (job['id'], 'sourcing', {}))['status'] == 'failed'


def test_state_restart_is_not_web_cold_start(store):
    queued = store.admit(payload(), 'owner', False)
    active = store.admit(payload(), 'owner', False)
    store.claim(active['id'])
    assert store.dispatch('recover', ()) == [queued['id']]
    assert store.get(active['id'])['status'] == 'sourcing'
    store.update(active['id'], _started=0)
    store.dispatch('recover', ())
    assert store.get(active['id'])['error'] == 'restarted'


def test_state_retention_and_full_active_capacity(store):
    jobs = [store.admit(payload(), 'owner', False) for _ in range(50)]
    with pytest.raises(HTTPException) as exc:
        store.admit(payload(), 'owner', False)
    assert exc.value.status_code == 429
    victim = jobs[0]
    store.update(victim['id'], 'failed')
    admitted = store.admit(payload(), 'owner', False)
    assert admitted['id'] != victim['id']
    assert len(store.all()) == 50
    store.update(admitted['id'], createdAt='2000-01-01T00:00:00+00:00')
    store.dispatch('prune', ())
    assert len(store.all()) == 49


def test_state_subscriber_rate_limit_survives_deletion(store):
    uid = str(uuid.uuid4())
    for _ in range(6):
        job = store.admit(payload(), uid, True)
        store.dispatch('delete', (job['id'], uid))
    with pytest.raises(HTTPException) as exc:
        store.admit(payload(), uid, True)
    assert exc.value.status_code == 429
    assert exc.value.headers == {'Retry-After': '3600'}


def test_hash_depends_on_actual_source_and_options():
    request = PodcastRequest(**payload())
    gathered = Gathered('Evidence', [])
    digest = content_hash(request, gathered, 'v1')
    assert digest == content_hash(request, gathered, 'v1')
    assert digest != content_hash(request, Gathered('Changed', []), 'v1')
    assert digest != content_hash(request.model_copy(update={'length': 'short'}), gathered, 'v1')
    assert digest != content_hash(request, gathered, 'v2')


def test_cloud_adapter_reuses_http_contract(tmp_path, monkeypatch):
    class Manager:
        async def start(self):
            pass

        async def close(self):
            pass

        async def enqueue(self, request, uid, public):
            return {'id': 'test-job', 'status': 'queued'}

        async def all(self):
            return []

        async def delete(self, job_id, uid):
            pass

    app = create_app(data_dir=tmp_path, token='owner-test', shared_secret='shared-test', manager_factory=Manager)
    with TestClient(app) as client:
        headers = {'Authorization': 'Bearer owner-test'}
        assert client.get('/v1/health').json() == {'ok': True}
        assert client.post('/studio/v1/podcasts', headers=headers, json=payload()).status_code == 202
        assert client.get('/v1/podcasts', headers=headers).json() == {'items': []}
        assert client.delete('/v1/podcasts/test-job', headers=headers).status_code == 204
        monkeypatch.setenv('STUDIO_ENABLED', '0')
        assert client.post('/v1/podcasts', headers=headers, json=payload()).status_code == 503
