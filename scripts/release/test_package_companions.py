"""Focused checks for dependency and upstream source integrity gates."""
import argparse
import contextlib
import hashlib
import importlib.util
import io
import tarfile
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile, ZipInfo

spec = importlib.util.spec_from_file_location("companions", Path(__file__).with_name("package-companions.py"))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class CompanionIntegrityTests(unittest.TestCase):
    def test_payload_fingerprint_ignores_zip_metadata_but_detects_changed_bytes(self):
        def archive(payload, year, compression):
            data = io.BytesIO()
            with ZipFile(data, "w") as z:
                item = ZipInfo("usr/bin/example", (year, 1, 1, 0, 0, 0))
                item.compress_type = compression
                z.writestr(item, payload)
            return ZipFile(io.BytesIO(data.getvalue()))
        with archive(b"original", 1980, ZIP_STORED) as a, archive(b"original", 2026, ZIP_DEFLATED) as b, archive(b"changed!", 2026, ZIP_DEFLATED) as c:
            self.assertEqual(release.inventory(a), release.inventory(b))
            self.assertNotEqual(release.inventory(a), release.inventory(c))

    def test_unresolved_or_unmapped_debian_sources_fail(self):
        with self.assertRaisesRegex(ValueError, "Unresolved"):
            release.validate_sources({"sources": [{"source": "sample", "version": "1", "files": []}], "binaries": []})
        with self.assertRaisesRegex(ValueError, "Missing source"):
            release.validate_sources({"sources": [], "binaries": [{"binary": "sample", "source": "sample", "srcversion": "1"}]})

    def test_dsc_must_cover_exact_source_archive_set(self):
        with tempfile.TemporaryDirectory() as directory:
            dsc = Path(directory) / "sample.dsc"
            dsc.write_text("Source: sample\nVersion: 1\nChecksums-Sha256:\n " + "a" * 64 + " 12 sample.tar.xz\n")
            record = {"filename": "sample.dsc", "sha256": "dsc", "size": dsc.stat().st_size}
            source = {"source": "sample", "version": "1", "files": [record, {"filename": "sample.tar.xz", "sha256": "a" * 64, "size": 12}]}
            release.verify_dsc({"sources": [source]}, {"dsc": dsc})
            source["files"][1]["size"] = 13
            with self.assertRaisesRegex(ValueError, "differs from .dsc"):
                release.verify_dsc({"sources": [source]}, {"dsc": dsc})

    def test_corrupted_cache_is_replaced_only_by_verified_download(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory)
            content = b"valid upstream content"
            sha = hashlib.sha256(content).hexdigest()
            record = {"url": "https://example.invalid/pinned", "sha256": sha, "size": len(content)}
            (cache / sha).write_bytes(b"damaged")
            with patch.object(release.urllib.request, "urlopen", return_value=io.BytesIO(content)) as download:
                self.assertEqual(release.fetch(record, cache).read_bytes(), content)
                download.assert_called_once()
            with patch.object(release.urllib.request, "urlopen") as download:
                release.fetch(record, cache)
                download.assert_not_called()

    def test_wrong_upstream_content_is_not_cached(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory)
            record = {"url": "https://example.invalid/pinned", "sha256": "a" * 64, "size": 3}
            with patch.object(release.urllib.request, "urlopen", side_effect=lambda *a, **k: io.BytesIO(b"bad")), patch.object(release.time, "sleep"):
                with self.assertRaisesRegex(RuntimeError, "SHA-256"):
                    release.fetch(record, cache)
            self.assertEqual(list(cache.iterdir()), [])


class Spake2SourceTests(unittest.TestCase):
    def source_set(self, directory, c_kind="complete", bad_file=None):
        """Fixtures model the tag archive's empty submodule and separate C tree."""
        inputs, downloads = [], {}
        for archive_path, (root, required) in release.SPAKE2_SOURCE_FILES.items():
            path = Path(directory) / Path(archive_path).name
            with tarfile.open(path, "w:gz") as archive:
                for relative in required:
                    if archive_path == release.SPAKE2_C_ARCHIVE:
                        if c_kind == "directory":
                            break
                        if c_kind == "missing" and relative == bad_file:
                            continue
                    member = tarfile.TarInfo(f"{root}/{relative}")
                    content = b"source or build input\n"
                    if archive_path == release.SPAKE2_C_ARCHIVE and relative == bad_file:
                        if c_kind == "empty":
                            content = b""
                        elif c_kind == "symlink":
                            member.type = tarfile.SYMTYPE
                            member.linkname = "missing-source"
                            content = b""
                    member.size = len(content)
                    archive.addfile(member, io.BytesIO(content))
                # This is all a GitHub tag archive provides for a submodule.
                member = tarfile.TarInfo(root + ("/android/src/main/cpp/spake2-c" if archive_path == release.SPAKE2_JAVA_ARCHIVE else ""))
                member.type = tarfile.DIRTYPE
                archive.addfile(member)
            record = {"archivePath": archive_path, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "size": path.stat().st_size}
            inputs.append(record)
            downloads[record["sha256"]] = path
        return inputs, downloads

    def test_separate_complete_source_archives_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            inputs, downloads = self.source_set(directory)
            release.verify_spake2_sources(inputs, downloads)

    def test_tag_only_archive_does_not_count_as_complete_source(self):
        with tempfile.TemporaryDirectory() as directory:
            inputs, downloads = self.source_set(directory)
            inputs = [r for r in inputs if r["archivePath"] != release.SPAKE2_C_ARCHIVE]
            with self.assertRaisesRegex(ValueError, "Expected one locked SPAKE2 source"):
                release.verify_spake2_sources(inputs, downloads)

    def test_package_includes_both_source_archives_and_assembly_instructions(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            inputs, downloads = self.source_set(directory)
            index = {"sources": [], "binaries": []}
            lock = {"debianSourcesSha256": release.json_digest(index),
                    "apkDependencies": {}, "upstreamInputs": inputs, "readme": "fixture"}
            apk = root / "app.apk"
            with ZipFile(apk, "w"):
                pass
            args = argparse.Namespace(apk=apk, project_root=root, sucrune_root=root,
                                      version="0.2.3", inspect=False,
                                      output_dir=root / "dist", cache_dir=root / "cache")

            def read_json(path):
                return {"companions-lock.json": lock, "debian-sources.json": index,
                        "dsh-linux-arm64-lock.json": {}}[path.name]

            with patch.object(release, "read_json", side_effect=read_json), \
                    patch.object(release, "inspect_dependencies", return_value={}), \
                    patch.object(release, "ARCHIVES", {}), \
                    patch.object(release, "fetch", side_effect=lambda record, cache: downloads[record["sha256"]]), \
                    contextlib.redirect_stdout(io.StringIO()):
                release.package(args)
            with ZipFile(args.output_dir / "third-party-notices.zip") as archive:
                for record in inputs:
                    self.assertEqual(archive.read(record["archivePath"]), downloads[record["sha256"]].read_bytes())
                instructions = archive.read("android/spake2/BUILDING.txt").decode()
                self.assertIn(release.SPAKE2_C_COMMIT, instructions)
                self.assertIn("--strip-components=1", instructions)
                self.assertIn("android/src/main/cpp/spake2-c", instructions)

    def test_hash_valid_but_incomplete_c_source_fails(self):
        cases = [("directory", None), ("empty", "sha512.c"),
                 ("missing", "include/spake2/spake2.h"), ("symlink", "spake2.c")]
        for kind, name in cases:
            with self.subTest(kind=kind, name=name), tempfile.TemporaryDirectory() as directory:
                inputs, downloads = self.source_set(directory, kind, name)
                # Correct checksums alone must not make an incomplete archive pass.
                self.assertTrue(all(release.valid_file(downloads[r["sha256"]], r) for r in inputs))
                with self.assertRaisesRegex(ValueError, "Missing or empty SPAKE2 source file"):
                    release.verify_spake2_sources(inputs, downloads)


if __name__ == "__main__":
    unittest.main()
