# StageGrid 0.1.3 validation record

This file distinguishes checks that were actually executed for this source package from checks that still require an Android toolchain/device.

## Executed in the delivery environment

- `tools/run-native-selftest.sh` — **PASS**
  - PCM16 WAV read path
  - stereo SPSC buffer ordering
  - deterministic common-timeline mapping for different source sample rates
- 16-stem synchronization fixture generation — **PASS**
  - 16 files generated
  - 48 kHz mono format verified
  - expected impulse at 30 seconds verified in every file
- C++ native engine/JNI syntax compilation against minimal Oboe/JNI/Android-log API stubs — **PASS** during implementation.
- Android resource XML/manifest parse — **PASS**
- Spanish/default vs English string-key parity — **PASS**
- `R.string` reference coverage — **PASS**
- duplicate Kotlin import scan — **PASS**
- unfinished-marker source scan — **PASS**

## Not executable in this delivery environment

The host used to create this package does not contain an Android SDK/NDK installation or a local Gradle distribution, and its shell cannot download Maven/Gradle dependencies. Therefore the following commands were **not** claimed as executed here:

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
./gradlew assembleDebug
```

No APK is included or described as compiled. The repository includes a pinned Gradle bootstrap and GitHub Actions workflow so these checks can run in a normal Android development environment with network access.

## Hardware qualification still required

Even after a successful Android build, stage readiness requires physical-device tests for long-run underruns/drift, USB reconnect behavior, output-device latency and 16/32-stem stress. See `docs/TESTING.md` and `docs/STATUS.md`.

## MP3 importer validation added in 0.1.2

- `Mp3ToWavDecoder.kt` was syntax/type-checked locally against minimal Android media API stubs with `kotlinc`.
- The existing native core self-test still passes after the MP3 importer changes.
- MP3 decoding uses Android `MediaExtractor` + `MediaCodec` only during import and writes a standard 16-bit PCM RIFF/WAV cache before the native engine sees the track.
- A physical Android decode test still needs to be run on the target device/Android Studio build; this container does not provide an Android runtime/SDK codec stack.


## Native click / stereo routing checks added in 0.1.3

- `ClickGridAnalyzer.kt` compiled on the host with `kotlinc` — **PASS**.
- Synthetic 48 kHz PCM test with a click transient at 123 ms — **PASS**; detected offset: 123 ms.
- Native core self-test after routing/click changes — **PASS**.
- Default/English string-resource parity after the new controls — **PASS**.
- `R.string` coverage after the new controls — **PASS**.
- Room schema version increased to 2 with a 1→2 migration adding `songs.gridOffsetMs` and `tracks.outputRoute`.
- Full Android `compileDebugKotlin` / `assembleDebug` still requires Android Studio/SDK on the target development machine and is not claimed as executed in this container.


## Windows Gradle bootstrap

`gradlew.bat` prefers `curl.exe -fL` with retries on Windows and falls back to PowerShell only when curl is unavailable. The downloaded Gradle 9.5.1 archive is still SHA-256 verified before extraction.
