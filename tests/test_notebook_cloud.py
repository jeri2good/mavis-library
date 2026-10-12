import asyncio
import json
import os
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from notebooklm import AudioFormat, AudioLength
from notebooklm.exceptions import AuthError, AuthExtractionError, RateLimitError

from notebook_cloud import MESSAGES, NotebookCloud, private_write, rejected, use_local
from service import PodcastRequest

COOKIE = 'fake-cookie-never-in-logs-unique-marker'
SECRET = json.dumps({'cookies': [{'name': 'SID', 'domain': '.google.com', 'value': COOKIE}], 'origins': [], 'saved_at': 100})
PAYLOAD = PodcastRequest(kind='chapter', title='Evidence', text='Bees pollinate flowers.', style='brief', length='short').model_dump()
GATHERED = {'text': PAYLOAD['text'], 'sources': [], 'no_live_sources': False, 'seconds': 0.1}


class Client:
    def __init__(self, path):
        self.path = path
        self.notebooks = SimpleNamespace(list=AsyncMock(return_value=[]), create=AsyncMock(return_value=SimpleNamespace(id='created')), delete=AsyncMock())
        self.sources = SimpleNamespace(add_text=AsyncMock(return_value=SimpleNamespace(id='source')))
        self.artifacts = SimpleNamespace(generate_audio=AsyncMock(return_value=SimpleNamespace(task_id='audio', is_rate_limited=False)), wait_for_completion=AsyncMock(return_value=SimpleNamespace(task_id='audio', status='completed', is_rate_limited=False)), download_audio=AsyncMock(side_effect=self.download))

    async def download(self, notebook_id, path, artifact_id):
        Path(path).write_bytes(b'm4a')

    async def __aenter__(self):
        assert self.path.stat().st_mode & 0o777 == 0o600
        assert self.path.parent.stat().st_mode & 0o777 == 0o700
        return self

    async def __aexit__(self, *args):
        document = json.loads(self.path.read_text())
        document['cookies'][0]['value'] = 'refreshed-fake-cookie'
        self.path.write_text(json.dumps(document))


@pytest.fixture
def setup(tmp_path):
    clients = []
    now = [1000.0]

    def factory(path):
        client = Client(path)
        clients.append(client)
        return client

    engine = NotebookCloud(tmp_path / 'auth', SECRET, factory, clock=lambda: now[0])
    output = tmp_path / 'out.mp3'

    async def convert(*args):
        Path(args[-1]).write_bytes(b'ID3fake')

    return engine, clients, now, output, AsyncMock(side_effect=convert), AsyncMock(return_value=80)


def generate(setup, uid='subscriber', payload=None, gathered=None):
    engine, _, _, output, convert, probe = setup
    return engine.generate('job', uid, {**PAYLOAD, **(payload or {})}, gathered or GATHERED, output, convert, probe)


@pytest.mark.parametrize('style', ['deep-dive', 'brief', 'debate', 'critique'])
@pytest.mark.parametrize('length', ['short', 'default', 'long'])
def test_complete_flow_refresh_cleanup(setup, style, length):
    result = asyncio.run(generate(setup, payload={'style': style, 'length': length}))
    engine, clients, _, output, convert, _ = setup
    assert result['ok'] and result['engine'] == 'notebooklm'
    assert result['durationSec'] == 80 and result['bytes'] == output.stat().st_size
    client = clients[0]
    client.notebooks.create.assert_awaited_once_with('Mavis Lounge job')
    client.notebooks.delete.assert_awaited_once_with('created')
    kwargs = client.artifacts.generate_audio.call_args.kwargs
    assert kwargs['audio_format'] == AudioFormat[style.upper().replace('-', '_')]
    assert kwargs['audio_length'] == AudioLength[length.upper()]
    assert kwargs['language'] == 'en' and kwargs['source_ids'] == ['source']
    assert 'warm and curious' in kwargs['instructions']
    assert client.artifacts.wait_for_completion.call_args.kwargs == {'timeout': 1200, 'initial_interval': 10, 'max_interval': 10}
    assert 'loudnorm=I=-16:TP=-1.5:LRA=11' in convert.call_args.args
    assert 'artist=Mavis Podcast Lounge' in convert.call_args.args
    saved = engine.directory / 'storage_state.json'
    assert saved.stat().st_mode & 0o777 == 0o600
    assert json.loads(saved.read_text())['cookies'][0]['value'] == 'refreshed-fake-cookie'
    assert json.loads(saved.read_text())['saved_at'] == 1000
    assert not client.path.exists()


@pytest.mark.parametrize('stage', ['add_text', 'generate_audio', 'wait_for_completion', 'download_audio', 'convert'])
@pytest.mark.parametrize('kind,error', [('auth', AuthError('expired ' + COOKIE)), ('quota', RateLimitError('too many requests ' + COOKIE)), ('source', ValueError('unsupported source ' + COOKIE)), ('challenge', RuntimeError('captcha challenge ' + COOKIE)), ('timeout', TimeoutError(COOKIE)), ('unavailable', RuntimeError(COOKIE))])
def test_failures_cleanup_redaction_and_auto_fallback(setup, caplog, capsys, stage, kind, error):
    engine, clients, _, _, convert, _ = setup
    original = engine.client_factory

    def factory(path):
        client = original(path)
        target = client.sources if stage == 'add_text' else client.artifacts
        if stage == 'convert':
            convert.side_effect = error
        else:
            getattr(target, stage).side_effect = error
        return client

    engine.client_factory = factory
    result = asyncio.run(generate(setup))
    assert result == rejected(kind)
    clients[0].notebooks.delete.assert_awaited_once_with('created')
    assert use_local('auto', result)
    assert COOKIE not in json.dumps(result) + caplog.text + capsys.readouterr().out
    if kind in ('auth', 'challenge'):
        assert engine.read_policy()['health']['loggedIn'] is False
        with pytest.raises(RuntimeError, match=MESSAGES[kind]):
            use_local('notebooklm', result)
    else:
        assert use_local('notebooklm', result)


def test_cleanup_failure_is_not_success(setup):
    engine = setup[0]
    original = engine.client_factory

    def factory(path):
        client = original(path)
        client.notebooks.delete.side_effect = RuntimeError(COOKIE)
        return client

    engine.client_factory = factory
    assert asyncio.run(generate(setup)) == rejected('cleanup')


def test_source_cap_separate_texts(setup):
    gathered = {**GATHERED, 'source_texts': [{'title': str(i), 'text': 'source text ' + str(i)} for i in range(55)]}
    assert asyncio.run(generate(setup, gathered=gathered))['ok']
    calls = setup[1][0].sources.add_text.call_args_list
    assert len(calls) == 50 and calls[0].args == ('created', '0', 'source text 0')


def test_daily_limit_global_restart_owner_opt_in_and_utc_reset(setup):
    engine, clients, now, _, _, _ = setup
    for i in range(6):
        assert asyncio.run(generate(setup, uid='user-' + str(i)))['ok']
    assert asyncio.run(generate(setup, uid='owner')) == rejected('cap')
    assert len(clients) == 6
    engine.owner_unlimited = True
    assert asyncio.run(generate(setup, uid='owner'))['ok']
    assert asyncio.run(generate(setup, uid='subscriber')) == rejected('cap')
    restarted = NotebookCloud(engine.directory, SECRET, engine.client_factory, clock=lambda: now[0])
    assert restarted.read_policy()['used'] == 7
    now[0] += 86400
    assert asyncio.run(generate(setup))['ok']
    assert engine.read_policy()['used'] == 1


def test_pause_persists_and_expires(setup):
    engine, clients, now, _, _, _ = setup
    engine.mark_failure(engine.read_policy(), 'quota')
    assert asyncio.run(generate(setup)) == rejected('paused')
    assert not clients
    assert NotebookCloud(engine.directory, SECRET).read_policy()['pause_until'] == 2800
    now[0] = 2801
    assert asyncio.run(generate(setup))['ok']


@pytest.mark.parametrize('saved_at,mtime,preferred', [(500, 500, True), (50, 500, False), (500, 50, False)])
def test_volume_cookie_preference(setup, tmp_path, saved_at, mtime, preferred):
    engine = setup[0]
    volume = engine.directory / 'storage_state.json'
    private_write(volume, json.dumps({'cookies': [{'value': 'volume-fake'}], 'saved_at': saved_at}))
    os.utime(volume, (mtime, mtime))
    chosen = json.loads(engine.storage(tmp_path / 'private').read_text())
    assert (chosen['cookies'][0]['value'] == 'volume-fake') is preferred


def test_health_cached_then_recovers_after_auth_failure(setup):
    engine, clients, now, _, _, _ = setup
    assert asyncio.run(engine.health())['loggedIn']
    assert asyncio.run(engine.health())['loggedIn'] and len(clients) == 1
    engine.mark_failure(engine.read_policy(), 'auth')
    assert not asyncio.run(engine.health())['loggedIn']
    now[0] += 301
    assert asyncio.run(engine.health())['loggedIn'] and len(clients) == 2


def test_health_login_failure_redacted(setup, caplog):
    engine = setup[0]
    original = engine.client_factory

    def factory(path):
        client = original(path)
        client.notebooks.list.side_effect = AuthExtractionError('SNlM0e', COOKIE, message='Google login ' + COOKIE)
        return client

    engine.client_factory = factory
    result = asyncio.run(engine.health())
    assert result == {'available': True, 'loggedIn': False, 'note': MESSAGES['auth']}
    assert COOKIE not in json.dumps(result) + caplog.text


def test_health_sdk_has_no_limit_parameter(setup):
    import inspect
    from notebooklm._notebooks import NotebooksAPI
    assert 'limit' not in inspect.signature(NotebooksAPI.list).parameters
    asyncio.run(setup[0].health())
    setup[1][0].notebooks.list.assert_awaited_once_with()


def test_count_only_returns_count(setup):
    engine = setup[0]
    original = engine.client_factory

    def factory(path):
        client = original(path)
        client.notebooks.list.return_value = [SimpleNamespace(title='Mavis Lounge fake'), SimpleNamespace(title='Personal private notebook')]
        return client

    engine.client_factory = factory
    assert asyncio.run(engine.count()) == {'ok': True, 'count': 1}


def test_single_generation_at_a_time(setup):
    engine = setup[0]
    active = 0
    maximum = 0
    original = engine.client_factory

    def factory(path):
        client = original(path)

        async def create(title):
            nonlocal active, maximum
            active += 1
            maximum = max(maximum, active)
            await asyncio.sleep(0.01)
            active -= 1
            return SimpleNamespace(id='created')

        client.notebooks.create.side_effect = create
        return client

    engine.client_factory = factory

    async def run():
        return await asyncio.gather(generate(setup), generate(setup))

    assert all(result['ok'] for result in asyncio.run(run()))
    assert maximum == 1


def test_missing_auth_is_safe(setup):
    setup[0].secret = ''
    assert asyncio.run(generate(setup)) == rejected('auth')


def test_changed_secret_supersedes_old_volume(setup, tmp_path):
    engine = setup[0]
    asyncio.run(generate(setup))
    fresh = SECRET.replace(COOKIE, 'new-sign-in')
    changed = NotebookCloud(engine.directory, fresh)
    chosen = json.loads(changed.storage(tmp_path / 'private').read_text())
    assert chosen['cookies'][0]['value'] == 'new-sign-in'


def test_reservation_checkpoint_before_google(setup):
    engine, clients, _, _, _, _ = setup

    async def checkpoint():
        assert not clients
        assert engine.read_policy()['used'] == 1

    engine.checkpoint = AsyncMock(side_effect=checkpoint)
    assert asyncio.run(generate(setup))['ok']
    engine.checkpoint.assert_awaited_once()
