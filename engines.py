import asyncio
import json
import os
import re
import shutil
import tempfile
from pathlib import Path

import edge_tts
import httpx
from dotenv import dotenv_values
from notebooklm import AudioFormat, AudioLength, GenerationState, NotebookLMClient

VOICES = {"HOST_A": "en-US-AndrewNeural", "HOST_B": "en-US-AriaNeural"}
WORDS = {"short": 1100, "default": 2000, "long": 3200}


class NotebookAuthError(Exception):
    pass


class NotebookQuotaError(Exception):
    pass


def auth_error(exc):
    return any(term in str(exc).lower() for term in ("auth", "cookie", "sign-in", "login", "unauthorized", "expired", "401", "403"))


def quota_error(exc):
    return any(term in str(exc).lower() for term in ("rate limit", "quota", "429", "resource exhausted"))


class NotebookEngine:
    def __init__(self, client_factory=None, storage=None):
        self.client_factory = client_factory or NotebookLMClient.from_storage
        self.storage = Path(storage or Path.home() / ".notebooklm/profiles/default/storage_state.json")
        self.expired = False

    def logged_in(self):
        return not self.expired and self.storage.is_file() and self.storage.stat().st_size > 10

    async def generate(self, title, text, style, length, language, output):
        if not self.logged_in():
            raise NotebookAuthError("NotebookLM needs a fresh sign-in")
        notebook_id = None
        try:
            async with self.client_factory() as client:
                notebook = await client.notebooks.create(title)
                notebook_id = notebook.id
                try:
                    source = await client.sources.add_text(notebook_id, title, text, wait=True)
                    for attempt in range(3):
                        try:
                            task = await client.artifacts.generate_audio(notebook_id, source_ids=[source.id], language=language, instructions="Two hosts, warm and curious, for a general listener.", audio_format=getattr(AudioFormat, style.upper().replace("-", "_")), audio_length=getattr(AudioLength, length.upper()))
                            result = await client.artifacts.wait_for_completion(notebook_id, task.task_id, timeout=1200)
                            if result.status != GenerationState.COMPLETED:
                                raise RuntimeError("NotebookLM audio generation failed")
                            await client.artifacts.download_audio(notebook_id, str(output), artifact_id=getattr(result, "artifact_id", None))
                            return
                        except Exception as exc:
                            if not quota_error(exc):
                                raise
                            if attempt == 2:
                                raise NotebookQuotaError("NotebookLM quota or rate limit reached") from exc
                            await asyncio.sleep(2 ** attempt)
                finally:
                    if notebook_id and os.getenv("KEEP_NOTEBOOKS", "0") != "1":
                        await client.notebooks.delete(notebook_id)
        except (NotebookAuthError, NotebookQuotaError):
            raise
        except Exception as exc:
            if auth_error(exc):
                self.expired = True
                raise NotebookAuthError("NotebookLM needs a fresh sign-in") from exc
            raise


class LLM:
    def __init__(self):
        env = dotenv_values(Path.home() / ".hermes/.env")
        self.key = os.getenv("TYPESAFE_API_KEY") or env.get("TYPESAFE_API_KEY")
        self.model = os.getenv("TYPESAFE_MODEL", "systemone")
        self.used = None

    async def complete(self, system, prompt):
        endpoints = []
        if self.key:
            endpoints.append(("TypeSafe", "https://api.typesafe.ai/v1/systemone/chat/completions", self.key, self.model))
        gateway = os.getenv("HERMES_LLM_BASE_URL", "http://127.0.0.1:8080/v1")
        endpoints.append(("Hermes local gateway", gateway.rstrip("/") + "/chat/completions", os.getenv("HERMES_LLM_API_KEY", "local"), os.getenv("HERMES_LLM_MODEL", "local")))
        last = None
        async with httpx.AsyncClient(timeout=120) as client:
            for name, url, key, model in endpoints:
                try:
                    response = await client.post(url, headers={"Authorization": f"Bearer {key}"}, json={"model": model, "messages": [{"role": "system", "content": system}, {"role": "user", "content": prompt}], "temperature": 0.65, "max_tokens": 6000})
                    response.raise_for_status()
                    self.used = name + " (" + model + ")"
                    return response.json()["choices"][0]["message"]["content"]
                except (httpx.HTTPError, KeyError, ValueError) as exc:
                    last = exc
        raise RuntimeError("No LLM endpoint available") from last


def parse_script(raw):
    raw = re.sub(r"^```(?:json)?\s*|\s*```$", "", raw.strip(), flags=re.I)
    try:
        lines = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise ValueError("LLM returned invalid script JSON") from exc
    if not isinstance(lines, list) or not lines or len(lines) > 250:
        raise ValueError("LLM returned invalid script lines")
    for line in lines:
        if not isinstance(line, dict) or line.get("speaker") not in VOICES or not isinstance(line.get("text"), str) or not line["text"].strip() or len(line["text"]) > 3000:
            raise ValueError("LLM returned invalid script lines")
    return [{"speaker": line["speaker"], "text": line["text"].strip()} for line in lines]


async def write_script(llm, title, text, style, length, no_sources=False):
    system = "Write grounded, conversational two-host podcast dialogue as ONLY a JSON array of {speaker: HOST_A|HOST_B, text: string}. Alex is HOST_A and Sam is HOST_B. No invented quotes or unsupported claims. No markdown. Style: " + style + "."
    if no_sources:
        system += " No live sources were found. Explicitly say 'No live sources were available; this discussion uses general background knowledge' in the opening."
    chunks = [text[i:i + 12000] for i in range(0, len(text), 12000)]
    if len(chunks) > 1:
        notes = []
        for chunk in chunks[:34]:
            notes.append(await llm.complete("Summarize only factual themes and concrete details present in this supplied text. No invented facts. Plain text, at most 300 words.", chunk))
        text = "\n".join(notes)[:45000]
    prompt = f"Title: {title}\nTarget about {WORDS[length]} spoken words, two hosts, short turns. Style {style}. Discuss rather than recite. Source material:\n{text[:50000]}"
    for attempt in range(2):
        raw = await llm.complete(system, prompt if not attempt else prompt + "\nYour previous response was malformed. Return ONLY valid JSON array with HOST_A/HOST_B and text.")
        try:
            return parse_script(raw)
        except ValueError:
            if attempt:
                raise ValueError("LLM returned invalid script JSON after retry")


async def render_audio(lines, output, title):
    if not shutil.which("ffmpeg"):
        raise RuntimeError("ffmpeg is required to render audio")
    with tempfile.TemporaryDirectory() as directory:
        directory = Path(directory)
        concat = directory / "segments.txt"
        paths = []
        for index, line in enumerate(lines):
            path = directory / f"{index:04d}.mp3"
            await edge_tts.Communicate(line["text"], VOICES[line["speaker"]]).save(str(path))
            paths.append(path)
        concat.write_text("".join(f"file '{path}'\n" for path in paths))
        process = await asyncio.create_subprocess_exec("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "concat", "-safe", "0", "-i", str(concat), "-af", "loudnorm=I=-16:TP=-1.5:LRA=11", "-ar", "44100", "-b:a", "128k", "-metadata", f"title={title}", "-metadata", "artist=Mavis Podcast Lounge", str(output), stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.PIPE)
        _, errors = await process.communicate()
        if process.returncode:
            raise RuntimeError("Audio rendering failed: " + errors.decode(errors="replace")[-500:])


async def duration_seconds(path):
    process = await asyncio.create_subprocess_exec("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", str(path), stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
    output, _ = await process.communicate()
    return round(float(output.strip())) if process.returncode == 0 else None
