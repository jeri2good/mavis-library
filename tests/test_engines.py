import asyncio
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from notebooklm import AudioFormat, AudioLength, GenerationState, GenerationStatus

import engines
from engines import LocalEngine, NotebookAuthError, NotebookEngine, NotebookQuotaError, parse_script, write_script
from service import PodcastRequest
from sources import Gathered

SCRIPT = [{"speaker": "HOST_A", "text": "Hello from Alex."}, {"speaker": "HOST_B", "text": "Hello from Sam."}]


class FakeLLM:
    def __init__(self, replies=None):
        self.replies = list(replies or [json.dumps(SCRIPT)])
        self.calls = []

    async def complete(self, system, prompt):
        self.calls.append((system, prompt))
        return self.replies.pop(0)


@pytest.mark.parametrize("raw", ["oops", "{}", "[]", '[{"speaker":"Alex","text":"x"}]', '[{"speaker":"HOST_A","text":""}]', '[{"speaker":"HOST_A","text":1}]', json.dumps([SCRIPT[0]]), json.dumps([dict(SCRIPT[0], extra="x"), SCRIPT[1]]), json.dumps([{ "speaker":"HOST_A", "text":"x" * 3001}, SCRIPT[1]])])
def test_script_invalid(raw):
    with pytest.raises(ValueError):
        parse_script(raw)


def test_script_code_fence():
    assert parse_script("```json\n" + json.dumps(SCRIPT) + "\n```") == SCRIPT


def test_script_repair_once():
    llm = FakeLLM(["bad json", json.dumps(SCRIPT)])
    assert asyncio.run(write_script(llm, "Title", "Source", "debate", "short")) == SCRIPT
    assert len(llm.calls) == 2 and "malformed" in llm.calls[1][1]


def test_script_retry_fails():
    with pytest.raises(ValueError, match="after retry"):
        asyncio.run(write_script(FakeLLM(["bad", "still bad"]), "Title", "Source", "critique", "short"))


def test_map_reduce_no_discarded_tail():
    llm = FakeLLM(["note"] * 34 + [json.dumps(SCRIPT)])
    asyncio.run(write_script(llm, "Title", "x" * 399996 + "TAIL", "deep-dive", "long"))
    assert "TAIL" in llm.calls[-2][1]
    assert len(llm.calls) == 35
    assert "3200" in llm.calls[-1][1]


def test_no_live_sources_intro():
    result = asyncio.run(write_script(FakeLLM(), "Title", "Topic", "brief", "short", True))
    assert "No live sources" in result[0]["text"]


class FakeNotebookClient:
    def __init__(self):
        self.notebooks = SimpleNamespace(list=AsyncMock(return_value=[]), create=AsyncMock(return_value=SimpleNamespace(id="notebook-1")), delete=AsyncMock())
        self.sources = SimpleNamespace(add_text=AsyncMock(return_value=SimpleNamespace(id="source-1")))
        self.artifacts = SimpleNamespace(generate_audio=AsyncMock(return_value=GenerationStatus("artifact-1", GenerationState.PENDING)), wait_for_completion=AsyncMock(return_value=GenerationStatus("artifact-1", GenerationState.COMPLETED)), download_audio=AsyncMock(side_effect=self.download))

    async def download(self, notebook_id, path, artifact_id):
        from pathlib import Path
        Path(path).write_bytes(b"fake downloaded bytes")

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        pass


def notebook(tmp_path, client):
    storage = tmp_path / "storage.json"
    storage.write_text(json.dumps({"cookies": [{"name": "SID", "domain": ".google.com", "value": "fake-test-cookie"}]}))
    return NotebookEngine(lambda: client, storage=storage, sleep=AsyncMock())


@pytest.mark.parametrize("style", ["deep-dive", "brief", "debate", "critique"])
@pytest.mark.parametrize("length", ["short", "default", "long"])
def test_notebook_api_contract(tmp_path, monkeypatch, style, length):
    client = FakeNotebookClient()
    engine = notebook(tmp_path, client)
    convert = AsyncMock()
    monkeypatch.setattr(engines, "run_process", convert)
    asyncio.run(engine.generate("Title", "Source", style, length, "en", tmp_path / "audio.mp3"))
    kwargs = client.artifacts.generate_audio.call_args.kwargs
    assert kwargs["audio_format"] == AudioFormat[style.upper().replace("-", "_")]
    assert kwargs["audio_length"] == AudioLength[length.upper()]
    assert kwargs["source_ids"] == ["source-1"]
    assert client.artifacts.wait_for_completion.call_args.kwargs["timeout"] == 1200
    assert client.artifacts.download_audio.call_args.kwargs["artifact_id"] == "artifact-1"
    client.notebooks.delete.assert_awaited_once_with("notebook-1")
    assert "mp3" in convert.call_args.args


def test_notebook_missing_login(tmp_path):
    engine = NotebookEngine(storage=tmp_path / "absent")
    assert asyncio.run(engine.logged_in()) is False
    with pytest.raises(NotebookAuthError, match="fresh sign-in"):
        asyncio.run(engine.generate("Title", "Source", "brief", "short", "en", tmp_path / "audio.mp3"))


def test_notebook_auth_cleanup_and_health(tmp_path):
    client = FakeNotebookClient()
    client.sources.add_text.side_effect = RuntimeError("expired cookie secret must not appear")
    engine = notebook(tmp_path, client)
    async def run():
        with pytest.raises(NotebookAuthError, match="^NotebookLM needs a fresh sign-in$"):
            await engine.generate("Title", "Source", "brief", "short", "en", tmp_path / "audio.mp3")
        assert not await engine.logged_in()
    asyncio.run(run())
    client.notebooks.delete.assert_awaited_once()


def test_notebook_rate_limit_retry_cleanup(tmp_path):
    client = FakeNotebookClient()
    client.artifacts.generate_audio.return_value = GenerationStatus("", GenerationState.FAILED, error_code="USER_DISPLAYABLE_ERROR")
    engine = notebook(tmp_path, client)
    with pytest.raises(NotebookQuotaError):
        asyncio.run(engine.generate("Title", "Source", "brief", "short", "en", tmp_path / "audio.mp3"))
    assert client.artifacts.generate_audio.await_count == 3
    assert engine.sleep.await_count == 2
    client.notebooks.delete.assert_awaited_once()


def test_notebook_keep_flag(tmp_path, monkeypatch):
    monkeypatch.setenv("KEEP_NOTEBOOKS", "1")
    monkeypatch.setattr(engines, "run_process", AsyncMock())
    client = FakeNotebookClient()
    asyncio.run(notebook(tmp_path, client).generate("Title", "Source", "brief", "short", "en", tmp_path / "audio.mp3"))
    client.notebooks.delete.assert_not_awaited()


def test_notebook_health_requires_verified_auth(tmp_path):
    client = FakeNotebookClient()
    client.notebooks.list.side_effect = RuntimeError("expired login")
    assert asyncio.run(notebook(tmp_path, client).logged_in()) is False


def test_local_fallback_transcript(tmp_path):
    renderer, stage = AsyncMock(), AsyncMock()
    engine = LocalEngine(FakeLLM(), renderer)
    transcript = asyncio.run(engine.generate(PodcastRequest(kind="chapter", title="Title", text="body"), Gathered("body", []), tmp_path / "a.mp3", stage, fallback=True))
    assert "made with the local engine" in transcript and "Sam:" in transcript
    assert [call.args[0] for call in stage.call_args_list] == ["writing", "generating"]


def test_notebook_conversion_is_real_mp3(tmp_path):
    import wave
    class WaveClient(FakeNotebookClient):
        async def download(self, notebook_id, path, artifact_id):
            with wave.open(path, "wb") as audio:
                audio.setnchannels(1)
                audio.setsampwidth(2)
                audio.setframerate(16000)
                import math
                import struct
                audio.writeframes(b"".join(struct.pack("<h", round(5000 * math.sin(2 * math.pi * 440 * index / 16000))) for index in range(16000)))
    output = tmp_path / "real.mp3"
    asyncio.run(notebook(tmp_path, WaveClient()).generate("Title", "Source", "brief", "short", "en", output))
    assert output.read_bytes().startswith(b"ID3")
    assert asyncio.run(engines.duration_seconds(output)) == 1


def test_edge_endpoint_migration(monkeypatch):
    monkeypatch.setattr(engines.edge_communicate, "WSS_URL", "wss://speech.platform.bing.com/legacy")
    engines.configure_edge_endpoint()
    assert engines.edge_communicate.WSS_URL.startswith("wss://api.msedgeservices.com/tts/cognitiveservices/websocket/v1?")
    monkeypatch.setattr(engines.edge_communicate, "WSS_URL", "wss://future.example.com/current")
    engines.configure_edge_endpoint()
    assert engines.edge_communicate.WSS_URL == "wss://future.example.com/current"


def test_tts_two_voices_pauses_and_metadata(tmp_path, monkeypatch):
    import math
    import struct
    import wave
    voices = []
    class Speech:
        def __init__(self, text, voice):
            voices.append(voice)

        async def save(self, path):
            with wave.open(path, "wb") as audio:
                audio.setnchannels(1)
                audio.setsampwidth(2)
                audio.setframerate(16000)
                audio.writeframes(b"".join(struct.pack("<h", round(5000 * math.sin(2 * math.pi * 440 * index / 16000))) for index in range(16000)))
    monkeypatch.setattr(engines.edge_tts, "Communicate", Speech)
    output = tmp_path / "two-hosts.mp3"
    asyncio.run(engines.render_audio(SCRIPT, output, "Test Title"))
    assert voices == ["en-US-AndrewNeural", "en-US-AriaNeural"]
    probe = asyncio.run(engines.run_process("ffprobe", "-v", "error", "-show_format", "-of", "json", str(output)))
    result = json.loads(probe)["format"]
    assert 2.4 < float(result["duration"]) < 2.6
    assert result["tags"]["title"] == "Test Title"
    assert result["tags"]["artist"] == "Mavis Podcast Lounge"


def test_notebook_network_failure_not_mislabeled_auth(tmp_path):
    client = FakeNotebookClient()
    client.notebooks.create.side_effect = ConnectionError("network unavailable")
    with pytest.raises(ConnectionError):
        asyncio.run(notebook(tmp_path, client).generate("T", "Source", "brief", "short", "en", tmp_path / "a.mp3"))


def test_notebook_timeout_cleanup(tmp_path):
    client = FakeNotebookClient()
    client.artifacts.wait_for_completion.side_effect = TimeoutError()
    with pytest.raises(TimeoutError):
        asyncio.run(notebook(tmp_path, client).generate("T", "Source", "brief", "short", "en", tmp_path / "a.mp3"))
    client.notebooks.delete.assert_awaited_once()


def test_llm_http_fallback(monkeypatch):
    import httpx
    monkeypatch.setenv("TYPESAFE_CHAT_URL", "https://example.com/chat/completions")
    calls = []
    class Client:
        def __init__(self, **kwargs):
            assert kwargs["trust_env"] is False

        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            pass

        async def post(self, url, headers, json):
            calls.append(url)
            if "example.com" in url:
                return httpx.Response(404, request=httpx.Request("POST", url))
            return httpx.Response(200, json={"choices": [{"message": {"content": "valid completion"}}]}, request=httpx.Request("POST", url))
    monkeypatch.setattr(engines.httpx, "AsyncClient", Client)
    llm = engines.LLM()
    llm.key = "fake-test-key"
    assert asyncio.run(llm.complete("system", "prompt")) == "valid completion"
    assert len(calls) == 2 and llm.used == "Hermes local gateway (mavis-gravity)"
    assert llm.typesafe_unavailable
