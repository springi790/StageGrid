# StageGrid validation record

This file distinguishes checks that were actually executed for this source package from checks that still require a complete Android toolchain/device.

## Previously executed foundation checks

- `tools/run-native-selftest.sh` — **PASS**
  - PCM16 WAV read path
  - stereo SPSC buffer ordering
  - deterministic common-timeline mapping for different source sample rates
- 16-stem synchronization fixture generation — **PASS**
  - 16 files generated
  - 48 kHz mono format verified
  - expected impulse at 30 seconds verified in every file
- C++ native engine/JNI syntax compilation against minimal Oboe/JNI/Android-log API stubs — **PASS** during implementation.
- Android resource XML/manifest parse — **PASS** in the recorded foundation validation.
- Spanish/default vs English string-key parity — **PASS** in the recorded foundation validation.
- `R.string` reference coverage — **PASS** in the recorded foundation validation.
- duplicate Kotlin import scan — **PASS** in the recorded foundation validation.
- unfinished-marker source scan — **PASS** in the recorded foundation validation.

## 0.3.0-alpha01 validation executed in the current implementation environment

### Import format policy — PASS

`ImportAudioFormat.kt` was compiled with the locally available Kotlin compiler and executable checks verified:

- case-insensitive M4A recognition;
- case-insensitive AAC recognition;
- MP3/M4A/AAC are playable and require normalization;
- FLAC remains detected but not playable;
- detected extension set is exactly `wav`, `mp3`, `m4a`, `aac`, `flac`, `ogg`.

The executable check completed with:

```text
ImportAudioFormat checks: PASS
```

### Shared platform decoder static type/syntax check — PASS

`PlatformAudioToWavDecoder.kt` was compiled with `kotlinc` against minimal stubs for:

- `android.media.AudioFormat`;
- `android.media.MediaCodec`;
- `android.media.MediaExtractor`;
- `android.media.MediaFormat`;
- `WavMetadataReader`.

The compile completed successfully. Only unused-parameter warnings from the intentionally minimal stubs were emitted.

This check validates Kotlin syntax/type usage for the new decoder implementation. It does **not** claim that a real Android codec stack decoded MP3/M4A/AAC media.

## Physical Android decoder validation still required for 0.3.0-alpha01

Representative device tests must cover:

- MP3 import regression;
- M4A/AAC-LC import through typical Android codec stacks;
- different M4A metadata/container layouts;
- common raw/ADTS AAC sources where supported by `MediaExtractor`;
- encoder delay/padding trimming and stem alignment;
- malformed/unsupported compressed input cleanup;
- long files and near-RIFF-limit normalized output;
- Click/Guide analysis against normalized M4A/AAC stems.

## Full Android build status in this implementation environment

The current host does not provide the complete Android SDK/NDK/Gradle dependency environment used by StageGrid CI, so the following commands are **not** claimed as executed here:

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
./gradlew assembleDebug
```

No APK is described as compiled by this environment. The repository includes a GitHub Actions workflow that runs `testDebugUnitTest` and `assembleDebug` for pull requests and the protected release flow.

## Historical MP3 importer validation

- The original `Mp3ToWavDecoder.kt` was syntax/type-checked against minimal Android media API stubs during the 0.1.2 implementation.
- MP3 decoding used Android `MediaExtractor` + `MediaCodec` only during import and wrote a standard 16-bit PCM RIFF/WAV cache before the native engine saw the track.
- In 0.3.0-alpha01 that API is retained as a compatibility facade over the shared `PlatformAudioToWavDecoder`.

## Native click / stereo routing historical checks

- `ClickGridAnalyzer.kt` compiled on the host with `kotlinc` — **PASS**.
- Synthetic 48 kHz PCM test with a click transient at 123 ms — **PASS**; detected offset: 123 ms.
- Native core self-test after routing/click changes — **PASS**.
- Default/English string-resource parity after the new controls — **PASS**.
- `R.string` coverage after the new controls — **PASS**.
- Room schema version increased to 2 with a 1→2 migration adding `songs.gridOffsetMs` and `tracks.outputRoute`.

## Hardware qualification still required

Even after a successful Android build, stage readiness requires physical-device tests for long-run underruns/drift, USB reconnect behavior, output-device latency, compressed-import timing and 16/32-stem stress. See `docs/TESTING.md` and `docs/STATUS.md`.

## Windows Gradle bootstrap

`gradlew.bat` prefers `curl.exe -fL` with retries on Windows and falls back to PowerShell only when curl is unavailable. The downloaded Gradle 9.5.1 archive is SHA-256 verified before extraction.
