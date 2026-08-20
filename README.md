# StageGrid

StageGrid is a native Android, local-first multitrack player for live performance. Stems, Native Click, Native Guide and the musical timeline share one real-time audio clock instead of independent Android media players.

> **Current development release: `0.3.0-alpha01` — expanded Android decoder/import layer.**
>
> This is the first 0.3 development build. It inherits the 0.2 live-performance feature set while beginning the expanded decoder/cache roadmap. Moving development to 0.3 does **not** imply that the previous 0.2 hardware qualification gate has been completed.

## Product principle

**A musician should be able to use the basic live workflow without understanding audio-engineering terminology.** Common performance actions stay visible; technical controls remain progressively disclosed.

## New in 0.3.0-alpha01

### MP3 + M4A + AAC import normalization

StageGrid now has one Android-platform compressed-audio normalization path instead of an MP3-only decoder.

```text
WAV ───────────────────────────────→ app-private playback WAV
MP3 ─┐
M4A ─┼→ MediaExtractor/MediaCodec → 16-bit PCM WAV → app-private playback WAV
AAC ─┘
```

The conversion remains an **import-time** operation. `MediaExtractor` and `MediaCodec` never run in the Oboe realtime callback, so live playback continues to use the deterministic WAV streaming path and shared native clock.

Implemented in alpha01:

- WAV remains the native import/playback source path.
- MP3, M4A and AAC are accepted as playable import sources.
- MP3/M4A/AAC normalize once to 16-bit PCM WAV in app-private storage.
- Android encoder-delay/padding metadata is trimmed when the platform exposes it.
- format-specific import warnings summarize which compressed sources were normalized.
- FLAC and OGG remain detectable and are reported clearly, but are not promoted to playable status yet.
- the previous `Mp3ToWavDecoder` API remains as a compatibility facade over the new platform decoder.
- pure Kotlin format-policy regression coverage protects the playable/planned boundary.

App version: `0.3.0-alpha01` (`versionCode 19`).

## Inherited 0.2 live foundation

The 0.3 line keeps the existing 0.2 behavior, including:

- safe performance-session recovery that always restores stopped;
- Native Guide local sample-pack recognition and reconstruction;
- source-language-first Guide matching and English ambiguity hardening;
- persistent Guide fingerprints and in-place Guide reanalysis;
- Musical Grid and editable sections;
- double-buffered section/Loop path preparation and synchronized handoff;
- arrangement-aware destination Guide phrases;
- Setlist Live navigation and bounded next-song filesystem-cache warming;
- portable `.stagebackup` creation/restore with size + SHA-256 validation;
- stereo Android/USB output selection and `L / L+R / R` routing.

## Current live workflow

```text
Import ZIP / folder / audio files
        ↓
normalize compressed sources locally when needed
        ↓
local playback WAV files
        ↓
Click → Musical Grid
Guide → optional local cue recognition
        ↓
automatic/editable sections
        ↓
Player / Mixer / Setlist Live
        ↓
manual section choice
        ↓
prepare destination audio + Guide phrase
        ↓
finish CURRENT authored section
        ↓
double-buffered synchronized handoff
        ↓
selected section start
```

A common stereo stage preset remains:

```text
Left  → Click + Guide
Right → Tracks
```

## Library, import and portability

Implemented:

- Kotlin / Jetpack Compose Android application.
- Room library for songs, tracks, sections and setlists.
- ZIP, folder and multi-file import through Android Storage Access Framework.
- Linked document-provider folder browsing, including Google Drive when exposed by Android.
- Imported audio copied into app-private storage for deterministic offline playback.
- ZIP-slip protection, bounded extraction and safe filenames.
- Optional `song.json` metadata/section markers.
- Post-import metadata editing.
- Import percentage, pipeline stage and current-file detail.
- Safe local multitrack deletion with confirmation and staged file rollback around the Room operation.
- Deleting a local song does not modify external `.stagebackup` snapshots.

### Audio formats currently playable in 0.3.0-alpha01

- WAV RIFF PCM: supported integer PCM depths handled by the parser.
- WAV 32-bit IEEE float where supported.
- MP3 through one-time Android `MediaExtractor` / `MediaCodec` normalization to PCM WAV.
- M4A when Android exposes a decodable audio track through the platform media stack.
- AAC when Android exposes a decodable audio track through the platform media stack.

FLAC and OGG are recognized by the import policy but remain planned for a later 0.3 alpha while the production path/licensing and device behavior are evaluated.

Compressed decoding never runs in the Oboe callback. StageGrid does not independently strip musical silence from each stem because intentional rests/count-ins must preserve the common timeline.

## Native audio engine

Implemented:

- one native Oboe stereo output stream;
- one master output-frame playhead shared by every stem;
- streaming decoder threads and preallocated SPSC buffers;
- two decoder banks per track for prepared Loop/section path changes;
- active audio continues while the inactive bank prepares a replacement path;
- synchronized all-track handoff after readiness/alignment checks;
- stale/unsafe prepared paths are rejected instead of being applied late;
- play, pause, stop and seek;
- master volume;
- per-track volume, mute, solo, pan and `L / L+R / R` route;
- native sample-clock Click with 1/4, 1/8, 1/8T and 1/16 subdivisions;
- native 1/2-bar section count-in;
- Android/USB stereo output-device selection;
- diagnostics for sample rate, burst size, underruns, callback load and path swaps/misses.

Manual section choices wait for the explicit `endMs` of the **current** section and then enter the requested section at its explicit `startMs`.

## Native Guide

StageGrid does not bundle third-party Guide audio. A user-supplied/licensed sample ZIP is installed locally through Android's document picker.

Supported pack language layouts currently include Spanish, English, French and Portuguese when present in the installed pack.

```text
Original Guide stem
       ↓
offline sample/fingerprint matching
       ↓
recognized structured events
       ├─ SECTION
       ├─ COUNT
       └─ DYNAMIC
       ↓
native-guide-events.json
       ↓
StageGrid Native Guide.wav
       ↓
optional arrangement-aware destination phrase
```

Recognition is sample/template matching, **not general-purpose speech-to-text**.

Implemented Native Guide behavior includes source-language probing, phase-robust fingerprints, structured event sidecars, generated Guide WAV, automatic editable section proposals, per-song output-language switching, reanalysis, persistent fingerprints and arrangement-aware destination lead-bar phrases.

## Setlist Live

A selected non-empty setlist can enter Setlist Live mode. Player shows current song, next song, setlist position, Previous/Next and Exit Setlist.

NEXT/PREV stop and unload the previous native song before loading the destination in a stopped state. The next song receives a bounded warm preload into the Android/Linux filesystem cache. This can reduce startup latency but is **not** a second native decoder graph, gapless transition or crossfade.

## Portable backup and restore

Settings can create a self-contained `.stagebackup` through Android SAF. The destination can be a local folder, compatible removable storage, Google Drive when exposed as a DocumentsProvider, or another writable provider.

A backup includes app-private song directories/audio, song/track/mixer metadata, sections, setlists/order, Native Guide sidecars/generated files and the currently installed user-supplied Guide pack.

Every payload file is declared with byte size + SHA-256. Restore stages and validates the complete archive before installing it, rebuilds device-specific app-private paths and preserves unrelated local library records.

Backups are explicit snapshots, not continuous/bidirectional Drive synchronization.

## Build requirements

- Android Gradle Plugin 9.2.1
- compileSdk / targetSdk 37
- JDK 17+
- Gradle 9.5.1
- NDK 28.2.13676358
- CMake 3.22.1
- Jetpack Compose
- Room
- Oboe

Windows build:

```bat
gradlew.bat testDebugUnitTest assembleDebug
```

Debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Local data and privacy

- No StageGrid account required.
- No analytics SDK.
- No ads.
- No broad storage permission.
- StageGrid does not upload song audio or Guide samples itself.
- Imported songs, session snapshots, Guide packs and fingerprint caches live in app-private storage.
- SAF access is limited to files/folders explicitly selected by the user.
- Drive access is performed by Android/Google Drive's document provider; StageGrid does not receive Drive credentials.

## Tests and CI

GitHub Actions runs `testDebugUnitTest` and `assembleDebug` before release PRs are merged into `main`.

Coverage includes stem classification, import-format policy, WAV parsing, Musical Grid conversion/snapping, manual section-boundary policy, Native Guide recognition/section inference, quiet anti-phase Guide recognition, English/Spanish source-language isolation, Click stable-train selection, Guide arrangement timing/sequence selection, setlist navigation and native/JNI compilation.

Physical-device qualification is still required before StageGrid is considered stage-ready.

## Known limitations of 0.3.0-alpha01

- M4A/AAC support intentionally relies on the Android platform codec stack; unusual codecs inside an `.m4a` container (for example a codec not supported by the device) can still fail import with a recoverable error.
- FLAC and OGG are detected but are not playable yet.
- Import normalization currently writes classic RIFF/WAV and therefore rejects a decoded cache that would exceed the RIFF data-size limit.
- Independently encoded compressed stems can contain codec delay or source-specific timing differences. StageGrid trims Android gapless metadata when available, but WAV stems exported from one common timeline remain the safest live-performance source.
- Waveform peak-cache generation and storage cache management are still pending in 0.3.
- Guide recognition remains sample/template matching and still requires representative physical-device validation.
- COUNT cue recognition remains less robust than longer SECTION phrases.
- Session recovery is approximate and always returns stopped; it is not sample-exact crash continuation.
- Arrangement-aware Guide relocation still uses the destination's original lead bar; the arbitrary virtual arrangement graph remains planned for 0.5.
- Setlist preload warms the OS file cache; it is not gapless dual-engine preload/crossfade.
- USB output selection is stereo-only; arbitrary 4/8/custom multichannel routing remains planned for 0.4.
- Tempo/pitch DSP, MIDI, pads, automation and SMPTE/LTC are later milestones.
- Physical-device/high-track-count stress validation remains required.

See [`docs/ROADMAP.md`](docs/ROADMAP.md) and [`docs/STATUS.md`](docs/STATUS.md).

## Release history

### 0.3.0-alpha01

**Expanded Android decoder/import foundation** — unified import-time MP3/M4A/AAC normalization, explicit format policy, gapless metadata trimming when available, clear FLAC/OGG planned-state reporting and regression coverage for the new import boundary.

### 0.2.0-alpha10.2

**English Guide recognition isolation** — bounded source-language probing, language-scoped main matching, preserved Spanish thresholds, stricter English ambiguity rejection and removal of the expensive alpha10.1 recovery pass.

### 0.2.0-alpha10.1

**Recognition hardening before beta** — adaptive Guide candidate discovery, phase-robust fingerprints, language-aware recovery matching, wider timing tolerance and stable Click-train grid anchoring.

### 0.2.0-alpha10

**Beta-readiness sprint: session recovery + Guide reanalysis/cache + richer dynamic Guide phrases** — includes planned alpha08/alpha09/alpha10 work in one integration sprint.

### 0.2.0-alpha07

**Library lifecycle + Live Setlist** — safe local song deletion, Setlist Live Previous/Next and bounded next-song OS-cache warming.

### 0.2.0-alpha06

**Portable backup/restore + first arrangement-aware Guide layer** — `.stagebackup`, SHA-256 validation and live destination section-name Guide cue.

### 0.2.0-alpha05.1

**Manual section-boundary transition hotfix** — current section finishes at explicit `endMs`; requested destination enters at explicit `startMs`.

### 0.2.0-alpha05

**Double-buffered live path hardening** — two decoder banks per track, background path preparation, synchronized handoff and stale-path rejection.

### 0.2.0-alpha04.2

**Per-song Guide language + import progress/performance pass**.

### 0.2.0-alpha04.1

**Delayed Native Guide section recovery after BPM becomes available**.

### 0.2.0-alpha04

**Native Guide recognition + automatic editable section proposals**.

### 0.2.0-alpha03

**Native count-in + initial section transition scheduling**.

### 0.2.0-alpha02

**Simplified live UX and routing presets**.

### 0.2.0-alpha01

**Musical Grid + visual Section Editor**.

### 0.1.3

**Native Click + stereo routing**.

### 0.1.2

**Shared-clock local WAV + MP3 normalization MVP**.

## Release documentation policy

Every StageGrid alpha, beta or release updates this README with the current version, new functionality, implemented behavior, known limitations and release history. `docs/ROADMAP.md` describes what comes next; `docs/STATUS.md` defines the exact implementation boundary.

## License

StageGrid-owned source is licensed under MIT. Third-party components retain their own licenses. See [`LICENSE`](LICENSE) and [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
