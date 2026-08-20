# StageGrid architecture

## Real-time invariant

The UI never owns the audio clock. `NativeAudioEngine` owns one Oboe output stream, one master frame counter, and one callback. Every stem is rendered against that same callback and the same logical playback path. Track volume/mute/solo/pan/output-route and click/guide controls are atomics consumed by the native mixer.

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

Disk I/O, Room, SAF, ZIP extraction, compressed-media decoding and UI work never occur in the Oboe callback. The callback does not allocate heap objects in its steady-state render path.

## Thread ownership

- **Audio callback:** consumes already-prepared PCM, applies mixer state, click and master gain, advances the authoritative output-frame clock.
- **Decoder workers:** read normalized/local WAV data and map each source sample rate to the common logical timeline ahead of the callback.
- **Kotlin coroutines / I/O:** import, Room operations, state persistence, SAF access and one-time compressed-audio normalization through Android `MediaExtractor` / `MediaCodec`.
- **Compose:** presentation only; recomposition cannot restart or become the audio clock.
- **Musical grid:** the importer may derive `gridOffsetMs` from a Click reference. Native click timing is calculated from `gridOffsetFrame + BPM`, never from an independently playing metronome file.

The decoder model intentionally favors isolation and straightforward determinism (one worker per loaded stem). A bounded decoder pool is a future optimization once profiling on target devices shows the right concurrency model; it must not move file I/O into the real-time callback.

## Packages

- `model/`: persistent domain entities.
- `data/`: Room DAOs/database/repository.
- `importer/`: SAF ZIP/folder import, import-format policy, WAV metadata, Android-platform compressed-audio normalization, stem classification.
- `audio/`: JNI bridge, playback state, device manager, controller.
- `service/`: foreground playback service and platform MediaSession.
- `ui/`: Compose presentation.
- `cpp/`: Oboe output, WAV reader, ring buffers, shared-clock mixer.

## State machine

Kotlin exposes explicit transport states (`IDLE`, `LOADING`, `READY`, `PLAYING`, `PAUSED`, `SEEKING`, `STOPPING`, `ERROR`) rather than trying to derive transport from several independent booleans. Audio is prepared on load but the native stream is only started when Play is requested after the media foreground service has been started.

## Playback / import format boundary

The native real-time engine renders uncompressed PCM/IEEE-float WAV directly. Compressed formats are handled at the importer boundary instead of inside the live engine.

In `0.3.0-alpha01`:

```text
WAV ───────────────────────────────→ local playback WAV path
MP3 ─┐
M4A ─┼→ PlatformAudioToWavDecoder → 16-bit PCM RIFF/WAV → local playback WAV path
AAC ─┘
```

`PlatformAudioToWavDecoder` uses Android `MediaExtractor` + `MediaCodec` during import, validates the decoded sample rate/channel count, applies Android encoder-delay/padding metadata when available and writes an app-private RIFF/WAV cache. The Oboe callback therefore never performs compressed decoding.

`Mp3ToWavDecoder` is retained only as a compatibility facade over the shared platform decoder. New importer code should use the shared decoder directly.

FLAC and OGG remain explicit detected-but-not-playable extension points in alpha01. Their eventual implementation must preserve this same boundary: normalize/prepare outside realtime, then give the native engine deterministic local playback data.

## Sample-rate mapping

All tracks are addressed by the common output timeline. A source frame is derived deterministically from the output-frame position and source/output sample-rate ratio, so stems do not maintain independent wall clocks. The current interpolator is intentionally simple; replacing it with a higher-quality `Resampler` abstraction does not require changing transport ownership.

## Looping and queued sections

Loops and scheduled section jumps are represented on the logical output-frame path. Decoder workers and the callback use that same path. When a live path changes, workers discard stale look-ahead and rebuild from the new logical position. This prevents independent-player drift, but boundary transitions still require physical-device stress validation before StageGrid can claim guaranteed glitch-free live rearrangement.

## Stereo routing and USB

Inside the stereo stream, every playable track has an explicit `BOTH`, `LEFT` or `RIGHT` route. `BOTH` retains the stereo/pan path. `LEFT`/`RIGHT` downmix the source to mono and place it only on the selected channel. The generated click has an independent route using the same matrix. This enables a two-output stage split without creating separate Android players.

Android output devices are enumerated through `AudioManager`. The selected `AudioDeviceInfo.id` is passed to Oboe and the stream is reopened against that device while preserving the logical transport position and grid offset. Current output is stereo. Arbitrary 4/8/10-channel routing is not advertised as complete; that phase requires multichannel stream negotiation plus a bus-to-channel output matrix.

## Persistence and recovery boundary

Room stores structured library/mixer/setlist state and DataStore stores lightweight application settings. Imported audio lives in app-private filesystem storage. StageGrid never auto-starts audible playback when opening the application; session recovery restores state silently and requires a new explicit Play action.
