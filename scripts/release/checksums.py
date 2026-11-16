"""Write checksums for the three distribution files, in publication order."""
import argparse
import hashlib
from pathlib import Path
import importlib.util


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dist', type=Path, required=True)
    parser.add_argument('--version', required=True)
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location('version', Path(__file__).with_name('inspect-version.py'))
    version_reader = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(version_reader)
    expected = version_reader.read_version(Path(__file__).resolve().parents[2], '')
    if args.version != expected['version']:
        raise ValueError('Version differs from app/build.gradle')
    names = [expected['apk_name'], 'third-party-notices.zip', 'debian-corresponding-source.zip']
    lines = []
    for name in names:
        path = args.dist / name
        if not path.is_file() or path.stat().st_size == 0:
            raise ValueError(f'Missing or empty release file: {name}')
        with path.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        lines.append(f'{digest}  {name}\n')
    (args.dist / 'SHA256SUMS.txt').write_text(''.join(lines), encoding='utf-8', newline='\n')


if __name__ == '__main__':
    main()
