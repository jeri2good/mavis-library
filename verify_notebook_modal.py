import argparse
import asyncio
import json
import subprocess
import time
import uuid
from pathlib import Path

import httpx
import modal

from benchmark_fixture import first_real_chapter
from verify_modal import signature


async def main(url, resume=None):
    shared = (Path.home() / '.secure/podcast-studio-shared-secret').read_text().strip()
    actor = modal.Cls.from_name('mavis-podcast-studio', 'NotebookLM')()
    store = modal.Cls.from_name('mavis-podcast-studio', 'State')()
    results_path = Path('data/notebook-live-results.json')
    results = json.loads(results_path.read_text()) if resume and results_path.is_file() else {'jobs': []}
    results['beforeCount'] = await actor.count.remote.aio()
    print(json.dumps({'beforeCount': results['beforeCount']}), flush=True)
    gathered = await modal.Function.from_name('mavis-podcast-studio', 'source').remote.aio({'kind': 'book', 'title': 'Alice', 'gutenbergId': '11'})
    text = first_real_chapter(gathered['text'])[:6000]
    transport = httpx.AsyncHTTPTransport(retries=3)
    async with httpx.AsyncClient(base_url=url, timeout=httpx.Timeout(120, connect=20), trust_env=False, transport=transport) as client:
        health = await client.get('/v1/health')
        assert health.status_code == 200
        results['healthBefore'] = health.json()
        print(json.dumps({'healthBefore': results['healthBefore']}), flush=True)
        for engine in ('notebooklm', 'auto'):
            existing = next((j for j in results['jobs'] if j['requestedEngine'] == engine), None)
            if existing and existing.get('status') in ('ready', 'failed'):
                continue
            started = time.monotonic()
            if existing:
                job_id = existing['id']
                record = (await store.execute.remote.aio('get', (job_id,)))['value']
                uid = record['owner_uid']
                summary = existing
            else:
                uid = str(uuid.uuid4())
                headers = {'X-Studio-Key': shared, 'X-Mavis-User': uid}
                response = await client.post('/v1/podcasts', headers=headers, json={'kind': 'chapter', 'title': 'Alice opening: NotebookLM proof ' + engine, 'text': text, 'style': 'brief', 'length': 'short', 'language': 'en', 'engine': engine})
                assert response.status_code == 202, 'Create HTTP ' + str(response.status_code)
                job_id = response.json()['id']
                summary = {'id': job_id, 'requestedEngine': engine, 'createdAtUnix': time.time(), 'createSeconds': time.monotonic() - started}
                results['jobs'].append(summary)
                results_path.write_text(json.dumps(results, indent=2))
            headers = {'X-Studio-Key': shared, 'X-Mavis-User': uid}
            print(json.dumps({'id': job_id, 'requestedEngine': engine}), flush=True)
            previous = None
            while time.monotonic() - started < 3300:
                response = await client.get('/v1/podcasts/' + job_id, headers=headers)
                assert response.status_code == 200, 'Poll HTTP ' + str(response.status_code)
                job = response.json()
                if job['status'] != previous:
                    print(json.dumps({'id': job_id, 'status': job['status'], 'engine': job['engine'], 'elapsedSeconds': time.monotonic() - started}), flush=True)
                    previous = job['status']
                if job['status'] in ('ready', 'failed'):
                    break
                await asyncio.sleep(10)
            summary.update(status=job['status'], engine=job['engine'], wallSeconds=time.time() - summary['createdAtUnix'])
            record = (await store.execute.remote.aio('get', (job_id,)))['value']
            summary.update(timings=record.get('_timings'), failureClass=record.get('_notebookFailure'), errorType=record.get('_notebookErrorType'), gpuSeconds=record.get('_gpuSeconds'))
            if job['status'] == 'ready':
                response = await client.get('/v1/podcasts/' + job_id + '/audio', params=signature(shared, job_id, uid))
                assert response.status_code == 200, 'Signed download HTTP ' + str(response.status_code)
                output = Path('data/notebook-proof-' + engine + '.mp3')
                output.write_bytes(response.content)
                probe = subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration,size:format_tags=title,artist:stream=codec_name,sample_rate,channels', '-of', 'json', str(output)], check=True, capture_output=True, text=True)
                summary.update(durationSec=job['durationSec'], bytes=len(response.content), ffprobe=json.loads(probe.stdout), signedAudioStatus=response.status_code, localNote='made with the local engine' in (job.get('transcript') or ''))
                assert summary['ffprobe']['streams'][0]['codec_name'] == 'mp3'
                assert summary['ffprobe']['format']['tags']['artist'] == 'Mavis Podcast Lounge'
                if job['engine'] == 'local':
                    assert summary['localNote']
            elif job['status'] == 'failed':
                summary['error'] = job.get('error')
            results_path.write_text(json.dumps(results, indent=2))
            print(json.dumps(summary), flush=True)
            results['afterCount'] = await actor.count.remote.aio()
            results_path.write_text(json.dumps(results, indent=2))
            print(json.dumps({'afterCount': results['afterCount']}), flush=True)
        health = await client.get('/v1/health')
        results['healthAfter'] = health.json()
        results_path.write_text(json.dumps(results, indent=2))
        print(json.dumps({'healthAfter': results['healthAfter']}), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--url', required=True)
    parser.add_argument('--resume', action='store_true')
    args = parser.parse_args()
    try:
        asyncio.run(main(args.url, args.resume))
    except Exception as exc:
        print(json.dumps({'verificationErrorType': type(exc).__name__, 'note': 'Resume saved IDs; raw transport exceptions are intentionally not printed'}), flush=True)
        raise SystemExit(1) from None
