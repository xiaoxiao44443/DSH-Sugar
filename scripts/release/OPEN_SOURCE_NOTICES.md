# Offline license catalog

The About / Open-source licenses page reads the two checked-in assets:

- `app/src/main/assets/open-source-notices.json`: component names, pinned versions,
  upstream source URLs, and complete offline license/copyright documents.
- `app/src/main/assets/open-source-links.json`: only IDs and source URLs. Android
  uses this small allowlist for browser actions instead of accepting a Web URL.

These files describe bundled software notices. They do not apply Sugar's MIT
license to third-party components or change the license of artwork.

## Reproduce or check the assets

Use Python 3.11+; no additional packages or network access are needed by the
generator. First prepare the matching guest assets using the normal build
workflow. Supply the verified `third-party-notices.zip` named by
`open-source-notices-inputs.json` (its HTTPS download URL and SHA-256 are pinned):

```sh
python scripts/release/generate-open-source-notices.py \
  --notices /path/to/third-party-notices.zip

python scripts/release/generate-open-source-notices.py \
  --notices /path/to/third-party-notices.zip --check
```

`--rootfs`, `--project-root`, and `--output-dir` support separate build or staging
directories. Generation is deterministic: no timestamps, random IDs, absolute
local paths, or automatic fetching of latest upstream material are included.

## Sources and updates

The companion archive supplies Android, Sucrune, native/runtime, npm and Debian
notices extracted from the distribution. The prepared rootfs supplies npm
package versions and repository metadata. Identical documents for the same
package and version are retained only once. JavaScript/TypeScript source files
accidentally matched by a broad notice scan are excluded; those are not license
documents. Debian packages keep their complete original copyright files instead
of being assigned an invented single SPDX label. GPL, LGPL and GFDL texts are
available offline, including the GPL text accompanying LGPL v3.

`open-source-notices-inputs.json` adds reviewed notices missing from the earlier
companion archive: Kotlin/JetBrains licenses, actual LibADB copyright notices,
SPAKE2 JNI/C copyright headers, and GNU LGPL standard texts. Each supplemental
document records its original source URL and the SHA-256 of its normalized UTF-8
text. `supplementalSources` records the upstream archives used to extract those
notices. Only license/copyright comments are copied from source code.

When changing dependencies, generate the matching release notices, review their
contents, refresh the pinned input hashes/versions and supplemental text, then
rerun the generator. A changed guest archive, npm lock, or dependency identity
fails generation instead of silently carrying forward the old catalog. App-only
version changes are read from `app/build.gradle`. Regenerate before building the
APK that will ship the updated catalog, and then create release companion
archives from that final signed APK as usual.

This catalog is an accessible presentation of the shipped notices, not a
substitute for corresponding-source and other redistribution obligations.
The source archives/build instructions remain in the release companions.
