#!/usr/bin/env python3
"""Read the Android release version and validate a GitHub tag trigger.

Print JSON and, when set, append non-secret version metadata to GITHUB_OUTPUT
and GITHUB_ENV. Branch/manual builds use the version's expected release tag.
"""

import argparse
import json
import os
from pathlib import Path
import re


def read_version(repo: Path, ref: str) -> dict:
    source = (repo / "app/build.gradle").read_text(encoding="utf-8-sig")
    # The project deliberately uses literal version values, one declaration each.
    # Anchor to entire lines so commented declarations and interpolation cannot
    # silently select a different artifact version.
    names = re.findall(r"^\s*versionName\s+(['\"])([^'\"\r\n]+)\1\s*(?://[^\r\n]*)?$", source, re.MULTILINE)
    codes = re.findall(r"^\s*versionCode\s+(\d+)\s*(?://[^\r\n]*)?$", source, re.MULTILINE)
    if len(names) != 1 or len(codes) != 1:
        raise ValueError("app/build.gradle must declare one literal versionName and versionCode")
    version = names[0][1]
    if not re.fullmatch(r"(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?", version):
        raise ValueError("versionName must be a release version such as 0.2.0 or 0.2.0-rc.1")
    code = int(codes[0])
    if not 1 <= code <= 2_100_000_000:
        raise ValueError("versionCode is outside Android's supported release range")
    tag = f"v{version}"
    if ref.startswith("refs/tags/") and ref != f"refs/tags/{tag}":
        raise ValueError(f"Tag {ref!r} does not match app version {tag!r}")
    return {
        "version": version,
        "version_code": code,
        "tag": tag,
        "apk_name": f"DSH-Sugar-{version}-arm64-v8a.apk",
    }


def append_github_file(variable: str, values: dict) -> None:
    destination = os.environ.get(variable)
    if not destination:
        return
    lines = []
    for name, value in values.items():
        value = str(value)
        if "\n" in value or "\r" in value:
            raise ValueError("GitHub metadata must contain single-line values")
        lines.append(f"{name}={value}\n")
    with Path(destination).open("a", encoding="utf-8", newline="") as output:
        output.writelines(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--ref", default=os.environ.get("GITHUB_REF", ""))
    args = parser.parse_args()
    values = read_version(args.repo.resolve(), args.ref)
    append_github_file("GITHUB_OUTPUT", values)
    append_github_file("GITHUB_ENV", {f"SUGAR_{key.upper()}": value for key, value in values.items()})
    print(json.dumps(values, ensure_ascii=True))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError) as error:
        raise SystemExit(f"Release version check failed: {error}")
