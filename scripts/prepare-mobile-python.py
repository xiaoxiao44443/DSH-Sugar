"""Build the project CPython layer from Sucrune's verified Debian ARM64 profile."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import runpy
import subprocess
import sys
import tempfile
import zipfile


VENV = {
    "url": "https://deb.debian.org/debian-security/pool/updates/main/p/python3.11/python3.11-venv_3.11.2-6+deb12u9_arm64.deb",
    "bytes": 5896,
    "sha256": "bc155b92437e80122ac68ccf37cca2ddab3462025e429109bffdf1cb4e8e93d4",
    "version": "3.11.2-6+deb12u9",
}


def build(profile, output, repo, cache, offline):
    sys.path.insert(0, str(repo / "scripts"))
    from debian_archive import payload

    spec = json.loads((profile / "manifest.json").read_bytes())
    if spec.get("name") != "debian-bookworm-python-arm64":
        raise ValueError("Expected Sucrune's Debian ARM64 CPython profile")
    # GuestImage also installs this profile's libresolv in the DSH control environment for sharp/libvips.
    base = json.loads((repo / "android/probeapp/src/androidTest/assets/linux-rootfs/manifest.json").read_bytes())
    control_libc = next(p["sha256"] for p in base["packages"] if p["id"] == "libc6")
    python_libc = next(p["sha256"] for p in spec["packages"] if p["name"] == "libc6")
    if control_libc != python_libc:
        raise ValueError("DSH control and CPython layers must use the same Debian ARM64 libc6 archive")
    if next(p["version"] for p in spec["packages"] if p["name"] == "python3.11") != VENV["version"]:
        raise ValueError("The CPython and venv Debian packages must match")
    # Reuse Sucrune's checked downloader for the matching Debian security archive.
    runpy.run_path(str(repo / "scripts/prepare-linux-python.py"))["package_bytes"](VENV, cache, offline)
    venv_package = cache / VENV["url"].rsplit("/", 1)[1]
    output.mkdir(parents=True, exist_ok=True)
    files = set()

    def write(archive, name, data):
        path = PurePosixPath(name)
        if path.is_absolute() or ".." in path.parts or "\\" in name or name in files:
            raise ValueError(f"Invalid CPython layer entry {name!r}")
        files.add(name)
        archive.writestr(zipfile.ZipInfo(name), data, zipfile.ZIP_DEFLATED, compresslevel=4)

    with tempfile.NamedTemporaryFile(dir=output, delete=False) as temp:
        tmp = Path(temp.name)
    try:
        with zipfile.ZipFile(tmp, "w") as archive:
            for row in spec["files"]:
                path = row["path"]
                if path == "/usr/bin/python3.11" or path.startswith((
                    "/usr/lib/python3.11/", "/usr/lib/python3/dist-packages/", "/usr/lib/aarch64-linux-gnu/", "/usr/share/doc/"
                )):
                    data = (profile / row["asset"]).read_bytes()
                    if len(data) != row["bytes"] or hashlib.sha256(data).hexdigest() != row["sha256"]:
                        raise ValueError(f"CPython asset failed verification: {path}")
                    write(archive, path[len("/usr/"):], data)
            with payload(venv_package) as deb:
                for module in ("__init__.py", "__main__.py", "_uninstall.py"):
                    path = "lib/python3.11/ensurepip/" + module
                    write(archive, path, deb.extractfile("./usr/" + path).read())
            for required in ("bin/python3.11", "lib/python3.11/socket.py", "lib/python3.11/json/__init__.py",
                             "lib/aarch64-linux-gnu/libresolv.so.2", "lib/python3/dist-packages/pip/__main__.py",
                             "lib/python3.11/venv/scripts/common/activate", "lib/python3.11/ensurepip/__main__.py"):
                if required not in files:
                    raise ValueError(f"Missing required CPython entry: {required}")
        with tmp.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        os.replace(tmp, output / "runtime.zip")
        (output / "runtime.sha256").write_text(digest, encoding="ascii")
        print(f"Prepared mobile_use CPython runtime: {len(files)} files, {digest[:12]}")
    finally:
        tmp.unlink(missing_ok=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sucrune", type=Path, required=True)
    parser.add_argument("--profile", type=Path, help="Existing prepared linux-python profile")
    parser.add_argument("--cache", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    profile = args.profile or args.sucrune / ".build/toolbox-inputs/linux-python"
    if not (profile / "manifest.json").is_file() and not args.profile:
        cmd = [sys.executable, str(args.sucrune / "scripts/prepare-linux-python.py"), "--output", str(profile)]
        if args.offline:
            cmd.append("--offline")
        subprocess.run(cmd, check=True)
    build(profile, args.out / "mobile-python", args.sucrune.resolve(),
          args.cache or Path(__file__).resolve().parents[1] / ".build/network-packages", args.offline)
