# Bundled model archives

The GigaAM v3 E2E CTC INT8 archive is 155,314,569 bytes, larger than GitHub's regular-file limit for a normal Git blob. The repository therefore stores the unchanged archive as four byte-for-byte chunks of at most 40,000,000 bytes. These parts are only a source-control storage format: Gradle verifies and joins them before packaging, and the Android app receives one ordinary `.7z` archive. The app does not download the model or join chunks.

## Repository files and integrity

The required files are:

- `model-parts/gigaam/manifest.json`
- `model-parts/gigaam/gigaam-v3-e2e-ctc-int8.7z.part001` through `.part004`
- `scripts/split_model_archive.py`
- `scripts/restore_model_archive.py`
- `scripts/verify_model_apk.py`
- `buildSrc/src/main/kotlin/RestoreGigaAmArchive.kt`

The manifest records the model ID, source revision, original archive name/size/SHA-256, chunk size/count, and the ordered size and SHA-256 for every part. The split and restore tools pin the expected archive size and checksum; editing a manifest cannot change the trusted archive identity. The restore checks the exact part sequence and count, rejects extra parts and unsafe names, verifies each chunk and the complete archive, and atomically replaces its output only after validation.

## Local Android Studio build

No separate Python or 7-Zip installation is needed to restore the archive for an Android build. From the repository root, the Gradle task can be run directly:

```powershell
.\gradlew.bat restoreGigaAmArchive
```

Normal builds, including Android Studio builds, automatically run the same JVM-based restore task before assets are packaged:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

The generated archive is placed under `app/build/generated/gigaamAssets/models/`, not alongside the tracked parts. The task is incremental, validates its inputs and output, keeps a correct generated archive untouched, and writes a replacement via a same-directory temporary file and atomic move when supported. The checked-in Whisper Tiny RU asset remains at `app/src/main/assets/models/whisper-tiny-ru.7z`.

The standalone Python restore command defaults to the requested source-tree destination `app/src/main/assets/models/gigaam-v3-e2e-ctc-int8.7z`. For a manual validation that will not create a duplicate asset source next to Gradle's generated asset, specify a separate output under the ignored build directory:

```powershell
python -B scripts/restore_model_archive.py --output .\app\build\manual-restore\gigaam-v3-e2e-ctc-int8.7z
```

To run the archive-tool tests:

```powershell
python -B -m unittest discover -s tests -v
```

## Splitting a newly prepared archive

Use the splitter only with the original archive for which the size and SHA-256 are trusted. It streams input, verifies the expected identity, stages chunks and a deterministic manifest in a temporary directory, and refuses to replace an existing output directory:

```powershell
python scripts/split_model_archive.py C:\path\to\gigaam-v3-e2e-ctc-int8.7z
```

The default output is `model-parts/gigaam`; an alternate new directory can be selected with `--output-dir`. For a replacement model/version, first review and update the trusted metadata constants in the split script, restore script, APK verifier, and Gradle task together. Generate new parts in a new directory, verify the original archive's SHA-256 independently, inspect the manifest and parts, and update this document and CI/build checks as appropriate. Do not copy expected hashes from an old manifest or derive a trusted hash from the parts being checked.

To check the restored archive independently:

```powershell
Get-FileHash .\app\build\generated\gigaamAssets\models\gigaam-v3-e2e-ctc-int8.7z -Algorithm SHA256
```

If 7-Zip is installed, it can additionally verify archive structure and list its contents:

```powershell
7z t .\app\build\generated\gigaamAssets\models\gigaam-v3-e2e-ctc-int8.7z
7z l .\app\build\generated\gigaamAssets\models\gigaam-v3-e2e-ctc-int8.7z
```

## GitHub Actions and APK verification

GitHub Actions runs the Python tooling tests, Gradle unit tests, and debug APK build. The Gradle Android Components API registers the generated model asset source for each variant, so the build fails if any part is absent, malformed, or fails checksums. A final step opens the APK as a ZIP and hashes the uncompressed GigaAM entry; it also requires the Whisper asset and rejects parts, manifests, or temporary files in the APK.

For a local APK check:

```powershell
python scripts/verify_model_apk.py app/build/outputs/apk/debug/app-debug.apk
```

The expected APK entries are `assets/models/gigaam-v3-e2e-ctc-int8.7z` and `assets/models/whisper-tiny-ru.7z`. The GigaAM entry's uncompressed size and SHA-256 must equal the pinned source archive. Neither chunks nor the manifest belong in the APK.

## Git rules and offline behavior

Commit all four `.partNNN` files, the manifest, and the maintenance scripts. Do not commit the restored `app/src/main/assets/models/gigaam-v3-e2e-ctc-int8.7z`; `.gitignore` excludes only that path. Do not ignore the assets directory or Whisper archive. No Git LFS, runtime downloads, model-service credentials, or network access are part of this design. Once built, the APK contains the one complete archive and remains usable offline with the existing model-archive installation flow.

The archive's presence does not register GigaAM as an installed model or establish that Android inference is supported. GigaAM inference and model-catalog integration remain separate work.
