#!/usr/bin/env python3
"""Generate the offline About/license catalog from pinned distribution notices.

No network access. The companion ZIP and the prepared rootfs are explicit inputs;
supplemental upstream notices are reviewed and pinned in the adjacent input file.
"""
from __future__ import annotations

import argparse
import hashlib
import html
import json
import re
import sys
import zipfile
from collections import Counter
from pathlib import Path, PurePosixPath
from urllib.parse import quote, urlsplit

HERE = Path(__file__).resolve().parent
GROUP_ORDER = {'project': 0, 'android': 1, 'runtime': 2, 'dependencies': 3}


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_json(path: Path) -> dict:
    return json.loads(path.read_bytes())


def serialized(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + '\n').encode('utf-8')


def text(data: bytes) -> str:
    # Original copyright text is retained; source line endings are normalized.
    return data.decode('utf-8-sig').replace('\r\n', '\n').replace('\r', '\n')


def https_url(value: object) -> str:
    if isinstance(value, dict):
        value = value.get('url', '')
    if not isinstance(value, str):
        return ''
    value = re.sub(r'^git\+', '', value).removesuffix('.git')
    if value.startswith('git://github.com/'):
        value = value.replace('git://', 'https://', 1)
    parsed = urlsplit(value)
    if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password:
        return ''
    return value


def is_notice(path: str) -> bool:
    name = PurePosixPath(path).name
    # The release archive is deliberately broad; do not present e.g.
    # notice-transition.ts or third-party-web-<hash>.js as license texts.
    return bool(re.fullmatch(r'(?:licen[cs]e(?:s)?|copying|copyright|notice(?:s)?|authors|third[-_. ]party[-_. ]notices?)'
                             r'(?:[._-][A-Za-z0-9._-]+)?', name, re.I)
                and PurePosixPath(name).suffix.lower() not in {'.js', '.ts', '.map', '.c', '.h', '.patch', '.gz'})


def package_root(path: str) -> str:
    parts = path.split('/')
    if 'node_modules' not in parts:
        return ''
    index = len(parts) - 1 - parts[::-1].index('node_modules')
    count = 2 if parts[index + 1].startswith('@') else 1
    return '/'.join(parts[:index + 1 + count])


def generate(project: Path, notices: Path, rootfs: Path, inputs_path: Path) -> tuple[dict, dict]:
    inputs = read_json(inputs_path)
    if inputs.get('schema') != 1:
        raise ValueError('Unsupported notice input schema')
    if sha(notices.read_bytes()) != inputs['noticesArchive']['sha256']:
        raise ValueError('The notices archive changed; audit and update open-source-notices-inputs.json first')
    if sha(rootfs.read_bytes()) != inputs['rootfsSha256']:
        raise ValueError('The prepared rootfs changed; regenerate the catalog from matching notices')
    if sha(serialized(read_json(project / 'scripts/dsh-linux-arm64-lock.json'))) != inputs['dshLockSha256']:
        raise ValueError('The DSH package lock changed; audit the notice inputs')

    config = (project / 'app/build.gradle').read_text(encoding='utf-8')
    version = re.search(r"versionName\s+['\"]([^'\"]+)", config).group(1)
    lock = read_json(project / 'scripts/release/companions-lock.json')
    for field, expected in inputs['dependencyIdentity'].items():
        if lock['apkDependencies'].get(field) != expected:
            raise ValueError(f'The {field} dependency identity changed; audit the notice inputs')

    components: dict[str, dict] = {}

    def add(identifier: str, name: str, version: str = '', license: str = '', group: str = 'dependencies',
            source: str = '', documents: list[dict] | None = None) -> dict:
        row = components.setdefault(identifier, dict(id=identifier, name=name, version=version,
            license=license, group=group, sourceUrl=https_url(source), documents=[]))
        for doc in documents or []:
            if not doc.get('text', '').strip():
                raise ValueError(f'Empty document for {identifier}: {doc.get("title")}')
            if not any(old['text'] == doc['text'] for old in row['documents']):
                row['documents'].append({'title': doc['title'], 'text': doc['text']})
        return row

    def doc(z: zipfile.ZipFile, path: str, title: str | None = None) -> dict:
        value = text(z.read(path))
        # Bouncy Castle distributes this license as a small HTML document.
        if path == 'android/bouncycastle/LICENSE':
            value = html.unescape(re.sub(r'<[^>]+>', '', value)).strip() + '\n'
        return {'title': title or path, 'text': value}

    with zipfile.ZipFile(notices) as z, zipfile.ZipFile(rootfs) as runtime:
        names = z.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate notice archive entries')
        audit = json.loads(z.read('dependency-audit.json'))
        for field, expected in inputs['dependencyIdentity'].items():
            if audit.get(field) != expected:
                raise ValueError(f'The notices ZIP does not match {field}')

        add('dsh-sugar', 'DSH Sugar', version, 'MIT', 'project',
            'https://github.com/xiaoxiao44443/DSH-Sugar',
            [{'title': 'LICENSE', 'text': text((project / 'LICENSE').read_bytes())}])
        revision = audit['sucrune-source-commit']
        add('sucrune', 'Sucrune', revision[:12], 'MIT', 'runtime',
            'https://github.com/xiaoxiao44443/Sucrune/tree/' + revision,
            [doc(z, 'runtime/Sucrune-LICENSE', 'LICENSE')])
        dsh_release = json.loads(z.read('npm/dsh-linux-arm64-lock.json'))['release'].rsplit('/', 1)[-1].removeprefix('dsh-v')
        add('deepseek-harness', 'DeepSeek Harness', dsh_release, 'MIT', 'runtime',
            'https://github.com/deepseek-ai/deepseek-harness/tree/dsh-v' + dsh_release,
            [doc(z, 'deepseek/LICENSE', 'LICENSE')])

        android = [
            ('libadb-android', 'LibADB Android', '3.1.1', 'Apache-2.0 OR GPL-3.0-or-later; BSD / MIT notices',
             'https://github.com/MuntashirAkon/libadb-android/tree/3.1.1', 'android/libadb/'),
            ('spake2-java', 'SPAKE2 Java / JNI', '2.2.1', 'LGPL-3.0',
             'https://github.com/MuntashirAkon/spake2-java/tree/2.2.1', 'android/spake2/'),
            ('conscrypt', 'Conscrypt Android', '2.5.3', 'Apache-2.0',
             'https://github.com/google/conscrypt/tree/2.5.3', 'android/conscrypt/'),
            ('bouncycastle', 'Bouncy Castle', '1.81', 'MIT',
             'https://github.com/bcgit/bc-java/tree/r1rv81', 'android/bouncycastle/'),
            ('androidx-annotation', 'AndroidX Annotation', '1.9.1', 'Apache-2.0',
             'https://android.googlesource.com/platform/frameworks/support/+/androidx-annotation-release/annotation/',
             'android/META-INF/androidx/annotation/annotation/'),
        ]
        for identifier, name, ver, license, url, prefix in android:
            # Extensionless SPDX license identifiers live inside LICENSES/.
            docs = [doc(z, path, path[len(prefix):]) for path in names
                    if path.startswith(prefix) and (is_notice(path) or '/LICENSES/' in path)]
            add(identifier, name, ver, license, 'android', url, docs)

        for supplement in inputs['supplements']:
            for document in supplement['documents']:
                if sha(document['text'].encode('utf-8')) != document['sha256']:
                    raise ValueError(f'Changed supplemental text: {supplement["id"]}/{document["title"]}')
            add(supplement['id'], supplement['name'], supplement.get('version', ''), supplement['license'],
                supplement['group'], supplement['sourceUrl'], supplement['documents'])

        gpl3 = doc(z, 'guest/usr/share/common-licenses/GPL-3', 'GNU General Public License v3')
        for identifier in ('spake2-java', 'spake2-c'):
            add(identifier, identifier, documents=[gpl3])

        known = [
            ('nodejs', 'Node.js', '24.22.2', 'MIT + bundled third-party notices',
             'https://nodejs.org/download/release/v24.22.2/node-v24.22.2.tar.xz', ['guest/usr/share/doc/node/LICENSE']),
            ('lua', 'Lua', '5.4.9', 'MIT', 'https://www.lua.org/ftp/lua-5.4.9.tar.gz',
             ['runtime/lua/LICENSE', 'runtime/lua/NOTICE.md']),
            ('musl-zig', 'musl / Zig runtime', 'Zig 0.17.1', 'MIT + bundled third-party notices',
             'https://ziglang.org/download/0.17.1/', ['runtime/musl/COPYRIGHT', 'runtime/musl/NOTICE.md', 'runtime/musl/ZIG-LICENSE']),
            ('go-runtime', 'Go runtime', '', 'BSD-3-Clause', 'https://go.dev/dl/', ['runtime/go/LICENSE']),
            ('npm', 'npm', '11.19.1', 'Artistic-2.0 + bundled third-party notices',
             'https://github.com/npm/cli/tree/v11.19.1', ['network-tools/opt/sugar/npm/11.19.1/LICENSE']),
            ('pnpm', 'pnpm', '10.28.2', 'MIT + bundled third-party notices',
             'https://github.com/pnpm/pnpm/tree/v10.28.2', ['apk/assets/pnpm/files/LICENSE']),
            ('python', 'Python', '3.11', 'PSF-2.0 + bundled third-party notices',
             'https://www.python.org/downloads/source/', ['python/lib/python3.11/LICENSE.txt']),
            ('pip', 'pip', '23.0.1', 'MIT', 'https://github.com/pypa/pip/tree/23.0.1',
             ['python/lib/python3/dist-packages/pip-23.0.1.dist-info/LICENSE.txt']),
            ('network-tools', 'Network tools', '', 'Bundled third-party notices',
             'https://github.com/xiaoxiao44443/DSH-Sugar/blob/main/scripts/prepare-network-tools.py',
             ['network-tools/usr/share/doc/sugar-network-tools/NOTICE']),
        ]
        for identifier, name, ver, license, url, paths in known:
            add(identifier, name, ver, license, 'runtime', url, [doc(z, p, PurePosixPath(p).name) for p in paths])

        metadata: dict[str, dict] = {}
        for path in runtime.namelist():
            if path.endswith('/package.json') and path.startswith('opt/dsh/node_modules/'):
                root = path.removesuffix('/package.json')
                if package_root(root) != root:
                    continue
                metadata[root] = json.loads(runtime.read(path))
        for path in names:
            if not path.startswith('guest/opt/dsh/node_modules/') or not is_notice(path):
                continue
            root = package_root(path.removeprefix('guest/'))
            info = metadata.get(root)
            if not info:
                raise ValueError('No package metadata for notice: ' + path)
            name, ver = info['name'], info.get('version', '')
            identifier = 'npm-' + sha((name + '@' + ver).encode())[:20]
            license = info.get('license', '')
            if not isinstance(license, str):
                license = json.dumps(license, ensure_ascii=False)
            url = https_url(info.get('repository')) or https_url(info.get('homepage'))
            url = url or 'https://www.npmjs.com/package/' + name + '/v/' + ver
            add(identifier, name, ver, license, 'dependencies', url,
                [doc(z, path, path.removeprefix('guest/' + root + '/'))])

        # The libvips binary distribution has its licensing table in README.md.
        vips_root = 'opt/dsh/node_modules/@img/sharp-libvips-linux-arm64'
        vips = metadata[vips_root]
        add('sharp-libvips', 'sharp-libvips dependency bundle', vips['version'], vips['license'], 'runtime',
            'https://github.com/lovell/sharp-libvips/tree/v' + vips['version'],
            [doc(z, 'guest/' + vips_root + '/README.md', 'Bundled library licensing'),
             doc(z, 'guest/' + vips_root + '/versions.json', 'Bundled library versions'),
             doc(z, 'android/spake2/LICENSE', 'GNU Lesser General Public License v3'), gpl3])

        # Retain each Debian package's complete copyright file rather than
        # inventing one SPDX identifier for a multi-license system package.
        debian = json.loads(z.read('debian/index.json'))['binaries']
        for path in names:
            match = re.fullmatch(r'(?:guest/usr|python)/share/doc/([^/]+)/copyright', path)
            if not match:
                continue
            name = match[1]
            entries = [row for row in debian if row['binary'] == name]
            versions = ', '.join(sorted({row['version'] for row in entries}))
            src = entries[0] if entries else None
            url = ('https://sources.debian.org/src/' + quote(src['source'], safe='') + '/' +
                   quote(src['srcversion'], safe='') + '/') if src else 'https://www.debian.org/'
            add('debian-' + name, name, versions, 'Debian copyright', 'dependencies', url, [doc(z, path, 'copyright')])

        common = [doc(z, path, PurePosixPath(path).name) for path in names if path.startswith('guest/usr/share/common-licenses/')]
        add('gnu-common-licenses', 'GNU license texts', '', 'GPL / LGPL / GFDL', 'runtime',
            'https://www.gnu.org/licenses/', common)

    result = sorted(components.values(), key=lambda r: (GROUP_ORDER[r['group']], r['name'].casefold(), r['version']))
    if not result or any(not row['documents'] for row in result):
        raise ValueError('Every component must have a nonempty offline notice')
    catalog = {'schema': 1, 'appVersion': version, 'components': result}
    links = {'schema': 1, 'components': [{'id': row['id'], 'sourceUrl': row['sourceUrl']} for row in result]}
    return catalog, links


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project-root', type=Path, default=HERE.parents[1])
    parser.add_argument('--notices', type=Path, required=True)
    parser.add_argument('--rootfs', type=Path)
    parser.add_argument('--inputs', type=Path, default=HERE / 'open-source-notices-inputs.json')
    parser.add_argument('--output-dir', type=Path)
    parser.add_argument('--check', action='store_true', help='Compare generated bytes with checked-in assets without changing files')
    args = parser.parse_args()
    rootfs = args.rootfs or args.project_root / 'app/build/generated/sugarAssets/guest/rootfs.zip'
    out = args.output_dir or args.project_root / 'app/src/main/assets'
    catalog, links = generate(args.project_root, args.notices, rootfs, args.inputs)
    for name, value in [('open-source-notices.json', catalog), ('open-source-links.json', links)]:
        target, content = out / name, serialized(value)
        if args.check:
            if not target.is_file() or text(target.read_bytes()).encode('utf-8') != content:
                raise ValueError('Stale generated asset: ' + str(target))
        else:
            out.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content)
        print(f'{name}: {len(content)} bytes')
    print('Components:', len(catalog['components']), dict(Counter(row['group'] for row in catalog['components'])))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, zipfile.BadZipFile) as error:
        sys.exit(str(error))
