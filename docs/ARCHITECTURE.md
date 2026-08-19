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

Disk I/O, Room, SAF, ZIP extraction and UI work never occur in the Oboe callback. The callback does not allocate heap objects in its steady-state render path.

## Thread ownership

- **Audio callback:** consumes already-prepared PCM, applies mixer state, click and master gain, advances the authoritative output-frame clock.
- **Decoder workers:** read WAV data and map each source sample rate to the common logical timeline ahead of the callback.
- **Kotlin coroutines / I/O:** import, Room operations, state persistence, SAF access and one-time MP3 normalization through Android MediaExtractor/MediaCodec.
- **Compose:** presentation only; recomposition cannot restart or become the audio clock.
- **Musical grid:** the importer may derive `gridOffsetMs` from a Click reference. Native click timing is calculated from `gridOffsetFrame + BPM`, never from an independently playing metronome file.

The 0.1 decoder model intentionally favors isolation and straightforward determinism (one worker per loaded stem). A bounded decoder pool is a future optimization once profiling on target devices shows the right concurrency model; it must not move file I/O into the real-time callback.

## Packages

- `model/`: persistent domain entities.
- `data/`: Room DAOs/database/repository.
- `importer/`: SAF ZIP/folder import, WAV metadata, MP3-to-WAV normalization, stem classification.
- `audio/`: JNI bridge, playback state, device manager, controller.
- `service/`: foreground playback service and platform MediaSession.
- `ui/`: Compose presentation.
- `cpp/`: Oboe output, WAV reader, ring buffers, shared-clock mixer.

## State machine

Kotlin exposes explicit transport states (`IDLE`, `LOADING`, `READY`, `PLAYING`, `PAUSED`, `SEEKING`, `STOPPING`, `ERROR`) rather than trying to derive transport from several independent booleans. Audio is prepared on load but the native stream is only started when Play is requested after the media foreground service has been started.

## MVP playback format

The native real-time engine renders uncompressed PCM/IEEE-float WAV directly. MP3 is supported at the importer boundary: Android MediaExtractor/MediaCodec decodes each MP3 once into a 16-bit PCM WAV cache in app-private storage before Room records the playable track path. The Oboe callback therefore never performs compressed decoding. FLAC/AAC/M4A/OGG remain detected-but-unsupported extension points and can use the same normalization boundary later.

## Sample-rate mapping

All tracks are addressed by the common output timeline. A source frame is derived deterministically from the output-frame position and source/output sample-rate ratio, so stems do not maintain independent wall clocks. The 0.1 interpolator is intentionally simple; replacing it with a higher-quality `Resampler` abstraction does not require changing transport ownership.

## Looping and queued sections

Loops and scheduled section jumps are represented on the logical output-frame path. Decoder workers and the callback use that same path. When a live path changes, workers discard stale look-ahead and rebuild from the new logical position. This prevents independent-player drift, but boundary transitions still require physical-device stress validation before StageGrid can claim guaranteed glitch-free live rearrangement.

## Stereo routing and USB

Inside the stereo stream, every playable track has an explicit `BOTH`, `LEFT` or `RIGHT` route. `BOTH` retains the stereo/pan path. `LEFT`/`RIGHT` downmix the source to mono and place it only on the selected channel. The generated click has an independent route using the same matrix. This enables a two-output stage split without creating separate Android players.

Android output devices are enumerated through `AudioManager`. The selected `AudioDeviceInfo.id` is passed to Oboe and the stream is reopened against that device while preserving the logical transport position and grid offset. MVP output is stereo. Arbitrary 4/8/10-channel routing is not advertised as complete; that phase requires multichannel stream negotiation plus a bus-to-channel output matrix.

## Persistence and recovery boundary

Room stores structured library/mixer/setlist state and DataStore stores lightweight application settings. Imported audio lives in app-private filesystem storage. StageGrid never auto-starts audible playback when opening the application; even future crash/session recovery must restore state silently and require a new explicit Play action.
