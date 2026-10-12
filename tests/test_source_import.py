import subprocess
import sys


def test_source_resolver_import_does_not_depend_on_aiohttp_exports():
    program = 'import aiohttp; aiohttp.__dict__.pop("abc", None); import sources; from aiohttp.abc import AbstractResolver; assert issubclass(sources.PublicResolver, AbstractResolver)'
    result = subprocess.run([sys.executable, '-c', program], capture_output=True, text=True)
    assert result.returncode == 0, result.stderr
