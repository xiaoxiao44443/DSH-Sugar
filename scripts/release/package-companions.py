#!/usr/bin/env python3
"""Build verified, deterministic release companions from the final signed APK.

Python 3.11+, standard library only. See README.md for the dependency update policy.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import re
import shutil
import subprocess
import tempfile
import time
import urllib.request
import zipfile
from pathlib import Path, PurePosixPath

HERE = Path(__file__).resolve().parent
ARCHIVES = {
    "assets/guest/rootfs.zip": "guest",
    "assets/network-tools/tools.zip": "network-tools",
    "assets/mobile-python/runtime.zip": "python",
    "assets/guest/zoneinfo.zip": "zoneinfo",
}


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def json_bytes(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode()


def json_digest(value: object) -> str:
    return digest(json_bytes(value))


def read_json(path: Path) -> object:
    return json.loads(path.read_bytes())


def relevant(name: str) -> bool:
    name = PurePosixPath(name).name.lower()
    return bool(re.match(r"^(?:licen[cs]es?|notices?|copyright|copying|authors|third.party|thirdparty)(?:$|[. _-])", name)
                or name.endswith(".license"))


def safe_name(name: str) -> str:
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts or "\\" in name or not name:
        raise ValueError(f"Unsafe archive name: {name!r}")
    return name


def inventory(archive: zipfile.ZipFile, names: list[str] | None = None) -> dict:
    """Ignore ZIP timestamps/order/compression; compare actual payload bytes."""
    rows = []
    names = names if names is not None else archive.namelist()
    if len(names) != len(set(names)):
        raise ValueError("Duplicate archive entries are not supported")
    for name in sorted(names):
        safe_name(name)
        info = archive.getinfo(name)
        if info.is_dir():
            continue
        with archive.open(info) as stream:
            sha = hashlib.file_digest(stream, "sha256").hexdigest()
        rows.append([name, info.file_size, sha])
    return {"files": len(rows), "sha256": json_digest(rows)}


def project_patch_targets(project: Path) -> set[str]:
    """Only Sugar's declared JS patches may vary with ordinary app changes."""
    targets = {"opt/sugar/browser-handoff.cjs"}
    for relative in ("guest/plugin-management.patch", "guest/profile-resolution.patch"):
        for line in (project / relative).read_text(encoding="utf-8").splitlines():
            if line.startswith("+++ b/"):
                targets.add(safe_name(line[6:]))
    for relative in ("guest/plugin-management-client.json", "guest/local-connection-client.json"):
        targets.update(read_json(project / relative))
    for name in targets - {"opt/sugar/browser-handoff.cjs"}:
        if not name.startswith("opt/dsh/node_modules/@deepseek-ai/") or not name.endswith(".js"):
            raise ValueError(f"Unexpected declared Sugar patch target: {name}")
    return targets


def sucrune_revision(root: Path) -> str:
    revision = subprocess.run(["git", "-C", str(root), "rev-parse", "HEAD"],
                              check=True, capture_output=True, text=True).stdout.strip()
    if subprocess.run(["git", "-C", str(root), "diff", "--quiet", "HEAD", "--"],
                      capture_output=True).returncode:
        raise ValueError("Sucrune has tracked source changes; audit and commit them before release")
    return revision


def inspect_dependencies(apk: zipfile.ZipFile, project: Path, sucrune: Path) -> dict:
    result = {}
    exclusions = {
        "assets/guest/rootfs.zip": project_patch_targets(project),
        "assets/network-tools/tools.zip": {"usr/bin/ss", "usr/bin/nc", "usr/bin/ping", "usr/bin/top", "opt/sugar/tools/top.py"},
    }
    for name in ARCHIVES:
        with tempfile.TemporaryFile() as payload:
            with apk.open(name) as stream:
                shutil.copyfileobj(stream, payload)
            payload.seek(0)
            with zipfile.ZipFile(payload) as nested:
                included = [n for n in nested.namelist() if n not in exclusions.get(name, set())]
                result[name] = inventory(nested, included)
    runtime_libraries = {f"lib/arm64-v8a/libsucrune_{name}.so" for name in ("core", "probe", "runner")}
    if not runtime_libraries <= set(apk.namelist()):
        raise ValueError("Missing built Sucrune native libraries")
    result["sucrune-source-commit"] = sucrune_revision(sucrune)
    result["sucrune-libraries"] = sorted(runtime_libraries)
    groups = {
        "native-third-party": [n for n in apk.namelist() if n.startswith("lib/") and n not in runtime_libraries],
        "pnpm": [n for n in apk.namelist() if n.startswith("assets/pnpm/files/")],
        "runtime-notices": [n for n in apk.namelist() if n.startswith("assets/third_party/")],
        "android-notices": [n for n in apk.namelist() if n.startswith("META-INF/") and relevant(n)],
    }
    for name, files in groups.items():
        if not files:
            raise ValueError(f"APK is missing required dependency group: {name}")
        result[name] = inventory(apk, files)
    result["dsh-lock"] = json_digest(read_json(project / "scripts/dsh-linux-arm64-lock.json"))
    gradle = (project / "app/build.gradle").read_text(encoding="utf-8")
    result["android-direct-dependencies"] = sorted(re.findall(
        r"(?m)^\s*(?:implementation|api|runtimeOnly)\s+['\"]([^'\"]+:[^'\"]+:[^'\"]+)['\"]", gradle))
    return result


def validate_sources(index: dict) -> dict[str, dict]:
    files = {}
    sources = set()
    for source in index["sources"]:
        sources.add((source["source"], source["version"].split(":")[-1]))
        if not source["files"]:
            raise ValueError(f"Unresolved source package: {source['source']}")
        for record in source["files"]:
            name = safe_name(record["filename"])
            if PurePosixPath(name).name != name or not re.fullmatch(r"[a-f0-9]{64}", record["sha256"]):
                raise ValueError(f"Invalid source record: {name}")
            if name in files and files[name] != record:
                raise ValueError(f"Conflicting source record: {name}")
            files[name] = record
    for binary in index["binaries"]:
        if (binary["source"], binary["srcversion"].split(":")[-1]) not in sources:
            raise ValueError(f"Missing source for binary: {binary['binary']}")
    return files


def valid_file(path: Path, record: dict) -> bool:
    if not path.is_file() or path.stat().st_size != record["size"]:
        return False
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest() == record["sha256"]


def fetch(record: dict, cache: Path) -> Path:
    """Content-addressed cache; bad/stale cache entries are downloaded again."""
    if not record["url"].startswith("https://"):
        raise ValueError("Release inputs must use HTTPS")
    target = cache / record["sha256"]
    if valid_file(target, record):
        return target
    failure = None
    for attempt in range(3):
        temp = target.with_suffix(f".download-{attempt}")
        try:
            req = urllib.request.Request(record["url"], headers={"User-Agent": "DSH-Sugar-release-builder"})
            with urllib.request.urlopen(req, timeout=45) as response, temp.open("wb") as out:
                size = 0
                while chunk := response.read(1024 * 1024):
                    size += len(chunk)
                    if size > record["size"]:
                        raise ValueError("Download exceeds locked size")
                    out.write(chunk)
            if not valid_file(temp, record):
                raise ValueError("Download size or SHA-256 does not match release lock")
            temp.replace(target)
            return target
        except (OSError, ValueError) as error:
            failure = error
            temp.unlink(missing_ok=True)
            if attempt < 2:
                time.sleep(attempt + 1)
    raise RuntimeError(f"Cannot verify upstream input {record['url']}: {failure}")


def add(archive: zipfile.ZipFile, name: str, data: bytes, compressed: bool = True) -> None:
    safe_name(name)
    if name in archive.namelist():
        raise ValueError(f"Duplicate output entry: {name}")
    entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    entry.create_system = 3
    entry.external_attr = 0o100644 << 16
    entry.compress_type = zipfile.ZIP_DEFLATED if compressed else zipfile.ZIP_STORED
    archive.writestr(entry, data)


def verify_dsc(index: dict, downloads: dict[str, Path]) -> None:
    for source in index["sources"]:
        expected = {r["filename"]: r for r in source["files"]}
        dscs = [r for r in source["files"] if r["filename"].endswith(".dsc")]
        if len(dscs) != 1:
            raise ValueError(f"Expected one .dsc for {source['source']}")
        text = downloads[dscs[0]["sha256"]].read_text(encoding="utf-8")
        for key, value in (("Source", source["source"]), ("Version", source["version"])):
            match = re.search(rf"(?m)^{key}:\s*([^\r\n]+)", text)
            if not match or match.group(1) != value:
                raise ValueError(f"{key} mismatch in {dscs[0]['filename']}")
        match = re.search(r"(?m)^Checksums-Sha256:\s*\n((?: [^\n]+\n)+)", text)
        if not match:
            raise ValueError("Missing source checksums in .dsc")
        listed = set()
        for line in match.group(1).splitlines():
            sha, size, name = line.split()
            if name not in expected or expected[name]["sha256"] != sha or expected[name]["size"] != int(size):
                raise ValueError(f"Source lock differs from .dsc: {name}")
            listed.add(name)
        if listed != set(expected) - {dscs[0]["filename"]}:
            raise ValueError(f"Source file inventory mismatch: {source['source']}")


def package(args: argparse.Namespace) -> None:
    lock = read_json(HERE / "companions-lock.json")
    index = read_json(HERE / "debian-sources.json")
    if json_digest(index) != lock["debianSourcesSha256"]:
        raise ValueError("Debian source lock was changed without a matching dependency audit")
    source_files = validate_sources(index)
    with zipfile.ZipFile(args.apk) as apk:
        actual = inspect_dependencies(apk, args.project_root, args.sucrune_root)
        if args.inspect:
            print(json_bytes(actual).decode(), end="")
            return
        if actual != lock["apkDependencies"]:
            changed = sorted(k for k in set(actual) | set(lock["apkDependencies"])
                             if actual.get(k) != lock["apkDependencies"].get(k))
            raise ValueError("Dependency audit required before reusing companion sources: " + ", ".join(changed))
        if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?", args.version):
            raise ValueError("Expected a semantic release version")
        args.output_dir.mkdir(parents=True, exist_ok=True)
        args.cache_dir.mkdir(parents=True, exist_ok=True)
        records = {r["sha256"]: r for r in list(source_files.values()) + lock["upstreamInputs"]}
        print(f"Verified APK dependency inventory; checking {len(records)} upstream inputs", flush=True)
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            downloads = dict(pool.map(lambda item: (item[0], fetch(item[1], args.cache_dir)), records.items()))
        verify_dsc(index, downloads)
        with tempfile.TemporaryDirectory(dir=args.output_dir) as staging:
            staging = Path(staging)
            with zipfile.ZipFile(staging / "debian-corresponding-source.zip", "w", allowZip64=True) as sources:
                add(sources, "index.json", json_bytes(index))
                for name, record in sorted(source_files.items()):
                    add(sources, "debian/" + name, downloads[record["sha256"]].read_bytes(), compressed=False)
                add(sources, "README.txt", b"Official, unmodified Debian corresponding source distributions. index.json maps packaged binaries to source versions and records upstream URLs, sizes and SHA-256 checksums. Extract each source with dpkg-source -x PACKAGE.dsc. Sugar packaging scripts and patches are published in the application source repository.\n")
            with zipfile.ZipFile(staging / "third-party-notices.zip", "w", allowZip64=True) as notices:
                for record in sorted(lock["upstreamInputs"], key=lambda r: r["archivePath"]):
                    add(notices, record["archivePath"], downloads[record["sha256"]].read_bytes())
                add(notices, "upstream-inputs.json", json_bytes(lock["upstreamInputs"]))
                add(notices, "debian/index.json", json_bytes(index))
                add(notices, "npm/dsh-linux-arm64-lock.json", json_bytes(read_json(args.project_root / "scripts/dsh-linux-arm64-lock.json")))
                add(notices, "dependency-audit.json", json_bytes(actual))
                for name in sorted(apk.namelist()):
                    if name.startswith("assets/third_party/"):
                        add(notices, "runtime/" + name.removeprefix("assets/third_party/"), apk.read(name))
                    elif name.startswith("assets/") and relevant(name) and not name.endswith("/"):
                        add(notices, "apk/" + name, apk.read(name))
                    elif name.startswith("META-INF/") and relevant(name):
                        add(notices, "android/" + name, apk.read(name))
                for name, prefix in ARCHIVES.items():
                    with tempfile.TemporaryFile() as payload:
                        with apk.open(name) as stream:
                            shutil.copyfileobj(stream, payload)
                        payload.seek(0)
                        with zipfile.ZipFile(payload) as nested:
                            for member in sorted(nested.namelist()):
                                if member.endswith("/") or member.endswith(".class"):
                                    continue
                                if (relevant(member) or member.startswith("usr/share/common-licenses/")
                                        or member.startswith("opt/dsh/node_modules/@deepseek-ai/libreoffice-kit-wasm/sources/")
                                        or member.startswith("opt/dsh/node_modules/@deepseek-ai/libreoffice-kit-wasm/licenses/")
                                        or member in {"opt/dsh/node_modules/@img/sharp-libvips-linux-arm64/README.md", "opt/dsh/node_modules/@img/sharp-libvips-linux-arm64/versions.json"}):
                                    add(notices, prefix + "/" + member, nested.read(member))
                add(notices, "README.txt", (f"DSH Sugar {args.version} - ARM64 Android distribution\n\n" + lock["readme"]).encode())
            for name in ("third-party-notices.zip", "debian-corresponding-source.zip"):
                path = staging / name
                with zipfile.ZipFile(path) as check:
                    if check.testzip() is not None:
                        raise ValueError(f"Invalid output archive: {name}")
                path.replace(args.output_dir / name)
                print(f"Created {name} ({(args.output_dir / name).stat().st_size} bytes)", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True, help="Final signed release APK")
    parser.add_argument("--project-root", type=Path, required=True)
    parser.add_argument("--sucrune-root", type=Path, required=True, help="Clean pinned Sucrune source checkout used by this build")
    parser.add_argument("--version", default="", help="Application versionName, without v prefix")
    parser.add_argument("--output-dir", type=Path, default=Path("dist"))
    parser.add_argument("--cache-dir", type=Path, default=Path(".cache/release-companions"))
    parser.add_argument("--inspect", action="store_true", help="Print actual dependency fingerprints; do not download/package")
    args = parser.parse_args()
    try:
        package(args)
    except (OSError, ValueError, RuntimeError, KeyError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Release companion error: {error}\n")


if __name__ == "__main__":
    main()
