import asyncio
import json
import time
from pathlib import Path

import modal

from benchmark_fixture import first_real_chapter


async def main():
    source = modal.Function.from_name('mavis-podcast-studio', 'source')
    gathered = await source.remote.aio({'kind': 'book', 'title': 'Alice', 'gutenbergId': '11', 'length': 'short'})
    chapter = first_real_chapter(gathered['text'])
    evidence = {'text': chapter, 'sources': gathered['sources'], 'no_live_sources': False}
    payload = {'kind': 'chapter', 'title': 'Alice: Down the Rabbit-Hole', 'text': chapter, 'length': 'short', 'style': 'deep-dive'}
    gpu = modal.Cls.from_name('mavis-podcast-studio', 'StudioGPU')()
    started = time.monotonic()
    results = await gpu.benchmark.remote.aio(payload, evidence)
    expected = [term for term in ('rabbit', 'watch', 'waistcoat', 'orange marmalade', 'bottle', 'key', 'door', 'curiosity') if term in chapter.lower()]
    for result in results:
        dialogue = ' '.join(line['text'] for line in result.get('lines', [])).lower()
        result['groundedDetailCoverage'] = sum(term in dialogue for term in expected)
        result['groundedDetailTotal'] = len(expected)
    Path('data/modal-benchmark-real-chapter.json').write_text(json.dumps({'totalSeconds': time.monotonic() - started, 'chapterChars': len(chapter), 'results': results}, indent=2))
    for result in results:
        print(json.dumps({key: value for key, value in result.items() if key != 'lines'}), flush=True)
    valid = [result for result in results if result['valid']]
    if not valid:
        raise RuntimeError('Both GPU candidates failed')
    chosen = min(valid, key=lambda result: abs(result['words'] / result['targetWords'] - 1))
    voiced = await gpu.voices.remote.aio(chosen['lines'][:4])
    Path('data/modal-voice-benchmark.json').write_text(json.dumps({key: value for key, value in voiced.items() if key != 'segments'}, indent=2))
    for index, data in enumerate(voiced['segments']):
        Path(f'data/modal-voice-{index}.wav').write_bytes(data)
    print(json.dumps({key: value for key, value in voiced.items() if key != 'segments'}), flush=True)


if __name__ == '__main__':
    asyncio.run(main())
