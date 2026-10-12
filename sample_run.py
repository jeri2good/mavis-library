import asyncio
import json
import shutil
from pathlib import Path

import httpx

from engines import VOICES

ROOT = Path(__file__).resolve().parent


async def run():
    token = (Path.home() / ".secure/podcast-lounge-token").read_text().strip()
    async with httpx.AsyncClient(base_url="http://127.0.0.1:8141", headers={"Authorization": "Bearer " + token}, timeout=30) as client:
        print("Health:", (await client.get("/v1/health")).json(), flush=True)
        results = []
        for sample, body in [
            ("alice", {"kind": "book", "title": "Alice in Wonderland — A Curious Conversation", "gutenbergId": "11", "length": "short", "style": "deep-dive", "engine": "local"}),
            ("vaccines", {"kind": "topic", "title": "How Vaccines Train the Immune System", "query": "how do vaccines train the immune system", "length": "short", "style": "deep-dive", "engine": "local"}),
        ]:
            response = await client.post("/v1/podcasts", json=body)
            response.raise_for_status()
            job_id = response.json()["id"]
            previous = None
            for _ in range(450):
                response = await client.get("/v1/podcasts/" + job_id)
                response.raise_for_status()
                job = response.json()
                if job["status"] != previous:
                    print(sample, job_id, job["status"], job["stage"], flush=True)
                    previous = job["status"]
                if job["status"] in ("ready", "failed"):
                    break
                await asyncio.sleep(4)
            if job["status"] != "ready":
                raise RuntimeError(sample + ": " + job.get("error", "timed out"))
            path = ROOT / "data" / ("sample-" + sample + ".mp3")
            shutil.copyfile(ROOT / "data" / (job_id + ".mp3"), path)
            (ROOT / "data" / ("sample-" + sample + "-transcript.txt")).write_text(job.get("transcript", ""))
            audio = await client.get("/v1/podcasts/" + job_id + "/audio", headers={"Range": "bytes=0-99"})
            assert audio.status_code == 206 and len(audio.content) == 100
            result = {"sample": sample, "id": job_id, "path": str(path), "durationSec": job["durationSec"], "bytes": path.stat().st_size, "sources": job["sources"], "voices": VOICES, "rangeStatus": audio.status_code, "spokenWords": len(job.get("transcript", "").split())}
            results.append(result)
            print(json.dumps(result), flush=True)
            (ROOT / "data/sample-results.json").write_text(json.dumps(results, indent=2))


if __name__ == "__main__":
    asyncio.run(run())
