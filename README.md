# StageGrid

StageGrid is a native Android, local-first multitrack player for live performance. Every stem, the native Click and the musical timeline share one real-time audio clock instead of using independent Android media players.

> **Current release: `0.2.0-alpha04` — Native Guide recognition + automatic section proposals.**
>
> StageGrid is under active development. Only functionality with a real implementation is presented as available; planned modules live in [`docs/ROADMAP.md`](docs/ROADMAP.md).

## Product principle

**A musician should be able to use the basic live workflow without understanding audio-engineering terminology.**

StageGrid keeps common actions visible and moves technical controls such as grid offsets and manual routing behind optional advanced controls. Common stage configurations should use presets rather than forcing the user to understand buses, channel masks or engine internals.

## New in 0.2.0-alpha04

This alpha adds the first StageGrid-native Guide pipeline on top of the Musical Grid, section editor and quantized transport introduced earlier in 0.2.

- **Install a Guide sample pack locally** from Settings using Android's document picker.
- StageGrid does **not** bundle or upload third-party Guide sample audio. The user supplies a sample pack they are licensed to use.
- Recognizes supported installed Guide languages. The current pack parser supports:
  - Spanish (`ES`)
  - English (`EN`)
  - French (`FR`)
  - Portuguese (`PT`)
- When a newly imported song contains a Guide stem, StageGrid analyzes it completely offline using **template/fingerprint matching** against the installed Guide samples.
- Recognition covers section calls, count samples and dynamic/Guide cues that exist in the installed pack.
- No cloud speech-recognition service is required and recognition never runs in the real-time Oboe callback.
- Recognized calls are persisted in `native-guide-events.json` beside the imported song.
- StageGrid can render a clean app-generated **`StageGrid Native Guide.wav`** in:
  - Automatic language
  - Spanish
  - English
  - French
  - Portuguese
  when that language exists in the installed pack.
- When native Guide reconstruction succeeds, the original imported Guide is retained but muted as a reference.
- If a song has a valid BPM/Musical Grid and no explicit section map from `song.json`, recognized section calls can create **automatic section proposals**.
- Automatic section markers are snapped to the Musical Grid and remain fully editable through **Edit sections**.
- The existing **Edit sections** button remains visible directly in the Player.
- App version: `0.2.0-alpha04` (`versionCode 7`).

### Important alpha04 limitation

Native Guide events are recognized and stored as structured events, but the generated Guide WAV still follows the song's original timeline. Fully moving those Guide events when a future live ReOrder/arrangement changes the song structure belongs to the upcoming arrangement/path work.

The recognition system is designed for sample-based Guide tracks built from cues matching the installed pack. It is **not a general-purpose speech-to-text engine** for arbitrary human recordings.

## Current live workflow

A typical StageGrid workflow now looks like:

```text
Settings
  ↓
Install Guide sample pack (optional)
  ↓
Library
  ↓
Import song / ZIP / Drive folder
  ↓
Click track → Musical Grid reference
Guide track → native cue recognition
  ↓
Automatic section proposals when possible
  ↓
Review with Edit sections
  ↓
Player
  ├─ Play / Pause / Stop
  ├─ Current + next section
  ├─ Edit sections
  ├─ Click / Guide
  ├─ Section count-in
  └─ Quantized section changes
```

For a common two-output stage setup:

```text
Left  → Click + Guide
Right → Tracks
```

## Native Guide workflow

Install the pack once:

```text
Settings
  ↓
Native Guides
  ↓
Install Guide pack
  ↓
Choose ZIP
  ↓
StageGrid indexes compatible samples locally
```

Then import a song containing a Guide stem:

```text
Imported Guide stem
       ↓
10 ms audio fingerprint analysis
       ↓
recognized cue events
       ├─ Intro
       ├─ Verse 1
       ├─ Chorus
       ├─ Bridge
       └─ dynamic/count cues
       ↓
native-guide-events.json
       ↓
StageGrid Native Guide.wav
```

If BPM and the Musical Grid are valid and `song.json` did not already define sections, section calls can also produce:

```text
Guide call
   ↓
expected section marker
   ↓
snap to musical bar
   ↓
Intro / Verse / Chorus / Bridge...
   ↓
Edit sections for review
```

Automatic section detection is intentionally a proposal, not an irreversible edit. Review the section map before relying on it for a live performance.

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
- Optional locally installed Guide sample pack with bounded extraction and WAV validation.

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
- No filesystem, Room, Guide recognition or Compose work inside the real-time callback.
- Shared source-rate mapping for stems with different source sample rates.
- Play, pause, stop and seek.
- Master volume.
- Per-track volume, mute, solo and pan.
- Per-track stereo routing: `L`, `L+R`, `R`.
- Basic Android/USB stereo output-device selection.
- Native diagnostics for sample rate, burst size, underruns and callback load.

### Native Click

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

### Native Guide

- Imported Guide stem detection through the existing stem classifier/manifest metadata.
- User-installed local Guide sample pack.
- Offline sample-template recognition at import time.
- Dominant Guide-language detection from matched samples.
- Native Guide output-language selection: Auto / ES / EN / FR / PT when available.
- Recognized cue event sidecar: `native-guide-events.json`.
- App-generated PCM `StageGrid Native Guide.wav`.
- Original Guide retained as a muted reference when reconstruction succeeds.
- Guide enable/disable remains a normal Player control.
- Native Guide playback uses the same shared multitrack clock as other PCM stems.

### Musical Grid

The Musical Grid maps absolute playback time to musical position:

```text
BPM + time signature + grid offset
                ↓
         bar / beat position
                ↓
 sections / snapping / queued jumps / count-in / Guide section proposals
```

Implemented:

- milliseconds ↔ bar/beat conversion;
- beat snapping;
- bar snapping;
- Player bar/beat readout;
- section boundaries based on the musical grid;
- next-bar live section quantization;
- section proposals from recognized Guide section calls.

### Sections

- Import section starts from `song.json`.
- Automatic section proposals from recognized native Guide cues when no explicit section map is present.
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
- Spanish and English UI resources.

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
- StageGrid does not upload your audio or Guide samples.
- Guide packs are installed into app-private local storage from a location explicitly selected by the user.
- Cloud-folder access is limited to locations explicitly granted through Android's document provider.
- Imported audio is copied locally for offline playback.

## Tests and CI

The repository includes tests for areas such as:

- stem classification;
- WAV parsing;
- Musical Grid conversion/snapping;
- next-bar quantization math;
- native Guide template recognition and automatic section inference;
- Room behavior;
- JNI/native library loading;
- host C++ shared-clock behavior.

GitHub Actions runs unit tests and `assembleDebug` for development pull requests before changes are merged into `main`.

## Known limitations of 0.2.0-alpha04

Still pending:

- native Guide recognition currently targets sample-based Guide stems matching the installed cue pack; arbitrary spoken Guide recordings are not generic speech-to-text;
- songs imported before installing/changing a Guide pack need to be re-imported for recognition in this alpha; an in-place re-analysis action is planned;
- native Guide events are stored structurally, but the generated Guide WAV does not yet relocate cues after arbitrary live ReOrder/arrangement changes;
- double-buffered arrangement/path updates for stronger glitch resistance during live path changes;
- high-track-count physical-device stress validation of count-in, quantized jumps and native Guide playback;
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

### 0.2.0-alpha04

**Native Guide recognition + automatic section proposals**

- local user-installed Guide sample packs;
- offline Guide cue fingerprint/template recognition;
- ES/EN/FR/PT installed-language handling;
- app-generated native Guide PCM track;
- original Guide retained as muted reference on successful reconstruction;
- recognized event sidecar for future arrangement-aware Guide behavior;
- automatic Musical Grid section proposals from recognized section cues;
- Guide recognition JVM test coverage.

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
