#!/usr/bin/env python3
"""Securely restore and verify the repository's pinned GigaAM archive."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PureWindowsPath
import re
import tempfile
from typing import Any


MODEL_ID = "gigaam-v3-e2e-ctc-int8"
ARCHIVE_NAME = "gigaam-v3-e2e-ctc-int8.7z"
ARCHIVE_SIZE_BYTES = 155_314_569
ARCHIVE_SHA256 = "a803fc4f257df7b602b5b28325cf550577abf768893941cdf7c6c918c4915125"
PART_SIZE_BYTES = 40_000_000
MODEL_REVISION = "0928919f7df949871b507e16497b9e8cff7d0c6b"
BUFFER_SIZE = 1024 * 1024
MANIFEST_KEYS = {
    "modelId",
    "archiveName",
    "archiveSizeBytes",
    "archiveSha256",
    "partSizeBytes",
    "partCount",
    "revision",
    "parts",
}
PART_KEYS = {"name", "sizeBytes", "sha256"}


class ArchiveRestoreError(ValueError):
    """The manifest, parts, or restored archive failed validation."""


def restore_archive(
    manifest_path: Path,
    parts_directory: Path,
    output_path: Path,
    *,
    expected_model_id: str = MODEL_ID,
    expected_archive_name: str = ARCHIVE_NAME,
    expected_size: int = ARCHIVE_SIZE_BYTES,
    expected_sha256: str = ARCHIVE_SHA256,
    expected_part_size: int = PART_SIZE_BYTES,
    expected_revision: str = MODEL_REVISION,
) -> bool:
    """Restore the pinned archive atomically; return False when output was already valid."""
    expected_sha256 = _validate_sha256(expected_sha256, "expected archive SHA-256")
    manifest = _read_manifest(manifest_path)
    parts = _validate_manifest(
        manifest,
        expected_model_id=expected_model_id,
        expected_archive_name=expected_archive_name,
        expected_size=expected_size,
        expected_sha256=expected_sha256,
        expected_part_size=expected_part_size,
        expected_revision=expected_revision,
    )
    parts_directory = parts_directory.resolve(strict=True)
    if not parts_directory.is_dir():
        raise ArchiveRestoreError(f"parts path is not a directory: {parts_directory}")
    part_paths = _resolve_parts(parts, manifest["archiveName"], parts_directory)
    _reject_extra_parts(parts_directory, manifest["archiveName"], part_paths)

    for item, part_path in zip(parts, part_paths, strict=True):
        size, digest = _hash_file(part_path)
        if size != item["sizeBytes"]:
            raise ArchiveRestoreError(
                f"wrong size for {item['name']}: {size}, expected {item['sizeBytes']}"
            )
        if digest != item["sha256"]:
            raise ArchiveRestoreError(f"SHA-256 mismatch for {item['name']}")

    output_path = output_path.absolute()
    if output_path.exists():
        size, digest = _hash_file(output_path)
        if size == expected_size and digest == expected_sha256:
            return False
    output_path.parent.mkdir(parents=True, exist_ok=True)

    temporary_path: Path | None = None
    try:
        descriptor, temporary_name = tempfile.mkstemp(
            prefix=f".{output_path.name}.", suffix=".tmp", dir=output_path.parent
        )
        temporary_path = Path(temporary_name)
        archive_digest = hashlib.sha256()
        archive_size = 0
        with os.fdopen(descriptor, "wb") as output_file:
            for item, part_path in zip(parts, part_paths, strict=True):
                part_digest = hashlib.sha256()
                part_size = 0
                with part_path.open("rb") as part_file:
                    while True:
                        chunk = part_file.read(BUFFER_SIZE)
                        if not chunk:
                            break
                        output_file.write(chunk)
                        part_digest.update(chunk)
                        archive_digest.update(chunk)
                        part_size += len(chunk)
                        archive_size += len(chunk)
                if part_size != item["sizeBytes"] or part_digest.hexdigest() != item["sha256"]:
                    raise ArchiveRestoreError(
                        f"{item['name']} changed while the archive was being restored"
                    )
            output_file.flush()
            os.fsync(output_file.fileno())

        if archive_size != expected_size:
            raise ArchiveRestoreError(
                f"wrong restored size: {archive_size}, expected {expected_size}"
            )
        if archive_digest.hexdigest() != expected_sha256:
            raise ArchiveRestoreError("restored archive SHA-256 does not match the trusted value")
        os.replace(temporary_path, output_path)
        temporary_path = None
        return True
    finally:
        if temporary_path is not None:
            try:
                temporary_path.unlink()
            except FileNotFoundError:
                pass


def _read_manifest(path: Path) -> dict[str, Any]:
    def reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result = {}
        for key, value in pairs:
            if key in result:
                raise ArchiveRestoreError(f"duplicate manifest key: {key}")
            result[key] = value
        return result

    try:
        with path.open("r", encoding="utf-8") as manifest_file:
            result = json.load(manifest_file, object_pairs_hook=reject_duplicate_keys)
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ArchiveRestoreError(f"cannot read manifest: {error}") from error
    if not isinstance(result, dict):
        raise ArchiveRestoreError("manifest must be a JSON object")
    return result


def _validate_manifest(
    manifest: dict[str, Any],
    *,
    expected_model_id: str,
    expected_archive_name: str,
    expected_size: int,
    expected_sha256: str,
    expected_part_size: int,
    expected_revision: str,
) -> list[dict[str, Any]]:
    if set(manifest) != MANIFEST_KEYS:
        raise ArchiveRestoreError("manifest fields do not match the required schema")
    expected_values = {
        "modelId": expected_model_id,
        "archiveName": expected_archive_name,
        "archiveSizeBytes": expected_size,
        "archiveSha256": expected_sha256,
        "partSizeBytes": expected_part_size,
        "revision": expected_revision,
    }
    for key, expected in expected_values.items():
        if manifest[key] != expected or type(manifest[key]) is not type(expected):
            raise ArchiveRestoreError(f"unexpected manifest value for {key}")
    if not isinstance(manifest["parts"], list) or type(manifest["partCount"]) is not int:
        raise ArchiveRestoreError("manifest part list or count has an invalid type")

    expected_count = (expected_size + expected_part_size - 1) // expected_part_size
    if manifest["partCount"] != expected_count or len(manifest["parts"]) != expected_count:
        raise ArchiveRestoreError("manifest part count is incorrect")
    validated = []
    remaining = expected_size
    for index, item in enumerate(manifest["parts"], start=1):
        if not isinstance(item, dict) or set(item) != PART_KEYS:
            raise ArchiveRestoreError(f"part {index} does not match the required schema")
        expected_name = f"{expected_archive_name}.part{index:03d}"
        if item["name"] != expected_name or not isinstance(item["name"], str):
            raise ArchiveRestoreError(f"part {index} is missing, unsafe, or out of order")
        expected_part_length = min(expected_part_size, remaining)
        if type(item["sizeBytes"]) is not int or item["sizeBytes"] != expected_part_length:
            raise ArchiveRestoreError(f"wrong declared size for {expected_name}")
        digest = _validate_sha256(item["sha256"], f"SHA-256 for {expected_name}")
        if item["sha256"] != digest:
            raise ArchiveRestoreError(f"SHA-256 for {expected_name} must be lowercase")
        validated.append(item)
        remaining -= expected_part_length
    if remaining != 0:
        raise ArchiveRestoreError("manifest parts do not cover the expected archive size")
    return validated


def _resolve_parts(parts: list[dict[str, Any]], archive_name: str, directory: Path) -> list[Path]:
    result = []
    for item in parts:
        name = item["name"]
        path_component = Path(name)
        windows_component = PureWindowsPath(name)
        if (
            path_component.is_absolute()
            or windows_component.is_absolute()
            or len(path_component.parts) != 1
            or len(windows_component.parts) != 1
            or ".." in path_component.parts
            or ".." in windows_component.parts
            or not re.fullmatch(re.escape(archive_name) + r"\.part[0-9]{3}", name)
        ):
            raise ArchiveRestoreError(f"unsafe part filename: {name!r}")
        part_path = directory / name
        try:
            resolved_path = part_path.resolve(strict=True)
        except OSError as error:
            raise ArchiveRestoreError(f"missing part: {name}") from error
        if resolved_path.parent != directory or not resolved_path.is_file():
            raise ArchiveRestoreError(f"part is not a regular file in the parts directory: {name}")
        result.append(resolved_path)
    return result


def _reject_extra_parts(directory: Path, archive_name: str, expected_paths: list[Path]) -> None:
    expected_names = {path.name for path in expected_paths}
    for candidate in directory.iterdir():
        if candidate.name.startswith(f"{archive_name}.part") and candidate.name not in expected_names:
            raise ArchiveRestoreError(f"unexpected extra part: {candidate.name}")


def _hash_file(path: Path) -> tuple[int, str]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as file:
        while True:
            chunk = file.read(BUFFER_SIZE)
            if not chunk:
                break
            digest.update(chunk)
            size += len(chunk)
    return size, digest.hexdigest()


def _validate_sha256(value: object, label: str) -> str:
    if not isinstance(value, str) or len(value) != 64:
        raise ArchiveRestoreError(f"{label} must be a 64-character hexadecimal string")
    try:
        bytes.fromhex(value)
    except ValueError as error:
        raise ArchiveRestoreError(
            f"{label} must be a 64-character hexadecimal string"
        ) from error
    return value.lower()


def main() -> int:
    repository_root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--manifest",
        type=Path,
        default=repository_root / "model-parts" / "gigaam" / "manifest.json",
    )
    parser.add_argument("--parts-dir", type=Path)
    parser.add_argument(
        "--output",
        type=Path,
        default=repository_root
        / "app"
        / "src"
        / "main"
        / "assets"
        / "models"
        / ARCHIVE_NAME,
    )
    args = parser.parse_args()
    parts_directory = args.parts_dir or args.manifest.parent
    try:
        restored = restore_archive(args.manifest, parts_directory, args.output)
    except (OSError, ArchiveRestoreError) as error:
        parser.exit(1, f"restore failed: {error}\n")
    print(
        f"{'Restored' if restored else 'Already valid'} {ARCHIVE_NAME}: "
        f"{ARCHIVE_SIZE_BYTES} bytes sha256={ARCHIVE_SHA256}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
