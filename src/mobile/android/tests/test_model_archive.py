import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import zipfile

from scripts import restore_model_archive as restore
from scripts import split_model_archive as split
from scripts import verify_model_apk


class ArchiveFixture(unittest.TestCase):
    part_size = 8
    revision = "test-revision"
    model_id = "test-model"
    archive_name = "test.7z"

    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.source = self.root / "source.7z"
        self.parts = self.root / "parts"
        self.output = self.root / "output" / self.archive_name

    def tearDown(self):
        self.temporary_directory.cleanup()

    def split_data(self, data):
        self.source.write_bytes(data)
        digest = hashlib.sha256(data).hexdigest()
        manifest = split.split_archive(
            self.source,
            self.parts,
            expected_size=len(data),
            expected_sha256=digest,
            part_size=self.part_size,
            model_id=self.model_id,
            archive_name=self.archive_name,
            revision=self.revision,
        )
        return manifest, digest

    def restore(self, digest, manifest_path=None, parts_directory=None, output_path=None):
        return restore.restore_archive(
            manifest_path or self.parts / "manifest.json",
            parts_directory or self.parts,
            output_path or self.output,
            expected_model_id=self.model_id,
            expected_archive_name=self.archive_name,
            expected_size=self.source.stat().st_size,
            expected_sha256=digest,
            expected_part_size=self.part_size,
            expected_revision=self.revision,
        )

    def edit_manifest(self, mutate):
        manifest_path = self.parts / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        mutate(manifest)
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")


class SplitAndRestoreTests(ArchiveFixture):
    def test_round_trip_small_file(self):
        original = bytes(range(37))
        _, digest = self.split_data(original)
        self.assertTrue(self.restore(digest))
        self.assertEqual(self.output.read_bytes(), original)
        self.assertEqual(hashlib.sha256(self.output.read_bytes()).hexdigest(), digest)

    def test_exact_chunk_boundary(self):
        original = bytes(range(16))
        manifest, digest = self.split_data(original)
        self.assertEqual([part["sizeBytes"] for part in manifest["parts"]], [8, 8])
        self.assertTrue(self.restore(digest))
        self.assertEqual(self.output.read_bytes(), original)

    def test_partial_final_chunk(self):
        manifest, _ = self.split_data(bytes(range(19)))
        self.assertEqual([part["sizeBytes"] for part in manifest["parts"]], [8, 8, 3])

    def test_repeat_restore_does_not_rewrite_valid_output(self):
        _, digest = self.split_data(b"valid archive bytes")
        self.assertTrue(self.restore(digest))
        before = self.output.stat().st_mtime_ns
        self.assertFalse(self.restore(digest))
        self.assertEqual(self.output.stat().st_mtime_ns, before)

    def test_split_refuses_existing_output_directory(self):
        _, digest = self.split_data(b"first")
        with self.assertRaises(FileExistsError):
            split.split_archive(
                self.source,
                self.parts,
                expected_size=5,
                expected_sha256=digest,
                part_size=self.part_size,
                model_id=self.model_id,
                archive_name=self.archive_name,
                revision=self.revision,
            )
        self.assertEqual((self.parts / "manifest.json").exists(), True)

    def test_split_checks_trusted_source_size_and_hash(self):
        self.source.write_bytes(b"not trusted")
        with self.assertRaises(ValueError):
            split.split_archive(
                self.source,
                self.parts,
                expected_size=10,
                expected_sha256="0" * 64,
                part_size=self.part_size,
                model_id=self.model_id,
                archive_name=self.archive_name,
                revision=self.revision,
            )
        self.assertFalse(self.parts.exists())

    def test_missing_part_fails_without_target(self):
        _, digest = self.split_data(bytes(range(19)))
        (self.parts / f"{self.archive_name}.part002").unlink()
        with self.assertRaises((restore.ArchiveRestoreError, FileNotFoundError)):
            self.restore(digest)
        self.assertFalse(self.output.exists())

    def test_extra_part_fails(self):
        _, digest = self.split_data(bytes(range(19)))
        (self.parts / f"{self.archive_name}.part999").write_bytes(b"unexpected")
        with self.assertRaises(restore.ArchiveRestoreError):
            self.restore(digest)
        self.assertFalse(self.output.exists())

    def test_reordered_parts_in_manifest_fail(self):
        _, digest = self.split_data(bytes(range(19)))
        self.edit_manifest(lambda manifest: manifest["parts"].reverse())
        with self.assertRaises(restore.ArchiveRestoreError):
            self.restore(digest)

    def test_tampered_part_fails(self):
        _, digest = self.split_data(bytes(range(19)))
        path = self.parts / f"{self.archive_name}.part001"
        path.write_bytes(b"!" + path.read_bytes()[1:])
        with self.assertRaises(restore.ArchiveRestoreError):
            self.restore(digest)

    def test_wrong_sized_part_fails(self):
        _, digest = self.split_data(bytes(range(19)))
        path = self.parts / f"{self.archive_name}.part001"
        path.write_bytes(path.read_bytes() + b"x")
        with self.assertRaises(restore.ArchiveRestoreError):
            self.restore(digest)

    def test_malformed_manifest_fails(self):
        _, digest = self.split_data(b"valid")
        (self.parts / "manifest.json").write_text("{", encoding="utf-8")
        with self.assertRaises(restore.ArchiveRestoreError):
            self.restore(digest)

    def test_incorrect_manifest_part_count_fails(self):
        _, digest = self.split_data(bytes(range(19)))
        self.edit_manifest(lambda manifest: manifest.update({"partCount": 2}))
        with self.assertRaises(restore.ArchiveRestoreError):
            self.restore(digest)

    def test_manifest_path_traversal_fails(self):
        _, digest = self.split_data(b"valid")
        self.edit_manifest(lambda manifest: manifest["parts"][0].update({"name": "../outside"}))
        with self.assertRaises(restore.ArchiveRestoreError):
            self.restore(digest)

    def test_wrong_expected_archive_hash_fails(self):
        _, digest = self.split_data(b"valid")
        with self.assertRaises(restore.ArchiveRestoreError):
            self.restore("0" * 64)
        self.assertNotEqual(digest, "0" * 64)

    def test_duplicate_manifest_keys_fail(self):
        _, digest = self.split_data(b"valid")
        path = self.parts / "manifest.json"
        text = path.read_text(encoding="utf-8")
        path.write_text(text.replace('"modelId": "test-model",', '"modelId": "test-model",\n  "modelId": "test-model",'), encoding="utf-8")
        with self.assertRaises(restore.ArchiveRestoreError):
            self.restore(digest)

    def test_write_failure_cleans_temp_without_partial_target(self):
        _, digest = self.split_data(bytes(range(19)))
        real_fdopen = os.fdopen

        class FailingWriter:
            def __init__(self, descriptor):
                self.file = real_fdopen(descriptor, "wb")

            def __enter__(self):
                return self

            def __exit__(self, *args):
                self.file.close()

            def write(self, _data):
                raise OSError("simulated disk full")

            def flush(self):
                self.file.flush()

            def fileno(self):
                return self.file.fileno()

        with mock.patch.object(restore.os, "fdopen", side_effect=lambda descriptor, _mode: FailingWriter(descriptor)):
            with self.assertRaises(OSError):
                self.restore(digest)
        self.assertFalse(self.output.exists())
        self.assertEqual(list(self.output.parent.glob("*.tmp")), [])
        self.assertEqual(list(self.output.parent.glob(f".{self.output.name}.*.tmp")), [])

    def test_replace_failure_cleans_temp_and_preserves_existing_output(self):
        _, digest = self.split_data(b"replacement")
        self.output.parent.mkdir()
        self.output.write_bytes(b"existing output")
        with mock.patch.object(restore.os, "replace", side_effect=OSError("simulated rename failure")):
            with self.assertRaises(OSError):
                self.restore(digest)
        self.assertEqual(self.output.read_bytes(), b"existing output")
        self.assertEqual(list(self.output.parent.glob(f".{self.output.name}.*.tmp")), [])


class RealGigaAmArchiveTests(unittest.TestCase):
    repository_root = Path(__file__).resolve().parents[1]
    parts_directory = repository_root / "model-parts" / "gigaam"

    @unittest.skipUnless((parts_directory / "manifest.json").is_file(), "real GigaAM parts are absent")
    def test_real_parts_restore_to_trusted_archive(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            output = Path(temporary_directory) / restore.ARCHIVE_NAME
            self.assertTrue(
                restore.restore_archive(
                    self.parts_directory / "manifest.json",
                    self.parts_directory,
                    output,
                )
            )
            size, digest = restore._hash_file(output)
            self.assertEqual(size, restore.ARCHIVE_SIZE_BYTES)
            self.assertEqual(digest, restore.ARCHIVE_SHA256)


class ApkVerificationTests(unittest.TestCase):
    def test_verifier_hashes_uncompressed_zip_entry_and_requires_both_assets(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            apk = Path(temporary_directory) / "app.apk"
            original_hash = verify_model_apk.ARCHIVE_SHA256
            original_size = verify_model_apk.ARCHIVE_SIZE_BYTES
            original_whisper_path = verify_model_apk.WHISPER_ASSET_PATH
            data = b"small archive"
            whisper = b"tracked whisper archive"
            digest = hashlib.sha256(data).hexdigest()
            try:
                verify_model_apk.ARCHIVE_SHA256 = digest
                verify_model_apk.ARCHIVE_SIZE_BYTES = len(data)
                whisper_path = Path(temporary_directory) / "whisper.7z"
                whisper_path.write_bytes(whisper)
                verify_model_apk.WHISPER_ASSET_PATH = whisper_path
                with zipfile.ZipFile(apk, "w") as archive:
                    archive.writestr(verify_model_apk.WHISPER_NAME, whisper, compress_type=zipfile.ZIP_STORED)
                    archive.writestr(verify_model_apk.GIGAAM_ENTRY, data, compress_type=zipfile.ZIP_STORED)
                self.assertEqual(verify_model_apk.verify_apk(apk), (len(data), digest))
                with zipfile.ZipFile(apk, "a") as archive:
                    archive.writestr(
                        f"assets/models/{verify_model_apk.ARCHIVE_NAME}.part001",
                        b"forbidden",
                    )
                with self.assertRaisesRegex(ValueError, "split or temporary"):
                    verify_model_apk.verify_apk(apk)
            finally:
                verify_model_apk.ARCHIVE_SHA256 = original_hash
                verify_model_apk.ARCHIVE_SIZE_BYTES = original_size
                verify_model_apk.WHISPER_ASSET_PATH = original_whisper_path


if __name__ == "__main__":
    unittest.main()
