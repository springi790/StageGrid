# StageGrid architecture

## Real-time invariant

The UI never owns the audio clock. `NativeAudioEngine` owns one Oboe output stream, one master frame counter and one callback. Every stem is rendered against that same callback and the same logical playback path. Track volume/mute/solo/pan/output-route and Click/Guide controls are atomics consumed by the native mixer.

```text
Compose UI
   │
AudioEngineController (Kotlin state + focus + service)
   │ JNI
NativeAudioEngine (C++)
   ├─ Master frame clock
   ├─ Track decoder thread 1 ─┐
   ├─ Track decoder thread 2 ─┼─> preallocated SPSC buffers -> mixer -> Oboe/AAudio
   ├─ ...                    ─┤
   └─ Generated click         ┘
```

Disk I/O, Room, SAF, ZIP extraction, compressed-media decoding, waveform analysis/cache generation and UI work never occur in the Oboe callback. The callback does not allocate heap objects in its steady-state render path.

## Thread ownership

- **Audio callback:** consumes already-prepared PCM, applies mixer state, Click/Guide/master gain and advances the authoritative output-frame clock.
- **Decoder workers:** read app-private WAV data and map each source sample rate to the common logical timeline ahead of the callback.
- **Kotlin coroutines / I/O:** import, Room operations, state persistence, SAF access, one-time compressed-audio normalization and waveform peak-cache generation.
- **Compose:** presentation and user intent only; recomposition cannot restart or become the audio clock.
- **Musical grid:** the importer may derive `gridOffsetMs` from a Click reference. Native Click timing is calculated from `gridOffsetFrame + BPM`, never from an independently playing metronome file.

The decoder model intentionally favors isolation and determinism. Any future decoder-pool optimization must preserve the rule that file I/O never moves into the realtime callback.

## Packages

- `model/`: persistent domain entities.
- `data/`: Room DAOs/database/repository.
- `importer/`: SAF ZIP/folder import, import-format policy, WAV metadata, Android-platform normalization and stem classification.
- `waveform/`: versioned regenerable waveform peak cache.
- `storage/`: StageGrid-owned filesystem accounting and safe cache eviction.
- `audio/`: JNI bridge, playback state, device manager and controller.
- `service/`: foreground playback service and platform MediaSession.
- `ui/`: Compose presentation and cached waveform rendering.
- `cpp/`: Oboe output, WAV reader, ring buffers and shared-clock mixer.

## State machine

Kotlin exposes explicit transport states (`IDLE`, `LOADING`, `READY`, `PLAYING`, `PAUSED`, `SEEKING`, `STOPPING`, `ERROR`) rather than deriving transport from several independent booleans. Audio is prepared on load, but the native stream only starts after an explicit Play action and foreground-service preparation.

## Playback / import format boundary

The native realtime engine renders uncompressed PCM/IEEE-float WAV. Every non-WAV source supported by 0.3 is handled at the import boundary:

```text
WAV ───────────────────────────────────────────→ local playback WAV
MP3 ─┐
M4A ─┤
AAC ─┼→ PlatformAudioToWavDecoder ─────────────→ PCM16 RIFF/WAV
FLAC ─┤
OGG ─┘
```

`PlatformAudioToWavDecoder` uses Android `MediaExtractor` + `MediaCodec` during import, validates decoded sample rate/channel count, applies platform encoder-delay/padding metadata when available and writes an app-private RIFF/WAV file. A source fails import cleanly when the current Android device cannot expose/decode its actual codec/container.

`Mp3ToWavDecoder` remains only as a compatibility facade. New importer code should use the shared platform decoder.

## Waveform cache boundary

Waveform rendering never asks the native engine to decode audio for graphics.

```text
app-private playback WAV files
        ↓  Dispatchers.IO
WaveformPeakCache
        ↓
library/<song>/cache/waveform-overview.sgpk
        ↓
Compose Player / Section Editor
```

The cache is a bounded min/max amplitude envelope, not audio. Every stem is mapped by absolute source time onto the longest song timeline, so a shorter stem remains short instead of being stretched visually.

The cache is versioned and source-signature checked. Source file name/length/mtime changes invalidate stale peaks. Cache deletion is safe because retained playback WAV files remain authoritative and can regenerate the cache.

The Player derives the visible playhead from `PlayerState.positionMs`, which itself follows the shared native transport. Tapping the waveform emits a normal seek intent; the waveform never maintains an independent clock.

## Storage boundary

`StorageCacheManager` only operates inside StageGrid-owned app-private files. It reports library/audio/cache/Guide usage and can remove regenerable song cache directories.

Cache eviction is intentionally conservative:

```text
CAN DELETE:   library/<song>/cache/**
MUST RETAIN:  library/<song>/audio/**
              Room records
              Guide source/generated material
              external .stagebackup snapshots
```

A future broader cache policy must keep essential playback data and user-authored state distinct from reproducible performance artifacts.

## Sample-rate mapping

All tracks are addressed by the common output timeline. A source frame is derived deterministically from output-frame position and the source/output sample-rate ratio, so stems do not maintain independent wall clocks. Replacing the current interpolator with a higher-quality resampler abstraction does not require changing transport ownership.

## Looping and queued sections

Loops and scheduled section jumps are represented on the logical output-frame path. Decoder workers and the callback use that same path. When a live path changes, workers discard stale look-ahead and rebuild from the new logical position. Boundary transitions still require physical-device stress validation before StageGrid can claim guaranteed glitch-free live rearrangement.

## Stereo routing and USB

Inside the current stereo stream, every playable track has an explicit `BOTH`, `LEFT` or `RIGHT` route. `BOTH` retains stereo/pan behavior. `LEFT`/`RIGHT` downmix the source to mono and place it only on the selected channel. Native Click has an independent route using the same matrix.

Android output devices are enumerated through `AudioManager`. The selected `AudioDeviceInfo.id` is passed to Oboe and the stream is reopened while preserving logical transport position and grid offset.

0.3 output remains stereo. 0.4 introduces a bus model, multichannel stream negotiation and bus-to-physical-channel matrix rather than overloading the existing stereo route enum.

## Persistence and recovery boundary

Room stores structured library/mixer/setlist state and DataStore stores lightweight application settings. Imported/normalized audio lives in app-private filesystem storage. StageGrid never auto-starts audible playback when opening the application; session recovery restores available state silently and requires a new explicit Play action.
