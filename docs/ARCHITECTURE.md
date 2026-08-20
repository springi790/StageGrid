# StageGrid architecture

## Real-time invariant

The UI never owns the audio clock. `NativeAudioEngine` owns one Oboe output stream, one master frame counter and one callback. Every stem is rendered against that same callback and logical timeline.

```text
Compose UI
   │
AudioEngineController (state + focus + device policy)
   │ JNI
NativeAudioEngine (C++)
   ├─ Master frame clock
   ├─ Track decoder workers ──> preallocated SPSC buffers
   ├─ Native Click / dynamic Guide cue
   └─ fixed output matrix ──> 2/4/6/8-channel Oboe/AAudio stream
```

Disk I/O, Room, SAF, ZIP extraction, compressed-media decoding, waveform analysis and UI work never occur in the Oboe callback. The steady-state callback uses fixed-size/previously allocated state; the 0.4 output matrix uses a stack `std::array<float, 8>` per rendered frame rather than allocating a variable channel buffer.

## Thread ownership

- **Audio callback:** consumes prepared PCM, applies mixer state, routes to physical output channels, mixes Click/Guide, applies master gain and advances the authoritative output-frame clock.
- **Decoder workers:** read app-private WAV data and map source sample rates to the common timeline ahead of the callback.
- **Kotlin coroutines / I/O:** import, Room, DataStore, backups, SAF, normalization and waveform caches.
- **Compose:** presentation and user intent only.
- **AudioDeviceManager:** observes Android output-device topology; it never becomes the transport clock.

## Packages

- `model/`: persistent domain entities and routing enums.
- `data/`: Room DAOs/database/repository and migrations.
- `importer/`: SAF import, format policy and import-time normalization.
- `waveform/`: versioned regenerable peak cache.
- `storage/`: app-private storage accounting/cache cleanup.
- `audio/`: JNI facade, playback state, device discovery and transport/device controller.
- `service/`: foreground playback service and MediaSession.
- `ui/`: Compose screens and musician-facing routing presets.
- `cpp/`: Oboe stream, WAV reader, SPSC buffers, shared-clock mixer and output matrix.

## Transport state

Kotlin exposes explicit states (`IDLE`, `LOADING`, `READY`, `PLAYING`, `PAUSED`, `SEEKING`, `STOPPING`, `ERROR`). Loading prepares media but never starts audible playback. Device loss also never causes automatic audible resume; after a live disconnect the user must explicitly press Play again.

## Import/playback boundary

Realtime playback remains WAV-only. Non-WAV sources normalize outside realtime:

```text
WAV ────────────────────────────────→ local playback WAV
MP3 / M4A / AAC / FLAC / OGG
        ↓ MediaExtractor / MediaCodec
        └───────────────────────────→ PCM16 local playback WAV
```

This boundary is unchanged by 0.4. Multichannel **output** does not mean compressed decoding or source-file I/O moves into the callback.

## 0.4 output model

The persistent routing model has two dimensions:

1. **OutputBus** selects a stereo physical pair: `1/2`, `3/4`, `5/6`, `7/8`.
2. **StereoRoute** selects behavior inside that pair: `BOTH`, `LEFT`, `RIGHT`.

```text
Track PCM L/R
   │
   ├─ bus 1/2 + BOTH  ──> physical 1 + 2 (stereo/pan)
   ├─ bus 3/4 + LEFT  ──> physical 3 (mono)
   ├─ bus 3/4 + RIGHT ──> physical 4 (mono)
   ├─ bus 5/6 + BOTH  ──> physical 5 + 6
   └─ bus 7/8 + BOTH  ──> physical 7 + 8
```

`LEFT/RIGHT` downmix source L/R to mono before placement. `BOTH` preserves stereo and equal-power pan behavior within the pair.

If a stored bus is not available on the **actual negotiated stream**, native routing normalizes it to bus `1/2`. This is a deliberate fail-audible policy: losing channel count must not silently remove a stem from the performance.

## Channel negotiation

`AudioDeviceManager` reads Android's advertised output channel counts and chooses a preferred even count up to 8.

When opening/reopening Oboe:

```text
request 8 → try 8 → 6 → 4 → 2
request 6 → try 6 → 4 → 2
request 4 → try 4 → 2
request 2 → try 2
```

For each count StageGrid may try Exclusive then Shared mode. The engine records both `requestedOutputChannelCount` and `outputChannelCount`; UI and presets use the **opened** count, not the advertised count.

Changing output devices preserves the logical transport position, Musical Grid offset, Loop state, scheduled jump and active count-in timing across sample-rate changes. Decoder banks are reset/re-preloaded against the new stream timeline.

## Device-loss policy

Android device callbacks feed `AudioEngineController`:

- if the selected interface disappears, StageGrid opens the unspecified/default Android stereo output;
- native playback remains stopped/paused after an actual stream-loss event;
- buses beyond `1/2` fold safely to `1/2` during fallback;
- StageGrid remembers the preferred interface for the current process;
- reconnect first matches the previous Android device ID, then the UI layer performs best-effort product-name/type matching if Android assigned a new ID.

This is intentionally conservative: reconnect can restore routing/output configuration, but it never starts sound by itself.

## Output test generator

The test generator lives in `NativeAudioEngine`, uses the already-open output stream and targets exactly one zero-based physical channel index. It is bounded in duration and level, and can run without a loaded song. Invalid channel indices fail rather than wrapping.

The output test shares the callback/matrix boundary but does not alter the song timeline.

## Persistence

### Room

`TrackEntity` stores:

- volume/mute/solo/pan;
- `outputRoute`;
- `outputBus`.

Room schema v3 adds `outputBus` with default `0` (`1/2`) so existing v2 libraries migrate without losing songs or the prior stereo route.

### DataStore

Generated Native Click keeps its route plus `clickBus` in lightweight settings.

### Portable backups

`.stagebackup` keeps format version 1 for backward compatibility. `outputBus` is an optional track manifest field:

- new 0.4 backup → restores bus;
- old backup with no `outputBus` → defaults to bus `1/2`.

## Native Guide routing

Generated Native Guide is a normal Guide `TrackEntity` and therefore follows its stored bus/route. Arrangement-aware temporary Guide cues copy the current Guide track bus/route into immutable cue data before publication to the callback.

## Waveform/storage boundary

0.3 behavior remains unchanged. Waveform peak generation is off-thread and regenerable; storage cleanup may remove only regenerable cache directories. Neither subsystem participates in multichannel rendering.

## Future boundary

0.5 may change the logical arrangement path and introduce dual-song preload/crossfade. It must still render through the same authoritative output clock/matrix abstraction rather than creating independent Android players per bus or song.
