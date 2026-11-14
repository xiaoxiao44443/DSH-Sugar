"""Assemble the pinned GNU/Linux ARM64 userland and the official DSH web profile."""
import argparse
import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request
import zipfile
from pathlib import Path, PurePosixPath
from urllib.parse import urlsplit


DSH_VERSION = "0.2.2-rc.3"
BASE_LAYERS = ("linux-rootfs", "linux-bash", "linux-stty", "linux-node-pty")
HERE = Path(__file__).resolve().parents[1]
DSH_PATCHES = (HERE / "guest/plugin-management.patch", HERE / "guest/profile-resolution.patch")
DSH_CLIENT_PATCH = HERE / "guest/plugin-management-client.json"


def digest(path, algorithm="sha256"):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


def checked_name(name):
    parts = name.split("/")
    if not name or any(part in ("", ".", "..") for part in parts) or "\\" in name or "\0" in name:
        raise ValueError(f"Invalid archive path: {name!r}")
    return name


def pinned_archive(lock, cache, offline):
    algo, value = lock["integrity"].split("-", 1)
    expected = base64.b64decode(value, validate=True).hex()
    if algo != "sha512" or len(expected) != 128:
        raise ValueError("Expected pinned npm SHA-512")
    archive = cache / (expected + ".tgz")
    if not archive.exists():
        if offline:
            raise FileNotFoundError(f"Missing offline npm archive {archive}")
        url = urlsplit(lock["resolved"])
        if url.scheme != "https" or url.netloc != "registry.npmjs.org" or url.query or url.fragment:
            raise ValueError("Unexpected pinned registry URL")
        cache.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=cache, delete=False) as tmp:
            temp_path = Path(tmp.name)
        try:
            with urllib.request.urlopen(url.geturl(), timeout=60) as response, temp_path.open("wb") as out:
                shutil.copyfileobj(response, out)
            if digest(temp_path, "sha512") != expected:
                raise ValueError(f"Checksum mismatch for {url.path}")
            os.replace(temp_path, archive)
        finally:
            temp_path.unlink(missing_ok=True)
    if digest(archive, "sha512") != expected:
        raise ValueError(f"Cached npm checksum mismatch: {archive}")
    return archive


class Rootfs:
    def __init__(self, zipped, stage):
        self.zipped = zipped
        self.stage = stage
        self.patch = "\n".join(path.read_text(encoding="utf-8") for path in DSH_PATCHES)
        self.patch_targets = {
            checked_name(line[6:]): None
            for line in self.patch.splitlines() if line.startswith("+++ b/")
        }
        self.client_edits = json.loads(DSH_CLIENT_PATCH.read_bytes())
        if not self.patch_targets or not self.client_edits or self.patch_targets.keys() & self.client_edits.keys():
            raise ValueError("Missing or overlapping DSH patch targets")
        if any(not checked_name(name).startswith("opt/dsh/node_modules/@deepseek-ai/")
               for name in (*self.patch_targets, *self.client_edits)):
            raise ValueError("Unexpected DSH patch targets")
        self.files = {}
        self.executables = []
        self.unpacked = 0

    def add(self, name, data, mode):
        name = checked_name(name)
        fingerprint = (hashlib.sha256(data).hexdigest(), bool(mode & 0o111))
        if name in self.files:
            if self.files[name] != fingerprint:
                raise ValueError("Conflicting rootfs file: " + name)
            return
        self.files[name] = fingerprint
        if fingerprint[1]:
            self.executables.append(name)
        self.unpacked += len(data)
        info = zipfile.ZipInfo(name)
        info.create_system = 3
        info.external_attr = (0o100755 if fingerprint[1] else 0o100644) << 16
        self.zipped.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=3)

    def add_layer(self, directory):
        spec = json.loads((directory / "manifest.json").read_bytes())
        for item in spec["files"]:
            name = checked_name(item["path"].removeprefix("/"))
            if name.startswith("opt/dsh/node_modules/") or item.get("package") == "hello":
                continue
            asset = directory / checked_name(item["asset"])
            data = asset.read_bytes()
            if len(data) != item["bytes"] or hashlib.sha256(data).hexdigest() != item["sha256"].lower():
                raise ValueError("Changed userland asset: " + name)
            self.add(name, data, item["mode"])
            if name == "bin/bash":
                self.add("bin/sh", data, item["mode"])

    def add_toolbox(self, toolbox):
        # Install the standalone multicall binary and its advertised applets.
        # Tiny shebang shims preserve argv without hundreds of physical copies.
        with zipfile.ZipFile(toolbox) as archive:
            manifest = json.loads(archive.read("manifest.json"))
            keep = {"/bin/busybox", "/usr/share/doc/busybox-static/copyright", "/etc/ssl/certs/ca-certificates.crt"}
            for row in manifest["files"]:
                name = row["path"]
                if name not in keep:
                    continue
                data = archive.read("root" + name)
                if hashlib.sha256(data).hexdigest() != row["sha256"]:
                    raise ValueError("Invalid toolbox asset: " + name)
                self.add(name[1:], data, row["mode"])
            for item in manifest["aliases"]:
                name = item["path"].removeprefix("/")
                if item["target"] != "/bin/busybox" or name in self.files:
                    continue
                applet = PurePosixPath(name).name
                if not re.fullmatch(r"[A-Za-z0-9._+\[\]-]+", applet):
                    raise ValueError("Unexpected BusyBox applet name")
                self.add(name, f'#!/bin/bash\nexec /bin/busybox \'{applet}\' "$@"\n'.encode(), 0o755)
            if "bin/busybox" not in self.files:
                raise ValueError("Missing BusyBox in prepared toolbox")

    def add_package(self, package_dir, archive):
        prefix = "opt/dsh/" + checked_name(package_dir) + "/"
        with tarfile.open(archive, "r|gz") as tar:
            root = None
            for member in tar:
                if member.isdir():
                    continue
                canonical = "/".join(part for part in member.name.split("/") if part != ".")
                top, sep, name = checked_name(canonical).partition("/")
                if not sep or (root is not None and top != root) or not member.isfile():
                    raise ValueError(f"Unsupported npm member in {package_dir}: {member.name!r}")
                root = top
                # Source maps and TypeScript declarations are development
                # metadata. Keep executable JS, plugin manifests and all UI assets.
                if name.endswith((".map", ".d.ts", ".d.cts", ".d.mts")):
                    continue
                if not 0 <= member.size <= 256 << 20:
                    raise ValueError("Oversized npm member")
                path = prefix + name
                data = tar.extractfile(member).read()
                if path in self.client_edits:
                    edits = self.client_edits[path]
                    if hashlib.sha256(data).hexdigest() != edits["sha256"]:
                        raise ValueError("Changed DSH client patch target: " + path)
                    for old, new in edits["edits"]:
                        anchor = old.encode("utf-8")
                        if not anchor or data.count(anchor) != 1:
                            raise ValueError("Missing or ambiguous DSH client patch anchor: " + path)
                        data = data.replace(anchor, new.encode("utf-8"), 1)
                if path in self.patch_targets:
                    source = self.stage / path
                    source.parent.mkdir(parents=True, exist_ok=True)
                    with source.open("xb") as stream:
                        stream.write(data)
                    self.patch_targets[path] = member.mode
                else:
                    self.add(path, data, member.mode)

    def add_patched_files(self):
        # Apply the reviewable diff to verified npm inputs in an isolated directory.
        # A changed or missing upstream file must fail the build.
        if any(mode is None for mode in self.patch_targets.values()) or self.client_edits.keys() - self.files.keys():
            raise ValueError("DSH patch target is missing from pinned archives")
        git_env = dict(os.environ, GIT_CEILING_DIRECTORIES=str(self.stage.parent.resolve()))
        subprocess.run(["git", "-c", "core.autocrlf=false", "apply", "--no-index", "--whitespace=nowarn", "-"],
                       input=self.patch.encode("utf-8"), cwd=self.stage, env=git_env, check=True)
        for name, mode in self.patch_targets.items():
            self.add(name, (self.stage / name).read_bytes(), mode)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sucrune", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True, help="Android generated assets root")
    parser.add_argument("--tarballs", type=Path)
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    repo = args.sucrune.resolve()
    cache = args.tarballs or HERE / ".build/package-tarballs"
    layer_root = repo / "android/probeapp/src/androidTest/assets"
    toolbox = repo / ".build/linux-test-assets/linux-toolbox/profile.bin"
    pin_path = HERE / "scripts/dsh-linux-arm64-lock.json"
    handoff = HERE / "guest/browser-handoff.cjs"
    inputs = [Path(__file__), *DSH_PATCHES, DSH_CLIENT_PATCH, handoff, pin_path, toolbox] + [layer_root / layer / "manifest.json" for layer in BASE_LAYERS]
    input_hash = hashlib.sha256("".join(digest(path) for path in inputs).encode()).hexdigest()
    output = args.out / "guest"
    output.mkdir(parents=True, exist_ok=True)
    bundle, receipt = output / "rootfs.zip", output / "bundle.json"
    if bundle.is_file() and receipt.is_file():
        old = json.loads(receipt.read_bytes())
        if old.get("inputsSha256") == input_hash and old.get("sha256") == digest(bundle):
            print(f"Verified cached DSH guest: {bundle}")
            return
    pins = json.loads(pin_path.read_bytes())
    packages = pins["packages"]
    if (pins["target"] != {"os": "linux", "cpu": "arm64", "libc": "glibc"}
            or packages["node_modules/@deepseek-ai/dsh"]["version"] != DSH_VERSION):
        raise ValueError("DSH lock does not match this app's target and version")
    with tempfile.TemporaryDirectory(dir=output) as temp:
        prepared = Path(temp) / "rootfs.zip"
        with zipfile.ZipFile(prepared, "w") as zipped:
            fs = Rootfs(zipped, Path(temp) / "upstream")
            for layer in BASE_LAYERS:
                fs.add_layer(layer_root / layer)
            fs.add_toolbox(toolbox)
            fs.add("opt/sugar/browser-handoff.cjs", handoff.read_bytes(), 0o644)
            for package, locked in sorted(packages.items()):
                fs.add_package(package, pinned_archive(locked, cache, args.offline))
            fs.add_patched_files()
            # The optional speech addon masks dlopen errors as a missing module.
            # ONNX Runtime also needs the glibc librt compatibility DSO.
            for path in ("usr/bin/node", "bin/bash", "lib/aarch64-linux-gnu/librt.so.1",
                         "opt/dsh/node_modules/sherpa-onnx-linux-arm64/sherpa-onnx.node",
                         "opt/dsh/node_modules/sherpa-onnx-linux-arm64/libsherpa-onnx-c-api.so",
                         "opt/dsh/node_modules/sherpa-onnx-linux-arm64/libonnxruntime.so",
                         "opt/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js",
                         "opt/dsh/node_modules/@deepseek-ai/dsh-deepseek-chat-import/package.json",
                         "opt/dsh/node_modules/@deepseek-ai/dsh-web-frontend/dist/index.html"):
                if path not in fs.files:
                    raise ValueError("Missing DSH guest file: " + path)
        result = dict(schema=1, version=DSH_VERSION, sha256=digest(prepared), entries=len(fs.files),
                      unpackedBytes=fs.unpacked, executables=sorted(fs.executables), inputsSha256=input_hash)
        metadata = Path(temp) / "bundle.json"
        metadata.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        os.replace(prepared, bundle)
        os.replace(metadata, receipt)
    print(f"Prepared DSH {DSH_VERSION}: {result['entries']} files, {bundle.stat().st_size:,} compressed bytes")


if __name__ == "__main__":
    main()
