import asyncio
import hashlib
import json
import os
import sqlite3
import time
import uuid
from pathlib import Path

from fastapi import HTTPException

from service import JobManager, PodcastRequest, TERMINAL, studio_enabled, timestamp, uid_hash


class StateStore(JobManager):
    def __init__(self, directory, audio_dir):
        self.data_dir = Path(directory)
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.audio_dir = Path(audio_dir)
        self.db = sqlite3.connect(self.data_dir / 'jobs.sqlite')
        self.db.execute('PRAGMA journal_mode=DELETE')
        self.db.execute('PRAGMA synchronous=FULL')
        self.db.execute('CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, record TEXT NOT NULL)')
        self.db.execute('CREATE TABLE IF NOT EXISTS create_events (uid_hash TEXT NOT NULL, created REAL NOT NULL)')
        self.db.execute('CREATE INDEX IF NOT EXISTS create_events_time ON create_events (created)')
        self.db.commit()
        self.global_create_limit = 60
        self.removed = []

    async def _delete(self, job_id):
        self.get(job_id)
        self.db.execute('DELETE FROM jobs WHERE id=?', (job_id,))
        self.db.commit()
        self.removed.append(job_id)

    def admit(self, payload, uid, public):
        if not studio_enabled():
            raise HTTPException(503, 'Studio generation is disabled')
        request = PodcastRequest(**payload)
        asyncio.run(self._prune())
        self.check_create_limits(uid, public)
        jobs = self.all()
        if len(jobs) >= 50:
            victim = next((job for job in reversed(jobs) if job['status'] in TERMINAL), None)
            if victim is None:
                raise HTTPException(429, 'Podcast queue is full (50 jobs); wait or delete a job')
            asyncio.run(self._delete(victim['id']))
        job_id = str(uuid.uuid4())
        job = {'id': job_id, 'owner_uid': uid, 'status': 'queued', 'progress': 0, 'stage': 'Queued', 'engine': 'local', 'title': request.title, 'kind': request.kind, 'style': request.style, 'createdAt': timestamp(), 'sources': [], '_request': request.model_dump(), '_calls': [], '_gpuSeconds': 0.0}
        now = time.time()
        with self.db:
            self.db.execute('DELETE FROM create_events WHERE created<=?', (now - 3600,))
            self.db.execute('INSERT INTO create_events VALUES (?, ?)', (uid_hash(uid, 64), now))
            self.db.execute('INSERT INTO jobs VALUES (?, ?)', (job_id, json.dumps(job)))
        return job

    def claim(self, job_id):
        job = self.get(job_id)
        if job['status'] != 'queued':
            return None
        return self.update(job_id, 'sourcing', progress=10, stage='Gathering source material', _started=time.time())

    def cache(self, job_id, digest):
        job = self.get(job_id)
        for other in self.all():
            if other['owner_uid'] == job['owner_uid'] and other.get('_contentHash') == digest and other['status'] == 'ready' and (self.audio_dir / (other['id'] + '.mp3')).is_file():
                return other
        self.update(job_id, _contentHash=digest)
        return None

    def dispatch(self, operation, args):
        if operation == 'admit':
            return self.admit(*args)
        if operation == 'all':
            return self.all()
        if operation == 'get':
            return self.get(*args)
        if operation == 'update':
            job_id, status, changes = args
            job = self.get(job_id)
            if job['status'] in TERMINAL:
                return job
            if status in TERMINAL:
                changes = {**changes, '_calls': []}
            return self.update(job_id, status, **changes)
        if operation == 'claim':
            return self.claim(*args)
        if operation == 'cache':
            return self.cache(*args)
        if operation == 'call':
            job_id, call_id = args
            job = self.get(job_id)
            calls = job.get('_calls', []) + [call_id]
            self.update(job_id, _calls=calls)
            return True
        if operation == 'delete':
            job = self.get(*args)
            asyncio.run(self._delete(job['id']))
            return job.get('_calls', [])
        if operation == 'cleanup_audio':
            self.removed.append(args[0])
            return True
        if operation == 'prune':
            asyncio.run(self._prune())
            return self.removed
        if operation == 'recover':
            now = time.time()
            pending = []
            for job in self.all():
                if job['status'] == 'queued':
                    pending.append(job['id'])
                elif job['status'] not in TERMINAL and now - job.get('_started', now) > 1800:
                    self.update(job['id'], 'failed', error='restarted', stage='Interrupted cloud worker')
            return pending
        raise ValueError('Unknown state operation')


def content_hash(request, gathered, version):
    payload = {'text': gathered.text, 'style': request.style, 'length': request.length, 'language': request.language, 'title': request.title, 'no_live_sources': gathered.no_live_sources, 'notebook_note': request.engine == 'notebooklm', 'version': version}
    return hashlib.sha256(json.dumps(payload, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
