# StageGrid

StageGrid is a native Android, local-first multitrack player for live performance. Stems, native Click, Guide and the musical timeline share one real-time audio clock instead of independent Android media players.

> **Current release: `0.2.0-alpha07` — Library lifecycle + Live Setlist.**
>
> StageGrid is under active development. Only functionality with a real implementation is presented as available; planned modules live in [`docs/ROADMAP.md`](docs/ROADMAP.md).

## Product principle

**A musician should be able to use the basic live workflow without understanding audio-engineering terminology.** Common actions remain visible while technical controls stay behind optional advanced controls.

## New in 0.2.0-alpha07

### Safe local multitrack deletion

Library now exposes an explicit **Delete** action for imported multitracks. Deletion requires confirmation and is blocked while playback, count-in, import, backup/restore or Native Guide rendering is active.

For a loaded song, StageGrid unloads the native WAV readers first. The complete app-private song folder is then moved into a temporary staging location before the Room song record is deleted. If the database operation fails, StageGrid attempts to put the staged files back. Room foreign-key cascades remove the deleted song's track, section and setlist-reference rows.

Deleting a song from the device does **not** modify `.stagebackup` files previously saved outside StageGrid.

### Live Setlist NEXT / PREV

A selected non-empty setlist can now enter **Setlist Live** mode. Player shows:

- active setlist and song position;
- current and next song;
- large **Previous** and **Next** controls;
- next-song preparation status;
- an explicit **Exit Setlist** action.

Changing songs stops/unloads the current native song graph and loads the destination in a stopped state. StageGrid never starts the new song automatically merely because NEXT/PREV was pressed.

Alpha07 also performs a bounded next-song **warm preload**: after current-song loading starts, it reads the first 512 KiB of each normalized next-song track on an IO dispatcher. This warms the Android/Linux filesystem cache and can reduce later disk-startup latency without keeping a second native decoder graph alive.

This is intentionally **not yet a gapless/crossfade engine**. Dual-song native preload and crossfade remain a later arrangement-engine feature.

### Section wording correction

The Player now describes a queued manual section change as waiting until the **current authored section ends**, matching the alpha05.1 engine policy. The obsolete “next bar” wording is no longer used for this action.

App version: `0.2.0-alpha07` (`versionCode 13`).

## Portable backup and restore

From **Settings → Backup & restore**, StageGrid can create a self-contained `.stagebackup` through Android Storage Access Framework. The destination can be a local folder, compatible USB/removable storage, Google Drive when Drive is exposed as a document provider, or another compatible writable provider.

A backup contains the local song directories and structured state needed for recovery on another device:

```text
StageGrid backup
├─ normalized stems / local song audio
├─ song metadata and sections
├─ persisted mixer state
├─ native-guide-events.json
├─ generated Native Guide files
├─ setlists + order
└─ currently installed user-supplied Guide pack
```

Track paths are stored relative to each song rather than as old-device app-private paths. Every payload file is declared with byte size and SHA-256. Restore stages and validates the archive before installing data; matching stable IDs are replaced while unrelated local library records are preserved.

Backup remains an explicit recovery snapshot, not continuous/bidirectional Drive synchronization.

## Sections and live path engine

Manual section selection waits for the explicit `endMs` of the current section and enters the selected destination at its explicit `startMs`.

Every playable track has two decoder banks. The active bank continues feeding Oboe while an inactive bank prepares a changed Loop or section-jump path. Once all tracks report the same prepared generation, the realtime callback performs the synchronized handoff.

```text
ACTIVE BANK ───────────────> Oboe
      │
      └─ current audio continues

INACTIVE BANK
      └─ prepare requested path
                 │
                 ▼
          all tracks ready
                 │
                 ▼
       shared-clock handoff
```

The callback does not open files, seek WAV readers, query Room or analyze Guide audio. Unsafe stale prepared paths are rejected instead of being activated late.

## Native Guide pipeline

StageGrid can install a user-supplied Guide sample pack through Android's document picker. StageGrid does not bundle or upload third-party Guide audio.

Supported pack-language handling currently includes Spanish (`ES`), English (`EN`), French (`FR`) and Portuguese (`PT`) when present in the selected pack.

```text
Imported Guide stem
       ↓
offline template matching
       ↓
recognized section / count / dynamic cues
       ↓
native-guide-events.json
       ↓
StageGrid Native Guide.wav
```

When reconstruction succeeds, the imported Guide remains muted as reference and the generated Native Guide becomes active. Recognized section cues can propose editable song sections. If BPM is entered later, persisted events are reused rather than re-analyzing Guide audio. A processed song can also switch generated Guide language without re-importing stems.

Alpha06 introduced the first arrangement-aware Guide layer: when a destination section is manually selected, StageGrid can prepare its matching spoken section-name cue outside the realtime callback and schedule it relative to the current section boundary while suppressing the conflicting fixed Guide call.

Full relocation of every count/dynamic cue across arbitrary future arrangements is still pending.

## What is implemented

### Library, import and portability

- Kotlin / Jetpack Compose Android app.
- Room Song, Track, Section, Setlist and SetlistSong data.
- ZIP, folder and multi-file import through SAF.
- Linked document-provider folder browsing, including Google Drive when exposed by Android.
- App-private local copies for offline playback.
- ZIP-slip protection, extraction limits and safe filenames.
- Optional `song.json` metadata/sections and post-import metadata editing.
- Import percentage, current stage and per-track processing progress.
- Confirmed safe local multitrack deletion with file staging before database deletion.
- Portable `.stagebackup` creation/restoration with SHA-256 validation and device-independent path reconstruction.

### Audio

Playable now:

- WAV RIFF PCM: 8/16/24/32-bit where supported by the parser;
- WAV RIFF IEEE float: 32-bit;
- MP3 via one-time Android `MediaExtractor` / `MediaCodec` normalization to PCM WAV during import.

MP3 decoding never runs in the Oboe realtime callback. StageGrid does not independently trim silence from stems because intentional rests and pre-roll must remain aligned.

### Native audio engine

- one Oboe stereo output stream and master transport clock;
- streaming decoder threads and preallocated SPSC buffers;
- two decoder banks per track for prepared live path handoff;
- synchronized all-track Loop/section handoff;
- Play/Pause/Stop/seek and master volume;
- per-track volume, mute, solo, pan and `L / L+R / R` routing;
- native sample-clock Click, subdivisions and routing;
- basic Android/USB stereo device selection;
- diagnostics for sample rate, burst size, underruns, callback load and path swaps/misses;
- preloaded short Native Guide section cues mixed on the shared transport clock.

### Musical Grid, sections and live workflow

- milliseconds ↔ bar/beat conversion and beat/bar snapping;
- visual/manual Section Editor;
- automatic Guide-derived section proposals;
- Edit Sections remains a first-class Player action;
- Section Loop / Exit Loop;
- manual section changes wait for current section end;
- 1/2-bar native count-in;
- simplified Player/Mixer terminology and routing presets;
- Live Setlist Previous/Next navigation with bounded next-song cache warming;
- foreground playback service, MediaSession, audio focus, LIVE mode and Performance Lock;
- Spanish and English resources.

## Build requirements

- Android Gradle Plugin 9.2.1
- compileSdk / targetSdk 37
- JDK 17+
- Gradle 9.5.1
- NDK 28.2.13676358
- CMake 3.22.1
- Jetpack Compose, Room and Oboe

Build on Windows:

```bat
gradlew.bat testDebugUnitTest assembleDebug
```

Debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Keep the local checkout current:

```bash
git switch main
git pull
```

## Local data and privacy

- No StageGrid account required.
- No analytics SDK or ads.
- No broad storage permission.
- StageGrid does not upload song audio or Guide samples itself.
- Imported songs and installed Guide packs live in app-private storage.
- SAF access is limited to user-selected files/folders.
- Backups are written only to the destination selected through Android's picker.
- Drive access is handled by Android/Google Drive's document provider rather than StageGrid receiving Drive credentials.

## Tests and CI

GitHub Actions runs unit tests and `assembleDebug` for development pull requests before merge into `main`.

Coverage includes stem classification, WAV parsing, Musical Grid conversion/snapping, section-boundary policy, Native Guide recognition/section inference, arrangement cue timing, Setlist Live navigation boundaries, Room behavior, JNI/native compilation and shared-clock audio behavior.

## Known limitations of 0.2.0-alpha07

- Physical-device stress testing with representative 16/32+ stem projects is still required before stage qualification.
- Arrangement-aware Guide handling currently relocates/replaces the selected **section-name call**, not every count/dynamic event in a fully arbitrary virtual arrangement.
- Very late section choices can intentionally skip their spoken replacement cue.
- In-place Guide re-analysis after changing/installing a Guide pack is pending.
- Guide fingerprint cache remains in-memory for the current app process.
- Backup is a manual snapshot, not continuous Drive synchronization.
- Setlist next-song preload warms the OS file cache; it is not a second native song graph, gapless transition or crossfade.
- Safe local deletion still needs physical-device low-storage/forced-failure qualification.
- Restorable in-progress performance sessions are pending.
- Waveform cache/editor, additional compressed codecs, arbitrary multichannel USB, tempo/pitch DSP, MIDI, pads, automation and SMPTE/LTC remain later milestones.
- Full `.stagepack` interchange, LAN remote, tablet split workspace, onboarding and final accessibility pass remain pending.

See [`docs/ROADMAP.md`](docs/ROADMAP.md) and [`docs/STATUS.md`](docs/STATUS.md).

## Release history

### 0.2.0-alpha07

**Library lifecycle + Live Setlist**

- confirmed safe local multitrack deletion;
- app-private song folder staging and rollback attempt if Room deletion fails;
- decoder unload before deleting a currently loaded song;
- external backups left untouched;
- Setlist Live current/next context and Previous/Next controls;
- destination songs load stopped rather than auto-playing;
- bounded 512 KiB-per-track next-song OS-cache warm preload;
- corrected queued-section boundary wording;
- deterministic Setlist navigation tests.

### 0.2.0-alpha06

**Portable backup/restore + section-aware Guide cue layer**

- portable `.stagebackup` through SAF with local/USB/Drive-provider support;
- songs/audio/metadata/sections/setlists/Guide sidecars/installed Guide pack included;
- SHA-256 validation and portable path reconstruction;
- selected live destination section can receive a Native Guide replacement call before its boundary.

### 0.2.0-alpha05.1

**Manual section-boundary transition hotfix** — current section finishes at explicit `endMs`; destination enters at explicit `startMs`.

### 0.2.0-alpha05

**Double-buffered live path hardening** — two decoder banks per track, background path preparation and synchronized realtime handoff.

### 0.2.0-alpha04.2

**Per-song Guide language + import progress/performance pass** — rerender Guide without recognition, visible import progress and Guide/I/O optimizations.

### 0.2.0-alpha04.1

**Native Guide section-recovery hotfix** — reuse persisted cue events when BPM is entered after import.

### 0.2.0-alpha04

**Native Guide recognition + automatic sections** — local Guide packs, ES/EN/FR/PT reconstruction, structured events and editable section proposals.

### 0.2.0-alpha03

**Initial section transitions + native count-in**.

### 0.2.0-alpha02

**Simplified live UX** — Player hierarchy, routing presets and friendlier terminology.

### 0.2.0-alpha01

**Musical Grid + Section Editor**.

### 0.1.3

**Native Click + stereo routing**.

### 0.1.2

**MP3 import path**.

## Release documentation policy

Every StageGrid alpha, beta or release updates this README with the current version, new functionality, implemented behavior, known limitations and release history.

`docs/ROADMAP.md` describes what comes next; `docs/STATUS.md` remains the precise implementation boundary.

## License

StageGrid-owned source is licensed under MIT. Third-party components retain their own licenses. See [`LICENSE`](LICENSE) and [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
