import asyncio
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from fastapi.testclient import TestClient

import modal_app
from cloud_state import StateStore
from notebook_cloud import MESSAGES, rejected
from service import PodcastRequest, create_app, public_job


@pytest.mark.parametrize('requested,kind', [('auto', kind) for kind in MESSAGES] + [('notebooklm', 'auth'), ('notebooklm', 'challenge'), ('notebooklm', 'success'), ('auto', 'success'), ('local', 'success')])
def test_pipeline_engine_selection_end_to_end(tmp_path, monkeypatch, capsys, requested, kind):
    store = StateStore(tmp_path / 'state', tmp_path / 'audio')
    job = store.admit(PodcastRequest(kind='chapter', title='Evidence', text='Bees pollinate.', engine=requested).model_dump(), 'subscriber', True)
    notebook = SimpleNamespace(generate=object())
    gpu = SimpleNamespace(script=object(), voices=object())
    calls = []

    async def state(operation, *args):
        return store.dispatch(operation, args)

    async def tracked(job_id, function, *args, **kwargs):
        calls.append(function)
        if function is modal_app.source:
            return {'text': 'Bees pollinate.', 'sources': [], 'no_live_sources': False, 'seconds': 0.1}
        if function is notebook.generate:
            return {'ok': True, 'engine': 'notebooklm', 'durationSec': 80, 'seconds': 5, 'bytes': 100} if kind == 'success' else rejected(kind)
        if function is gpu.script:
            return {'lines': [{'speaker': 'HOST_A', 'text': 'Bees pollinate.'}, {'speaker': 'HOST_B', 'text': 'Flowers benefit.'}], 'model': 'fake', 'gpuSeconds': 0.01}
        if function is gpu.voices:
            return {'segments': [], 'voices': ['a', 'b'], 'gpuSeconds': 0.01}
        assert function is modal_app.finish
        return {'durationSec': 90, 'seconds': 1, 'bytes': 120}

    monkeypatch.setattr(modal_app, 'state', state)
    monkeypatch.setattr(modal_app, 'tracked', tracked)
    monkeypatch.setattr(modal_app, 'NotebookLM', lambda: notebook)
    monkeypatch.setattr(modal_app, 'StudioGPU', lambda: gpu)
    try:
        asyncio.run(modal_app.pipeline.get_raw_f()(job['id']))
        result = public_job(store.get(job['id']))
        if requested == 'notebooklm' and kind in ('auth', 'challenge'):
            assert result['status'] == 'failed' and result['error'] == MESSAGES[kind]
            assert gpu.script not in calls
        elif requested != 'local' and kind == 'success':
            assert result['status'] == 'ready' and result['engine'] == 'notebooklm'
            assert result['durationSec'] == 80 and gpu.script not in calls
            assert 'transcript' not in result
        else:
            assert result['status'] == 'ready' and result['engine'] == 'local'
            assert gpu.script in calls
            assert ('made with the local engine' in result['transcript']) is (requested != 'local')
        assert 'owner_uid' not in result
        assert 'fake-cookie-never-in-logs' not in json.dumps(result) + capsys.readouterr().out
    finally:
        store.db.close()


def test_http_health_cloud_status_extension(tmp_path):
    class Manager:
        start = AsyncMock()
        close = AsyncMock()
        notebook_health = AsyncMock(return_value={'available': True, 'loggedIn': False, 'note': MESSAGES['auth']})

    app = create_app(data_dir=tmp_path, token='fake', shared_secret='fake', manager_factory=Manager)
    with TestClient(app) as client:
        result = client.get('/v1/health')
        assert result.status_code == 200
        assert result.json() == {'ok': True, 'notebooklm': {'available': True, 'loggedIn': False, 'note': MESSAGES['auth']}}


def test_shared_health_invalidated_immediately(tmp_path):
    store = StateStore(tmp_path / 'state', tmp_path / 'audio')
    try:
        store.dispatch('notebook_health', ({'available': True, 'loggedIn': True, 'note': 'Authenticated'},))
        assert store.dispatch('notebook_health', ())['health']['loggedIn']
        store.dispatch('notebook_health', ({'available': True, 'loggedIn': False, 'note': MESSAGES['auth']},))
        assert not store.dispatch('notebook_health', ())['health']['loggedIn']
        store.dispatch('notebook_health', ({'available': True, 'loggedIn': True, 'note': 'Authenticated'}, 1234))
        assert store.dispatch('notebook_health', ())['checked_at'] == 1234
    finally:
        store.db.close()
