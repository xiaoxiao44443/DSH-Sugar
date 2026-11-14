"""Run the bundled pnpm -> npm metadata lookup against a disposable loopback registry."""
import argparse
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from threading import Thread
import zipfile


def check(assets):
    node = shutil.which("node")
    if not node:
        raise RuntimeError("Node is required for the npm CLI smoke test")
    pnpm = assets / "pnpm/files/bin/pnpm.cjs"
    rows = {entry["path"]: entry for entry in json.loads((assets / "network-tools/manifest.json").read_bytes())}
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        with zipfile.ZipFile(assets / "network-tools/tools.zip") as package:
            def verified(path):
                data = package.read(path[1:])
                row = rows[path]
                assert len(data) == row["bytes"] and hashlib.sha256(data).hexdigest() == row["sha256"], path
                return data

            cli = {}
            for command in ("npm", "npx"):
                path = "/usr/bin/" + command
                source = verified(path).decode("ascii")
                entry = re.search(r"^process\.argv\[1\] = '(/opt/sugar/npm/[0-9.]+/bin/[a-z-]+\.js)'$", source, re.M)
                assert entry and rows[path]["executable"], source
                assert source.startswith("#!/usr/bin/node\n") and "require(process.argv[1])" in source
                cli[command] = root / entry[1][1:]
            payload = cli["npm"].parent.parent
            prefix = "/" + payload.relative_to(root).as_posix() + "/"
            for path in rows:
                if path.startswith(prefix):
                    target = root / path[1:]
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(verified(path))
            version = json.loads((payload / "package.json").read_bytes())["version"]

        # pnpm itself prepends cwd/node_modules/.bin and the host Node directory to PATH.
        # Keep its npm delegate on our fixture even on machines that have their own npm.
        launchers = root / "node_modules/.bin"
        launchers.mkdir(parents=True)
        if os.name == "nt":
            (launchers / "npm.cmd").write_text(f'@"{node}" "{cli["npm"]}" %*\n', encoding="utf-8")
        else:
            import shlex
            launcher = launchers / "npm"
            launcher.write_text(f'#!/bin/sh\nexec {shlex.quote(node)} {shlex.quote(str(cli["npm"]))} "$@"\n')
            launcher.chmod(0o755)
        user_agent = []

        class Registry(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path != "/sugar-npm-qa":
                    self.send_error(404)
                    return
                user_agent.append(self.headers.get("User-Agent", ""))
                response = json.dumps({
                    "name": "sugar-npm-qa", "versions": {"1.0.0": {"name": "sugar-npm-qa", "version": "1.0.0"}},
                    "dist-tags": {"latest": "1.0.0"}, "time": {"1.0.0": "2026-01-01T00:00:00.000Z"},
                }).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(response)))
                self.end_headers()
                self.wfile.write(response)

            def log_message(self, *args):
                pass

        with ThreadingHTTPServer(("127.0.0.1", 0), Registry) as registry:
            Thread(target=registry.serve_forever, daemon=True).start()
            env = {k: v for k, v in os.environ.items() if not k.lower().startswith("npm_")}
            env.update(PATH=str(launchers) + os.pathsep + os.environ["PATH"], HOME=tmp, USERPROFILE=tmp,
                       npm_config_registry=f"http://127.0.0.1:{registry.server_port}/",
                       npm_config_cache=str(root / "cache"), npm_config_userconfig=str(root / "user.npmrc"),
                       npm_config_globalconfig=str(root / "global.npmrc"), npm_config_update_notifier="false",
                       npm_config_fetch_retries="0",
                       NO_PROXY="127.0.0.1")
            def call(script, *args):
                return subprocess.run([node, script, *args], cwd=tmp, env=env, timeout=40,
                                      check=True, stdout=subprocess.PIPE, text=True, encoding="utf-8").stdout.strip()

            try:
                assert call(cli["npm"], "--version") == call(cli["npx"], "--version") == version
                data = json.loads(call(pnpm, "view", "sugar-npm-qa", "versions", "time", "dist-tags",
                                       "--json", "--config.fetch-retries=0"))
                assert data["versions"] == ["1.0.0"] and data["dist-tags"]["latest"] == "1.0.0"
                assert "1.0.0" in data["time"] and any(f"npm/{version} " in s for s in user_agent), user_agent
                print(f"Bundled npm/npx {version} and pnpm registry metadata lookup passed")
            finally:
                registry.shutdown()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("assets", type=Path, help="Prepared sugarAssets folder")
    check(parser.parse_args().assets.resolve())
