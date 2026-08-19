# StageGrid

StageGrid is a native Android, local-first multitrack player prototype aimed at live performance. The project is intentionally designed around one real-time audio clock rather than one independent Android media player per stem.

> **Current release: 0.1.3 MVP source — native click + stereo routing.** The implemented surface is intentionally smaller than the long-term product specification. Features that do not have a real implementation are not shown as finished UI.

## What is implemented

- Native Kotlin/Jetpack Compose Android application.
- Room library using UUID song IDs and separate Song/Track/Section/Setlist tables.
- Storage Access Framework import from ZIP, folder, or multiple files.
- Private local library copy; imported source ZIP/folder is never modified.
- ZIP-slip protection, nesting/file-count/expanded-size limits, safe filenames.
- PCM/IEEE-float WAV metadata parsing and playback (8/16/24/32-bit where valid).
- MP3 import through Android MediaExtractor/MediaCodec with one-time offline normalization to 16-bit PCM WAV in the private library.
- Automatic Click, Guide, drums, bass, guitars, keys, synth, strings, vocals and percussion classification from English/Spanish filenames.
- Optional open `song.json` import for metadata, stem types and section markers.
- Post-import metadata editor for title, artist, BPM, key, time signature, musical-grid offset and notes.
- One native Oboe output stream, one master output-frame playhead and one shared logical playback path for all stems.
- Streaming decoder threads and preallocated SPSC buffers; no filesystem/Room/Compose work in the real-time callback.
- Deterministic source-rate mapping so 44.1/48/96 kHz stems follow the same output timeline.
- Play, pause, stop, seek, master volume.
- Per-track volume, mute, solo, pan and explicit stereo output assignment (`L`, `L+R`, `R`) persisted to Room.
- Imported Click is retained as a timing reference only; the live metronome is generated natively from the master clock when BPM is known.
- Automatic first-click transient detection establishes a musical-grid offset; the offset can be corrected manually.
- Native click subdivisions: quarter, eighth, eighth-note triplet and sixteenth; click can be routed to `L`, `L+R` or `R`.
- Guide enable/disable and per-track stereo routing.
- Imported song sections, section loop, loop exit and queued section jump.
- Basic local setlists.
- Foreground playback service, Android MediaSession and media notification.
- Audio-focus handling.
- LIVE keep-screen-on mode and Performance Lock.
- Android output-device enumeration and basic stereo selection, including compatible USB endpoints.
- Diagnostics for output sample rate, burst size, callback load estimate, loaded tracks and underruns.
- Spanish and English resources.

See [`docs/STATUS.md`](docs/STATUS.md) for the exact boundary between implemented and planned features.

## Architecture

```text
Compose UI
    │ state / commands
    ▼
StageGridViewModel
    │
    ▼
AudioEngineController ── Room / DataStore / AudioFocus / Foreground Service
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
one Oboe output stream → AAudio/OpenSL ES chosen by Oboe → Android audio device
```

The UI does not own timing. The C++ engine owns the transport frame position. Volume/mute/solo/pan/click/guide controls cross to native atomics and are read by the callback without consulting Room or Compose.

More detail: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Build requirements

The project pins:

- Android Gradle Plugin 9.2.1
- compileSdk / targetSdk 37
- JDK 17
- Gradle 9.5.1
- NDK 28.2.13676358
- CMake 3.22.1
- Jetpack Compose BOM 2026.08.00
- Room 2.8.4
- Oboe 1.10.0

Use an Android Studio release that supports AGP 9.2.1 and API 37. Install Android SDK Platform 37, NDK 28.2.13676358 and CMake 3.22.1 from SDK Manager.

### Android Studio

1. Extract/open the `StageGrid` directory.
2. Select JDK 17 for Gradle.
3. Let Android Studio install/sync the requested SDK/NDK/CMake packages.
4. Select the `app` configuration and a physical Android device.
5. Run the app.

### Command line

macOS/Linux:

```bash
./gradlew testDebugUnitTest assembleDebug
```

Windows:

```bat
gradlew.bat testDebugUnitTest assembleDebug
```

The debug APK is produced at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

This source archive includes a checksum-verifying Gradle bootstrap in `gradlew`/`gradlew.bat`. If the standard binary wrapper JAR is absent, the script downloads only the pinned official Gradle 9.5.1 distribution and validates its SHA-256 before execution.

> Source-package note: the binary `gradle-wrapper.jar` is intentionally not embedded in this archive. The included bootstrap scripts are the reproducible fallback. If Android Studio reports a missing wrapper JAR on first open, run `./gradlew --version` (or `gradlew.bat --version`) once with Internet access, then resync the project.

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

Tap **Import ZIP**, choose the archive through Android's Storage Access Framework, review the detected tracks, then complete/edit the metadata. StageGrid copies playable WAVs into its app-private library. Deleting the original ZIP afterward does not break the imported copy.

### Folder

Tap **Import folder** and grant access to the selected folder through the system picker. StageGrid recursively copies supported import files into a staging area, validates them, then commits the song to Room.

### Multiple WAV / MP3 files

Tap **Import WAV / MP3** and select the stems together. StageGrid creates one song and opens the metadata editor after analysis.

## `song.json`

` song.json ` is optional. Without it, StageGrid creates a song from the WAV filenames and asks for missing metadata. With it, metadata, explicit track types and section starts can be transported with the audio.

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

### Playable in 0.1.3

- WAV RIFF PCM: 8/16/24/32-bit
- WAV RIFF IEEE float: 32-bit
- MP3: decoded locally during import with Android's platform `MediaExtractor` + `MediaCodec`, then stored as 16-bit PCM WAV in StageGrid's private library.
- Mono or multichannel input WAV; the MVP currently takes the first two channels into the stereo mix.
- Per-track source sample rates from 8 kHz to 384 kHz are mapped to the common output timeline by the native reader.

MP3 decoding never runs in the Oboe real-time callback. The conversion happens once during import, so playback still uses the same deterministic PCM path as WAV tracks and remains fully offline afterward. When encoder delay/padding metadata is exposed by Android, the importer trims it from the normalized cache.

For a live multitrack project, WAV is still recommended, preferably with every stem exported from the same timeline and sample rate. Separately encoded MP3 stems can contain codec priming/padding metadata, and support for that metadata varies by Android/device encoder history.

### Detected but deliberately not advertised as playable yet

- FLAC
- M4A/AAC
- OGG

The importer reports these formats instead of creating a nonfunctional playback button.

## Native click, grid alignment and Guide

Filename detection recognizes, among others:

- `click` → CLICK
- `guide`, `cue`, `cues`, `guia`, `guía` → GUIDE
- `drums`, `drum`, `bateria`, `batería` → DRUMS
- `bass`, `bajo` → BASS
- `guitar`, `gtr`, `guitarra`, `acoustic`, `eg` → GUITAR
- `keys`, `piano`, `keyboard` → KEYS
- `synth` → SYNTH
- `strings` → STRINGS
- `vox`, `vocals`, `bgv`, `voces` → VOCALS
- `perc`, `percussion`, `percusión` → PERCUSSION

If a Click WAV/MP3 exists, StageGrid keeps it in the local project as a **reference**, analyzes its first strong transient and stores that position as the song's musical-grid offset. The imported click audio itself is not mixed into the live output. The actual click is generated by the native engine from the same master frame clock as the stems.

The Player lets the user select `1/4`, `1/8`, `1/8T` or `1/16`, and route the native click to `L`, `L+R` or `R`. The detected grid offset is editable in song metadata because an automatic transient detector can encounter unusual click files or intentional pre-rolls.

For MP3 imports, StageGrid first removes codec encoder delay/padding when Android exposes those values, then analyzes the normalized PCM reference. It does **not** blindly trim silence independently from each stem, because doing so could destroy intentional musical rests and stem alignment.

## Sections and live loop

Section starts from `song.json` are converted to local Room `SectionEntity` records. Their end is the next section start (or song end). While playing:

- tapping a section queues a jump at the end of the current section;
- tapping a section while stopped/paused seeks to its start;
- **Loop** loops the current section;
- **Exit loop** disables the loop and restores the normal logical path.

The 0.1 implementation rebuilds a short decoder look-ahead when the live playback path changes. Functional behavior exists, but glitch-free section editing at a boundary has not yet been hardware stress-validated on the full target-device matrix. See `docs/STATUS.md`.

## Stereo L/R routing

The 0.1.3 mixer has a real two-channel routing matrix for each playable stem:

- **L** — downmix that stem to mono and send only to the left output.
- **L+R** — preserve normal stereo/pan behavior across both outputs.
- **R** — downmix that stem to mono and send only to the right output.

The native click has the same `L / L+R / R` selector. This supports common two-output live setups such as **Tracks → R** and **Click/Guide → L**. This is still a stereo matrix; it is not the future 4/8/10-output USB router.

## USB audio

The MVP lists Android output devices with `AudioManager`, identifies USB endpoints, and can ask Oboe to reopen the stereo stream on a selected Android audio-device ID.

Important: **this is not the finished 4/8/10-output router.** Discrete Tracks/Click/Guide buses require a multichannel Oboe stream, device channel-mask negotiation and per-bus output matrices. The settings screen does not pretend those routes already exist.

## Background playback

Starting Play launches a media-playback foreground service before beginning audible playback. The service owns a platform MediaSession and notification controls. Pausing leaves the prepared audio service available for fast resume; Stop resets transport, abandons audio focus and stops the foreground service.

## Local data and privacy

- No account.
- No cloud dependency.
- No analytics SDK.
- No ads.
- No audio upload.
- No broad storage permission.
- File access is selected by the user through Android's Storage Access Framework.

## Tests

Source includes:

- JVM tests for stem classification and WAV metadata parsing.
- Android instrumentation smoke test for JNI/native library loading.
- Room database instrumentation test.
- Host C++ self-test for WAV reading, stereo SPSC buffering and deterministic shared-clock sample-rate mapping.
- A fixture generator for the 16-stem impulse synchronization test described in the product specification.

See [`docs/TESTING.md`](docs/TESTING.md).

## Known limitations of 0.1.3

The following are **not** represented as completed product features in this build:

- compressed audio decoding beyond MP3 (FLAC/AAC/M4A/OGG);
- waveform editor/cache UI;
- manual visual section editor;
- gapless setlist auto-next/crossfade;
- arbitrary USB multichannel output routing;
- tempo/time stretching;
- pitch shifting/transposition;
- pads;
- MIDI input/output/clock/cues;
- SMPTE/LTC;
- automation;
- stagepack backup/import;
- LAN remote control;
- tablet split-screen Player + Mixer layout;
- complete crash/session recovery matrix.

The architecture leaves room for these modules, but they are not fake controls. See [`docs/ROADMAP.md`](docs/ROADMAP.md).

## License

StageGrid-owned source is licensed under MIT. Third-party components keep their own licenses. See [`LICENSE`](LICENSE) and [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
