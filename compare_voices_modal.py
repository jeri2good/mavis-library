import asyncio
import io
import json
import subprocess
import wave
from pathlib import Path

import modal


async def main():
    benchmark = json.loads(Path('data/modal-benchmark-real-chapter.json').read_text())
    chosen = next(result for result in benchmark['results'] if result['model'] == 'Qwen/Qwen3-4B-Instruct-2507')
    lines = chosen['lines'][:2]
    gpu = modal.Cls.from_name('mavis-podcast-studio', 'StudioGPU')()
    kokoro = await gpu.voices.remote.aio(lines)
    output = io.BytesIO()
    with wave.open(output, 'wb') as combined:
        for index, data in enumerate(kokoro['segments']):
            with wave.open(io.BytesIO(data), 'rb') as source:
                if index == 0:
                    combined.setparams(source.getparams())
                combined.writeframes(source.readframes(source.getnframes()))
    Path('data/modal-compare-kokoro.wav').write_bytes(output.getvalue())
    results = {'scriptWords': sum(len(line['text'].split()) for line in lines), 'kokoro': {key: value for key, value in kokoro.items() if key != 'segments'}}
    call = await gpu.dia_sample.spawn.aio(lines)
    try:
        dia = await call.get.aio(timeout=600)
        Path('data/modal-compare-dia.wav').write_bytes(dia['audio'])
        results['dia'] = {key: value for key, value in dia.items() if key != 'audio'}
    except Exception as exc:
        await call.cancel.aio(terminate_containers=True)
        results['dia'] = {'verified': False, 'errorType': type(exc).__name__}
    for name in ('kokoro', 'dia'):
        path = Path(f'data/modal-compare-{name}.wav')
        if path.exists():
            probe = subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration,size', '-of', 'json', str(path)], capture_output=True, text=True, check=True)
            results[name]['ffprobe'] = json.loads(probe.stdout)['format']
    Path('data/modal-voice-comparison.json').write_text(json.dumps(results, indent=2))
    print(json.dumps(results), flush=True)


if __name__ == '__main__':
    asyncio.run(main())
