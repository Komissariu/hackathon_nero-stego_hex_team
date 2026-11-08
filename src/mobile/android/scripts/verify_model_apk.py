#!/usr/bin/env python3
"""Verify the model archive assets in a built APK using their uncompressed bytes."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import re
import zipfile

try:
    from .restore_model_archive import ARCHIVE_NAME, ARCHIVE_SHA256, ARCHIVE_SIZE_BYTES, BUFFER_SIZE
except ImportError:
    from restore_model_archive import ARCHIVE_NAME, ARCHIVE_SHA256, ARCHIVE_SIZE_BYTES, BUFFER_SIZE

WHISPER_NAME = "assets/models/whisper-tiny-ru.7z"
GIGAAM_ENTRY = f"assets/models/{ARCHIVE_NAME}"
WHISPER_ASSET_PATH = (
    Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "assets" / "models" / "whisper-tiny-ru.7z"
)


def verify_apk(apk_path: Path) -> tuple[int, str]:
    with zipfile.ZipFile(apk_path) as apk:
        names = set(apk.namelist())
        for required in (WHISPER_NAME, GIGAAM_ENTRY):
            if required not in names:
                raise ValueError(f"APK is missing required model asset: {required}")

        forbidden = [
            name
            for name in names
            if re.search(r"\.7z\.part[0-9]+$", name)
            or Path(name).name == "manifest.json"
            or ".tmp" in Path(name).name
        ]
        if forbidden:
            raise ValueError(f"APK contains split or temporary archive files: {forbidden}")

        info = apk.getinfo(GIGAAM_ENTRY)
        if info.compress_type != zipfile.ZIP_STORED:
            raise ValueError("APK GigaAM asset must be stored without ZIP compression")
        if info.file_size != ARCHIVE_SIZE_BYTES:
            raise ValueError(
                f"APK GigaAM entry is {info.file_size} bytes, expected {ARCHIVE_SIZE_BYTES}"
            )
        digest = hashlib.sha256()
        size = 0
        with apk.open(info, "r") as model_file:
            while True:
                chunk = model_file.read(BUFFER_SIZE)
                if not chunk:
                    break
                digest.update(chunk)
                size += len(chunk)
        result = digest.hexdigest()
        if size != ARCHIVE_SIZE_BYTES or result != ARCHIVE_SHA256:
            raise ValueError(
                f"APK GigaAM bytes failed verification: size={size}, sha256={result}"
            )

        whisper_info = apk.getinfo(WHISPER_NAME)
        if whisper_info.compress_type != zipfile.ZIP_STORED:
            raise ValueError("APK Whisper asset must be stored without ZIP compression")
        whisper_digest = hashlib.sha256()
        whisper_size = 0
        with WHISPER_ASSET_PATH.open("rb") as source, apk.open(whisper_info, "r") as bundled:
            while True:
                source_chunk = source.read(BUFFER_SIZE)
                bundled_chunk = bundled.read(BUFFER_SIZE)
                if source_chunk != bundled_chunk:
                    raise ValueError("APK Whisper asset does not match the tracked source archive")
                if not source_chunk:
                    break
                whisper_digest.update(source_chunk)
                whisper_size += len(source_chunk)
        if whisper_size != whisper_info.file_size:
            raise ValueError("APK Whisper asset size does not match the tracked source archive")
        return size, result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    try:
        size, digest = verify_apk(args.apk)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        parser.exit(1, f"APK verification failed: {error}\n")
    print(f"Verified {args.apk}: GigaAM {size} bytes sha256={digest}; Whisper asset present")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
