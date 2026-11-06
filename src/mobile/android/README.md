# Fluffy Disco

On-device Android conversation transcription with speaker diarization and timestamped transcript.

## Features

- Local audio transcription on Android
- Automatic speaker detection and separation
- Structured transcript with participant labels
- Timestamps for each utterance

## Requirements

- Android Studio
- JDK 17+
- Android device or emulator

## Build

From the root of the `hackathon_nero-stego_hex_team` repository, run:

```bash
cd src/mobile/android
./gradlew assembleDebug
```

On Windows PowerShell, use the batch wrapper:

```powershell
cd src/mobile/android
.\gradlew.bat assembleDebug
```

Or open the project in Android Studio and run the app.

## Usage

1. Select or import a conversation recording.
2. Start transcription.
3. Review the structured transcript with speakers and timestamps.

## Privacy

All audio processing happens locally on the device. Recordings are not uploaded to a server.

## Status

Early development. Features and UI may change.
