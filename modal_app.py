import asyncio
import json
import os
import shutil
import subprocess
import tempfile
import time
from pathlib import Path

import modal

APP_NAME = 'mavis-podcast-studio'
MODEL = 'Qwen/Qwen3-4B-Instruct-2507'
CANDIDATES = (MODEL, 'Qwen/Qwen3-1.7B')
MODEL_REVISIONS = {'Qwen/Qwen3-4B-Instruct-2507': 'cdbee75f17c01a7cc42f958dc650907174af0554',
                   'Qwen/Qwen3-1.7B': '70d244cc86ccca08cf5af4e1e306ecf908b1ad5e'}
GPU_SECONDS_LIMIT = 900
PIPELINE_VERSION = 'qwen3-4b-kokoro-v1'
app = modal.App(APP_NAME)
metadata = modal.Volume.from_name('mavis-podcast-studio-state', create_if_missing=True)
audio_volume = modal.Volume.from_name('mavis-podcast-studio-audio', create_if_missing=True)
models = modal.Volume.from_name('mavis-podcast-studio-models', create_if_missing=True)
studio_secret = modal.Secret.from_name('podcast-studio')
notebook_auth = modal.Volume.from_name('mavis-podcast-studio-notebooklm-auth', create_if_missing=True)
notebook_secret = modal.Secret.from_name('notebooklm-auth')
studio_env = {'STUDIO_DEPLOY_ENABLED': os.getenv('STUDIO_ENABLED', '')}
runtime_image = (modal.Image.debian_slim(python_version='3.12')
                 .apt_install('ffmpeg')
                 .pip_install_from_requirements('requirements.txt'))
base_image = runtime_image.add_local_python_source('service', 'sources', 'engines', 'cloud_state', 'cloud_budget', 'notebook_cloud')
gpu_image = (runtime_image.apt_install('espeak-ng', 'libgomp1')
             .pip_install('vllm==0.11.0', 'kokoro==0.9.4', 'soundfile==0.13.1', 'transformers==4.57.1')
             .pip_install('https://github.com/explosion/spacy-models/releases/download/en_core_web_sm-3.8.0/en_core_web_sm-3.8.0-py3-none-any.whl')
             .env({'HF_HOME': '/models/huggingface', 'VLLM_LOGGING_LEVEL': 'ERROR', 'TOKENIZERS_PARALLELISM': 'false'})
             .add_local_python_source('service', 'sources', 'engines', 'cloud_state', 'cloud_budget'))


@app.cls(image=base_image, cpu=0.25, memory=512, min_containers=0, max_containers=1,
         scaledown_window=60, timeout=120, secrets=[studio_secret], env=studio_env,
         volumes={'/state': metadata, '/audio': audio_volume})
@modal.concurrent(max_inputs=1)
class State:
    @modal.method()
    def execute(self, operation, args=()):
        from cloud_state import StateStore
        from fastapi import HTTPException
        metadata.reload()
        audio_volume.reload()
        store = StateStore('/state', '/audio')
        try:
            value = store.dispatch(operation, args)
            result = {'ok': True, 'value': value}
        except HTTPException as exc:
            result = {'ok': False, 'status': exc.status_code, 'detail': exc.detail, 'headers': exc.headers}
        finally:
            store.db.close()
        metadata.commit()
        if store.removed:
            for job_id in store.removed:
                Path('/audio', job_id + '.mp3').unlink(missing_ok=True)
            audio_volume.commit()
        return result


async def state(operation, *args):
    from fastapi import HTTPException
    result = await State().execute.remote.aio(operation, args)
    if not result['ok']:
        raise HTTPException(result['status'], result['detail'], headers=result.get('headers'))
    return result['value']


@app.function(image=base_image, cpu=0.5, memory=1024, max_containers=2, timeout=240)
async def source(payload):
    from service import PodcastRequest
    from sources import gather
    started = time.monotonic()
    gathered = await gather(PodcastRequest(**payload))
    return {'text': gathered.text, 'sources': gathered.sources, 'no_live_sources': gathered.no_live_sources, 'source_texts': gathered.source_texts, 'seconds': time.monotonic() - started}


@app.cls(image=base_image, cpu=0.5, memory=1024, min_containers=0, max_containers=1,
         scaledown_window=60, timeout=1500, secrets=[notebook_secret], volumes={'/auth': notebook_auth, '/audio': audio_volume})
@modal.concurrent(max_inputs=1)
class NotebookLM:
    @modal.enter()
    def enter(self):
        from notebook_cloud import NotebookCloud, silence_transport_logs
        silence_transport_logs()
        self.private = tempfile.TemporaryDirectory()
        os.chmod(self.private.name, 0o700)
        os.environ['NOTEBOOKLM_HOME'] = self.private.name
        self.engine = NotebookCloud('/auth', os.getenv('STORAGE_STATE_JSON', ''), daily_max=os.getenv('NOTEBOOKLM_DAILY_MAX', '12'), owner_uid=os.getenv('NOTEBOOKLM_OWNER_UID', 'owner'), owner_unlimited=os.getenv('NOTEBOOKLM_OWNER_UNLIMITED', '0') == '1', checkpoint=notebook_auth.commit.aio)

    @modal.exit()
    def exit(self):
        self.private.cleanup()

    async def publish_health(self):
        policy = self.engine.read_policy()
        health = policy.get('health')
        if health:
            await state('notebook_health', health, policy.get('checked_at', 0))

    @modal.method()
    async def health(self):
        await notebook_auth.reload.aio()
        health = await self.engine.health()
        await self.publish_health()
        await notebook_auth.commit.aio()
        return health

    @modal.method()
    async def count(self):
        await notebook_auth.reload.aio()
        result = await self.engine.count()
        await notebook_auth.commit.aio()
        return result

    @modal.method()
    async def generate(self, job_id, uid, payload, gathered):
        from engines import duration_seconds, run_process
        from notebook_cloud import failure, rejected
        await notebook_auth.reload.aio()
        with tempfile.TemporaryDirectory(dir=self.private.name) as directory:
            output = Path(directory, 'output.mp3')
            try:
                result = await self.engine.generate(job_id, uid, payload, gathered, output, run_process, duration_seconds)
            finally:
                await self.publish_health()
                await notebook_auth.commit.aio()
            if result['ok']:
                try:
                    await audio_volume.reload.aio()
                    shutil.copyfile(output, Path('/audio', job_id + '.mp3'))
                    await audio_volume.commit.aio()
                except Exception as exc:
                    result = rejected(failure(exc))
        await self.publish_health()
        await notebook_auth.commit.aio()
        return result


@app.cls(image=gpu_image, gpu='L4', cpu=2, memory=12288, min_containers=0,
         max_containers=1, scaledown_window=60, timeout=GPU_SECONDS_LIMIT,
         startup_timeout=180, volumes={'/models': models})
@modal.concurrent(max_inputs=1)
class StudioGPU:
    @modal.enter()
    def enter(self):
        self.process = None
        self.current_model = None
        self.kokoro = None
        self.load_seconds = 0.0

    def load_llm(self, model):
        import httpx
        if self.current_model == model and self.process and self.process.poll() is None:
            return
        self.stop_llm()
        started = time.monotonic()
        self.server_log = tempfile.TemporaryFile(mode='w+')
        self.process = subprocess.Popen([
            'python', '-m', 'vllm.entrypoints.openai.api_server', '--model', model, '--revision', MODEL_REVISIONS[model],
            '--host', '127.0.0.1', '--port', '8000', '--max-model-len', '16384',
            '--gpu-memory-utilization', '0.65', '--max-num-seqs', '1',
            '--enforce-eager', '--disable-log-requests', '--uvicorn-log-level', 'error',
        ], stdout=self.server_log, stderr=self.server_log)
        while time.monotonic() - started < 180:
            if self.process.poll() is not None:
                self.server_log.seek(0)
                print(self.server_log.read()[-4000:])
                raise RuntimeError('GPU LLM server failed to start')
            try:
                if httpx.get('http://127.0.0.1:8000/health', timeout=2).status_code == 200:
                    self.current_model = model
                    self.load_seconds += time.monotonic() - started
                    models.commit()
                    return
            except httpx.HTTPError:
                pass
            time.sleep(1)
        self.stop_llm()
        raise RuntimeError('GPU LLM startup timed out')

    def stop_llm(self):
        if self.process and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()
        self.current_model = None

    @modal.exit()
    def exit(self):
        self.stop_llm()

    def dialogue(self, payload, gathered, model):
        import httpx
        from engines import write_script
        from service import PodcastRequest
        from sources import Gathered
        self.load_llm(model)

        class Client:
            async def complete(inner, system, prompt):
                if system.startswith('Write conversational'):
                    from engines import WORDS
                    target = WORDS[payload.get('length', 'default')]
                    prompt += f'\nLength is important: produce approximately {target // 50} alternating turns, each around 50 spoken words. Do not finish after a short overview. Cover distinct evidence-backed details without repeating or inventing facts.'
                async with httpx.AsyncClient(timeout=600, trust_env=False) as client:
                    result = await client.post('http://127.0.0.1:8000/v1/chat/completions', json={
                        'model': model, 'messages': [{'role': 'system', 'content': system}, {'role': 'user', 'content': prompt}],
                        'temperature': 0.65, 'max_tokens': 9000, 'chat_template_kwargs': {'enable_thinking': False},
                    })
                    result.raise_for_status()
                    return result.json()['choices'][0]['message']['content']

        request = PodcastRequest(**payload)
        return asyncio.run(write_script(Client(), request.title, gathered['text'], request.style, request.length, gathered['no_live_sources']))

    @modal.method()
    def prepare(self):
        started = time.monotonic()
        self.load_llm(MODEL)
        return {'model': MODEL, 'gpuSeconds': time.monotonic() - started}

    @modal.method()
    def script(self, payload, gathered):
        started = time.monotonic()
        before = self.load_seconds
        lines = self.dialogue(payload, gathered, MODEL)
        return {'lines': lines, 'model': MODEL, 'gpuSeconds': time.monotonic() - started, 'loadSeconds': self.load_seconds - before}

    @modal.method()
    def benchmark(self, payload, gathered):
        from engines import WORDS
        results = []
        for candidate in CANDIDATES:
            started = time.monotonic()
            before = self.load_seconds
            try:
                lines = self.dialogue(payload, gathered, candidate)
                words = sum(len(line['text'].split()) for line in lines)
                results.append({'model': candidate, 'seconds': time.monotonic() - started,
                                'loadSeconds': self.load_seconds - before, 'words': words,
                                'targetWords': WORDS[payload['length']], 'turns': len(lines),
                                'valid': True, 'lines': lines})
            except Exception as exc:
                results.append({'model': candidate, 'seconds': time.monotonic() - started, 'valid': False, 'errorType': type(exc).__name__})
        self.stop_llm()
        return results

    @modal.method()
    def voices(self, lines):
        started = time.monotonic()
        import numpy as np
        import soundfile as sf
        from kokoro import KModel, KPipeline
        if self.kokoro is None:
            self.kokoro = KPipeline(lang_code='a', model=KModel(repo_id='hexgrad/Kokoro-82M').to('cuda').eval())
        segments = []
        with tempfile.TemporaryDirectory() as directory:
            for index, line in enumerate(lines):
                voice = 'am_michael' if line['speaker'] == 'HOST_A' else 'af_heart'
                chunks = [audio.numpy() for _, _, audio in self.kokoro(line['text'], voice=voice) if audio is not None]
                if not chunks:
                    raise RuntimeError('GPU TTS returned no audio')
                samples = np.concatenate(chunks + [np.zeros(5280, dtype=np.float32)])
                path = Path(directory, f'{index}.wav')
                sf.write(path, samples, 24000)
                segments.append(path.read_bytes())
        models.commit()
        return {'segments': segments, 'voices': ['am_michael', 'af_heart'], 'model': 'hexgrad/Kokoro-82M', 'gpuSeconds': time.monotonic() - started}

    @modal.method()
    def dia_sample(self, lines):
        started = time.monotonic()
        self.stop_llm()
        import torch
        from transformers import AutoProcessor, DiaForConditionalGeneration
        checkpoint = 'nari-labs/Dia-1.6B-0626'
        revision = 'ef2795fcc29c5abe6ffc91fd33808588b49bbc66'
        torch.manual_seed(42)
        processor = AutoProcessor.from_pretrained(checkpoint, revision=revision)
        model = DiaForConditionalGeneration.from_pretrained(checkpoint, revision=revision, torch_dtype=torch.float16).to('cuda')
        text = ' '.join(('[S1] ' if line['speaker'] == 'HOST_A' else '[S2] ') + line['text'] for line in lines)
        inputs = processor(text=[text], padding=True, return_tensors='pt').to('cuda')
        with torch.inference_mode():
            outputs = model.generate(**inputs, max_new_tokens=8192)
        decoded = processor.batch_decode(outputs)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, 'dia.wav')
            processor.save_audio(decoded, str(path))
            data = path.read_bytes()
        del model
        torch.cuda.empty_cache()
        models.commit()
        return {'audio': data, 'model': checkpoint, 'gpuSeconds': time.monotonic() - started}


@app.function(image=base_image, cpu=1, memory=1024, max_containers=2, timeout=600,
              volumes={'/audio': audio_volume})
async def finish(job_id, title, lines, segments=None, cached_id=None):
    from engines import duration_seconds, render_audio, run_process
    started = time.monotonic()
    audio_volume.reload()
    output = Path('/audio', job_id + '.mp3')
    with tempfile.TemporaryDirectory() as directory:
        temporary = Path(directory, 'output.mp3')
        if cached_id:
            shutil.copyfile(Path('/audio', cached_id + '.mp3'), temporary)
        elif segments is None:
            await render_audio(lines, temporary, title)
        else:
            paths = []
            for index, data in enumerate(segments):
                path = Path(directory, f'{index}.wav')
                path.write_bytes(data)
                paths.append(path)
            concat = Path(directory, 'segments.txt')
            concat.write_text(''.join(f"file '{path}'\n" for path in paths))
            await run_process('ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-f', 'concat', '-safe', '0', '-i', str(concat), '-af', 'loudnorm=I=-16:TP=-1.5:LRA=11', '-ar', '44100', '-b:a', '128k', '-metadata', f'title={title}', '-metadata', 'artist=Mavis Podcast Lounge', '-f', 'mp3', str(temporary))
        duration = await duration_seconds(temporary)
        shutil.copyfile(temporary, output)
    audio_volume.commit()
    return {'durationSec': duration, 'seconds': time.monotonic() - started, 'bytes': output.stat().st_size}


async def tracked(job_id, function, *args, timeout=600, terminate_containers=True):
    call = await function.spawn.aio(*args)
    try:
        await state('call', job_id, call.object_id)
        return await call.get.aio(timeout=timeout)
    except BaseException:
        await call.cancel.aio(terminate_containers=terminate_containers)
        raise


async def optional_llm(payload, gathered):
    import httpx
    from engines import write_script
    from service import PodcastRequest
    from sources import Gathered
    if not all(os.getenv(key) for key in ('STUDIO_FALLBACK_LLM_URL', 'STUDIO_FALLBACK_LLM_KEY', 'STUDIO_FALLBACK_LLM_MODEL')):
        raise RuntimeError('GPU script failed; optional API fallback is not configured')

    class Client:
        async def complete(self, system, prompt):
            async with httpx.AsyncClient(timeout=180, trust_env=False) as client:
                result = await client.post(os.environ['STUDIO_FALLBACK_LLM_URL'], headers={'Authorization': 'Bearer ' + os.environ['STUDIO_FALLBACK_LLM_KEY']}, json={'model': os.environ['STUDIO_FALLBACK_LLM_MODEL'], 'messages': [{'role': 'system', 'content': system}, {'role': 'user', 'content': prompt}], 'max_tokens': 9000})
                result.raise_for_status()
                return result.json()['choices'][0]['message']['content']
    request = PodcastRequest(**payload)
    return await write_script(Client(), request.title, gathered['text'], request.style, request.length, gathered['no_live_sources'])


@app.function(image=base_image, cpu=0.25, memory=512, max_containers=2, timeout=3000,
              secrets=[studio_secret])
async def pipeline(job_id):
    from cloud_budget import GPUBudget, GPUBudgetExceeded
    from cloud_state import content_hash
    from fastapi import HTTPException
    from service import PodcastRequest
    from sources import Gathered
    started = time.monotonic()
    timings = {}
    budget = GPUBudget(GPU_SECONDS_LIMIT)
    try:
        job = await state('claim', job_id)
        if job is None:
            return
        payload = job['_request']
        gathered = await tracked(job_id, source, payload, timeout=250)
        timings['source'] = gathered['seconds']
        await state('update', job_id, None, {'sources': gathered['sources']})
        if payload['engine'] != 'local':
            from notebook_cloud import failure, rejected, use_local
            await state('update', job_id, 'generating', {'progress': 35, 'stage': 'Generating NotebookLM audio'})
            before = time.monotonic()
            try:
                notebook_result = await tracked(job_id, NotebookLM().generate, job_id, job['owner_uid'], payload, gathered, timeout=2700, terminate_containers=False)
            except Exception as exc:
                notebook_result = rejected(failure(exc))
            timings['notebooklm'] = time.monotonic() - before
            if not notebook_result['ok']:
                await state('update', job_id, None, {'_notebookFailure': notebook_result['failure'], '_notebookErrorType': notebook_result.get('errorType')})
            if not use_local(payload['engine'], notebook_result):
                timings['total'] = time.monotonic() - started
                await state('update', job_id, 'ready', {'engine': 'notebooklm', 'progress': 100, 'stage': 'Ready', 'durationSec': notebook_result['durationSec'], '_timings': timings, '_bytes': notebook_result['bytes'], '_gpuSeconds': 0.0, '_gpuBudgetSeconds': 0.0, '_cacheHit': False})
                return
            await state('update', job_id, None, {'engine': 'local', '_notebookFailure': notebook_result['failure']})
        digest = content_hash(PodcastRequest(**payload), Gathered(gathered['text'], gathered['sources'], gathered['no_live_sources']), PIPELINE_VERSION)
        cached = await state('cache', job_id, digest)
        await state('update', job_id, 'writing', {'progress': 25, 'stage': "Writing Alex and Sam's dialogue"})
        if cached:
            await state('update', job_id, 'generating', {'progress': 55, 'stage': 'Reusing cached audio'})
            result = await tracked(job_id, finish, job_id, payload['title'], [], None, cached['id'])
            transcript = cached.get('transcript', '')
            model_used, voices_used = cached.get('_llmUsed'), cached.get('_voicesUsed')
        else:
            gpu = StudioGPU()
            before = time.monotonic()
            try:
                written = await budget.run(tracked, job_id, gpu.script, payload, gathered)
                lines = written['lines']
                model_used = written['model']
                timings['llmLoad'] = written.get('loadSeconds', 0.0)
            except GPUBudgetExceeded:
                raise
            except Exception:
                lines = await optional_llm(payload, gathered)
                model_used = 'Optional API fallback'
            timings['script'] = time.monotonic() - before
            if payload['engine'] != 'local':
                lines.insert(0, {'speaker': 'HOST_A', 'text': 'This podcast was made with the local engine.'})
            transcript = '\n\n'.join(('Alex' if line['speaker'] == 'HOST_A' else 'Sam') + ': ' + line['text'] for line in lines)
            await state('update', job_id, 'generating', {'progress': 55, 'stage': 'Synthesizing two-host audio'})
            before = time.monotonic()
            try:
                voiced = await budget.run(tracked, job_id, gpu.voices, lines)
                segments, voices_used = voiced['segments'], voiced['voices']
            except GPUBudgetExceeded:
                raise
            except Exception:
                segments, voices_used = None, ['en-US-AndrewNeural', 'en-US-AriaNeural']
            timings['voices'] = time.monotonic() - before
            result = await tracked(job_id, finish, job_id, payload['title'], lines, segments)
        timings['finish'] = result['seconds']
        timings['total'] = time.monotonic() - started
        await state('update', job_id, 'ready', {'progress': 100, 'stage': 'Ready', 'engine': 'local', 'durationSec': result['durationSec'], 'transcript': transcript, '_timings': timings, '_gpuSeconds': budget.execution_seconds, '_gpuBudgetSeconds': budget.seconds, '_llmUsed': model_used, '_voicesUsed': voices_used, '_cacheHit': bool(cached), '_bytes': result['bytes']})
        print(json.dumps({'jobId': job_id, 'timings': timings, 'gpuSeconds': budget.execution_seconds, 'gpuBudgetSeconds': budget.seconds, 'cacheHit': bool(cached)}))
    except HTTPException as exc:
        if exc.status_code != 404:
            raise
    except Exception as exc:
        from notebook_cloud import MESSAGES
        error = str(exc) if str(exc) in MESSAGES.values() else 'GPU budget exceeded (900 seconds); generation stopped' if 'GPU budget exceeded' in str(exc) else 'Generation failed (' + type(exc).__name__ + '); check source or engine connectivity'
        try:
            await state('update', job_id, 'failed', {'error': error, 'stage': 'Failed', '_timings': timings, '_gpuSeconds': budget.execution_seconds, '_gpuBudgetSeconds': budget.seconds})
        except HTTPException:
            pass
        print(json.dumps({'jobId': job_id, 'errorType': type(exc).__name__, 'stage': 'failed'}))


class CloudManager:
    def __init__(self):
        self.audio_lock = asyncio.Lock()
        self.health_lock = asyncio.Lock()
        self.health_call = None

    async def notebook_health(self):
        snapshot = await state('notebook_health')
        if time.time() - snapshot['checked_at'] < 300:
            return snapshot['health']
        async with self.health_lock:
            try:
                if self.health_call is None:
                    self.health_call = await NotebookLM().health.spawn.aio()
                result = await self.health_call.get.aio(timeout=25)
                self.health_call = None
                return result
            except modal.exception.TimeoutError:
                return snapshot['health']
            except Exception:
                self.health_call = None
                return {'available': False, 'loggedIn': False, 'note': 'NotebookLM is temporarily unavailable'}

    async def start(self):
        pass

    async def close(self):
        pass

    async def enqueue(self, request, uid='owner', public=False):
        job = await state('admit', request.model_dump(), uid, public)
        call = await pipeline.spawn.aio(job['id'])
        try:
            await state('call', job['id'], call.object_id)
        except BaseException:
            await call.cancel.aio(terminate_containers=True)
            raise
        return job

    async def all(self):
        return await state('all')

    async def get(self, job_id, uid=None):
        return await state('get', job_id, uid)

    async def delete(self, job_id, uid=None):
        calls = await state('delete', job_id, uid)
        for call_id in calls:
            await modal.FunctionCall.from_id(call_id).cancel.aio(terminate_containers=False)
        await state('cleanup_audio', job_id)

    async def audio_path(self, job_id):
        from fastapi import HTTPException
        async with self.audio_lock:
            await audio_volume.reload.aio()
            source_path = Path('/audio', job_id + '.mp3')
            if not source_path.is_file():
                raise HTTPException(409, 'Podcast audio is missing')
            local_path = Path('/tmp', job_id + '.mp3')
            await asyncio.to_thread(shutil.copyfile, source_path, local_path)
            return local_path


@app.function(image=base_image, cpu=0.25, memory=512, min_containers=0, max_containers=1,
              scaledown_window=60, timeout=300, secrets=[studio_secret], env=studio_env, volumes={'/audio': audio_volume})
@modal.concurrent(max_inputs=32)
@modal.asgi_app()
def web():
    from service import create_app
    return create_app(token=os.environ['OWNER_BEARER_TOKEN'], shared_secret=os.environ['STUDIO_SHARED_SECRET'], manager_factory=CloudManager)


@app.function(image=base_image, cpu=0.25, memory=512, max_containers=1, timeout=300,
              schedule=modal.Period(hours=1))
async def maintenance():
    await state('prune')
    for job_id in await state('recover'):
        await pipeline.spawn.aio(job_id)
