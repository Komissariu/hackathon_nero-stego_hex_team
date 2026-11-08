#!/usr/bin/env python3
"""Split the trusted GigaAM archive into deterministic, checksum-verified parts."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile


MODEL_ID = "gigaam-v3-e2e-ctc-int8"
ARCHIVE_NAME = "gigaam-v3-e2e-ctc-int8.7z"
ARCHIVE_SIZE_BYTES = 155_314_569
ARCHIVE_SHA256 = "a803fc4f257df7b602b5b28325cf550577abf768893941cdf7c6c918c4915125"
PART_SIZE_BYTES = 40_000_000
MODEL_REVISION = "0928919f7df949871b507e16497b9e8cff7d0c6b"
COPY_BUFFER_SIZE = 1024 * 1024


def split_archive(
    source: Path,
    output_directory: Path,
    *,
    expected_size: int = ARCHIVE_SIZE_BYTES,
    expected_sha256: str = ARCHIVE_SHA256,
    part_size: int = PART_SIZE_BYTES,
    model_id: str = MODEL_ID,
    archive_name: str = ARCHIVE_NAME,
    revision: str = MODEL_REVISION,
) -> dict:
    """Split an archive into a new directory without replacing existing output."""
    source = source.resolve(strict=True)
    if not source.is_file():
        raise ValueError(f"source is not a regular file: {source}")
    if expected_size <= 0 or part_size <= 0:
        raise ValueError("archive and part sizes must be positive")
    if source.stat().st_size != expected_size:
        raise ValueError(
            f"unexpected source size: {source.stat().st_size}, expected {expected_size}"
        )
    expected_sha256 = _validate_sha256(expected_sha256, "expected archive SHA-256")

    output_directory = output_directory.absolute()
    if output_directory.exists():
        raise FileExistsError(f"refusing to overwrite existing output directory: {output_directory}")
    output_directory.parent.mkdir(parents=True, exist_ok=True)
    staging_directory = Path(
        tempfile.mkdtemp(prefix=f".{output_directory.name}.tmp-", dir=output_directory.parent)
    )
    try:
        parts = []
        archive_digest = hashlib.sha256()
        total_size = 0
        with source.open("rb") as input_file:
            index = 1
            while True:
                part_name = f"{archive_name}.part{index:03d}"
                part_digest = hashlib.sha256()
                part_size_actual = 0
                part_path = staging_directory / part_name
                with part_path.open("xb") as part_file:
                    while part_size_actual < part_size:
                        chunk = input_file.read(min(COPY_BUFFER_SIZE, part_size - part_size_actual))
                        if not chunk:
                            break
                        part_file.write(chunk)
                        part_digest.update(chunk)
                        archive_digest.update(chunk)
                        part_size_actual += len(chunk)
                        total_size += len(chunk)
                    part_file.flush()
                    os.fsync(part_file.fileno())
                if part_size_actual == 0:
                    part_path.unlink()
                    break
                parts.append(
                    {
                        "name": part_name,
                        "sizeBytes": part_size_actual,
                        "sha256": part_digest.hexdigest(),
                    }
                )
                index += 1

        actual_sha256 = archive_digest.hexdigest()
        if total_size != expected_size or actual_sha256 != expected_sha256:
            raise ValueError(
                "source changed or failed trusted archive verification: "
                f"size={total_size}, sha256={actual_sha256}"
            )

        manifest = {
            "modelId": model_id,
            "archiveName": archive_name,
            "archiveSizeBytes": total_size,
            "archiveSha256": actual_sha256,
            "partSizeBytes": part_size,
            "partCount": len(parts),
            "revision": revision,
            "parts": parts,
        }
        manifest_path = staging_directory / "manifest.json"
        with manifest_path.open("x", encoding="utf-8", newline="\n") as manifest_file:
            json.dump(manifest, manifest_file, ensure_ascii=True, indent=2)
            manifest_file.write("\n")
            manifest_file.flush()
            os.fsync(manifest_file.fileno())

        if output_directory.exists():
            raise FileExistsError(f"refusing to overwrite existing output directory: {output_directory}")
        os.rename(staging_directory, output_directory)
        return manifest
    except BaseException:
        shutil.rmtree(staging_directory, ignore_errors=True)
        raise


def _validate_sha256(value: object, label: str) -> str:
    if not isinstance(value, str) or len(value) != 64:
        raise ValueError(f"{label} must be a 64-character hexadecimal string")
    try:
        bytes.fromhex(value)
    except ValueError as error:
        raise ValueError(f"{label} must be a 64-character hexadecimal string") from error
    return value.lower()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="path to the original trusted .7z archive")
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "model-parts" / "gigaam",
        help="new directory for the parts and manifest (must not already exist)",
    )
    args = parser.parse_args()
    try:
        manifest = split_archive(args.source, args.output_dir)
    except (OSError, ValueError) as error:
        parser.exit(1, f"split failed: {error}\n")

    print(f"Split {manifest['archiveName']} ({manifest['archiveSizeBytes']} bytes)")
    for part in manifest["parts"]:
        print(f"{part['name']}: {part['sizeBytes']} bytes sha256={part['sha256']}")
    print(f"Manifest: {args.output_dir / 'manifest.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
