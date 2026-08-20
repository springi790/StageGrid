# StageGrid

StageGrid is a native Android, local-first multitrack player for live performance. Stems, native Click, Guide and the musical timeline share one real-time audio clock instead of independent Android media players.

> **Current release: `0.2.0-alpha06` — Portable backup/restore + section-aware native Guide cues.**
>
> StageGrid is under active development. Only functionality with a real implementation is presented as available; planned modules live in [`docs/ROADMAP.md`](docs/ROADMAP.md).

## Product principle

**A musician should be able to use the basic live workflow without understanding audio-engineering terminology.**

Common actions remain visible while technical controls such as grid offsets and manual routing stay behind optional advanced controls.

## New in 0.2.0-alpha06

### Portable library backup and restore

StageGrid can now create a self-contained `.stagebackup` from **Settings → Backup & restore** using Android's Storage Access Framework.

The system folder picker means the destination can be:

- a local device folder;
- compatible USB/removable storage;
- Google Drive when Drive is exposed by Android as a document provider;
- another compatible DocumentsProvider.

No StageGrid account or Drive API/OAuth integration is required.

A backup contains the complete local song directories plus the structured library state needed to reconstruct the app on another device:

```text
StageGrid backup
├─ song audio / normalized WAV stems
├─ song metadata
├─ sections
├─ mixer state stored with tracks
├─ native-guide-events.json
├─ generated Native Guide files
├─ setlists + setlist order
└─ currently installed user-supplied Guide pack
```

Track paths are stored as portable song-relative paths rather than Android device-specific absolute paths. Restore rebuilds the new app-private paths on the destination device.

Every archived file is listed in `manifest.json` with its byte size and SHA-256 digest. Restore copies the selected backup into a staging area, safely extracts it with path/size/count limits, verifies the complete file set and hashes, and only then installs song data into the library. Matching stable IDs are replaced; unrelated songs already on the device are preserved.

Backup and restore expose percentage, current stage and the file/song currently being processed. Playback/import/Guide rendering must be stopped before a backup operation starts. Before restore, StageGrid closes the native song decoders so WAV files can be replaced safely.

### Section-aware Guide cue relocation — first arrangement layer

Manual section selection still follows the alpha05.1 rule: **the current section finishes at its authored end marker**.

Alpha06 adds the first arrangement-aware behavior on top of that transition. If a Native Guide and compatible installed cue sample are available, StageGrid resolves the selected destination section back to its canonical cue key and prepares the spoken target cue off the realtime thread.

Example:

```text
CURRENT: VERSE
bar 17 ─ bar 18 ─ bar 19 ─ bar 20
                 user selects BRIDGE
                         │
                         ▼
              final bar of current section
                 Native Guide: “Bridge”
                         │
                         ▼
bar 20 boundary ────────┼──> BRIDGE start
```

When the selection happens early enough, the replacement section call is scheduled approximately one bar before the current section ends. A later selection can move that spoken call shortly after the tap when enough useful time remains. A selection made too close to the boundary skips the spoken replacement rather than cutting a cue or destabilizing the audio transition.

The original rendered Guide is temporarily suppressed around the replacement **section-name** cue to avoid two conflicting section calls. The short cue PCM is loaded/resampled before publication; the native callback only mixes already-prepared PCM against the same master timeline used by stems and Click.

This alpha does **not yet relocate every count/dynamic cue** for a completely arbitrary future arrangement graph. It is the first event-aware layer: the destination section name follows the live manual section choice.

App version: `0.2.0-alpha06` (`versionCode 12`).

## Double-buffered live path engine

Every track has two decoder banks. The active bank continues feeding Oboe while an inactive bank prepares a changed Loop/section-jump timeline. Once every track has the matching generation and enough aligned data, the realtime callback hands all tracks over together.

```text
ACTIVE BANK ───────────────> Oboe
      │
      └─ current audio continues

INACTIVE BANK
      └─ prepare selected future path
                 │
                 ▼
          all tracks ready
                 │
                 ▼
       atomic shared-clock handoff
```

The callback does not open files, seek WAV readers, query Room, analyze Guides or wait for UI work. Stale prepared paths are cancelled instead of being activated after the old/new timelines have already diverged.

Native diagnostics expose successful prepared-bank swaps, missed safe windows and whether a replacement path is pending.

## Native Guide pipeline

Install a Guide sample pack once from Settings using Android's document picker. StageGrid does not bundle or upload third-party Guide audio; the user supplies a pack they are licensed to use.

Installed-language handling supports Spanish (`ES`), English (`EN`), French (`FR`) and Portuguese (`PT`) when those languages are present in the selected pack.

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
       ↓
optional live section-aware replacement cue
```

When reconstruction succeeds, the original Guide remains as a muted reference and the generated Guide becomes the active Guide track. Recognized section calls can generate editable automatic sections when a valid Musical Grid exists. If BPM is entered after import, StageGrid reuses saved cue events rather than re-analyzing the audio.

A processed song can change its generated Guide language directly from Player without re-importing stems or repeating Guide recognition.

The recognizer is designed for sample-based Guide tracks matching the installed cue pack. It is **not** a general-purpose speech-to-text system.

## Current live workflow

```text
Import song
  ↓
Click → Musical Grid reference
Guide → native cue recognition
  ↓
automatic/editable sections
  ↓
Player
  ├─ Play / Pause / Stop
  ├─ Click / Guide
  ├─ count-in
  ├─ section Loop
  └─ select destination section
             ↓
      announce selected section
      when a native cue is available
             ↓
      finish CURRENT section
             ↓
      double-buffered handoff
             ↓
      selected section start
```

A common stereo stage preset is:

```text
Left  → Click + Guide
Right → Tracks
```

## What is implemented

### Library, import and portability

- Kotlin / Jetpack Compose Android app.
- Room library with Song, Track, Section and Setlist entities.
- ZIP, folder and multi-file import through Android Storage Access Framework.
- Linked cloud-folder browsing through Android document providers, including Google Drive through the system picker.
- Cloud selections copied to app-private storage for deterministic offline playback.
- ZIP-slip protection, extraction limits and safe filenames.
- Optional `song.json` metadata and section markers.
- Post-import metadata editing.
- Overall import percentage and current-stage descriptions.
- Track-level WAV/MP3 processing progress.
- Portable `.stagebackup` creation to a user-selected SAF folder/provider.
- Restore from `.stagebackup` after reinstall/device change.
- Per-file size + SHA-256 integrity validation before restore.
- Portable reconstruction of app-private track paths.
- Song/section/setlist/Guide-sidecar restore by stable IDs.
- Installed Guide pack included in the portable library backup.

### Audio formats

Playable now:

- WAV RIFF PCM: 8/16/24/32-bit where supported by the parser.
- WAV RIFF IEEE float: 32-bit.
- MP3 through one-time Android `MediaExtractor` / `MediaCodec` normalization to PCM WAV during import.

MP3 decoding never runs in the Oboe real-time callback. StageGrid does not independently trim musical silence from stems because intentional rests/pre-roll must remain aligned to the shared timeline.

### Native audio engine

- One native Oboe stereo output stream.
- One master output-frame playhead shared by all stems.
- Streaming decoder threads + preallocated SPSC buffers.
- Two decoder banks per track for prepared live path handoff.
- Active playback continues while a replacement Loop/jump path is decoded.
- All tracks hand off together after readiness/alignment checks.
- Unsafe stale path swaps are rejected.
- Play, pause, stop and seek.
- Master volume.
- Per-track volume, mute, solo, pan and `L / L+R / R` route.
- Basic Android/USB stereo device selection.
- Diagnostics for sample rate, burst size, underruns, callback load and path swaps/misses.
- Preloaded short Native Guide section cues can be mixed on the shared transport clock for live manual section choices.

### Native Click

- Imported Click retained as timing reference only.
- First-click transient detection for `gridOffsetMs`.
- Manual grid-offset correction.
- Native sample-clock Click.
- 1/4, 1/8, 1/8T and 1/16 subdivisions.
- Click route: `L`, `L+R`, `R`.

### Native Guide

- Imported Guide detection.
- User-installed local Guide sample packs.
- Offline template/fingerprint recognition.
- Default or per-song output language.
- Structured `native-guide-events.json` storage.
- App-generated `StageGrid Native Guide.wav`.
- Original Guide retained muted after successful reconstruction.
- Automatic section proposals and delayed section recovery after BPM becomes available.
- Live target-section cue lookup from persisted section proposals.
- Off-callback cue loading/resampling and shared-clock native cue playback.
- Temporary fixed-Guide suppression around a replacement section-name call.

### Musical Grid and sections

- milliseconds ↔ bar/beat conversion;
- beat/bar snapping;
- Player bar/beat readout;
- visual/manual Section Editor;
- section create/rename/resize/delete;
- automatic section proposals from Native Guide cues;
- Section Loop / Exit Loop;
- manual section changes queued for the explicit end of the current section;
- section destinations enter at their explicit start marker;
- 1- or 2-bar native count-in;
- target-section Guide call planned relative to the current section boundary when possible.

**Edit sections remains a first-class Player action.** Automatic recovery only replaces the untouched `Full Song` fallback, protecting manual section work.

### Mixer, setlists and live operation

- Friendly routing presets plus manual `L / L+R / R` routing.
- Basic local setlists.
- Foreground playback service + MediaSession/notification controls.
- Audio-focus handling.
- LIVE keep-screen-on mode.
- Performance Lock.
- Spanish and English UI resources.

The current routing matrix is stereo; arbitrary 4/8/custom USB output routing is planned for a later milestone.

## Backup workflow

Create a backup:

```text
Settings
  ↓
Backup & restore
  ↓
Create backup
  ↓
Android folder picker
  ↓
local folder / USB / Drive provider
  ↓
StageGrid-YYYYMMDD-HHMMSS.stagebackup
```

Restore after installing StageGrid on another device:

```text
Settings
  ↓
Backup & restore
  ↓
Restore
  ↓
select .stagebackup
  ↓
copy to staging
  ↓
validate manifest + complete file set + SHA-256
  ↓
restore private song files
  ↓
restore Room library/setlists
```

A backup is a snapshot. Alpha06 does not automatically synchronize later library changes to Drive in the background.

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

## Keeping your local checkout current

```bash
git switch main
git pull
```

## Local data and privacy

- No StageGrid account required.
- No analytics SDK.
- No ads.
- No broad storage permission.
- StageGrid does not upload audio or Guide samples itself.
- Guide packs and imported songs live in app-private storage.
- SAF access is limited to files/folders explicitly selected by the user.
- Backups are written only to the destination chosen through Android's picker.
- A Drive destination is handled by Android/Google Drive's document provider rather than by StageGrid receiving Drive credentials.

## Tests and CI

GitHub Actions runs unit tests and `assembleDebug` for development pull requests before changes are merged into `main`.

Coverage includes stem classification, WAV parsing, Musical Grid conversion/snapping, section-boundary transitions, Native Guide recognition/section inference, arrangement cue timing, Room behavior, JNI/native compilation and shared-clock audio behavior.

## Known limitations of 0.2.0-alpha06

- Physical-device stress validation with representative 16/32+ stem projects is still required before stage qualification.
- Arrangement-aware Guide handling currently relocates/replaces the **selected section-name call**. Full relocation of every count/dynamic cue in an arbitrary virtual arrangement is still pending.
- A very late manual selection can skip its spoken replacement cue if there is not enough time before the section boundary.
- In-place Guide audio re-analysis for songs imported before installing/changing a Guide pack is still pending.
- Guide fingerprint caching remains in-memory across the current app process.
- Portable backup is manual snapshot backup, not continuous/bidirectional Drive synchronization.
- Setlist Live NEXT/PREV with next-song preload is pending.
- Restorable in-progress performance sessions are pending.
- Waveform cache/editor, additional compressed codecs, arbitrary multichannel USB, tempo/pitch DSP, MIDI, pads, automation and SMPTE/LTC remain later milestones.
- Full project interchange semantics for the later `.stagepack` format remain separate from the alpha06 disaster-recovery `.stagebackup` format.
- Tablet split Player + Mixer, onboarding and final accessibility pass remain pending.

See [`docs/ROADMAP.md`](docs/ROADMAP.md).

## Release history

### 0.2.0-alpha06

**Portable backup/restore + section-aware Guide cue layer**

- portable `.stagebackup` to any writable Android document-provider folder;
- local/USB/Google Drive-provider destination support through SAF;
- songs, audio, metadata, sections, setlists, Native Guide sidecars and installed Guide pack included;
- SHA-256 and byte-size validation before restore;
- device-independent track path reconstruction;
- visible backup/restore percentage and stages;
- manual section changes continue to wait for current section end;
- selected target section can be announced from the installed Native Guide sample pack before that boundary;
- late-choice timing policy and tests;
- fixed rendered Guide suppression around the replacement section-name call.

### 0.2.0-alpha05.1

**Manual section-boundary transition hotfix**

- current section finishes at explicit `endMs`;
- requested section enters at explicit `startMs`;
- no next-bar truncation of a manually selected current section.

### 0.2.0-alpha05

**Double-buffered live path hardening**

- two decoder banks per track;
- background Loop/section-jump path preparation;
- synchronized all-track realtime handoff;
- stale late path rejection + diagnostics.

### 0.2.0-alpha04.2

**Per-song Guide language + import progress/performance pass**

- change processed Guide language without re-analysis;
- import percentage/current operation;
- Guide template caching and import I/O optimizations.

### 0.2.0-alpha04.1

**Native Guide section-recovery hotfix**

- reuse persisted cue events when BPM is entered after import;
- recover sections without re-analyzing Guide audio.

### 0.2.0-alpha04

**Native Guide recognition + automatic sections**

- local user-installed cue packs;
- ES/EN/FR/PT recognition/reconstruction;
- structured Guide event sidecar;
- automatic editable section proposals.

### 0.2.0-alpha03

**Initial section transitions + native count-in**

- live section scheduling;
- 1/2-bar native count-in;
- synchronized stem entry.

### 0.2.0-alpha02

**Simplified live UX**

- simplified Player hierarchy;
- routing presets;
- friendlier terminology and Section Editor.

### 0.2.0-alpha01

**Musical Grid + Section Editor**

- bar/beat timeline;
- beat/bar snapping;
- manual visual section editing.

### 0.1.3

**Native Click + stereo routing**

### 0.1.2

**MP3 import path**

## Release documentation policy

Every StageGrid alpha, beta or release updates this README with the current version, new functionality, implemented behavior, known limitations and release history.

`docs/ROADMAP.md` describes what comes next; `docs/STATUS.md` remains the precise implementation boundary.

## License

StageGrid-owned source is licensed under MIT. Third-party components retain their own licenses. See [`LICENSE`](LICENSE) and [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
