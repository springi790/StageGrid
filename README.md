# StageGrid

StageGrid is a native Android, local-first multitrack player for live performance. Stems, Native Click, Native Guide and the musical timeline share one authoritative real-time audio clock.

> **Current development release: `0.4.0-alpha05` — USB multichannel routing integration alpha.**
>
> This build collapses the complete planned 0.4 feature scope into the final alpha. It is feature-complete source for the milestone, but real 4/8-output behavior still requires physical USB-interface qualification.

## Product rule

The common live workflow stays preset-driven. A musician should not need to understand Android channel masks or audio HAL internals to route a show.

## New in 0.4.0-alpha05

### 2 / 4 / 6 / 8-channel output negotiation

StageGrid now asks Oboe/AAudio for the best even output count advertised by the selected device, up to eight channels.

```text
8 requested → try 8 → 6 → 4 → 2
6 requested → try 6 → 4 → 2
4 requested → try 4 → 2
2 requested → try 2
```

The app reports the **requested** and **actually opened** channel counts separately. If Android cannot open the full interface width, StageGrid uses the lower working stream rather than treating the device as unusable.

### Persistent output buses

Each track now has:

- a stereo-pair **bus**: `1/2`, `3/4`, `5/6`, `7/8`;
- the existing route inside that bus: `L`, `L+R`, `R`.

Examples:

```text
Bus 1/2 + L+R → stereo outputs 1 + 2
Bus 3/4 + L   → mono output 3
Bus 3/4 + R   → mono output 4
Bus 7/8 + R   → mono output 8
```

If a song is assigned to a bus unavailable on the current fallback stream, that bus folds to `1/2` so a track does not silently disappear.

Track bus assignments persist in Room schema v3. Existing 0.3 libraries migrate with every track on bus `1/2`, while preserving their previous `L / L+R / R` route.

### Mixer presets

The simple stereo workflows remain available, plus:

- **4-out:** Tracks 1/2 · Click 3 · Guide 4;
- **6-out:** Main 1/2 · Vocals 3/4 · Click 5 · Guide 6;
- **8-out:** rhythm 1/2 · instruments 3/4 · vocals/other 5/6 · Click 7 · Guide 8;
- **Custom:** choose bus and L/L+R/R per track.

Presets requiring more channels are disabled when Android actually opened fewer channels.

Native Click has its own persistent bus. Native Guide and arrangement-generated Guide phrases follow the Guide track's current bus/route.

### Output test

Settings exposes numbered output-test buttons for the negotiated stream. The native engine generates a short, bounded low-level tone only on the selected physical channel. This is intended for verifying actual interface channel order before configuring stage routing.

### USB disconnect / reconnect

When the selected interface disappears:

- StageGrid falls back to Android stereo;
- non-1/2 buses fold to 1/2;
- a live stream-loss event does not automatically resume sound;
- the preferred interface is remembered for the current process;
- reconnect restoration supports the previous Android device ID and best-effort product-name/type matching if Android assigns a new ID.

### Backup compatibility

`.stagebackup` remains format version 1.

- 0.4 backups include optional `outputBus` per track;
- 0.4 restores it when present;
- older 0.3 backups have no `outputBus` and therefore restore safely to bus `1/2`.

App version: **`0.4.0-alpha05` (`versionCode 28`)**.

## Realtime architecture

```text
app-private playback WAVs
        ↓
decoder worker per stem
        ↓
preallocated SPSC buffers
        ↓
        one shared output-frame clock
        ↓
track mixer + Native Click + Native Guide
        ↓
fixed 8-slot output matrix
        ↓
negotiated 2/4/6/8-channel Oboe stream
```

Disk I/O, Room, SAF, compressed decoding and waveform generation never run inside the Oboe callback.

## Inherited 0.3 feature set

0.4 retains:

- WAV/MP3/M4A/AAC/FLAC/OGG import policy;
- import-time normalization of non-WAV sources to playback-ready PCM WAV;
- versioned waveform peak cache;
- Player waveform with playhead/section markers/tap-to-seek;
- Section Editor waveform reference;
- storage accounting and safe regenerable-cache cleanup.

## Inherited live foundation

- Room song/track/section/setlist library;
- ZIP/folder/multi-file SAF import;
- shared-clock Oboe transport;
- volume/mute/solo/pan;
- Native Click and subdivisions;
- Musical Grid and editable sections;
- native count-in;
- double-buffered Loop/section path changes;
- local Native Guide recognition/reconstruction/reanalysis;
- arrangement-aware destination Guide phrases;
- Setlist Live;
- safe stopped session recovery;
- portable `.stagebackup` with byte-size + SHA-256 validation.

## Debug APKs from GitHub

The `Android CI` workflow runs automatically on `feature/**` pushes and builds:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Artifact name:

```text
stagegrid-debug-apk
```

GitHub builds use the project-specific stable debug key so a new debug APK can update a previous CI debug APK without uninstalling. Debug builds also show a small automatic version watermark such as:

```text
StageGrid 0.4.0-alpha05 • DEBUG
```

## Build requirements

- Android Gradle Plugin 9.2.1
- compileSdk / targetSdk 37
- JDK 17+
- Gradle 9.5.1
- NDK 28.2.13676358
- CMake 3.22.1
- Jetpack Compose / Room / Oboe

Local build:

```bash
./gradlew testDebugUnitTest assembleDebug
```

## Current qualification boundary

Automated/JVM validation can prove model consistency and compilation. It cannot prove how a particular Android phone + USB interface exposes physical outputs.

For `0.4.0-alpha05`, physical testing must verify:

- requested versus negotiated channels;
- physical output order with the test tone;
- 4/8-out presets and custom routing;
- disconnect/reconnect fallback;
- routing persistence and backup round trip;
- high-track-count underruns/drift under multichannel output.

See [`docs/TESTING.md`](docs/TESTING.md), [`docs/STATUS.md`](docs/STATUS.md), [`docs/ROADMAP.md`](docs/ROADMAP.md) and [`VALIDATION.md`](VALIDATION.md).

## Known limitations

- Android may expose fewer channels than the physical interface supports; StageGrid cannot bypass a phone/OEM HAL limitation.
- Physical channel ordering can vary by interface/driver and must be verified with the output test.
- reconnect restoration is best-effort when Android creates a completely different device identity.
- multichannel support currently tops out at 8 physical outputs.
- 0.5 virtual arrangements/dual-song crossfade, 0.6 DSP, 0.7 MIDI, 0.8 pads/automation/timecode and 0.9 project interchange/remote remain future milestones.

## Release history

### 0.4.0-alpha05

**Complete 0.4 integration alpha** — 2/4/6/8-channel Oboe negotiation, persistent stereo-pair buses, 4/6/8 presets, custom routing, per-output test signal, safe stereo fallback/reconnect policy and backward-compatible routing backup metadata.

### 0.3.0-alpha05

**Complete 0.3 integration alpha** — expanded import normalization, waveform peak cache/UI and storage/cache manager.

### 0.2.0-alpha10.2

**Live workflow/Native Guide hardening culmination** — sections, double-buffered paths, Setlist Live, backup/recovery and recognition hardening.

## License

StageGrid-owned source is licensed under MIT. Third-party components retain their own licenses. See `LICENSE` and `THIRD_PARTY_NOTICES.md`.
