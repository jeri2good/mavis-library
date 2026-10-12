import asyncio
import hashlib
import hmac
import json
import os
import subprocess
import time
from pathlib import Path

import httpx


URL = 'https://jermaine-hawthorne--mavis-podcast-studio-web.modal.run'
ALICE_ID = '0d9707f3-f4ee-4b10-a282-f88c31ecfc5b'


def deploy(disabled):
    env = dict(os.environ)
    if disabled:
        env['STUDIO_ENABLED'] = '0'
    else:
        env.pop('STUDIO_ENABLED', None)
    log = Path('data/kill-disabled-deploy.log' if disabled else 'data/kill-restored-deploy.log')
    with log.open('w') as stream:
        subprocess.run(['modal', 'deploy', 'modal_app.py'], env=env, stdout=stream, stderr=subprocess.STDOUT, check=True)


async def check_disabled():
    secret = (Path.home() / '.secure/podcast-studio-shared-secret').read_text().strip()
    headers = {'X-Studio-Key': secret, 'X-Mavis-User': 'owner'}
    async with httpx.AsyncClient(base_url=URL, timeout=120, transport=httpx.AsyncHTTPTransport(retries=3), trust_env=False) as client:
        assert (await client.get('/v1/health')).json() == {'ok': True}
        payload = {'kind': 'topic', 'title': 'Disabled studio guard check', 'query': 'test', 'length': 'short'}
        response = await client.post('/v1/podcasts', headers=headers, json=payload)
        assert response.status_code == 503
        assert response.json() == {'detail': 'Studio generation is disabled'}
        assert (await client.post('/v1/podcasts', json=payload)).status_code == 401
        print('Live kill-switch: authenticated create 503; missing-key create 401; health 200.', flush=True)


async def check_restored():
    secret = (Path.home() / '.secure/podcast-studio-shared-secret').read_text().strip()
    owner = (Path.home() / '.secure/podcast-lounge-token').read_text().strip()
    headers = {'Authorization': 'Bearer ' + owner}
    async with httpx.AsyncClient(base_url=URL, timeout=120, transport=httpx.AsyncHTTPTransport(retries=3), trust_env=False) as client:
        response = await client.get('/v1/health')
        assert response.status_code == 200 and response.json() == {'ok': True}
        response = await client.get('/v1/podcasts/' + ALICE_ID, headers=headers)
        assert response.status_code == 200 and response.json()['status'] == 'ready'
        assert 'owner_uid' not in response.json()
        response = await client.get('/v1/podcasts', headers=headers)
        assert response.status_code == 200 and any(job['id'] == ALICE_ID for job in response.json()['items'])
        exp = str(int(time.time()) + 600)
        sig = hmac.new(secret.encode(), f'{ALICE_ID}.{exp}.owner'.encode(), hashlib.sha256).hexdigest()
        response = await client.get('/studio/v1/podcasts/' + ALICE_ID + '/audio', params={'exp': exp, 'uid': 'owner', 'sig': sig}, headers={'Range': 'bytes=0-1023'})
        assert response.status_code == 206 and len(response.content) == 1024
        print('Restored deployment: health 200; Bearer get/list 200; persisted signed prefix audio 206.', flush=True)


if __name__ == '__main__':
    try:
        deploy(True)
        asyncio.run(check_disabled())
    finally:
        deploy(False)
    asyncio.run(check_restored())
    Path('data/modal-kill-switch.json').write_text(json.dumps({'disabledCreate': 503, 'disabledMissingKey': 401, 'restoredHealth': 200, 'ownerBearerGet': 200, 'ownerBearerList': 200, 'restoredPrefixSignedRange': 206, 'restored': True}, indent=2))
