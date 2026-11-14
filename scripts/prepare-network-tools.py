"""Build the incremental ARM64 command and library bundle for Sugar."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import runpy
import sys
import tarfile
import tempfile
import zipfile


# Debian's Bookworm ARM64 binary package and published SHA-256.
WGET = {
    "filename": "pool/main/w/wget/wget_1.21.3-1+deb12u1_arm64.deb",
    "size": 921644,
    "sha256": "a5ff67df5132ab6792524b18b7139833d5fabc85512976bdd1ebf9eac695921c",
}
LIB = "/usr/lib/aarch64-linux-gnu/"
GIT_COMMANDS = (
    "/usr/bin/git",
    "/usr/bin/git-upload-pack",
    "/usr/bin/git-receive-pack",
    "/usr/bin/git-upload-archive",
    "/usr/lib/git-core/git-remote-http",
    "/usr/lib/git-core/git-remote-https",
)
GIT_TEMPLATES = "/usr/share/git-core/templates/"
BUSYBOX_APPLETS = ("bc", "free", "hostname", "killall", "nc", "netstat", "nslookup", "pidof", "setsid", "top", "uptime", "vi")

# npm from the official Node release index for the guest's ARM64 binary.
NODE_VERSION = "24.22.2"
NPM_VERSION = "11.19.1"
NPM_LOCK = {
    "resolved": f"https://registry.npmjs.org/npm/-/npm-{NPM_VERSION}.tgz",
    "integrity": "sha512-ztsxKxt/kkIaAs+2i0GU6I+DRmUdrNasxTZKJe9TCdSjKxlhah/4r/hl5ygMD6XAg1qZ9c2TNomR4qgOydp10g==",
}


def add_npm(files, executable, base_manifest, archives, offline):
    node_package = next(p for p in base_manifest["packages"] if p["id"] == "node")
    if node_package["name"] != f"node-v{NODE_VERSION}-linux-arm64.tar.xz":
        raise ValueError("Update the pinned npm CLI for this Node release")
    helpers = runpy.run_path(str(Path(__file__).with_name("prepare-guest.py")))
    filename = base64.b64decode(NPM_LOCK["integrity"].split("-", 1)[1]).hex() + ".tgz"
    local_cache = Path(__file__).resolve().parents[1] / ".build/package-tarballs"
    if not (archives / filename).is_file() and (local_cache / filename).is_file():
        archives = local_cache
    archive = helpers["pinned_archive"](NPM_LOCK, archives, offline)
    root = f"/opt/sugar/npm/{NPM_VERSION}"
    with tarfile.open(archive, "r|gz") as tar:
        for member in tar:
            if member.isdir():
                continue
            top, _, relative = member.name.partition("/")
            if top != "package" or not member.isfile() or member.size > 16 << 20:
                raise ValueError("Invalid npm archive entry: " + member.name)
            path = root + "/" + helpers["checked_name"](relative)
            if path in files:
                raise ValueError("Duplicate npm archive entry: " + path)
            files[path] = tar.extractfile(member).read()
            if member.mode & 0o111:
                executable.add(path)
    package = json.loads(files[root + "/package.json"])
    if (package["name"], package["version"]) != ("npm", NPM_VERSION):
        raise ValueError("Unexpected npm release")
    for command in ("npm", "npx"):
        target = root + "/" + helpers["checked_name"](package["bin"][command])
        if target not in executable:
            raise ValueError("Missing npm executable: " + target)
        path = "/usr/bin/" + command
        files[path] = (f"#!/usr/bin/node\nprocess.argv[1] = '{target}'\nrequire(process.argv[1])\n").encode("ascii")
        executable.add(path)


def build(repo, cache, out, offline, archives):
    sys.path.insert(0, str(repo / "scripts"))
    from debian_archive import atomic_file, elf_deps, fetch, payload

    files = {}
    toolbox_commands = {"/usr/bin/curl", "/usr/bin/jq", *GIT_COMMANDS}
    project_only = {"/usr/bin/ping", "/opt/sugar/tools/top.py"}
    executable = {"/usr/bin/wget", *toolbox_commands}
    with payload(fetch(WGET, cache, offline)) as package:
        for name in ("usr/bin/wget", "usr/share/doc/wget/copyright"):
            files["/" + name] = package.extractfile("./" + name).read()

    extra_notices = {"/usr/share/doc/wget/copyright"}
    for record in json.loads(Path(__file__).with_name("userland-packages.json").read_bytes()):
        notice = f"/usr/share/doc/{record['package']}/copyright"
        extra_notices.add(notice)
        with payload(fetch(record, cache, offline)) as package:
            for guest, member in {**record["members"], notice: notice[1:]}.items():
                info = package.getmember("./" + member)
                if not info.isfile() or guest in files:
                    raise ValueError(f"Invalid or duplicate Debian package member: {guest}")
                files[guest] = package.extractfile(info).read()
                if guest.startswith("/usr/bin/"):
                    executable.add(guest)

    base = repo / "android/probeapp/src/androidTest/assets/linux-rootfs/manifest.json"
    base_manifest = json.loads(base.read_bytes())
    base_libs = {PurePosixPath(entry["path"]).name for entry in base_manifest["files"]}
    with zipfile.ZipFile(repo / ".build/linux-test-assets/linux-toolbox/profile.bin") as toolbox:
        rows = {r["path"]: r for r in json.loads(toolbox.read("manifest.json"))["files"]}

        def include(name):
            data = toolbox.read("root" + name)
            if hashlib.sha256(data).hexdigest() != rows[name]["sha256"] or len(data) != rows[name]["bytes"]:
                raise ValueError("Changed network tool asset: " + name)
            files[name] = data

        for path in sorted(toolbox_commands):
            include(path)
        # A venv launches the real interpreter via its own bin/python. All extension
        # modules must also load when invoked directly from a fresh shell.
        python_objects = [name for name in rows if name == "/usr/bin/python3.11" or
                          (name.startswith("/usr/lib/python3.11/lib-dynload/") and name.endswith(".so"))]
        if "/usr/bin/python3.11" not in python_objects or len(python_objects) < 2:
            raise ValueError("Missing CPython interpreter or dynamic modules")
        pending = [data for data in files.values() if data.startswith(b"\x7fELF")]
        for name in python_objects:
            include(name)
            pending.append(files.pop(name))
        while pending:
            for dep in elf_deps(pending.pop())[1]:
                path = LIB + dep
                if dep not in base_libs and path not in files:
                    include(path)
                    pending.append(files[path])

        include("/bin/busybox")
        busybox = files.pop("/bin/busybox")
        for applet in BUSYBOX_APPLETS:
            if b"\0" + applet.encode("ascii") + b"\0" not in busybox:
                raise ValueError("Applet missing from the pinned BusyBox: " + applet)
            path = "/usr/bin/" + applet
            files[path] = f'#!/bin/sh\nexec /bin/busybox {applet} "$@"\n'.encode("ascii")
            executable.add(path)

        # Project tools use the private interpreter bundled in each workspace. The
        # DSH control environment keeps the BusyBox implementation of top.
        guest = Path(__file__).resolve().parents[1] / "guest"
        files["/usr/bin/ss"] = (guest / "ss.sh").read_bytes()
        files["/usr/bin/nc"] = (guest / "nc.sh").read_bytes()
        executable.add("/usr/bin/ss")
        files["/usr/bin/ping"] = (guest / "project-ping.py").read_bytes()
        executable.add("/usr/bin/ping")
        files["/opt/sugar/tools/top.py"] = (guest / "project-top.py").read_bytes()
        files["/usr/bin/top"] = (
            '#!/bin/sh\nif [ -r /opt/sugar/tools/top.py ]; then\n'
            '    exec /opt/sugar/mobile-python/bin/python3.11 -I /opt/sugar/tools/top.py "$@"\n'
            'fi\nexec /bin/busybox top "$@"\n'
        ).encode("ascii")

        templates = [path for path in rows if path.startswith(GIT_TEMPLATES)]
        if GIT_TEMPLATES + "info/exclude" not in templates:
            raise ValueError("Missing Git repository templates")
        for path in templates:
            include(path)
            if rows[path]["mode"] & 0o111:
                executable.add(path)

        include("/etc/ssl/certs/ca-certificates.crt")
        include("/usr/lib/ssl/cert.pem")
        if files["/usr/lib/ssl/cert.pem"] != files["/etc/ssl/certs/ca-certificates.crt"]:
            raise ValueError("curl, GnuTLS and Python OpenSSL must use the same CA bundle")
        # Keep the notices with the tools while avoiding dozens of per-project file imports.
        names = {p["package"] for p in json.loads((repo / "scripts/git-curl-packages.json").read_bytes())["packages"]}
        names.update(("jq", "libjq1", "libonig5", "libuuid1", "libpcre2-8-0", "busybox-static"))
        names.update(p["name"] for p in json.loads((repo / "scripts/linux-python-packages.json").read_bytes())["packages"])
        notices = bytearray()
        for name in sorted(extra_notices |
                           {name for name in rows if name.startswith("/usr/share/common-licenses/") or
                            (name.endswith("/copyright") and PurePosixPath(name).parent.name in names)}):
            if name not in files:
                include(name)
            notices.extend(name.encode("ascii") + b"\n\n" + files.pop(name) + b"\n\n")
        files["/usr/share/doc/sugar-network-tools/NOTICE"] = notices

    add_npm(files, executable, base_manifest, archives, offline)
    out.mkdir(parents=True, exist_ok=True)
    entries = []
    with tempfile.NamedTemporaryFile(dir=out, delete=False) as tmp:
        staged = Path(tmp.name)
    try:
        with zipfile.ZipFile(staged, "w") as zipped:
            # Libraries and CA stores must be available before publishing the commands.
            for path in sorted(files, key=lambda p: (p.startswith("/usr/bin/"), p)):
                data = files[path]
                entries.append(dict(path=path, bytes=len(data), sha256=hashlib.sha256(data).hexdigest(),
                                    executable=path in executable))
                if path in project_only:
                    entries[-1]["projectOnly"] = True
                zipped.writestr(zipfile.ZipInfo(path[1:]), data, zipfile.ZIP_DEFLATED, compresslevel=4)
        os.replace(staged, out / "tools.zip")
        atomic_file(out / "manifest.json", json.dumps(entries, separators=(",", ":")).encode("ascii"))
    finally:
        staged.unlink(missing_ok=True)
    print(f"Prepared ARM64 guest commands, dependencies and Python wheels: {len(entries)} files")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sucrune", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--cache", type=Path)
    parser.add_argument("--tarballs", type=Path, default=Path(__file__).resolve().parents[1] / ".build/package-tarballs")
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    build(args.sucrune.resolve(), args.cache or Path(__file__).resolve().parents[1] / ".build/network-packages",
          args.out / "network-tools", args.offline, args.tarballs)
