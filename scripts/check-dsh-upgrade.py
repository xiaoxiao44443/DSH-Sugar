"""Check Sugar's settings and recovery integration against the prepared DSH rootfs."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
from zipfile import BadZipFile, ZipFile


ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "app/src/main"
KOTLIN = ANDROID / "kotlin/app/dsh/sugar"
RECOVERY = ANDROID / "assets/recovery"
MODULES = "opt/dsh/node_modules/"


def match(pattern, source, label):
    found = re.search(pattern, source)
    if not found:
        raise ValueError(f"Cannot locate {label}. Review this compatibility check against the current implementation.")
    return found[1]


def check_settings(bundle):
    patch = (KOTLIN / "runtime/SettingsDocumentPatch.kt").read_text(encoding="utf-8")
    editor = (KOTLIN / "SettingsDocumentEditor.kt").read_text(encoding="utf-8")
    directory = match(r'const val directory = "/([^"]+)"', patch, "the patched settings module")
    size = int(match(r'const val originalSize = (\d+)L', patch, "the settings size guard"))
    digest = match(r'const val originalDigest = "([\da-f]{64})"', patch, "the settings SHA-256 guard")
    delegate = json.loads(match(r'const val delegate = ("(?:\\.|[^"\\])*")', patch, "the upstream click handler"))
    selectors = match(r"b\.matches\('([^']+)'\)", editor, "the recovery button selectors")
    action = match(r"(?:^|,)\s*\.([\w-]+)\s*>\s*button(?:,|$)", selectors, "the original button selector")
    source = bundle.read(f"{directory}/client.js")
    actual = hashlib.sha256(source).hexdigest()
    if len(source) != size or actual != digest:
        raise ValueError(f"Normal settings patch would be skipped: {len(source)} bytes, SHA-256 {actual}. "
                         "Inspect the new upstream button and review the Android patch before updating its guards.")
    text = source.decode()
    # Verify that the compiled CSS module belongs to the wrapper which renders the config button.
    # The production guard also requires precisely one replaceable upstream handler.
    wrapper = match(r'(\w+)\s*=\s*\{\s*"action":\s*`' + re.escape(action) + '`', text, "the packaged button class")
    if text.count(delegate) != 1:
        raise ValueError("The normal-mode handler must match exactly once in the shipped bundle.")
    start = text.index("function SettingsDocumentAction(")
    body = text[start:text.index("var SettingsDocumentStore=", start)]
    if (f"className:{wrapper}.action,children:[" not in body or ".Button,{" not in body or
            delegate + 'children:t(`openDocument`)' not in body or f".{action}{{" not in text):
        raise ValueError("The original recovery selector no longer identifies the shipped open-config control.")
    print(f"Settings control OK: {size} bytes, SHA-256 {actual}, .{action} > button", flush=True)


def check_dependencies(bundle, unpacked):
    policy = (RECOVERY / "recovery.patch.yml").read_text(encoding="utf-8")
    registry = (RECOVERY / "preset-registry.mjs").read_text(encoding="utf-8")
    packages = set(re.findall(r"^\s*name:\s*'(@deepseek-ai/[\w-]+)'\s*$", policy, re.MULTILINE))
    if not packages:
        raise ValueError("The recovery policy declares no packaged DSH plugins.")
    paths = {match(r"from '/opt/dsh/node_modules/([^']+)'", registry, "the upstream registry")}
    paths.update(["@deepseek-ai/dsh/lib/profile-boot.js", "@deepseek-ai/dsh-launch-environment/lib/index.js"])
    for name in packages:
        manifest = json.loads(bundle.read(MODULES + name + "/package.json"))
        entry = manifest["exports"]["."]
        if isinstance(entry, dict):
            entry = entry["default"]
        if not entry.startswith("./"):
            raise ValueError(f"Unexpected Cordis package entry for {name}: {entry}")
        paths.update([name + "/" + entry[2:], name + "/package.json"])
    for item in sorted(paths):
        info = bundle.getinfo(MODULES + item)
        if not info.file_size:
            raise ValueError(f"Empty recovery dependency: {item}")
        if unpacked and bundle.read(info) != (unpacked / "node_modules" / item).read_bytes():
            raise ValueError(f"Host test directory differs from this rootfs: {item}")
    print(f"Recovery package entries OK: {len(paths)} checked", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("rootfs", nargs="?", type=Path,
                        default=ROOT / "app/build/generated/sugarAssets/guest/rootfs.zip")
    parser.add_argument("--host-dsh", type=Path, help="also start the real DSH Host using this rootfs's unpacked opt/dsh")
    args = parser.parse_args()
    with ZipFile(args.rootfs) as bundle:
        version = json.loads(bundle.read(MODULES + "@deepseek-ai/dsh/package.json"))["version"]
        print(f"DSH {version} in {args.rootfs}", flush=True)
        check_settings(bundle)
        check_dependencies(bundle, args.host_dsh)
    if args.host_dsh:
        subprocess.run(["node", ROOT / "scripts/check-recovery-preset.mjs", args.host_dsh.resolve()], check=True)


if __name__ == "__main__":
    try:
        main()
    except (BadZipFile, KeyError, OSError, ValueError, subprocess.CalledProcessError) as error:
        sys.exit(f"DSH upgrade check failed: {error}")
