import os
import secrets
import subprocess
import tempfile
from pathlib import Path


def main():
    directory = Path.home() / '.secure'
    path = directory / 'podcast-studio-shared-secret'
    owner_path = directory / 'podcast-lounge-token'
    with os.fdopen(os.open(owner_path, os.O_RDONLY | os.O_NOFOLLOW)) as stream:
        owner = stream.read().strip()
    if len(owner) < 32 or not owner.isascii():
        raise RuntimeError('Owner token is invalid')
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    except FileExistsError:
        with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW)) as stream:
            os.fchmod(stream.fileno(), 0o600)
            shared = stream.read().strip()
    else:
        shared = secrets.token_hex(32)
        with os.fdopen(fd, 'w') as stream:
            stream.write(shared + '\n')
            stream.flush()
            os.fsync(stream.fileno())
    if len(shared) != 64 or any(c not in '0123456789abcdef' for c in shared):
        raise RuntimeError('Studio shared-secret file is invalid')
    payload = f'STUDIO_SHARED_SECRET={shared}\nOWNER_BEARER_TOKEN={owner}\nSTUDIO_ENABLED=1\n'
    with tempfile.NamedTemporaryFile(mode='w', dir=directory, suffix='.env') as stream:
        stream.write(payload)
        stream.flush()
        result = subprocess.run(['modal', 'secret', 'create', 'podcast-studio', '--from-dotenv', stream.name], text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError('Modal Secret creation failed; check authentication/name collision without exposing values')
    print('Created Modal Secret podcast-studio; shared-secret file mode 0600. Values withheld.')


if __name__ == '__main__':
    main()
