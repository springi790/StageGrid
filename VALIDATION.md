# StageGrid validation record

This file distinguishes checks actually executed during implementation from checks that still require the complete Android toolchain and physical hardware.

## Previously executed foundation checks

- `tools/run-native-selftest.sh` — **PASS**
  - PCM16 WAV read path
  - stereo SPSC buffer ordering
  - deterministic common-timeline mapping for different source sample rates
- 16-stem synchronization fixture generation — **PASS**
  - 16 files generated
  - 48 kHz mono format verified
  - expected impulse at 30 seconds verified in every file
- C++ native engine/JNI syntax compilation against minimal Oboe/JNI/Android-log API stubs — **PASS** during foundation implementation.
- Android resource XML/manifest parse, ES/EN string parity, `R.string` coverage and source hygiene checks were recorded as **PASS** for the earlier foundation.

## 0.3.0-alpha01 validation already completed

### Shared platform decoder static type/syntax check — PASS

`PlatformAudioToWavDecoder.kt` was compiled with `kotlinc` against minimal stubs for Android media APIs and `WavMetadataReader`.

This validated Kotlin syntax/type usage for the decoder implementation. It did **not** claim that a real Android codec stack decoded representative media.

## 0.3.0-alpha05 validation executed in the current implementation environment

### Final import format policy — PASS

A pure Kotlin executable check compiled `ImportAudioFormat` and verified:

- WAV/MP3/M4A/AAC/FLAC/OGG are recognized;
- every format is currently marked playable;
- every non-WAV format requires import-time normalization;
- WAV remains the direct playback source path.

### Waveform peak-cache algorithm — PASS

`WaveformPeakCache` compiled with the local Kotlin/JVM compiler and an executable synthetic-WAV check verified:

- a 2-second stem with an impulse at 1.0 seconds maps near the 50% peak bucket;
- a 1-second stem with an impulse at 0.5 seconds maps near the 25% bucket of the same 2-second shared song timeline;
- shorter stems therefore are **not** visually stretched to the longest stem duration;
- `loadOrGenerate` writes the peak cache;
- cache deletion removes the regenerable peak file;
- retained playback WAV remains present after cache deletion;
- the cache can regenerate afterward.

Executable result:

```text
StageGrid 0.3 pure Kotlin checks: PASS
```

### Storage cache safety — PASS

A pure Kotlin executable check compiled `StorageCacheManager` with the waveform cache and verified:

- local song/audio/cache/Guide accounting is discovered from the expected app-private paths;
- `clearRegenerableCaches()` removes song cache data;
- playback WAV data remains present;
- installed Guide-pack data remains present.

Executable result:

```text
StageGrid storage cache check: PASS
```

## Full Android build status in this implementation environment

The current host does not provide the complete Android SDK/NDK/Gradle dependency environment used by StageGrid CI, so the following commands are **not** claimed as executed here:

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
./gradlew assembleDebug
```

The repository GitHub Actions workflow is configured to run `testDebugUnitTest` and `assembleDebug` for pull requests.

## Physical Android validation required for 0.3.0-alpha05

### Import formats

Test at least one representative file of each source type:

- WAV;
- MP3;
- M4A/AAC-LC;
- raw/common AAC where `MediaExtractor` exposes it;
- FLAC;
- OGG/Vorbis and, if relevant to your source library/device, OGG/Opus.

For each imported compressed/container source verify:

- import completes or gives a clear recoverable per-file error;
- resulting song duration is sensible;
- all stems start in sync;
- intentional leading silence/count-ins remain intact;
- Click analysis still aligns correctly when Click came from a normalized source;
- Native Guide analysis still works when Guide came from a normalized source.

### Waveform

Verify on a real phone:

- opening Player generates a waveform the first time;
- generation does not interrupt currently playing audio;
- playhead follows transport while playing/paused/seeking;
- tapping different waveform positions seeks correctly;
- section boundary lines are visually aligned with authored sections;
- opening Section Editor shows the same waveform without changing position by accidental taps;
- long songs and 16/32+ stems do not cause an ANR or excessive memory growth.

### Storage/cache

Verify in Settings:

- storage totals appear and are plausible;
- opening a waveform increases regenerable-cache usage;
- **Clear regenerable cache** reduces that value;
- songs still load/play afterward;
- Native Guide still works;
- an external `.stagebackup` file is untouched;
- reopening the song regenerates its waveform.

### Regression / live-use checks

Re-run the existing critical live paths:

- Play/Pause/Stop/Seek;
- Native Click subdivisions/routing;
- Native Guide on/off and language behavior;
- manual section jump at authored section boundary;
- Loop / Exit Loop;
- Setlist Live Previous/Next;
- session recovery after process death (must restore stopped);
- backup + restore;
- USB stereo select/disconnect/reconnect;
- representative high-track-count playback while watching diagnostics for underruns.

## Hardware qualification still required beyond 0.3

Even after a successful Android build, StageGrid is not stage-qualified until synchronization, prolonged playback, high-track-count load, USB behavior, process death and backup recovery are validated on representative physical devices. Later 0.4–0.9 systems add their own qualification requirements.

## Windows Gradle bootstrap

`gradlew.bat` prefers `curl.exe -fL` with retries on Windows and falls back to PowerShell when curl is unavailable. The downloaded Gradle archive is SHA-256 verified before extraction.
