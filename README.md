# StageGrid

StageGrid is a native Android, local-first multitrack player for live performance. Every stem, the native Click and the musical timeline share one real-time audio clock instead of using independent Android media players.

> **Current release: `0.2.0-alpha03` — Quantized sections + native count-in.**
>
> StageGrid is under active development. Only functionality with a real implementation is presented as available; planned modules live in [`docs/ROADMAP.md`](docs/ROADMAP.md).

## Product principle

**A musician should be able to use the basic live workflow without understanding audio-engineering terminology.**

StageGrid keeps common actions visible and moves technical controls such as grid offsets and manual routing behind optional advanced controls. Common stage configurations should use presets rather than forcing the user to understand buses, channel masks or engine internals.

## New in 0.2.0-alpha03

This alpha builds on the Musical Grid and simplified 0.2 UI.

- **Edit sections** remains visible directly in the Player instead of being hidden in Advanced options.
- Live section changes are now **quantized to the next musical bar** when the song has a valid BPM/grid.
- The selected destination is shown as queued until the next bar begins.
- Added section **count-in / pre-roll** options:
  - No count-in
  - 1 bar
  - 2 bars
- While stopped, move to/select a section and use **Play section with count-in**.
- Count-in timing runs in the native sample-clock engine, not with Android UI timers.
- Imported stems are gated during count-in and enter together at the exact target frame.
- Virtual negative-time pre-roll allows a count-in even when the target section starts at the beginning of the song.
- The generated Click continues to use the same master musical clock as playback.
- Count-in preference is persisted with DataStore.
- App version: `0.2.0-alpha03` (`versionCode 6`).

## Current live workflow

A typical two-output setup can now be configured without manual routing knowledge:

```text
Library
  ↓
Import / Drive folder
  ↓
Load song
  ↓
Player
  ├─ Play / Pause / Stop
  ├─ Current + next section
  ├─ Edit sections
  ├─ Click / Guide
  ├─ Section count-in
  └─ Quantized section changes

Mixer preset
  L → Click + Guide
  R → Tracks
```

When playing, tapping another section queues it for the next musical bar boundary instead of jumping immediately at an arbitrary point in the measure.

When stopped, you can select a section, choose a 1- or 2-bar count-in and start that section with Click-only pre-roll before all stems enter together.

## What is implemented

### Library and import

- Native Kotlin / Jetpack Compose Android application.
- Room library with Song, Track, Section and Setlist entities.
- Import from ZIP, folder or multiple WAV/MP3 files through Android Storage Access Framework.
- Persistent linked cloud-folder access through Android document providers.
- Google Drive folders can be selected through Android's system picker without storing Google credentials in StageGrid.
- Browse linked folders/subfolders, refresh them and choose only the files to copy into StageGrid.
- Imported songs are copied into app-private storage for deterministic offline playback.
- ZIP-slip protection, extraction limits and safe filenames.
- Optional `song.json` for metadata, stem types and section markers.
- Post-import metadata editing for title, artist, BPM, key, time signature, grid offset and notes.

### Audio formats

Playable now:

- WAV RIFF PCM: 8/16/24/32-bit where supported by the parser.
- WAV RIFF IEEE float: 32-bit.
- MP3 through one-time Android `MediaExtractor` / `MediaCodec` normalization to PCM WAV during import.

MP3 decoding never runs in the Oboe real-time callback. Playback uses the same deterministic PCM path as WAV stems.

StageGrid removes codec encoder delay/padding when Android exposes those values, but it does **not** independently trim musical silence from each stem because that would destroy intentional timeline alignment.

Planned decoder/cache expansion includes FLAC, AAC/M4A and OGG.

### Native audio engine

- One native Oboe output stream.
- One master output-frame playhead shared by all stems.
- Streaming decoder threads and preallocated SPSC buffers.
- No filesystem, Room or Compose work inside the real-time callback.
- Shared source-rate mapping for stems with different source sample rates.
- Play, pause, stop and seek.
- Master volume.
- Per-track volume, mute, solo and pan.
- Per-track stereo routing: `L`, `L+R`, `R`.
- Basic Android/USB stereo output-device selection.
- Native diagnostics for sample rate, burst size, underruns and callback load.

### Native Click and Guide

- Imported Click files are retained as alignment references.
- Automatic first-click transient detection can establish `gridOffsetMs`.
- Grid offset can be corrected manually.
- Live Click is generated natively from the master sample clock.
- Click subdivisions:
  - quarter / negras
  - eighth / corcheas
  - triplets / tresillos
  - sixteenth / semicorcheas
- Click routing: `L`, `L+R`, `R`.
- Guide enable/disable.

### Musical Grid

The Musical Grid maps absolute playback time to musical position:

```text
BPM + time signature + grid offset
                ↓
         bar / beat position
                ↓
 sections / snapping / queued jumps / count-in
```

Implemented:

- milliseconds ↔ bar/beat conversion;
- beat snapping;
- bar snapping;
- Player bar/beat readout;
- section boundaries based on the musical grid;
- next-bar live section quantization.

### Sections

- Import section starts from `song.json`.
- Visual/manual Section Editor.
- Create, rename, resize and delete sections.
- Set start/end from the current playhead.
- Friendly presets such as Intro, Verse, Chorus, Bridge and Outro.
- Precise bar/beat editing remains optional.
- Section Loop / Exit Loop.
- Quantized queued section changes while playing.
- 1- or 2-bar native count-in before starting a selected section.

**Edit sections is intentionally a main Player action.** It is not hidden under Advanced options. Structural editing is still disabled while transport is actively playing until the safer double-buffered arrangement path is complete.

### Mixer and routing

The simple Mixer starts with volume, Mute and Solo. Common output setups are available as presets, including:

```text
Stage split
Left  → Click + Guide
Right → Tracks
```

and:

```text
Everything → Stereo
```

Manual per-track `L / L+R / R` configuration remains available when needed.

This is a two-channel stereo matrix, not the future arbitrary 4/8/custom USB output router.

### Setlists and live operation

- Basic local setlists.
- Foreground playback service.
- Android MediaSession and notification controls.
- Audio-focus handling.
- LIVE keep-screen-on mode.
- Performance Lock.
- Spanish and English resources.

## Count-in behavior

Count-in is implemented in the native engine so timing remains tied to audio frames.

For a two-bar count-in:

```text
Click only
| 1 2 3 4 | 1 2 3 4 |
                      ↓ exact frame
               selected section
               all stems enter
```

The engine can use a virtual timeline before frame zero, so a section beginning at the very start of a song can still receive a full count-in.

During count-in, imported stems are consumed/prepared but not mixed to the output. At the target frame the gate opens and the stems enter together. This avoids scheduling the entry with a coroutine, timer or UI callback.

## Quantized live section changes

With a valid Musical Grid, tapping a section while playback is active does not immediately seek.

Example:

```text
Current: Verse
Bar 12 Beat 2

User taps: Chorus
        ↓
Queued: Chorus
        ↓
Bar 13 Beat 1
        ↓
Chorus starts
```

If a valid grid is unavailable, StageGrid falls back to the current section boundary instead of pretending musical quantization exists.

## Build requirements

The project currently pins:

- Android Gradle Plugin 9.2.1
- compileSdk / targetSdk 37
- JDK 17+
- Gradle 9.5.1
- NDK 28.2.13676358
- CMake 3.22.1
- Jetpack Compose
- Room
- Oboe

### Android Studio

1. Clone/open the `StageGrid` repository.
2. Use Android Studio's bundled JBR/JDK or another compatible JDK.
3. Let Android Studio sync Gradle and install the requested SDK/NDK/CMake packages.
4. Select the `app` configuration and a physical Android device.
5. Run the app.

### Command line

macOS/Linux:

```bash
./gradlew testDebugUnitTest assembleDebug
```

Windows PowerShell / CMD:

```bat
gradlew.bat testDebugUnitTest assembleDebug
```

Debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

`gradlew` / `gradlew.bat` include StageGrid's checksum-verifying Gradle bootstrap fallback. On Windows, the bootstrap attempts to use Android Studio's bundled JBR if `JAVA_HOME` is absent or malformed.

## Keeping your local checkout current

After changes are merged to `main`:

```bash
git switch main
git pull
```

Then build normally.

## Local data and privacy

- No StageGrid account required.
- No analytics SDK.
- No ads.
- No broad storage permission.
- StageGrid does not upload your audio.
- Cloud-folder access is limited to locations explicitly granted through Android's document provider.
- Imported audio is copied locally for offline playback.

## Tests and CI

The repository includes tests for areas such as:

- stem classification;
- WAV parsing;
- Musical Grid conversion/snapping;
- next-bar quantization math;
- Room behavior;
- JNI/native library loading;
- host C++ shared-clock behavior.

GitHub Actions runs unit tests and `assembleDebug` for development pull requests before changes are merged into `main`.

## Known limitations of 0.2.0-alpha03

Still pending:

- double-buffered arrangement/path updates for stronger glitch resistance during live path changes;
- high-track-count physical-device stress validation of count-in and quantized jumps;
- Setlist Live NEXT/PREV transport with next-song preload;
- persisted/restorable performance session;
- waveform peak cache/editor;
- compressed formats beyond the current WAV/MP3 path;
- arbitrary multichannel USB routing;
- tempo/time stretching;
- pitch shifting/transposition;
- MIDI input/output/clock/cues;
- pads;
- automation;
- SMPTE/LTC;
- complete `.stagepack` export/backup workflow;
- LAN remote control;
- tablet split Player + Mixer layout;
- first-run onboarding and final accessibility pass.

See [`docs/ROADMAP.md`](docs/ROADMAP.md).

## Release history

### 0.2.0-alpha03

**Quantized sections + native count-in**

- next-bar live section changes;
- 1/2-bar section count-in;
- native sample-clock pre-roll;
- stem gate opening at the target frame;
- persisted count-in preference;
- clearer queued/count-in status in Player;
- visible Edit sections action retained.

### 0.2.0-alpha02

**Simplified live UX**

- Player hierarchy simplified;
- common routing presets;
- friendly musical terminology;
- simplified Section Editor;
- technical options progressively disclosed.

### 0.2.0-alpha01

**Musical Grid + Section Editor**

- bar/beat timeline;
- grid offset support;
- beat/bar snapping;
- manual visual section editing.

### 0.1.3

**Native Click + stereo routing**

- native generated Click;
- subdivisions;
- Click/stem `L / L+R / R` routing;
- Click reference/grid detection.

### 0.1.2

**MP3 import path**

- Android MediaCodec MP3 normalization to local PCM WAV.

## Release documentation policy

Every StageGrid alpha, beta or release should update this README in the same development cycle with:

- current version;
- new functionality;
- implemented behavior;
- known limitations;
- release history.

`docs/ROADMAP.md` describes what comes next; `docs/STATUS.md` should remain the precise implementation boundary.

## License

StageGrid-owned source is licensed under MIT. Third-party components retain their own licenses. See [`LICENSE`](LICENSE) and [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
