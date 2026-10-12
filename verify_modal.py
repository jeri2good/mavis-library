import argparse
import asyncio
import hashlib
import hmac
import json
import os
import subprocess
import time
import uuid
from pathlib import Path

import httpx
import modal


def signature(secret, job_id, uid, exp=None):
    exp = str(int(time.time()) + 600 if exp is None else exp)
    sig = hmac.new(secret.encode(), f'{job_id}.{exp}.{uid}'.encode(), hashlib.sha256).hexdigest()
    return {'exp': exp, 'uid': uid, 'sig': sig}


async def main(url, mode, resume_topic=None, resume_alice=None):
    secret = (Path.home() / '.secure/podcast-studio-shared-secret').read_text().strip()
    state = modal.Cls.from_name('mavis-podcast-studio', 'State')()
    resume_id = resume_topic or resume_alice
    existing = (await state.execute.remote.aio('get', (resume_id,)))['value'] if resume_id else None
    uid = existing['owner_uid'] if existing else str(uuid.uuid4())
    other = str(uuid.uuid4())
    headers = {'X-Studio-Key': secret, 'X-Mavis-User': uid}
    results = {'url': url, 'jobs': [], 'checks': {}}
    transport = httpx.AsyncHTTPTransport(retries=3)
    async with httpx.AsyncClient(base_url=url, timeout=httpx.Timeout(120, connect=20), trust_env=False, transport=transport) as client:
        started = time.monotonic()
        response = await client.get('/v1/health')
        assert response.status_code == 200 and response.json()['ok'] is True
        results['coldHealthSeconds'] = time.monotonic() - started
        for label, bad_headers in [('missing_key', {}), ('wrong_key', {'X-Studio-Key': 'wrong', 'X-Mavis-User': uid})]:
            response = await client.get('/v1/podcasts', headers=bad_headers)
            results['checks'][label] = response.status_code
            assert response.status_code == 401
        payloads = [
            ('topic', {'kind': 'topic', 'title': 'How vaccines train the immune system', 'query': 'how vaccines train the immune system', 'length': 'short', 'engine': 'local'}),
            ('alice', {'kind': 'book', 'title': 'Alice in Wonderland', 'gutenbergId': '11', 'length': 'short', 'engine': 'local'}),
        ] if mode == 'short' else [('default', {'kind': 'book', 'title': 'Alice in Wonderland: default length', 'gutenbergId': '11', 'length': 'default', 'engine': 'local'})]
        if mode == 'warm':
            from benchmark_fixture import first_real_chapter
            gathered = await modal.Function.from_name('mavis-podcast-studio', 'source').remote.aio({'kind': 'book', 'title': 'Alice', 'gutenbergId': '11'})
            chapter = first_real_chapter(gathered['text'])
            payloads = [('warm', {'kind': 'chapter', 'title': 'Alice opening chapter: warm timing', 'text': chapter, 'length': 'short', 'engine': 'local'})]
            gpu = modal.Cls.from_name('mavis-podcast-studio', 'StudioGPU')()
            results['warmPreparation'] = await gpu.prepare.remote.aio()
            stats = await gpu.script.get_current_stats.aio()
            results['gpuBeforeCreate'] = {'runners': stats.num_total_runners, 'runningInputs': stats.num_running_inputs}
            assert stats.num_total_runners == 1 and stats.num_running_inputs == 0, 'Warm test requires an already-idle GPU container'
        for label, payload in payloads:
            started = time.monotonic()
            resumed_id = {'topic': resume_topic, 'alice': resume_alice}.get(label)
            if resumed_id:
                job_id, create_seconds = resumed_id, None
                print(json.dumps({'sample': label, 'id': job_id, 'resumed': True}), flush=True)
            else:
                response = await client.post('/v1/podcasts', headers=headers, json=payload)
                assert response.status_code == 202, f'Create failed: HTTP {response.status_code}'
                job_id = response.json()['id']
                create_seconds = time.monotonic() - started
                print(json.dumps({'sample': label, 'id': job_id, 'createStatus': response.status_code, 'createSeconds': create_seconds}), flush=True)
            previous = None
            while time.monotonic() - started < 1800:
                response = await client.get('/v1/podcasts/' + job_id, headers=headers)
                assert response.status_code == 200
                job = response.json()
                if job['status'] != previous:
                    print(json.dumps({'sample': label, 'status': job['status'], 'elapsedSeconds': time.monotonic() - started}), flush=True)
                    previous = job['status']
                if job['status'] in ('ready', 'failed'):
                    break
                await asyncio.sleep(8)
            if job['status'] != 'ready':
                raise RuntimeError(f"Sample {label} did not become ready: {job.get('error', job['status'])}")
            path = '/v1/podcasts/' + job_id + '/audio'
            params = signature(secret, job_id, uid)
            response = await client.get(path, params=params)
            assert response.status_code == 200
            output = Path(f'data/modal-sample-{label}.mp3')
            output.write_bytes(response.content)
            probe = subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration,size:format_tags=title,artist', '-of', 'json', str(output)], check=True, capture_output=True, text=True)
            info = json.loads(probe.stdout)['format']
            response = await client.get(path, params=params, headers={'Range': 'bytes=0-1023'})
            assert response.status_code == 206 and len(response.content) == 1024
            assert response.headers['content-range'].startswith('bytes 0-1023/')
            assert response.headers['cache-control'] == 'private, no-store'
            results['checks'][label + '_range'] = response.status_code
            tamper = dict(params, sig='0' * 64)
            expired = signature(secret, job_id, uid, int(time.time()) - 1)
            wrong_uid = dict(params, uid=other)
            for test, capability in [('tamper', tamper), ('expired', expired), ('wrong_uid_signature', wrong_uid)]:
                response = await client.get(path, params=capability)
                assert response.status_code == 401
                results['checks'][label + '_' + test] = response.status_code
            response = await client.get(path, params=signature(secret, job_id, other))
            assert response.status_code == 404
            results['checks'][label + '_valid_other_uid_audio'] = response.status_code
            response = await client.get('/v1/podcasts/' + job_id, headers={'X-Studio-Key': secret, 'X-Mavis-User': other})
            assert response.status_code == 404
            results['checks'][label + '_other_uid_metadata'] = response.status_code
            record = (await state.execute.remote.aio('get', (job_id, uid)))['value']
            if mode == 'warm':
                assert record['_timings']['llmLoad'] == 0, 'Warm job unexpectedly reloaded its LLM'
            summary = {'sample': label, 'id': job_id, 'createSeconds': create_seconds, 'totalSeconds': time.monotonic() - started, 'ffprobe': info,
                       'timings': record.get('_timings'), 'gpuSeconds': record.get('_gpuSeconds'), 'gpuBudgetSeconds': record.get('_gpuBudgetSeconds'),
                       'model': record.get('_llmUsed'), 'voices': record.get('_voicesUsed'), 'gpuCostEstimateUSD': record.get('_gpuSeconds', 0) * 0.000222}
            results['jobs'].append(summary)
            Path(f'data/modal-verified-{mode}.json').write_text(json.dumps(results, indent=2))
            print(json.dumps(summary), flush=True)
        if mode == 'short':
            payload = payloads[-1][1]
            started = time.monotonic()
            response = await client.post('/studio/v1/podcasts', headers=headers, json=payload)
            assert response.status_code == 202
            cached_id = response.json()['id']
            for _ in range(40):
                response = await client.get('/v1/podcasts/' + cached_id, headers=headers)
                if response.json()['status'] == 'ready':
                    break
                await asyncio.sleep(3)
            assert response.json()['status'] == 'ready'
            record = (await state.execute.remote.aio('get', (cached_id, uid)))['value']
            assert record['_cacheHit'] and record['_gpuSeconds'] == 0
            results['cache'] = {'seconds': time.monotonic() - started, 'gpuSeconds': 0, 'id': cached_id}
            response = await client.delete('/v1/podcasts/' + cached_id, headers=headers)
            assert response.status_code == 204 and response.content == b''
            assert (await client.get('/v1/podcasts/' + cached_id, headers=headers)).status_code == 404
            results['checks']['delete'] = 204
        Path(f'data/modal-verified-{mode}.json').write_text(json.dumps(results, indent=2))
        print(json.dumps({'checks': results['checks'], 'cache': results.get('cache')}), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--url', required=True)
    parser.add_argument('--mode', choices=['short', 'default', 'warm'], default='short')
    parser.add_argument('--resume-topic')
    parser.add_argument('--resume-alice')
    args = parser.parse_args()
    try:
        asyncio.run(main(args.url, args.mode, args.resume_topic, args.resume_alice))
    except httpx.HTTPError as exc:
        print('Verification transport failed: ' + type(exc).__name__)
        raise SystemExit(1)
