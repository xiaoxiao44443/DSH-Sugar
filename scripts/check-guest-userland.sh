#!/bin/bash
# Run inside a Sugar project shell (no model request or network necessary).
set -euo pipefail
work=$(mktemp -d "${PWD%/}/.sugar-userland.XXXXXXXX")
start=$PWD
trap 'cd -- "$start" && rm -rf -- "$work"' EXIT
cd "$work"

test "$(printf '%s\n' '{"n":41}' | jq -r '.n + 1')" = 42
file /bin/bash | grep -q 'ELF'
printf 'Sugar test\n' > probe.txt
test "$(file --brief --mime-type probe.txt)" = text/plain
zip -q probe.zip probe.txt
test "$(unzip -p probe.zip probe.txt)" = 'Sugar test'
install -Dm755 probe.txt nested/probe
test -x nested/probe && cmp probe.txt nested/probe
for applet in free vi bc nc setsid; do command -v "$applet" >/dev/null; done
test "$(printf '6*7\n' | bc)" = 42
test "$(getconf LONG_BIT)" = 64
test "$(getconf PAGESIZE)" -gt 0
getent hosts localhost | grep -q localhost
top -b -n 1 | grep -q "RSS(KiB)"
# One live listener, queried from separately executed guest utilities.
python3 - <<'PY'
import socket
import subprocess

with socket.socket() as listener:
    listener.setblocking(False)
    listener.bind(('127.0.0.1', 0))
    listener.listen(1)
    port = str(listener.getsockname()[1])
    for tool in ('ss', 'netstat'):
        rows = subprocess.check_output([tool, '-lnt'], text=True)
        assert any('127.0.0.1:' + port in row.split() for row in rows.splitlines()), (tool, rows)
    subprocess.run(['nc', '-z', '-w', '2', '127.0.0.1', port], check=True, timeout=5)
PY
nohup /bin/sh -c 'printf done' </dev/null > nohup-result
test "$(cat nohup-result)" = done

pip3 --version
python3 -m ensurepip --version
python3 -m venv .venv
python3 - <<'PY'
from zipfile import ZipFile

name = "sugar_probe-1.0.dist-info"
files = {
    "sugar_probe.py": "def main():\n    print('local-wheel-ok')\n",
    name + "/METADATA": "Metadata-Version: 2.1\nName: sugar-probe\nVersion: 1.0\n",
    name + "/WHEEL": "Wheel-Version: 1.0\nGenerator: sugar-test\nRoot-Is-Purelib: true\nTag: py3-none-any\n",
    name + "/entry_points.txt": "[console_scripts]\nsugar-probe = sugar_probe:main\n",
}
record = name + "/RECORD"
files[record] = "".join(path + ",,\n" for path in [*files, record])
with ZipFile("sugar_probe-1.0-py3-none-any.whl", "w") as wheel:
    for path, data in files.items():
        wheel.writestr(path, data)
PY
env -u LD_LIBRARY_PATH -u PYTHONPATH -u PYTHONHOME .venv/bin/python -c \
    'import bz2, sqlite3, ssl, sys; assert sys.prefix != sys.base_prefix'
.venv/bin/pip --disable-pip-version-check install --no-index ./sugar_probe-1.0-py3-none-any.whl
test "$(.venv/bin/sugar-probe)" = local-wheel-ok
python3 -c 'import importlib.util; assert importlib.util.find_spec("sugar_probe") is None'
printf '%s\n' 'Sugar guest userland OK'
