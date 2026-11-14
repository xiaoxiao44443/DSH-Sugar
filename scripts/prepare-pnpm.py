"""Prepare the pinned, portable pnpm CLI for the Android APK (no native addon toolchain)."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import runpy
import tarfile
import tempfile

VERSION = "10.28.2"
INTEGRITY = "sha512-QYcvA3rSL3NI47Heu69+hnz9RI8nJtnPdMCPGVB8MdLI56EVJbmD/rwt9kC1Q43uYCPrsfhO1DzC1lTSvDJiZA=="
LOCK = {"resolved": f"https://registry.npmjs.org/pnpm/-/pnpm-{VERSION}.tgz", "integrity": INTEGRITY}
FILES = ("LICENSE", "package.json", "bin/pnpm.cjs", "dist/pnpm.cjs", "dist/pnpmrc",
         "dist/worker.js", "dist/templates/completion.bash", "dist/templates/completion.zsh",
         "dist/templates/completion.fish", "dist/templates/completion.ps1")


def main():
    script = Path(__file__).resolve()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--tarballs", type=Path, default=script.parents[1] / ".build" / "package-tarballs")
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    file_name = base64.b64decode(INTEGRITY.split("-", 1)[1]).hex() + ".tgz"
    bundled_cache = script.parents[1] / ".build" / "package-tarballs"
    cache = bundled_cache if not (args.tarballs / file_name).is_file() and (bundled_cache / file_name).is_file() else args.tarballs
    archive = runpy.run_path(str(script.with_name("prepare-guest.py")))["pinned_archive"](LOCK, cache, args.offline)
    output = args.out / "pnpm"
    output.mkdir(parents=True, exist_ok=True)
    manifest = {}
    with tarfile.open(archive, "r:gz") as package:
        for name in FILES:
            member = package.getmember("package/" + name)
            if not member.isfile() or member.size > 15_000_000:
                raise ValueError("Invalid pnpm source member " + name)
            data = package.extractfile(member).read()
            digest = hashlib.sha256(data).hexdigest()
            file = output / "files" / name
            manifest[name] = {"size": len(data), "sha256": digest}
            if file.is_file() and hashlib.sha256(file.read_bytes()).hexdigest() == digest:
                continue
            file.parent.mkdir(exist_ok=True, parents=True)
            with tempfile.NamedTemporaryFile(dir=file.parent, delete=False) as tmp:
                tmp.write(data)
            os.replace(tmp.name, file)
    content = json.dumps({"version": VERSION, "files": manifest}, sort_keys=True).encode()
    receipt = output / "manifest.json"
    if not receipt.is_file() or receipt.read_bytes() != content:
        with tempfile.NamedTemporaryFile(dir=output, delete=False) as tmp:
            tmp.write(content)
        os.replace(tmp.name, receipt)
    print(f"Prepared portable pnpm {VERSION} ({sum(x['size'] for x in manifest.values()):,} bytes)")


if __name__ == "__main__":
    main()
