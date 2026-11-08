# Fluffy Disco

On-device Android conversation transcription with local Whisper Tiny RU inference.

## Features

- Local audio transcription on Android
- Prototype offline diarization pipeline (Silero VAD + ECAPA embedding + global speaker clustering)
- Structured transcript output
- Chunk-level timestamps (30-second Whisper windows)

## Current limitations

- The bundled Whisper Tiny RU model is configured for Russian transcription.
- The ECAPA graph and sidecar currently present in this worktree are local-only, git-ignored evaluation files. This worktree's debug APK packages them and checksum-verifies their copy into app-private storage. Their redistribution/training-data license is not verified: do not share this APK or redistribute the weights. A clean checkout without the local files still falls back to `speaker_unknown`.
- Silero frame inference includes the upstream 64-sample context required at 16 kHz; omitting it caused speech probabilities near zero and has been corrected.
- Whisper/GigaAM expose chunk-level rather than word-level times. A transcript chunk overlapping multiple diarized speakers is kept intact as `speaker_unknown`; the app does not yet produce separate speaker-attributed text turns because there is no verified word alignment or Russian conversation/WER test set.
- No labeled Russian conversational diarization evaluation set is available; clustering thresholds are initial, uncalibrated values. DER, whole-pipeline RTF, peak PSS, and 4–6 GB device support have not been measured.

## Requirements

- Android Studio
- JDK 17+
- Android device or emulator

## Build

```bash
git clone <repo-url>
cd fluffy-disco
./gradlew assembleDebug
```

Or open the project in Android Studio and run the app.

## Usage

1. Select or import a conversation recording.
2. Start transcription.
3. Review the structured transcript with speakers and timestamps.

## Privacy

All audio processing happens locally on the device. Recordings are not uploaded to a server.

## Bundled offline model archives

Model installation reads 7z files from `app/src/main/assets/models/`, verifies a developer-pinned SHA-256, and extracts them into the app's private `files/models/<model-id>/<version>/<sha256>/` directory. Runtime model installation does not download files or make network requests. The bundled Whisper Tiny RU archive is registered with its measured size and SHA-256 in `ModelCatalog`.

The `basic` model is Whisper Tiny RU. Its offline archive contains the supplied ONNX encoder and plain decoder, tokenizer, and inference configuration. The encoder and decoder weights are dynamically quantized to signed 8-bit ONNX weights to keep the APK asset under GitHub's 100 MiB per-file limit; the source model folders are not modified. Audio is decoded to mono 16 kHz PCM, split into 30-second chunks, converted to Whisper log-mel features, and greedily decoded on-device with ONNX Runtime. Chunk boundaries are the only timestamps; the ASR engine's speaker label is only a placeholder. The optional post-ASR diarization stage above can replace it only when its required models are available.

The bundled `silero_vad_16k_op15.onnx` comes from Silero VAD commit `066409f3d9db45a30d21dfcbf9147826ffdd7052`, is MIT-licensed, and is verified by SHA-256 when copied to app-private storage. The diarization pipeline reads PCM from the temporary WAV in bounded blocks, closes the VAD ONNX session before opening the ECAPA session, and clusters normalized 192-dimensional embeddings globally. See [the diarization report](docs/diarization-report.md) for model metadata and validation limits.

The team ECAPA model expects `waveform` float `[1, 48000]` and returns `embedding` float `[1, 1, 192]`. It depends on `ecapa_ru.onnx.data` (83,419,892 bytes) alongside the 652,140-byte graph. In this worktree, the exact pair is supplied locally, ignored by Git, and packaged only into the local debug APK; `DiarizationModelFiles.prepare` verifies both size and SHA-256 before atomically copying them into `files/models/diarization/`. The private package passed an Android ONNX Runtime inference smoke test. Model and training-data redistribution rights remain unverified; do not share this APK or include these files in a release until the rights and trusted source are approved. A fresh checkout does not contain the ignored pair.

### Prepare and register a model

1. Prepare the model's real runtime files in a clean directory, for example `model-files`. Include only files needed by that model and preserve their relative paths. Record the required runtime files exactly as they should appear after extraction.
2. Create the asset directory and archive the contents of the model directory. From the repository root in PowerShell:

   ```powershell
   New-Item -ItemType Directory -Force .\app\src\main\assets\models
   Push-Location .\model-files
   7z a -t7z -mx=9 -m0=LZMA2 -md=64m -ms=on ..\app\src\main\assets\models\<model-id>-v1.7z .\*
   Pop-Location
   ```

   Keep the archive's paths relative to `model-files`; avoid absolute paths, `..` segments, symbolic links, duplicate names, and a top-level `.installed` entry. Choose an LZMA2 dictionary size that fits the app's 128 MiB default decoder memory cap (the configured cap must be between 64 and 256 MiB).
3. Calculate the trusted checksum, compressed archive size, and unpacked size on the development machine:

   ```powershell
   Get-FileHash .\app\src\main\assets\models\<model-id>-v1.7z -Algorithm SHA256
   (Get-ChildItem .\model-files -File -Recurse | Measure-Object -Property Length -Sum).Sum
   (Get-Item .\app\src\main\assets\models\<model-id>-v1.7z).Length
   ```

   Set `maxUnpackedBytes` to a reviewed upper bound at or above the measured unpacked size. Set `maxEntries` to a reasonable ceiling for the archive. The installer uses the archive's actual trusted metadata size to verify the copy and preflight free storage.
4. Register the archive on the real `ModelInfo` entry in `ModelCatalog` with `bundledArchive = BundledModelArchive(...)`. Set its version, asset path under `models/`, actual 64-character SHA-256 from the previous command, exact `requiredFiles`, compressed archive byte length, maximum unpacked bytes, maximum entry count, and decoder memory limit. Do not copy a checksum from another build or compute the expected checksum at install time.
5. Build and verify that the archive is included in the APK:

   ```powershell
   $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
   $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
   .\gradlew.bat :app:assembleDebug
   & "$env:JAVA_HOME\bin\jar.exe" tf .\app\build\outputs\apk\debug\app-debug.apk | Select-String 'assets/models/'
   ```

6. Install the APK, enable airplane mode, open **Settings → Transcription models**, install the bundled model, then force-stop and relaunch the app to verify that the installation marker and required files are recognized. Verify the archive hash and extracted file paths against the registered metadata. The archive's registration alone is not proof of correct inference; validate model input/output names and run an audio transcription on a device before release.

The APK asset packer is configured not to recompress `.7z` files. Large model archives must remain below GitHub's 100 MiB regular-file limit unless the repository has an authenticated, approved Git LFS workflow.

The GigaAM v3 archive is stored as checksummed Git parts and restored automatically by Gradle before assets are packaged. See [docs/model-archives.md](docs/model-archives.md) for local build, archive maintenance, integrity, and APK verification instructions.

## Status

Early development. Features and UI may change.
