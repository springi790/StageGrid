# StageGrid

StageGrid is a native Android, local-first multitrack player aimed at live performance. The project is built around one real-time audio clock shared by every stem, the native click and the musical timeline instead of running one independent Android media player per track.

> **Current release: `0.2.0-alpha01` — Musical Grid + visual section editor.**
>
> StageGrid is under active development. Only features with a real implementation are presented as available; planned modules are documented separately in the roadmap.

## Current release — 0.2.0-alpha01

The first StageGrid 0.2 alpha introduces musical time as a first-class concept.

### New in 0.2.0-alpha01

- Deterministic **Musical Grid** based on BPM, time signature and `gridOffsetMs`.
- Conversion between absolute audio time and musical positions (`bar / beat`).
- Beat and bar snapping.
- Current bar/beat readout in Player.
- Visual/manual **Section Editor**.
- Create, rename, resize and delete song sections.
- Set section start/end directly from the current playhead.
- Section boundaries stored as deterministic millisecond positions for compatibility with the current native engine.
- Editing is disabled while transport is playing to avoid mutating live playback structures before the 0.2 double-buffered arrangement engine lands.
- JVM unit tests covering musical-time conversion, grid offset, common time signatures and snapping.
- App version bumped to `0.2.0-alpha01` (`versionCode 4`).

## What is implemented

### Library and import

- Native Kotlin / Jetpack Compose Android application.
- Room library using UUID song IDs and separate Song / Track / Section / Setlist tables.
- Storage Access Framework import from ZIP, folder or multiple files.
- Persistent linked cloud-folder access through Android's document provider system.
- Google Drive folders can be linked from Android's system picker without storing Google credentials or API keys in StageGrid.
- Browse linked folders/subfolders, refresh them and choose only the ZIP/StagePack/stems to copy into the local StageGrid library.
- Imported source files are copied into app-private storage so playback remains offline and does not depend on Drive or the original source folder.
- ZIP-slip protection, nesting/file-count/expanded-size limits and safe filenames.
- Optional open `song.json` import for metadata, stem types and section markers.
- Post-import metadata editor for title, artist, BPM, key, time signature, musical-grid offset and notes.

### Audio formats

- PCM/IEEE-float WAV metadata parsing and playback (8/16/24/32-bit where valid).
- MP3 import through Android `MediaExtractor` / `MediaCodec` with one-time offline normalization to 16-bit PCM WAV in the private library.
- When Android exposes MP3 encoder delay/padding metadata, StageGrid removes that technical priming/padding during normalization.
- Musical silence is never blindly trimmed per stem because doing so could destroy intentional alignment.
- Mono or multichannel WAV input; the current stereo engine uses the first two channels in the stereo mix.
- Per-track source sample rates are mapped to one common output timeline by the native reader.

### Native audio engine

- One native Oboe output stream.
- One master output-frame playhead shared by all stems.
- Streaming decoder threads and preallocated SPSC buffers.
- No filesystem, Room or Compose work inside the real-time callback.
- Deterministic source-rate mapping so stems with different source sample rates stay on the same timeline.
- Play, pause, stop, seek and master volume.
- Per-track volume, mute, solo and pan.
- Persisted per-track stereo routing: `L`, `L+R`, `R`.
- Output-device enumeration and basic stereo device selection, including compatible USB endpoints.
- Diagnostics for output sample rate, burst size, callback-load estimate, loaded tracks and underruns.

### Native Click and Guide

- Imported Click files are retained as timing references instead of being required as the live click track.
- Automatic first-click transient detection can establish the musical-grid offset.
- Grid offset can be corrected manually.
- Native metronome generated from the same master clock as the stems.
- Click subdivisions:
  - quarter (`1/4`)
  - eighth (`1/8`)
  - eighth-note triplet (`1/8T`)
  - sixteenth (`1/16`)
- Native click routing: `L`, `L+R`, `R`.
- Guide enable/disable with normal per-track routing.

### Musical Grid and Sections

- BPM + time signature + grid offset define the song's musical timeline.
- Conversion between milliseconds and `bar / beat` positions.
- Beat and bar snapping.
- Player displays the current musical position.
- Imported `song.json` sections remain supported.
- Manual visual Section Editor is available when transport is stopped.
- Sections can be created, renamed, resized and deleted.
- Start/end can be taken from the current playhead and snapped to beat or bar boundaries.
- Section loop, loop exit and queued section jumps remain available.

### Setlists and live operation

- Basic local setlists.
- Foreground playback service.
- Android MediaSession and media notification.
- Audio-focus handling.
- LIVE keep-screen-on mode.
- Performance Lock.
- Spanish and English resources.

See [`docs/STATUS.md`](docs/STATUS.md) for the exact boundary between implemented and planned functionality and [`docs/ROADMAP.md`](docs/ROADMAP.md) for future releases.

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
    ├─ Track decoder #1 ─┐
    ├─ Track decoder #2 ─┼─ preallocated SPSC buffers
    ├─ ...               ┤
    ├─ generated click   ┘
    ▼
real-time mixer callback
    ▼
one Oboe output stream → AAudio/OpenSL ES → Android audio device

MusicalGrid
    │ BPM + time signature + grid offset
    ├─ ms → bar/beat
    ├─ bar/beat → ms
    └─ beat/bar snapping
```

The UI does not own transport timing. The native C++ engine owns the playback frame position. The Musical Grid provides a deterministic mapping between that absolute timeline and musical positions used by Sections and future arrangement features.

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

Use an Android Studio release compatible with the configured AGP/API level and install the requested Android SDK Platform, NDK and CMake versions from SDK Manager.

### Android Studio

1. Clone/open the `StageGrid` repository.
2. Use Android Studio's bundled JBR/JDK or another compatible JDK.
3. Let Android Studio sync Gradle and install requested SDK/NDK/CMake packages.
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

The debug APK is produced at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

`gradlew` / `gradlew.bat` include StageGrid's checksum-verifying Gradle bootstrap fallback. On Windows, the bootstrap also attempts to use Android Studio's bundled JBR when the host `JAVA_HOME` is absent or malformed.

## Keeping your local checkout current

After a StageGrid update is merged to `main`:

```bash
git switch main
git pull
```

Then build normally:

```bash
./gradlew testDebugUnitTest assembleDebug
```

On Windows:

```bat
gradlew.bat testDebugUnitTest assembleDebug
```

## Importing a song

### ZIP

Example:

```text
Stage Demo.zip
└── Stage Demo/
    ├── 01 Click.wav
    ├── 02 Guide.wav
    ├── 03 Drums.wav
    ├── 04 Bass.wav
    ├── 05 Acoustic.wav
    ├── 06 EG 1.wav
    ├── 07 EG 2.wav
    ├── 08 Keys.wav
    ├── 09 BGV.wav
    └── song.json       (optional)
```

Use **Import ZIP**, select the archive through Android's Storage Access Framework and complete/edit the detected metadata. StageGrid copies supported audio into its app-private library.

### Folder

Use **Import folder** and grant access to a folder through Android's system picker. StageGrid validates supported files and imports them into the local library.

### Multiple WAV / MP3 files

Use **Import WAV / MP3** and choose the stems together. StageGrid creates one song and opens the metadata editor after analysis.

### Google Drive / cloud folder

Use **Google Drive / cloud** in the Library, then choose a specific folder through Android's document picker.

StageGrid stores persistent access only to the folder granted by Android. It can then:

- browse that folder and its subfolders;
- refresh the listing;
- select one ZIP/StagePack or multiple stems;
- copy/import only the selected files;
- keep the resulting song fully local for playback.

This is intentionally a controlled import workflow rather than automatic background replacement of live-performance files.

## `song.json`

`song.json` is optional. Without it, StageGrid derives what it can from filenames and asks for missing metadata. With it, song metadata, explicit track types and section starts can travel with the audio.

Example:

```json
{
  "version": 1,
  "title": "Stage Demo",
  "artist": "Local Band",
  "bpm": 72,
  "key": "C",
  "timeSignature": "4/4",
  "tracks": [
    { "name": "Drums", "file": "03 Drums.wav", "type": "drums" },
    { "name": "Bass", "file": "04 Bass.wav", "type": "bass" },
    { "name": "Click", "file": "01 Click.wav", "type": "click" },
    { "name": "Guide", "file": "02 Guide.wav", "type": "guide" }
  ],
  "sections": [
    { "name": "Intro", "start": 0.0 },
    { "name": "Verse 1", "start": 16.0 },
    { "name": "Chorus 1", "start": 48.0 },
    { "name": "Bridge", "start": 96.0 }
  ]
}
```

Full notes: [`docs/SONG_JSON.md`](docs/SONG_JSON.md).

## Audio formats

### Playable now

- WAV RIFF PCM: 8/16/24/32-bit where supported by the parser.
- WAV RIFF IEEE float: 32-bit.
- MP3: decoded locally during import with Android's platform `MediaExtractor` + `MediaCodec`, then stored as PCM WAV in StageGrid's private library.

MP3 decoding never runs in the Oboe real-time callback. The conversion happens once during import so playback uses the same deterministic PCM path as WAV stems and remains fully offline afterward.

For live multitrack work, WAV remains the recommended source format, preferably with every stem exported from the same timeline and sample rate.

### Planned decoder/cache expansion

- FLAC
- AAC / M4A
- OGG

These are part of the later decoder/cache roadmap rather than advertised as fully supported live-playback formats today.

## Native click, grid alignment and Guide

Filename detection recognizes common English/Spanish names such as Click, Guide/Guía, drums/batería, bass/bajo, guitars, keys, synth, strings, vocals/BGV and percussion.

If a Click WAV/MP3 exists, StageGrid can analyze it as a **timing reference** and store the first reliable click position as `gridOffsetMs`. The imported click itself does not need to be mixed into the live output; the native metronome is generated from the master clock.

For MP3 references, codec encoder delay/padding is removed when Android exposes it before the reference is analyzed. StageGrid does not independently trim musical silence from each stem.

## Musical Grid

The Musical Grid converts between the absolute playback timeline and musical positions.

Example:

```text
BPM: 72
Time signature: 4/4
Grid offset: 183 ms

absolute audio time
        ↕
bar / beat
        ↕
section boundaries
```

The first 0.2 alpha supports:

- current bar/beat display;
- beat snapping;
- bar snapping;
- section boundaries defined from bar/beat positions;
- playhead-to-section-boundary editing.

Future 0.2 work will use this same grid for count-in/pre-roll, quantized jumps and safer live arrangement transitions.

## Sections and live loop

Sections can originate from `song.json` or be created manually with the visual Section Editor.

While playing:

- tapping a section queues/jumps according to the current transport behavior;
- tapping a section while stopped/paused seeks to its start;
- **Loop** loops the current section;
- **Exit loop** disables the loop.

For safety, manual section structure editing is currently disabled during active playback. A later 0.2 milestone introduces double-buffered live path changes so arrangement updates cannot starve the real-time callback.

## Stereo L/R routing

Each playable stem has a two-channel route:

- **L** — downmix the stem to mono and send only to the left output.
- **L+R** — preserve normal stereo/pan behavior across both outputs.
- **R** — downmix the stem to mono and send only to the right output.

The native click has the same `L / L+R / R` selector. This supports common two-output live setups such as:

```text
L → Click + Guide
R → Tracks
```

This is still a stereo routing matrix. Discrete 4/8/custom USB output routing belongs to the multichannel USB roadmap.

## USB audio

StageGrid lists Android output devices with `AudioManager`, identifies compatible USB endpoints and can request that Oboe reopen its stereo stream on a selected Android audio-device ID.

This is **not yet** the finished 4/8/custom-output router. That requires multichannel stream negotiation, channel masks, buses and a full output matrix.

## Background playback

Starting playback launches a media-playback foreground service before audible output. The service owns a platform MediaSession and notification controls. Audio focus is handled through Android's audio APIs.

## Local data and privacy

- No StageGrid account required.
- No analytics SDK.
- No ads.
- No audio upload performed by StageGrid.
- No broad storage permission.
- Files/folders are selected by the user through Android's Storage Access Framework.
- Cloud-folder access is scoped to locations explicitly granted through Android's document provider system.
- Imported songs are copied locally for deterministic offline playback.

## Tests and CI

The source includes tests for areas such as:

- stem classification;
- WAV metadata parsing;
- musical-grid conversion and snapping;
- JNI/native library loading;
- Room database behavior;
- host C++ WAV/shared-clock behavior.

GitHub Actions runs the Android test/build pipeline for development pull requests and can upload the debug APK artifact after a successful build.

See [`docs/TESTING.md`](docs/TESTING.md).

## Known limitations of 0.2.0-alpha01

The following are not yet complete production features:

- section count-in/pre-roll;
- double-buffered arrangement/path updates;
- setlist NEXT/PREV live transport with next-song preload;
- persisted/restorable performance session;
- decoder support beyond current WAV/MP3 path;
- waveform peak cache/editor;
- arbitrary USB multichannel routing;
- tempo/time stretching;
- pitch shifting/transposition;
- MIDI input/output/clock/cues;
- pads;
- automation;
- SMPTE/LTC;
- complete `.stagepack` backup/export workflow;
- LAN remote control;
- full tablet split-screen Player + Mixer UI;
- final physical-device stress/qualification matrix.

See [`docs/ROADMAP.md`](docs/ROADMAP.md).

## Release history

### 0.2.0-alpha01

**Musical Grid + Section Editor**

- musical bar/beat timeline;
- grid offset support;
- beat/bar snapping;
- visual/manual section editor;
- playhead-based section boundaries;
- musical-grid unit tests.

### 0.1.3

**Native Click + stereo routing**

- native click generated from the master clock;
- `1/4`, `1/8`, `1/8T`, `1/16` subdivisions;
- MP3 timing-reference normalization improvements;
- per-track `L / L+R / R` routing;
- native-click output routing.

### 0.1.2

**Local WAV + MP3 MVP**

- shared-clock native multitrack engine;
- WAV playback;
- MP3 → PCM import-time normalization;
- local library;
- mixer;
- sections;
- setlists;
- foreground playback and MediaSession.

## Documentation policy

Starting with the 0.2 line, every version change should update the README in the same development cycle with:

1. current version;
2. new functionality;
3. changed behavior;
4. known limitations;
5. release-history entry.

`docs/STATUS.md` remains the source for precise implementation status and `docs/ROADMAP.md` remains the source for planned milestones.

## License

StageGrid-owned source is licensed under MIT. Third-party components keep their own licenses. See [`LICENSE`](LICENSE) and [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
