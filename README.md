# StageGrid

StageGrid is a native Android, local-first multitrack player for live performance. Every stem, the native click and the musical timeline share one real-time audio clock instead of using independent Android media players.

> **Current release: `0.2.0-alpha02` — Simplified live UX.**
>
> StageGrid is under active development. Only features with a real implementation are presented as available; planned modules live in [`docs/ROADMAP.md`](docs/ROADMAP.md).

## Product principle

**A musician should be able to use the basic live workflow without understanding audio-engineering terminology.**

StageGrid therefore keeps common actions visible and moves technical controls such as grid offsets and manual L/R routing behind optional advanced controls. Presets should be preferred over requiring the user to understand buses, channel masks or internal engine concepts.

## New in 0.2.0-alpha02

This alpha is the first dedicated usability pass on top of the 0.2 Musical Grid foundation.

- Player reorganized around **current section, next section, transport, Click and Guide**.
- Technical grid information moved behind **Advanced options**.
- Click subdivisions now use musician-friendly names such as **Negras / Corcheas / Tresillos / Semicorcheas** in Spanish.
- Mixer now starts with volume, Mute and Solo instead of exposing every routing control at once.
- Added common stereo routing presets:
  - **Click + Guide → Left / Tracks → Right**
  - **Everything in stereo**
  - **Click + Guide → Left / Tracks → Stereo**
- Manual per-track L/R routing remains available under an advanced control.
- Internal track enum names are replaced in the UI with friendly instrument names.
- Section Editor simplified around the playhead:
  - **Add a section here**
  - **Start = here**
  - **End = here**
  - common section-name presets: Intro, Verse, Chorus, Bridge and Outro.
- Exact bar/beat fields remain available under **Precise editing** instead of dominating the default editor.
- Roadmap now includes usability as an explicit StageGrid requirement.
- App version: `0.2.0-alpha02` (`versionCode 5`).

## What is implemented

### Library and import

- Native Kotlin / Jetpack Compose Android application.
- Room library with Song, Track, Section and Setlist entities.
- Import from ZIP, folder or multiple WAV/MP3 files through Android Storage Access Framework.
- Persistent linked cloud-folder access through Android document providers.
- Google Drive folders can be selected from the system picker without storing Google credentials or API keys in StageGrid.
- Browse linked folders, refresh them and import only selected archives/stems.
- Imported songs are copied into app-private storage for deterministic offline playback.
- Optional `song.json` metadata/track/section import.
- Post-import metadata editing for title, artist, BPM, key, time signature, grid offset and notes.

### Audio engine

- One native Oboe output stream and one shared master playhead.
- WAV playback with deterministic shared-clock sample-rate mapping.
- MP3 decoded once during import through Android `MediaExtractor` / `MediaCodec`, then cached as PCM WAV.
- Encoder delay/padding is trimmed when Android exposes it; musical silence is never blindly trimmed per stem.
- Streaming decoder threads and preallocated SPSC buffers.
- Play, pause, stop, seek and master volume.
- Per-track volume, mute, solo and pan.
- Persisted stereo output assignment: `L`, `L+R`, `R`.
- Audio output-device enumeration and compatible stereo USB endpoint selection.
- Foreground playback service, MediaSession, media notification and audio-focus handling.

### Native Click and Guide

- Imported Click can be used as a timing reference instead of the live metronome source.
- First-click transient analysis can establish the song grid offset.
- Native click generated from the same master clock as the stems.
- Click subdivisions: quarter, eighth, triplet and sixteenth.
- Native click routing: left, both or right.
- Guide enable/disable and normal per-track routing.

### Musical Grid and sections

- BPM + time signature + grid offset define the musical timeline.
- Deterministic conversion between milliseconds and bar/beat positions.
- Beat and bar snapping.
- Current musical position displayed in Player using plain-language labels.
- Visual/manual Section Editor.
- Create, rename, resize and delete sections.
- Start/end boundaries can be captured from the current playhead.
- Section loop, loop exit and queued section jumps.
- Manual section-structure editing remains disabled during active playback until double-buffered path updates land.

### Setlists and live operation

- Basic local setlists.
- LIVE keep-screen-on mode.
- Performance Lock.
- Spanish and English resources.

See [`docs/STATUS.md`](docs/STATUS.md) for the exact implemented/planned boundary.

## Simplified routing

For a typical two-output live setup, open **Mixer → Configuración rápida de salidas** and choose:

```text
Left  → Click + Guide
Right → Tracks
```

StageGrid applies the individual track routes automatically. If a custom setup is needed, enable **Configurar salidas manualmente** and choose Left / Both / Right for each stem.

This remains a two-channel stereo matrix. True 4/8/custom USB routing is planned for the 0.4 multichannel milestone.

## Section workflow

With a song that has BPM and time-signature information:

1. Load the song.
2. Move the playhead to the desired position.
3. Open **Advanced options → Edit song sections**.
4. Choose whether changes align to a **whole bar** or **one beat**.
5. Tap **Add a section here** or select an existing section.
6. Use **Start = here** / **End = here**.
7. Save changes.

Exact bar/beat numbers are still available under **Precise editing**.

## Architecture

```text
Compose UI
    │ state / commands
    ▼
StageGridViewModel
    │
    ├──────────────► Room / DataStore / Storage Access Framework
    │
    ▼
AudioEngineController ── AudioFocus / Foreground Service / MediaSession
    │ JNI
    ▼
NativeAudioEngine (C++)
    │
    ├─ master output-frame clock
    ├─ stem decoder threads
    ├─ preallocated SPSC buffers
    └─ generated native click
    ▼
real-time mixer callback
    ▼
one Oboe output stream → Android audio device

MusicalGrid
    ├─ BPM + time signature + grid offset
    ├─ ms ↔ bar/beat
    └─ beat/bar snapping
```

More detail: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

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

### Build

After updates are merged to `main`:

```bash
git switch main
git pull
```

Windows:

```bat
gradlew.bat testDebugUnitTest assembleDebug
```

macOS/Linux:

```bash
./gradlew testDebugUnitTest assembleDebug
```

Debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

On Windows, StageGrid's Gradle bootstrap can fall back to Android Studio's bundled JBR when `JAVA_HOME` is absent or malformed.

## Supported audio today

- WAV RIFF PCM: 8/16/24/32-bit where supported by the parser.
- WAV RIFF IEEE float: 32-bit.
- MP3: decoded during import and cached as PCM WAV.

WAV remains the recommended source format for live multitrack work.

Planned decoder/cache expansion includes FLAC, AAC/M4A and OGG after licensing/performance evaluation.

## Local data and privacy

- No StageGrid account required.
- No analytics SDK.
- No ads.
- No StageGrid audio upload.
- No broad storage permission.
- Files/folders are selected through Android Storage Access Framework.
- Cloud-folder access is limited to locations explicitly granted by the user.
- Imported songs are stored locally for offline playback.

## Known limitations of 0.2.0-alpha02

Not yet complete:

- section count-in/pre-roll;
- fully quantized section-jump workflow;
- double-buffered arrangement/path updates;
- setlist NEXT/PREV live transport and next-song preload;
- restorable performance sessions;
- first-run onboarding/help flow;
- waveform peak cache/editor;
- arbitrary USB multichannel output routing;
- tempo/time stretching and pitch shifting;
- MIDI, pads, automation and SMPTE/LTC;
- complete `.stagepack` backup/export workflow;
- LAN remote control;
- final tablet layout and physical-device qualification matrix.

See [`docs/ROADMAP.md`](docs/ROADMAP.md).

## Release history

### 0.2.0-alpha02 — Simplified live UX

- simplified Player hierarchy;
- friendly Click terminology;
- progressive disclosure of technical controls;
- quick stereo routing presets;
- friendly track labels;
- simplified playhead-based Section Editor;
- precise bar/beat editing kept optional;
- formal usability requirements added to the roadmap.

### 0.2.0-alpha01 — Musical Grid + Section Editor

- musical bar/beat timeline;
- grid-offset support;
- beat/bar snapping;
- visual/manual Section Editor;
- playhead-based section boundaries;
- musical-grid unit tests.

### 0.1.3 — Native Click + stereo routing

- native click generated from the master clock;
- quarter/eighth/triplet/sixteenth subdivisions;
- per-track and Click L / L+R / R routing;
- imported Click used as timing reference.

### 0.1.2 — WAV + MP3 shared-clock MVP

- local library/import;
- Oboe shared-clock multitrack playback;
- import-time MP3 normalization;
- mixer, sections, setlists and foreground playback foundation.

## Release documentation rule

Every StageGrid version/alpha/beta should update this README in the same development cycle with:

- current version;
- new functionality;
- important behavior changes;
- current limitations;
- release-history entry.

## License

StageGrid-owned source is licensed under MIT. Third-party components retain their own licenses. See [`LICENSE`](LICENSE) and [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
