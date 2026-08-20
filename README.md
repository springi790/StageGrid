# StageGrid

StageGrid is a native Android, local-first multitrack player for live performance. Stems, Native Click, Native Guide and the musical timeline share one real-time audio clock instead of independent Android media players.

> **Current development release: `0.3.0-alpha05` — complete expanded decoder / waveform / cache layer.**
>
> Alpha05 intentionally collapses the remaining planned 0.3 alphas into one integration build. It inherits the 0.2 live-performance feature set; moving development forward does **not** waive the physical-hardware qualification gates from earlier versions.

## Product principle

**A musician should be able to use the basic live workflow without understanding audio-engineering terminology.** Common performance actions stay visible; technical controls remain progressively disclosed.

## New in 0.3.0-alpha05

### Expanded import normalization

StageGrid keeps compressed/container decoding at the import boundary:

```text
WAV ───────────────────────────────────────────→ app-private playback WAV
MP3 ─┐
M4A ─┤
AAC ─┼→ Android MediaExtractor / MediaCodec ───→ 16-bit PCM WAV
FLAC ─┤
OGG ─┘
```

Implemented:

- WAV remains the direct native playback source path.
- MP3, M4A, AAC, FLAC and OGG are accepted as import sources.
- Non-WAV sources normalize once to 16-bit PCM RIFF/WAV before a playable track is registered.
- Android encoder-delay/padding metadata is trimmed when exposed by the platform.
- PCM16 decoder output is consumed directly; PCM-float decoder output is converted to PCM16.
- sample rate/channel bounds and classic RIFF output-size limits are validated.
- temporary/partial decode files are removed on failure.
- `Mp3ToWavDecoder` remains as a compatibility facade over the shared platform normalizer.

Platform codec availability still matters: an unusual codec/container combination that Android cannot expose/decode fails import cleanly instead of entering the live engine.

### Regenerable waveform peak cache

Waveforms are not generated in the Oboe callback. `WaveformPeakCache` reads the retained playback WAV files on an I/O dispatcher and creates a bounded song overview cache at:

```text
library/<song-id>/cache/waveform-overview.sgpk
```

The peak cache:

- stores min/max amplitude buckets instead of another audio copy;
- maps every stem by absolute source time onto the common song timeline;
- supports PCM integer WAV and 32-bit IEEE-float WAV inputs used by StageGrid;
- is versioned and source-signature checked;
- is written through a temporary file before replacement;
- can be deleted at any time and regenerated from the retained playback WAV files.

### Waveform UI

The Player now shows a waveform overview with:

- synchronized playhead;
- section-boundary markers;
- tap-to-seek when transport policy allows seeking.

The Section Editor shows the same waveform as a read-only visual reference while preserving its existing bar/beat editing controls.

### Storage and cache manager

Settings now includes **Storage and cache** accounting for:

- complete local StageGrid usage;
- library data;
- playback-ready audio;
- regenerable caches;
- local Guide resources;
- local song count.

The clear-cache action removes only regenerable `library/<song>/cache` data. It does not delete playback audio, songs, Room records, Guide content or external `.stagebackup` snapshots.

App version: `0.3.0-alpha05` (`versionCode 23`).

## Inherited live foundation

The current source retains the existing StageGrid live workflow:

- Room song/track/section/setlist library;
- ZIP/folder/multi-file SAF import;
- app-private deterministic playback media;
- one Oboe stereo output stream and one shared transport clock;
- streaming WAV decoder workers with preallocated SPSC buffers;
- double-buffered Loop/section path preparation and synchronized handoff;
- volume/mute/solo/pan and `L / L+R / R` routing;
- Native Click, subdivisions and section count-in;
- Musical Grid and editable sections;
- local Native Guide recognition/reconstruction, language switching, reanalysis and persistent fingerprints;
- arrangement-aware destination Guide lead-bar phrases;
- Setlist Live Previous/Next plus bounded filesystem-cache warming;
- safe stopped session recovery;
- portable `.stagebackup` creation/restore with byte-size + SHA-256 validation;
- Android/USB stereo output-device selection.

## Current live workflow

```text
Import ZIP / folder / audio files
        ↓
normalize non-WAV sources locally
        ↓
app-private playback WAV files
        ↓
optional regenerable waveform peak cache
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

## Audio formats in 0.3.0-alpha05

| Source | Import behavior | Live engine input |
| --- | --- | --- |
| WAV | retained/copied | WAV |
| MP3 | platform decode once | PCM16 WAV |
| M4A | platform decode once | PCM16 WAV |
| AAC | platform decode once | PCM16 WAV |
| FLAC | platform decode once | PCM16 WAV |
| OGG | platform decode once | PCM16 WAV |

StageGrid deliberately does **not** decode compressed media in the realtime callback. Independently encoded stems can still contain source-specific timing differences; stems exported as WAV from one common timeline remain the safest stage source.

## Native Guide

StageGrid does not bundle third-party Guide audio. A user-supplied/licensed sample ZIP is installed locally.

Supported pack language layouts currently include Spanish, English, French and Portuguese when present. Recognition is sample/template matching rather than general-purpose speech-to-text.

Implemented behavior includes phase-robust fingerprints, source-language probing, structured SECTION/COUNT/DYNAMIC events, generated Native Guide WAV, automatic editable section proposals, output-language switching, persistent fingerprints, reanalysis and arrangement-aware destination phrases.

## Portable backup and restore

Settings can create a self-contained `.stagebackup` through Android SAF. The destination can be local storage, compatible removable storage, Google Drive when exposed as a DocumentsProvider, or another writable provider.

Backups contain the retained song directories/audio, Room song/track/section/setlist state, Guide sidecars/generated files and the installed user-supplied Guide pack. Payload entries are validated by byte size + SHA-256 before restore installation.

Waveform caches are regenerable performance artifacts. Their absence does not make a restored song unplayable.

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

## Tests and qualification

GitHub Actions is configured to run `testDebugUnitTest` and `assembleDebug` for pull requests.

0.3 adds regression coverage for the final import-format policy and waveform cache behavior, including different-duration stems mapped to the same absolute song timeline and safe cache eviction/regeneration.

**CI success is not stage qualification.** Physical Android testing is still required for real platform codec behavior, long/high-track-count songs, repeated live transitions, USB devices, process death, backups and prolonged performance use.

## Known limitations of 0.3.0-alpha05

- FLAC/OGG/M4A/AAC/MP3 normalization relies on the Android device's platform extractor/decoder availability for the actual source codec/container.
- Normalized output uses classic RIFF/WAV; decoded data larger than the classic RIFF limit is rejected.
- Waveform generation is intentionally lazy; the first view of a song after import/cache cleanup can take longer while peaks are built.
- The waveform is an overview cache, not a destructive audio editor.
- Guide recognition remains sample/template matching and COUNT cues remain naturally more ambiguous than longer SECTION calls.
- Session recovery is approximate and always returns stopped; it is not sample-exact crash continuation.
- Arrangement-aware Guide relocation is still based on the original destination lead bar; the arbitrary virtual arrangement graph remains planned for 0.5.
- Setlist preload warms the OS file cache; it is not the future dual-song gapless/crossfade engine.
- USB routing remains stereo-only in 0.3. Arbitrary 4/8/custom routing is the 0.4 milestone.
- Tempo/pitch DSP, MIDI, pads, automation, SMPTE/LTC, portable project interchange and LAN remote remain later milestones.

See [`docs/ROADMAP.md`](docs/ROADMAP.md), [`docs/STATUS.md`](docs/STATUS.md) and [`VALIDATION.md`](VALIDATION.md).

## Release history

### 0.3.0-alpha05

**Complete 0.3 integration alpha** — platform normalization for MP3/M4A/AAC/FLAC/OGG, versioned absolute-timeline waveform peak cache, Player/Section Editor waveform UI, storage accounting and safe regenerable-cache eviction.

### 0.3.0-alpha01

**Expanded decoder/import foundation** — shared Android import-time normalizer for MP3/M4A/AAC and centralized import format policy.

### 0.2.0-alpha10.2

**English Guide recognition isolation** — bounded source-language probing, language-scoped main matching, preserved Spanish thresholds and stricter English ambiguity rejection.

### 0.2.0-alpha10

**Beta-readiness integration sprint** — session recovery, Guide reanalysis/cache and richer arrangement-aware Guide phrases.

### 0.2.0-alpha07

**Library lifecycle + Live Setlist** — safe local song deletion, Setlist Live Previous/Next and bounded next-song OS-cache warming.

### 0.2.0-alpha06

**Portable backup/restore + arrangement-aware Guide foundation**.

### 0.2.0-alpha05 / alpha05.1

**Double-buffered live paths + authored section-boundary transition policy**.

### 0.2.0-alpha01–alpha04.2

**Musical Grid, simplified live UX, count-in and Native Guide foundation**.

### 0.1.2 / 0.1.3

**Shared-clock local playback MVP, MP3 import normalization, Native Click and stereo routing**.

## Release documentation policy

Every StageGrid alpha, beta or release updates this README with the current version, implemented behavior, known limitations and release history. `docs/ROADMAP.md` describes what comes next; `docs/STATUS.md` defines the exact implementation boundary.

## License

StageGrid-owned source is licensed under MIT. Third-party components retain their own licenses. See [`LICENSE`](LICENSE) and [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
