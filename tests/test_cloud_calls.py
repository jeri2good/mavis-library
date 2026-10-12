import asyncio
from types import SimpleNamespace

import modal
import pytest
from fastapi import HTTPException

import modal_app


@pytest.mark.parametrize('failure', ['timeout', 'deleted', 'cancelled'])
def test_tracked_gpu_call_terminates_container_on_failure(monkeypatch, failure):
    cancelled = []

    async def get(timeout):
        if failure == 'cancelled':
            raise asyncio.CancelledError()
        raise modal.exception.TimeoutError('timeout')

    async def cancel(terminate_containers):
        cancelled.append(terminate_containers)

    call = SimpleNamespace(object_id='fake-call', get=SimpleNamespace(aio=get), cancel=SimpleNamespace(aio=cancel))

    async def spawn(*args):
        return call

    async def state(*args):
        if failure == 'deleted':
            raise HTTPException(404, 'Podcast not found')

    monkeypatch.setattr(modal_app, 'state', state)
    function = SimpleNamespace(spawn=SimpleNamespace(aio=spawn))
    error = HTTPException if failure == 'deleted' else asyncio.CancelledError if failure == 'cancelled' else modal.exception.TimeoutError
    with pytest.raises(error):
        asyncio.run(modal_app.tracked('fake-job', function, timeout=1))
    assert cancelled == [True]


def test_cloud_web_enqueue_only_stores_and_spawns(monkeypatch):
    operations = []

    async def state(operation, *args):
        operations.append(operation)
        return {'id': 'fake-job', 'status': 'queued'} if operation == 'admit' else True

    async def spawn(job_id):
        operations.append('spawn')
        return SimpleNamespace(object_id='fake-call')

    monkeypatch.setattr(modal_app, 'state', state)
    monkeypatch.setattr(modal_app, 'pipeline', SimpleNamespace(spawn=SimpleNamespace(aio=spawn)))
    request = SimpleNamespace(model_dump=lambda: {'kind': 'chapter'})
    result = asyncio.run(modal_app.CloudManager().enqueue(request))
    assert result == {'id': 'fake-job', 'status': 'queued'}
    assert operations == ['admit', 'spawn', 'call']
