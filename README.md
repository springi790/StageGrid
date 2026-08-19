# StageGrid

StageGrid is a native Android, local-first multitrack player for live performance. Stems, native Click, Guide and the musical timeline share one real-time audio clock instead of independent Android media players.

> **Current release: `0.2.0-alpha04.1` — Native Guide section-recovery hotfix.**
>
> StageGrid is under active development. Only functionality with a real implementation is presented as available; planned modules live in [`docs/ROADMAP.md`](docs/ROADMAP.md).

## Product principle

**A musician should be able to use the basic live workflow without understanding audio-engineering terminology.**

Common actions remain visible while technical controls such as grid offsets and manual routing stay behind optional advanced controls.

## New in 0.2.0-alpha04.1

This hotfix closes an important alpha04 workflow gap: Guide recognition could succeed before the song BPM was known, so `StageGrid Native Guide.wav` was generated but automatic sections could not be created yet.

StageGrid now:

- keeps recognized Guide cues in `native-guide-events.json` as before;
- reuses those persisted events after BPM/time-signature metadata becomes available;
- generates the automatic section map **without re-analyzing the Guide audio**;
- retries section recovery when the song is loaded, so an already-imported alpha04 song can be fixed without re-importing its stems;
- replaces only the untouched import placeholder `Full Song` section;
- never overwrites a section map that has already been created or edited manually;
- updates the stored native-Guide section proposals after successful recovery;
- uses the current BPM, time signature and grid offset when rebuilding the section markers.

App version: `0.2.0-alpha04.1` (`versionCode 8`).

### Existing-song recovery

For a song already imported with a generated native Guide but no automatic sections:

```text
StageGrid Native Guide exists
        +
native-guide-events.json exists
        +
BPM / Musical Grid is valid
        +
only "Full Song" exists
        ↓
load song or save metadata
        ↓
rebuild section proposals from saved cues
        ↓
Intro / Verse / Chorus / Bridge ...
```

No multitrack re-import is required.

## Native Guide pipeline

Install a Guide sample pack once from Settings using Android's document picker. StageGrid does not bundle or upload third-party Guide sample audio; the user supplies a pack they are licensed to use.

Current installed-language handling supports Spanish (`ES`), English (`EN`), French (`FR`) and Portuguese (`PT`) when those languages are present in the selected pack.

When a song contains a Guide stem:

```text
Imported Guide stem
       ↓
offline template/fingerprint matching
       ↓
recognized cue events
       ├─ section calls
       ├─ count cues
       └─ dynamic cues
       ↓
native-guide-events.json
       ↓
StageGrid Native Guide.wav
```

When reconstruction succeeds, the original Guide remains in the library as a muted reference and the generated Guide becomes the active Guide track.

If a valid Musical Grid is available, recognized section calls can also produce editable automatic sections. If BPM is entered only after import, alpha04.1 now performs that section-generation step later from the saved cue events.

The recognition system is designed for sample-based Guide tracks matching the installed cue pack. It is **not** a general-purpose speech-to-text engine for arbitrary recordings.

## Current live workflow

```text
Settings
  ↓
Install Guide sample pack (optional)
  ↓
Library / Drive folder
  ↓
Import song
  ↓
Click → Musical Grid reference
Guide → native cue recognition
  ↓
Automatic section proposals when possible
  ↓
Edit sections
  ↓
Player
  ├─ Play / Pause / Stop
  ├─ Current + next section
  ├─ Edit sections
  ├─ Click / Guide
  ├─ Section count-in
  └─ Quantized section changes
```

A common stereo stage preset is:

```text
Left  → Click + Guide
Right → Tracks
```

## What is implemented

### Library and import

- Native Kotlin / Jetpack Compose Android application.
- Room library with Song, Track, Section and Setlist entities.
- ZIP, folder and multi-file import through Android Storage Access Framework.
- Persistent linked cloud-folder access through Android document providers, including Google Drive through the system picker.
- Selected cloud files are copied into app-private storage for deterministic offline playback.
- ZIP-slip protection, extraction limits and safe filenames.
- Optional `song.json` metadata, stem types and section markers.
- Post-import metadata editing for title, artist, BPM, key, time signature, grid offset and notes.

### Audio formats

Playable now:

- WAV RIFF PCM: 8/16/24/32-bit where supported by the parser.
- WAV RIFF IEEE float: 32-bit.
- MP3 through one-time Android `MediaExtractor` / `MediaCodec` normalization to PCM WAV during import.

MP3 decoding never runs in the Oboe real-time callback. StageGrid does not independently trim musical silence from stems because intentional rests/pre-roll must remain aligned to the shared timeline.

### Native audio engine

- One native Oboe output stream.
- One master output-frame playhead shared by all stems.
- Streaming decoder threads and preallocated SPSC buffers.
- No filesystem, Room, Guide recognition or Compose work inside the real-time callback.
- Shared source-rate mapping.
- Play, pause, stop and seek.
- Master volume.
- Per-track volume, mute, solo and pan.
- Per-track stereo routing: `L`, `L+R`, `R`.
- Basic Android/USB stereo output-device selection.
- Native diagnostics for sample rate, burst size, underruns and callback load.

### Native Click

- Imported Click retained as a timing reference.
- First-click transient detection for `gridOffsetMs`.
- Manual grid-offset correction.
- Native sample-clock Click.
- 1/4, 1/8, 1/8T and 1/16 subdivisions.
- Click routing: `L`, `L+R`, `R`.

### Native Guide

- Imported Guide detection.
- User-installed local Guide sample packs.
- Offline template/fingerprint recognition.
- Auto or selected output language when installed.
- Structured `native-guide-events.json` cue storage.
- App-generated `StageGrid Native Guide.wav`.
- Original Guide retained muted after successful reconstruction.
- Persisted cue events can now regenerate sections after BPM becomes available.
- Generated Guide playback uses the same shared multitrack clock as the stems.

### Musical Grid and sections

The Musical Grid maps:

```text
BPM + time signature + grid offset
                ↓
         bar / beat position
                ↓
 sections / snapping / queued jumps / count-in / Guide proposals
```

Implemented:

- milliseconds ↔ bar/beat conversion;
- beat/bar snapping;
- Player bar/beat readout;
- visual/manual section editor;
- section creation, rename, resize and delete;
- start/end from current playhead;
- automatic section proposals from recognized native Guide cues;
- delayed section recovery from persisted Guide events when BPM is supplied later;
- Section Loop / Exit Loop;
- next-bar quantized section changes;
- 1- or 2-bar native count-in.

**Edit sections remains a first-class Player action.** Automatic recovery only replaces the untouched `Full Song` fallback, so manual section work is protected.

### Mixer, setlists and live operation

- Friendly routing presets plus manual `L / L+R / R` routing.
- Basic local setlists.
- Foreground playback service.
- Android MediaSession and notification controls.
- Audio-focus handling.
- LIVE keep-screen-on mode.
- Performance Lock.
- Spanish and English UI resources.

The current routing matrix is stereo; arbitrary 4/8/custom USB output routing is planned for a later milestone.

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

Build on Windows:

```bat
gradlew.bat testDebugUnitTest assembleDebug
```

Debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

`gradlew.bat` also attempts to use Android Studio's bundled JBR when `JAVA_HOME` is absent or malformed.

## Keeping your local checkout current

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
- StageGrid does not upload audio or Guide samples.
- Guide packs are installed into app-private local storage.
- Cloud-folder access is limited to locations explicitly granted through Android's document provider.
- Imported audio is copied locally for offline playback.

## Tests and CI

GitHub Actions runs unit tests and `assembleDebug` for development pull requests before changes are merged into `main`.

Coverage includes areas such as stem classification, WAV parsing, Musical Grid conversion/snapping, quantized transitions, native Guide recognition/section inference, Room behavior, JNI/native loading and shared-clock native behavior.

## Known limitations of 0.2.0-alpha04.1

Still pending:

- in-place Guide **audio re-analysis** for songs imported before a Guide pack was installed/changed; alpha04.1 can recover sections only when recognized events already exist;
- arbitrary live ReOrder does not yet relocate generated Guide audio dynamically;
- double-buffered arrangement/path updates;
- high-track-count physical-device stress validation;
- Setlist Live NEXT/PREV with next-song preload;
- restorable performance sessions;
- waveform peak cache/editor;
- AAC/M4A/FLAC/OGG expansion;
- arbitrary multichannel USB routing;
- tempo/time stretching and pitch shifting;
- MIDI, pads, automation and SMPTE/LTC;
- complete `.stagepack` backup/export;
- LAN remote control;
- tablet split Player + Mixer workspace;
- first-run onboarding and final accessibility pass.

See [`docs/ROADMAP.md`](docs/ROADMAP.md).

## Release history

### 0.2.0-alpha04.1

**Native Guide section-recovery hotfix**

- reuse persisted Guide cue events when BPM is entered after import;
- regenerate automatic sections without Guide audio re-analysis;
- recover already-imported alpha04 songs when loaded;
- protect manually authored/edited section maps;
- keep sidecar section proposals synchronized after recovery.

### 0.2.0-alpha04

**Native Guide recognition + automatic section proposals**

- local user-installed Guide sample packs;
- offline Guide cue fingerprint/template recognition;
- ES/EN/FR/PT handling;
- app-generated native Guide PCM track;
- original Guide retained as muted reference;
- structured event sidecar;
- automatic Musical Grid section proposals when BPM is already available during import.

### 0.2.0-alpha03

**Quantized sections + native count-in**

- next-bar live section changes;
- 1/2-bar section count-in;
- native sample-clock pre-roll;
- synchronized stem entry.

### 0.2.0-alpha02

**Simplified live UX**

- simplified Player hierarchy;
- routing presets;
- friendly terminology;
- simplified Section Editor.

### 0.2.0-alpha01

**Musical Grid + Section Editor**

- bar/beat timeline;
- beat/bar snapping;
- manual visual section editing.

### 0.1.3

**Native Click + stereo routing**

- native generated Click;
- subdivisions;
- `L / L+R / R` routing;
- Click-reference grid detection.

### 0.1.2

**MP3 import path**

- Android MediaCodec MP3 normalization to local PCM WAV.

## Release documentation policy

Every StageGrid alpha, beta or release updates this README with the current version, new functionality, implemented behavior, known limitations and release history.

`docs/ROADMAP.md` describes what comes next; `docs/STATUS.md` remains the precise implementation boundary.

## License

StageGrid-owned source is licensed under MIT. Third-party components retain their own licenses. See [`LICENSE`](LICENSE) and [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
