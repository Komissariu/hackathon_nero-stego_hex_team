# Offline diarization feasibility and implementation report

## Status

**Three concrete causes/limitations are established; the VAD contract bug is fixed, while model availability and ASR turn-to-text granularity remain unresolved.**

1. Earlier APKs did not contain ECAPA or its 83 MB external-data sidecar, and the app has no user-facing offline installation flow. Before manual staging, on-device `TranscriptionProcessor` logcat reported both missing app-private paths and replaced ASR placeholder labels with `speaker_unknown`. The current worktree now builds a private debug APK from a locally supplied, git-ignored pair; a clean checkout still lacks these files.
2. The Kotlin Silero adapter omitted the upstream model's 64-sample recurrent audio context. With the same ONNX model, real speech therefore produced near-zero probabilities and no VAD intervals. `SileroVadOnnxEngine` now prepends the prior 64 audio samples to each 512-sample frame (model input `[1,576]`), while retaining 512-sample timestamps and resetting context/state per file.
3. ASR segment boundaries are mistaken for utterance boundaries downstream. `TranscriptionProcessor` runs ASR first and diarization second. Whisper emits one `TranscriptSegment` per nonblank 30-second processing window with a placeholder `speaker_1`; GigaAM likewise emits one segment per up-to-30-second input chunk. The aligner cannot recover word/utterance boundaries that ASR did not return. Before this pass, its 80% dominant-overlap rule could even assign a mixed segment to the dominant speaker; for less dominant coverage it returned `speaker_unknown`. Both outcomes lose turn-level attribution.

The context fix is verified in host ORT and by targeted Android tests. The current local debug APK contains the exact local ECAPA pair, and an Android instrumentation test extracted it to app-private storage, verified its hashes, and successfully ran inference. A small labeled two-speaker embedding/clustering sanity test also separated example clips; its A–B–A labels use embeddings from two separate VoxCeleb clips assigned synthetic ordered intervals. This is **not** an A–B–A conversation recording, a diarization benchmark, or evidence of Russian conversational quality. The pair is git-ignored and the model's training-data and redistribution rights remain unverified; the APK is for local evaluation only and must not be shared. This pass makes the aligner conservative: if an ASR segment overlaps turns for multiple known speaker IDs or any `speaker_unknown` turn, it is retained intact but assigned `speaker_unknown` instead of assigning the dominant speaker. Adjacent turns for the same speaker are combined as interval coverage before applying the 80% threshold. Debuggable builds now log aggregate ASR/turn/unknown counts, without audio, text, or embeddings. This exposes where turn information is discarded; it does not produce speaker-separated text. Speaker-aware re-ASR was not enabled because no Russian multi-speaker reference exists to assess WER, omissions, duplication, or latency. There is no labeled Russian conversation/RTTM set, no DER/WER result, and no 4–6 GB device or peak-PSS acceptance result.

## Git and scope

- Current branch: `srpnsk-high-quality-offline-diarization`.
- Starting commit/base: `1774b5d` (`origin/main`, also `origin/HEAD`), the checkout's current stable tip. The local `main` ref was at `7d033ec` and two commits behind this starting point.
- Requested branch name: `feature/high-quality-offline-diarization`. The workspace branch-renaming tool applied the configured `srpnsk-` prefix, producing the branch name above. No raw Git branch rename was used.
- ECAPA graph and sidecar are ignored local evaluation inputs and are not part of this source change or its pull request; their model/data redistribution rights remain unverified.
- Changed files include `.gitignore` entries that keep the local ECAPA pair out of version control, the standalone `data/diarization/` pipeline and tests, the bundled Silero VAD model/license, a narrow `TranscriptionProcessor` integration, and this report/README. ECAPA binaries are intentionally ignored rather than tracked.
- `TranscriptionEngine`, `TranscriptionEngineFactory`, `AudioDecoder`, Whisper, GigaAM, `TranscriptSegment`, model catalog/repository/installer, transcript storage/JSON, recording, export, and navigation were not changed.

Changed-file inventory (26 tracked/untracked source files; the two ECAPA binaries are git-ignored and not included):

- `.gitignore`
- `README.md`
- `docs/diarization-report.md`
- `app/src/main/java/com/example/fluffydisco/data/transcription/TranscriptionProcessor.kt`
- `app/src/main/assets/models/diarization/SILERO-VAD-LICENSE.txt`
- `app/src/main/assets/models/diarization/silero_vad_16k_op15.onnx`
- `app/src/main/java/com/example/fluffydisco/data/diarization/DiarizationModelFiles.kt`
- `app/src/main/java/com/example/fluffydisco/data/diarization/OfflineDiarizationProcessor.kt`
- `app/src/main/java/com/example/fluffydisco/data/diarization/Pcm16WavFile.kt`
- `app/src/main/java/com/example/fluffydisco/data/diarization/SileroVadOnnxEngine.kt`
- `app/src/main/java/com/example/fluffydisco/data/diarization/SpeakerChangeDetector.kt`
- `app/src/main/java/com/example/fluffydisco/data/diarization/SpeakerClustering.kt`
- `app/src/main/java/com/example/fluffydisco/data/diarization/SpeakerEmbedding.kt`
- `app/src/main/java/com/example/fluffydisco/data/diarization/SpeakerEmbeddingOnnxEngine.kt`
- `app/src/main/java/com/example/fluffydisco/data/diarization/SpeakerTurnRefiner.kt`
- `app/src/main/java/com/example/fluffydisco/data/diarization/SpeechInterval.kt`
- `app/src/main/java/com/example/fluffydisco/data/diarization/TranscriptSpeakerAligner.kt`
- `app/src/androidTest/java/com/example/fluffydisco/data/diarization/EcapaDiagnosticInstrumentedTest.kt`
- `app/src/test/java/com/example/fluffydisco/data/diarization/BundledSileroVadModelTest.kt`
- `app/src/test/java/com/example/fluffydisco/data/diarization/Pcm16WavFileTest.kt`
- `app/src/test/java/com/example/fluffydisco/data/diarization/SpeakerChangeDetectorTest.kt`
- `app/src/test/java/com/example/fluffydisco/data/diarization/SpeakerClusteringTest.kt`
- `app/src/test/java/com/example/fluffydisco/data/diarization/SpeakerEmbeddingTest.kt`
- `app/src/test/java/com/example/fluffydisco/data/diarization/SpeakerTurnRefinerTest.kt`
- `app/src/test/java/com/example/fluffydisco/data/diarization/TranscriptSpeakerAlignerTest.kt`
- `app/src/androidTest/java/com/example/fluffydisco/data/diarization/SileroVadOnnxInstrumentedTest.kt`

## Existing processing path

`TranscriptionProcessor` still selects the configured ASR engine and decodes to the existing temporary mono 16 kHz 16-bit PCM WAV. It runs whole-recording ASR first, then checksum-copies the small VAD model from APK assets into app-private files, checks for the ECAPA graph and sidecar, and—only when both exist—runs global diarization and alignment before saving via the unchanged JSON schema. VAD session is closed before the ECAPA session is opened. PCM is read by bounded random-access buffers; embeddings and the clustering matrix are the retained per-window state. Missing ECAPA files produce an explicit warning and `speaker_unknown`; inference and I/O exceptions propagate through the existing worker error path.

Whisper parses PCM in fixed windows of `30 * 16,000` samples and returns one segment spanning each nonblank window; it uses the absolute sample offset for those window timestamps and assigns placeholder `speaker_1`. It does not emit word/utterance times. GigaAM also processes at most 30 seconds per chunk and returns one text segment per nonblank chunk, with placeholder `speaker_1`. The processor invokes either complete-file ASR before diarization; the technical chunk boundaries are thus the only text timestamps handed to the aligner. The aligner never splits or duplicates text. A multi-speaker ASR segment is now kept as a single `speaker_unknown` segment even if one speaker covers more than 80% of it. A segment overlapping just one known speaker can receive that speaker only if the configured coverage is met. This deliberately avoids false attribution but does **not** convert a long ASR chunk into individual human turns.

Debug builds emit a metadata-only `ASR_ALIGNMENT` log with ASR segment count, diarized turn count, count of ASR segments overlapping multiple speaker IDs, unknown segment count, and assigned segment counts per ID. It contains no recording path, transcript content, audio, or embeddings. These counters expose ASR-to-turn information loss; they are not a transcript-quality or speaker-accuracy metric.

The change detector evaluates cross-context mean cosine distances and `SpeakerTurnRefiner` uses detected points to snap existing different-speaker boundaries. It does not create speaker identities by itself. Uncertain/short embeddings cannot start a cluster; they attach to a global cluster only if they pass the configured similarity threshold, otherwise remain unknown. Speaker IDs are numbered by first cluster appearance. Alignment diagnostics count ASR chunks overlapping multiple known speakers and chunks overlapping an unknown turn; either kind of overlap blocks a confident speaker assignment. Coverage from adjacent/overlapping intervals for the same speaker is unioned to avoid undercounting or double-counting.

## Confirmed root-cause checks

| Check | Observation | Conclusion / action |
|---|---|---|
| Earlier APK model entries | Silero ONNX and its license were present; ECAPA graph and sidecar were absent. | The earlier app build could not load ECAPA. |
| Current local debug APK model entries | Silero ONNX/license and the exact `ecapa_ru.onnx`/`.data` pair are present; both entry hashes/sizes match the inspected TEMP originals. | This worktree's local debug APK can load ECAPA; these ignored artifacts are not available in a clean checkout and are not cleared for redistribution. |
| Installed app before diagnostic staging | Logcat from `TranscriptionProcessor` named both missing ECAPA paths under `/data/user/0/com.example.fluffydisco/files/models/diarization/` and said ASR labels were being replaced with `speaker_unknown`. | Confirmed missing-model fallback, not an inference-quality hypothesis. No installer populates those paths. |
| ONNX graph and external data | Graph and sidecar hashes/sizes match the inspected files; graph input is `waveform` float `[s16,48000]`, output is `embedding` float `[1,1,192]`; 206 initializers refer to the sibling sidecar. Inference succeeds only when both files are adjacent. | External-data discovery is correct when developer files are staged; no external-data load error occurred in host or Android diagnostic inference. |
| PCM passed to ECAPA | Existing WAV parser verifies mono PCM16/16 kHz. Values are converted as signed PCM16 / 32768 to float32; measured windows contain finite, non-zero audio. | No channel/rate/integer-versus-float error was found in the tested files. |
| Silero frame preprocessing before fix | Kotlin passed `[1,512]`. The pinned upstream `OnnxWrapper` prepends 64 samples of prior audio at 16 kHz, sends `[1,576]`, then retains the last 64 samples; context and recurrent state reset between files. | Confirmed VAD contract mismatch. Kotlin now implements the upstream 64-sample context. |
| Silero after context fix | On the same pinned model and the same public speech clips, host probabilities rose from near-zero to medians above `0.9995`; the Android test found speech intervals in both clips. | Confirmed correction restores speech detections for these clips without changing thresholds. |
| ASR turn-to-text boundary | Source confirms one Whisper `TranscriptSegment` per nonblank 30-second PCM window (and one GigaAM segment per <=30-second chunk); both use placeholder `speaker_1`. `TranscriptionProcessor` calls `transcribe` before diarization. | Confirmed granularity loss point: no word/utterance alignment is produced at the ASR stage, so the current aligner cannot return several text segments for turns inside a single ASR chunk. |
| Mixed-speaker alignment before this pass | For any single speaker covering >=80% of an ASR segment, the old aligner assigned that speaker to the entire text despite other-speaker overlap; otherwise it preserved the entire text as `speaker_unknown`. | Confirmed unsafe dominant-speaker shortcut. The aligner now rejects segments overlapping more than one known speaker or any unknown turn, regardless of dominant share; it preserves text/timestamps and does not fabricate a split. |
| Full natural conversation pipeline | No Russian multi-speaker audio or word/turn ground truth is available in this worktree/TEMP set. `gigaam-reference.wav` is an unlabeled ASR fixture; two public VoxCeleb clips are separate single-speaker examples. | Cannot establish end-to-end conversational diarization, WER change, omissions, duplicates, or accuracy. |
| Clustering/alignment | A constructed A–B–A ordering of real ECAPA embeddings from two labeled but separate clips yields `speaker_1, speaker_2, speaker_1` with the current prototype threshold. | Evidence that embeddings carry speaker information for this pair only; not a natural conversation or production threshold calibration. |

This evidence supports the missing-model and VAD-context failures as causes of unusable output. It does not establish that these are the only failure modes on arbitrary recordings.

## Model artifacts and contracts

The Silero model is included as `app/src/main/assets/models/diarization/silero_vad_16k_op15.onnx`. It was obtained from the upstream file at pinned commit `066409f3d9db45a30d21dfcbf9147826ffdd7052`, verified after copying, and ships with the upstream MIT license in `SILERO-VAD-LICENSE.txt`. Its ONNX contract is: `input` float `[batch, sequence]`, `state` float `[2, batch, 128]`, scalar `sr` int64; outputs `output` float `[batch,1]`, `stateN` float `[2,batch,128]`. At 16 kHz, the official helper at that pinned commit combines 64 preceding samples with each 512-sample frame (model input `[1,576]`); the Kotlin engine now matches this and resets both context and recurrent state for each file.

The exact ECAPA graph and sidecar originated in the developer TEMP model directory and are now present as git-ignored assets in this worktree only, for a requested private debug APK. Local export metadata identifies a team fine-tune over SpeechBrain ECAPA-VoXCeleb-base with 3-second windows. **No LICENSE, model card, provenance manifest, or training-data permissions accompany this pair; its redistribution and training-data rights are unverified.** The current APK is consequently local evaluation output only; do not share or release it. The distinct upstream SpeechBrain model card `speechbrain/spkrec-ecapa-voxceleb` declares Apache-2.0 for that published model and says it was trained on VoxCeleb1+2; that does not by itself establish the rights to this separate team fine-tune.

A public alternative was evaluated rather than silently substituted. [`mpai-community/ecapa-tdnn-3dspeaker-onnx`](https://huggingface.co/mpai-community/ecapa-tdnn-3dspeaker-onnx/tree/80baf69c67e70431fb882196d60e49ee49e7abd5), revision `80baf69c67e70431fb882196d60e49ee49e7abd5`, has an [Apache-2.0 LICENSE](https://huggingface.co/mpai-community/ecapa-tdnn-3dspeaker-onnx/blob/80baf69c67e70431fb882196d60e49ee49e7abd5/LICENSE) and README attribution, and its published ONNX hash is `38fdfc7d2bc9e2925349baab9639fcaf4b4c755be83ee7d616b6e3fbc9d5eab3` (83,303,532 bytes). Its README explicitly says the exact source checkpoint and conversion command were not recorded ("most probably" a named ModelScope checkpoint), and describes a fixed `feature` input `[1,360,80]` of Kaldi-style log-Mel features and `embedding` output `[1,192]`. Host ONNX Runtime 1.31.1 CPU inference with a correctly shaped feature tensor returned finite `[1,192]`; the current Android engine instead feeds raw PCM `waveform` `[1,48000]` and expects `[1,1,192]`. It is therefore not a drop-in replacement; selecting it would require a new 3.6-second feature frontend, model-specific validation, and confirmation of source-checkpoint/training-data rights. It was not substituted.

The app-private location is `files/models/diarization/ecapa_ru.onnx` plus adjacent `ecapa_ru.onnx.data`. When both files are in the APK's `models/diarization/` assets, `DiarizationModelFiles.prepare` copies them through temporary files, verifies exact sizes and SHA-256 values, and atomically installs them into app-private storage. It rejects a one-file asset pair. No runtime network download or user-facing model installation flow is provided.

The following existing **ASR-only** archive integrity checks were made; they are not diarization artifacts:

| Artifact | Size | SHA-256 | Result |
|---|---:|---|---|
| `app/src/main/assets/models/whisper-tiny-ru.7z` | 102,747,308 bytes | `3f8ff19e0223564f6ee54e22986f3112d65b5c136c71d029ea5f5461180a1ecb` | Matches catalog size and hash |
| GigaAM archive part 001 | 40,000,000 bytes | `b7f0b801978c9a0fd4c0203e87905744bef53f93f38abc52b4ab630fa3d1a853` | Matches manifest |
| GigaAM archive part 002 | 40,000,000 bytes | `0617575d5fc621a6e616c723e09fff8c7106612a1fe7f4992a2f781bb28ec361` | Matches manifest |
| GigaAM archive part 003 | 40,000,000 bytes | `70971def2d64d4706ce6d479a219276513ee6ecde2e5f1fc03bb44f0928d91e4` | Matches manifest |
| GigaAM archive part 004 | 35,314,569 bytes | `15dc88ac82237a6694b213e11ec0a8761403fbbc1db438b29b098d697ca52a17` | Matches manifest |

The repository's GigaAM manifest identifies revision `0928919f7df949871b507e16497b9e8cff7d0c6b`; GigaAM is an ASR model and is not used as a speaker model. No standalone 7-Zip executable was available during the audit. The configured Gradle archive restoration/build checks remain the appropriate repository validation for those existing assets.

| Diarization model | Source / license | SHA-256 / size | Tensor contract | Runtime evidence |
|---|---|---|---|---|
| Silero VAD `silero_vad_16k_op15.onnx` | `snakers4/silero-vad`, pinned commit `066409f3d9db45a30d21dfcbf9147826ffdd7052`, MIT | `7ed98ddbad84ccac4cd0aeb3099049280713df825c610a8ed34543318f1b2c49`; 1,289,603 bytes | Input context + frame `[1,576]`, recurrent state `[2,1,128]`, `sr=16000` | Host comparison and on-device instrumented speech/state-reset tests passed |
| Team `ecapa_ru.onnx` + external data | Local `ml-optimized` export/fine-tune; no accompanying license/provenance; training-data and redistribution rights unknown | Graph `b6408df4ff25fccbb24a93020bf11e3d2b92978269f2439160a7fce1ea602e52`, 652,140 bytes. Sidecar `6cd82747bf973507b57f8df79158b36719515386acc77f62ecf1fe8c0dc0a0a4`, 83,419,892 bytes. | Input `waveform` float `[1,48000]`; output `embedding` float `[1,1,192]`; 206 external initializers refer to sibling sidecar | Exact pair appears in this local-only APK; verified APK entries and Android app-private extraction/inference passed |
| MPAI 3D-Speaker ECAPA alternative | `mpai-community/ecapa-tdnn-3dspeaker-onnx`, Apache-2.0 LICENSE; card admits exact checkpoint/conversion not recorded | `38fdfc7d2bc9e2925349baab9639fcaf4b4c755be83ee7d616b6e3fbc9d5eab3`; 83,303,532 bytes | Input `feature` float `[1,360,80]`; output `embedding` float `[1,192]`; 3.6 s Kaldi-style log-Mel frontend required | Host CPU ONNX smoke passed; incompatible with current raw-waveform engine contract and not selected |

The sherpa-onnx Android speaker-diarization project and its model/Kotlin documentation were reviewed as conceptual references only. The existing ONNX Runtime is used directly; no sherpa JNI/runtime dependency is added. Its docs explicitly warn that clustering thresholds need tuning; the initial cosine-distance thresholds in this implementation are not calibrated and must not be interpreted as measured quality settings.

## Dataset and quality

No Russian conversational diarization corpus, RTTM/turn annotations, prepared 1,330-recording dataset, or development/test split was found in the repository. The existing `gigaam-reference.wav` is an ASR fixture without speaker labels. For a narrow speaker-embedding sanity check, two public WAV examples were fetched from the SpeechBrain ECAPA model card's VoxCeleb widgets (speaker IDs 10003 and 10004); they are separate single-speaker clips, not a conversation. They were kept in TEMP/on-device test cache only, not committed or packaged:

| Clip | Source | Format / duration | Size / SHA-256 |
|---|---|---|---|
| VoxCeleb speaker 10003 | `https://cdn-media.huggingface.co/speech_samples/VoxCeleb1_00003.wav` | mono PCM16, 16 kHz, 4.2001 s | 134,446 bytes / `f9f522865b589d717ec80f89eaf50993caf81467ee8b27c9c3951b6ea56296b4` |
| VoxCeleb speaker 10004 | `https://cdn-media.huggingface.co/speech_samples/VoxCeleb_00004.wav` | mono PCM16, 16 kHz, 17.7601 s | 568,366 bytes / `ff2718f724166d000dbb66d64d6293c823ca4c8fd5cbe304e6c2cabead003ff2` |

The source model-card revision inspected was `0f99f2d0ebe89ac095bcc5903c4dd8f72b367286`; it labels the examples as different VoxCeleb speaker IDs. These samples are useful for a basic embedding sanity check only. They are not Russian, not naturally alternating conversation, and do not support generalization or DER claims.

| Metric / case | Result |
|---|---|
| DER (strict no-collar/overlap protocol), JER | Not measured: no labeled conversational diarization corpus |
| False alarm, missed speech, speaker confusion, speaker-count error | Not measured on conversation ground truth |
| ECAPA same/different-speaker sanity | Same-speaker overlapping windows from clip 10003 cosine `0.805868`; its two windows overlap substantially. Across those 2 windows and 11 windows from clip 10004, cross-speaker mean cosine `0.087723` (22 pairs, range `0.024451–0.168458`). These two clips show separation, not a calibrated population distribution. |
| Constructed embedding A–B–A | The two real clip embeddings assigned to synthetic ordered 3-second intervals cluster as `speaker_1, speaker_2, speaker_1`. This is a clustering sanity check over real embeddings, **not an A–B–A recording or conversation diarization test**. |
| Natural A–B–A / A–B–A–B / A–B–C–A conversations | Not available; not tested |
| Short-turn and boundary quality | Not measured |
| Overlap detection | Not implemented or measured |
| WER impact | Not measured |
| Text-to-speaker alignment | Conservative alignment is unit-tested; real word-level alignment quality not measured. Mixed-speaker or unknown-turn overlap remains one intact `speaker_unknown` ASR chunk. |

No ASR text is split across speakers by duration or assigned to a diarization turn. Doing so without word timestamps would be unsupported and could silently misattribute text.

### Speaker-aware ASR decision

The current information loss is visible directly in code:

1. Whisper parses PCM in fixed `30 * 16,000`-sample windows and creates at most one segment per nonblank window. The decoder is started with `<|notimestamps|>`, so no word or utterance boundaries are emitted.
2. GigaAM similarly creates one segment per nonblank window of up to 30 seconds.
3. `TranscriptionProcessor` calls complete-recording `engine.transcribe` before global diarization; the speaker turns cannot guide ASR ranges.
4. The aligner receives only those coarse chunk times. It cannot divide text safely at turn changes. The previous dominant-overlap rule could misattribute the whole chunk; the current behavior preserves text/timestamps but returns `speaker_unknown` whenever more than one known speaker (or any unknown turn) overlaps it.

For example, one ASR text segment spanning 30 seconds with A speaking 27.5 seconds and B speaking 2.5 seconds used to be labeled entirely A under the 80% rule. It is now retained intact as `speaker_unknown`; it is not split into separate speaker utterances. This is the confirmed point where voice-turn information cannot be represented in the existing ASR result.

Speaker-aware re-ASR is **not enabled** because no labeled Russian conversation is available to compare WER, loss/duplication, speaker attribution, and latency. A viable candidate would first diarize globally, coalesce reliable adjacent turns by global ID, extract bounded PCM ranges, reuse one ASR session for all ranges, and offset local times back to the full recording. It would also need a tested context policy around turn boundaries; otherwise short responses, coarticulation, uncertain boundaries, and overlap may be clipped or duplicated. This candidate is not verified, so it is not made the default. The current absence of separate speaker-attributed text turns is an acceptance blocker, not a completed feature.

The worktree has no real Russian multi-speaker conversation/RTTM. Its only checked-in WAV, `gigaam-reference.wav`, is an unlabeled 11.29-second ASR fixture. TEMP SpeechBrain examples are single-speaker English clips (additional formats include WAV and FLAC); they are not substitutes for one-speaker, A–B–A–B, or A–B–C–A conversation tests. Consequently there is no real conversational WER/DER, segment-loss/duplication, or 30-second-crossing result.

## Performance and device validation

Host measurements in this report used the isolated Python 3.14 TEMP venv `fluffy-disco-diarization-venv` with ONNX Runtime **1.31.1** and ONNX **1.23.2**. A separate TEMP environment named `fluffy-disco-diarization-ort1232` contains ONNX Runtime **1.24.1**; that environment was not used for the host measurements reported here. The Android app uses its existing ONNX Runtime 1.23.2 dependency. A connected `SM-G996N` running Android 14 (API 34) was available; `dumpsys` reported total RAM `7,373,724 KB` (an 8 GB-class phone, not the 4–6 GB target).

### Silero context regression, same model and audio

The comparison uses the exact pinned Silero file and the two public clips above. The old path sent only each 512-sample block. The corrected path prepends 64 samples of prior audio and sends `[1,576]`, as in upstream `utils_vad.py`; the final partial audio block is zero-padded, and recurrent state/context are reset per file.

| Audio | 512 samples only: max / median probability; frames ≥0.5 | With 64-sample context: median / max probability; frames ≥0.5 |
|---|---|---|
| Speaker 10003, 132 frames | `0.074235` / `0.000455`; `0/132` | `0.999766` / `0.999999`; `125/132` |
| Speaker 10004, 556 frames | `0.003080` / `0.000518`; `0/556` | `0.999584` / `0.999994`; `498/556` |
| Existing GigaAM ASR reference, 353 frames | `0.003088` / `0.000518`; `0/353` | `0.999917` / `0.999999`; `325/353` |

This is a direct reproduction of the preprocessing contract bug, not threshold tuning. The corrected Kotlin detector also returned one bounded speech interval for each public speaker clip on Android.

| Configuration | DER | RTF | Peak PSS | Java/native heap | Outcome |
|---|---:|---:|---:|---:|---|
| Android `SM-G996N`, current debug APK's packaged ECAPA assets | Not a DER test | Not benchmarked | Not measured | Not measured | `stagedEcapaLoadsAndEmbedsRealReferenceAudio` passed (1 test, 0 failures/skips, 7.181 s). `prepare()` copied both assets into app-private storage; checksum validation and real 192D ONNX inference succeeded. The GigaAM fixture yielded 2 VAD intervals, 6 embeddings, 2 turns; this is not a speaker-count/DER result. |
| Android `SM-G996N`, prior manually staged developer-only model/audio test | Not a DER test | Not benchmarked | Not measured | Not measured | Earlier two-method diagnostic result: public clips had VAD interval counts `[1,1]`, ECAPA window counts `[2,11]`, same-speaker cosine `0.805868`, cross-speaker mean `0.087723`; synthetic-order embedding A–B–A labels were `speaker_1, speaker_2, speaker_1`. Not a conversation test. |
| Android same-device GigaAM reference WAV, with TEMP ECAPA staged | Not measured; speaker labels unknown | Not benchmarked | Not measured | Not measured | Full `OfflineDiarizationProcessor` returned 2 speech intervals, 2 observations, 2 turns; a separate six-window ECAPA smoke returned finite 192D embeddings. Not evidence of correct speaker count. |
| Host Silero, with-context path, above clips | N/A | Not a pipeline benchmark | Not measured | N/A | Real speech probabilities and frame counts shown in table above, ONNX Runtime 1.31.1. |
| 4 GB / 6 GB Android target | Not measured | Not measured | Not measured | Not measured | No target device available; support unconfirmed. |

The current connected test used the app's existing Android ONNX Runtime 1.23.2. Its retained XML is `app/build/outputs/androidTest-results/connected/debug/TEST-SM-G996N - 14.xml`; it records `stagedEcapaLoadsAndEmbedsRealReferenceAudio`, 1 test, 0 failures/errors/skips, 7.181 seconds. The accompanying logcat result records `ecapa=loaded dims=192`, two VAD intervals and six embeddings. The test calls `DiarizationModelFiles.prepare()` on the installed APK, asserts both extracted files pass exact SHA/size validation, then opens the graph with ONNX Runtime and runs inference; it does not depend on manually staged app-private weights. No PSS peak, Java/native heap peak, full-pipeline RTF, long-recording, OOM/ANR, airplane-mode, or cancellation-under-inference measurement was made. The device is 8 GB class and cannot substantiate 4–6 GB support. The global clusterer bounds its dense distance matrix to 16,777,216 float cells (64 MiB); this is not total subsystem memory.

## Integration and tests

Implemented classes include `Pcm16WavFile`, `SileroVadOnnxEngine`, `SpeakerEmbeddingOnnxEngine`, `SpeakerEmbedding`, `SpeakerChangeDetector`, `SpeakerClustering`, `SpeakerTurnRefiner`, `TranscriptSpeakerAligner`, `DiarizationModelFiles`, and `OfflineDiarizationProcessor`. VAD and embedding consume sequential bounded frames/windows from a random-access PCM WAV file; embeddings and the distance matrix are retained for offline global clustering. The VAD and ECAPA sessions are created/closed sequentially. The clusterer's largest pairwise matrix is bounded as described above.

`TranscriptionProcessor` runs the new pass only when the ECAPA graph and `.data` file are present and validated under app-private files. If both pair assets are bundled, `DiarizationModelFiles.prepare` checksum-verifies and atomically copies them from APK assets; if they are absent in a clean checkout, the existing explicit warning and `speaker_unknown` fallback remain. In this worktree the exact pair is locally supplied and git-ignored; it is not part of a clone or tracked diff. The built APK is private evaluation output due unresolved model/data licensing, not a distributable release. Existing JSON fields/schema and storage are unchanged. Existing ASR engines and their artifacts were not edited.

Unit tests cover PCM bounds/format, vector normalization, synthetic A–B–A/A–B–C–A global clustering, short/uncertain suppression, first-appearance IDs, isolated-first-cluster handling, change detection/refinement, and conservative text alignment. The Android `SileroVadOnnxInstrumentedTest` passed on silence twice, confirming state reset. `EcapaDiagnosticInstrumentedTest` passed both methods on the connected phone after manual staging of the verified TEMP ECAPA pair and labeled clips; it verifies graph/sidecar loading, real PCM and embedding inference, VAD intervals, and the constructed embedding-clustering sanity above. It is an opt-in developer diagnostic (assumes staged files when absent), not a bundled-model test.

Validation results:

- `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --no-configuration-cache` — **BUILD SUCCESSFUL** with the git-ignored local ECAPA pair staged as APK assets; 46 unit tests, 0 failures/errors/skips; instrumentation Kotlin compilation passed.
- `.\gradlew.bat :app:connectedDebugAndroidTest --no-configuration-cache '-Pandroid.testInstrumentationRunnerArguments.class=com.example.fluffydisco.data.diarization.EcapaDiagnosticInstrumentedTest#stagedEcapaLoadsAndEmbedsRealReferenceAudio'` — **BUILD SUCCESSFUL** on `SM-G996N` / Android 14; retained result is 1 test, 0 failures/errors/skips. It verifies APK-asset extraction, exact checksums, external-data loading, and real-audio Android ONNX inference.
- Earlier targeted device execution: `EcapaDiagnosticInstrumentedTest` **2 passed**, `SileroVadOnnxInstrumentedTest` **1 passed** on `SM-G996N` / Android 14, with temporarily staged developer-only weights and public sample clips.
- `python -B -m unittest discover -s tests -v` — **20 tests passed**, including restoration and integrity checks for the real GigaAM archive parts.
- `python -B scripts/verify_model_apk.py app/build/outputs/apk/debug/app-debug.apk` — passed for existing Whisper/GigaAM assets. Final APK ZIP inspection confirmed Silero/license and both ECAPA entries; their uncompressed sizes and SHA-256 values exactly match the locally staged source pair.
- Python CPU ONNX checks on runtime 1.31.1 and Android 1.23.2 instrumented inference verified finite ECAPA output `[1,1,192]`; no general accuracy or RTF conclusion is drawn from those smoke checks.
- `git diff --check` — passed. Protected decoder, ASR engines, transcript model/schema, model catalog/installer, storage/JSON, export, recording, and navigation files remain unchanged.

Final private local `app-debug.apk`: built `2026-11-08 02:14:16`, 438,696,627 bytes, SHA-256 `1173239e311eba2d90d072adce1cd0edde9b0f3e791f43e017f5c02214c9c757`. APK entries `assets/models/diarization/ecapa_ru.onnx` and `assets/models/diarization/ecapa_ru.onnx.data` were streamed from the ZIP and verified as 652,140 bytes / `b6408df4ff25fccbb24a93020bf11e3d2b92978269f2439160a7fce1ea602e52` and 83,419,892 bytes / `6cd82747bf973507b57f8df79158b36719515386acc77f62ecf1fe8c0dc0a0a4`, respectively. Android extraction and inference passed as above. These model binaries are git-ignored local inputs and license-unverified; do not redistribute this APK.

No natural Russian conversation, DER, JER, speaker confusion/count error, WER change, peak PSS/heap, airplane-mode, long-audio, or cancellation test was available.

## Acceptance blockers

The 64-sample Silero context bug is fixed and its speech-detection improvement is measured. Remaining blockers:

- The current private local debug APK contains ECAPA, but the team fine-tune's redistribution/training-data license remains unknown. Do not share or release this APK or redistribute the ignored weights. A clean checkout lacks the ignored model pair, so its build still falls back to `speaker_unknown`; there is no user-facing installation flow.
- No labeled Russian conversational dataset/RTTM or independent train/dev/test split is available. The two public VoxCeleb clips support only a narrow speaker-embedding sanity check; DER, JER, speaker confusion/count error, natural A–B–A turn detection, and WER impact remain unmeasured.
- The available phone is an 8 GB-class `SM-G996N`, not a 4–6 GB target. Peak PSS/heap, full pipeline RTF, long recordings, airplane mode, ANR/OOM, and 4–6 GB support remain unverified.
- ASR provides chunk-level rather than word-level times. The conservative aligner leaves mixed/long chunks unknown rather than inventing word assignments, so useful text-speaker alignment remains limited.
- Clustering thresholds are still prototype heuristics, not calibrated or claimed as generally accurate.
