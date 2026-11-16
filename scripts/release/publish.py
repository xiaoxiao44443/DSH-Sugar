"""Publish a tag release only after every distribution asset is verified."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


def gh(*args, optional=False):
    result = subprocess.run(['gh', *args], text=True, capture_output=True)
    if result.returncode and not optional:
        raise RuntimeError(result.stderr.strip() or 'GitHub CLI failed')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dist', required=True, type=Path)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--repo', default=os.environ.get('GITHUB_REPOSITORY'))
    parser.add_argument('--notes', type=Path)
    args = parser.parse_args()
    if not args.repo or not re.fullmatch(r'v\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?', args.tag):
        raise ValueError('A repository and a vX.Y.Z tag are required')
    version = args.tag[1:]
    names = [f'DSH-Sugar-{version}-arm64-v8a.apk', 'third-party-notices.zip',
             'debian-corresponding-source.zip', 'SHA256SUMS.txt']
    hashes, sizes = {}, {}
    for name in names:
        path = args.dist / name
        if not path.is_file() or path.is_symlink():
            raise ValueError(f'Missing regular release asset: {name}')
        with path.open('rb') as stream:
            hashes[name] = hashlib.file_digest(stream, 'sha256').hexdigest()
        sizes[name] = path.stat().st_size
    checksums = ''.join(f'{hashes[name]}  {name}\n' for name in names[:-1])
    if (args.dist / 'SHA256SUMS.txt').read_text(encoding='utf-8') != checksums:
        raise ValueError('Release checksums do not match the downloaded artifacts')

    def read_release():
        response = gh('release', 'view', args.tag, '--repo', args.repo,
                      '--json', 'isDraft,assets,url', optional=True)
        if response.returncode:
            if 'release not found' not in response.stderr.lower():
                raise RuntimeError(response.stderr.strip())
            return None
        return json.loads(response.stdout)

    def verify(release):
        assets = {asset['name']: asset for asset in release['assets']}
        if set(assets) != set(names):
            raise ValueError('Unexpected or missing release assets')
        for name in names:
            asset = assets[name]
            if (asset['state'] != 'uploaded' or asset['size'] != sizes[name]
                    or asset.get('digest') != 'sha256:' + hashes[name]):
                raise ValueError(f'Uploaded release asset differs: {name}')

    release = read_release()
    if release and not release['isDraft']:
        # Reruns may verify identical assets, never replace an already public APK.
        verify(release)
        print('Already published with identical assets:', release['url'])
        return
    if not release:
        flags = ['--notes-file', str(args.notes)] if args.notes and args.notes.is_file() else ['--generate-notes']
        if version.startswith('0.') or '-' in version:
            flags += ['--prerelease']
        gh('release', 'create', args.tag, '--repo', args.repo, '--verify-tag', '--draft',
           '--title', f'DSH Sugar {args.tag}', *flags)
    gh('release', 'upload', args.tag, '--repo', args.repo, '--clobber',
       *(str(args.dist / name) for name in names))
    verify(read_release())
    gh('release', 'edit', args.tag, '--repo', args.repo, '--draft=false')
    published = read_release()
    verify(published)
    if published['isDraft']:
        raise RuntimeError('Release is still a draft')
    print(published['url'])


if __name__ == '__main__':
    main()
