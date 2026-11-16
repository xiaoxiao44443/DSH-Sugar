"""Focused checks for dependency and upstream source integrity gates."""
import hashlib
import importlib.util
import io
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


if __name__ == "__main__":
    unittest.main()
