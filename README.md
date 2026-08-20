# StageGrid

StageGrid is a native Android, local-first multitrack player for live performance. Stems, Native Click, Native Guide and the musical timeline share one real-time audio clock instead of independent Android media players.

> **Current release: `0.2.0-alpha10.3` — English Guide hardening + manual section cues.**
>
> App version: `versionCode 19`, `versionName 0.2.0-alpha10.3`.

## Product principle

**A musician should be able to use the basic live workflow without understanding audio-engineering terminology.** Common performance actions stay visible and technical controls stay progressively disclosed.

## New in 0.2.0-alpha10.3

Physical-device feedback showed an English-specific recognition failure where different calls could collapse into repeated short labels such as `Vamp`, while Spanish recognition remained reliable. Alpha10.3 changes the English classifier instead of globally changing sensitivity again, but automatic Guide recognition is now explicitly treated as an **experimental aid rather than a required workflow**.

### Manual sections are authoritative for Guide SECTION cues

When the musician creates, renames, moves or deletes a section through the section editor, StageGrid now rebuilds the Native Guide SECTION calls from that manual section map. A section cue is scheduled approximately one musical bar before the section start when a valid grid exists (or with a conservative time lead when no valid grid exists).

The manual path:

- works even if automatic Guide recognition found no useful SECTION cues;
- can create `StageGrid Native Guide.wav` from an installed Guide pack when no Native Guide track existed yet;
- preserves already available COUNT / DYNAMIC events while replacing automatic SECTION events with the manual structure;
- maps common Spanish/English section names and shorthand such as `Verso 1`, `Verse 1`, `V1`, `C2`, `In2`, `Puente`, `P`, `Vp`, `Intro` and `Final` to installed cue samples when available;
- marks `native-guide-events.json` with `sectionCueSource = manual` so later systems know the musician-authored section map is authoritative.

This provides a reliable fallback: automatic recognition can be tested and improved without blocking normal live preparation.

### English acoustic second-stage matcher (experimental)

Candidate discovery still uses the proven phase-robust 10 ms energy envelope. When the source Guide is confidently detected as English, StageGrid performs a second-stage acoustic comparison using a lightweight decimated multi-band fingerprint.

The English score combines:

- temporal energy/envelope shape;
- coarse low / low-mid / high-mid / high speech-band movement;
- gain-independent relative band energy;
- semantic margin against the next-best cue.

This gives the matcher information that `RMS` shape alone does not contain, making short English calls such as `Verse`, `Vamp`, `Rap`, `Tag` and `Solo` easier to distinguish.

Spanish and other installed languages keep the alpha10.2 envelope path. The English acoustic pass is intentionally isolated so a correction for English does not destabilize Spanish recognition that already works well in physical-device testing.

### Anti-collapse safety guard

`Vamp`, `Rap`, `Tag` and `Solo` are no longer allowed to become convenient low-confidence defaults. If most recognized SECTION events in an English song collapse into one of those labels, StageGrid discards weak repetitions and preserves only high-confidence occurrences rather than generating an obviously misleading section map.

### Better section naming and recognition diagnostics

Repeated generic `Verse` calls can now be numbered by occurrence (`Verse 1`, `Verse 2`, ... / `Verso 1`, `Verso 2`, ...) when the source sample did not already include an explicit number.

Recognition diagnostics are persisted in `native-guide-events.json` alongside the accepted cues. Each diagnostic records the best candidate, runner-up, confidence, acceptance state and rejection reason. This gives future field feedback a concrete way to distinguish “candidate not found” from “candidate found but ambiguous” without repeatedly guessing global thresholds.

## Current 0.2 feature set

### Library / import

- Kotlin + Jetpack Compose Android app.
- Room library for songs, tracks, sections and setlists.
- ZIP, folder and multi-file import through Android Storage Access Framework.
- Local/offline app-private playback storage.
- WAV playback and one-time MP3 → PCM WAV normalization.
- Import percentage, current pipeline stage and file detail.
- Safe local multitrack deletion with staged rollback around database deletion.
- Linked document-provider folders, including Google Drive when exposed through Android SAF.

### Shared-clock native audio

- one Oboe stereo output stream;
- one authoritative native master frame clock;
- streaming stem decoder threads and preallocated buffers;
- two decoder banks per track for prepared Loop/section transitions;
- synchronized all-track handoff;
- play / pause / stop / seek;
- per-track volume, mute, solo, pan and `L / L+R / R` routing;
- native Click with quarter/eighth/triplet/sixteenth subdivisions;
- native section count-in;
- Android/USB stereo output-device selection;
- runtime diagnostics for underruns/callback/path handoffs.

Manual section choices wait for the explicit end of the **current** section and then enter the selected destination at its explicit start marker.

### Native Guide

StageGrid does not bundle third-party Guide audio. A user-supplied/licensed sample ZIP can be installed locally.

Implemented:

- Spanish, English, French and Portuguese pack layouts when present;
- experimental offline sample/fingerprint recognition;
- SECTION / COUNT / DYNAMIC events;
- `native-guide-events.json` structured sidecar;
- generated `StageGrid Native Guide.wav` on the same playback clock;
- original Guide retained muted as a reference after successful reconstruction;
- automatic editable section proposals (experimental recognition path);
- manual section → Native Guide SECTION cue generation as the reliable fallback path;
- delayed section recovery when BPM is entered after import;
- per-song Native Guide output-language switching;
- Guide reanalysis without reimporting/reconverting the other stems;
- persistent energy-fingerprint cache;
- arrangement-aware relocation of a destination lead phrase containing SECTION/COUNT/DYNAMIC calls;
- English-only acoustic discrimination and anti-collapse protection in alpha10.3.

Automatic recognition remains **experimental sample/template matching, not general-purpose speech-to-text**. A musician can prepare the complete section map manually without depending on recognition.

### Setlist Live

- Setlist current/next context in Player.
- Previous / Next navigation.
- Destination song loads stopped; navigation never auto-plays.
- Bounded filesystem-cache warming of the next song.

The preload is not yet a second full decoder graph, gapless handoff or crossfade.

### Backup / restore

Settings can create a portable `.stagebackup` through Android SAF. A destination can be a local folder, removable storage or Google Drive when exposed as a DocumentsProvider.

A backup includes app-private song data/audio, mixer metadata, sections, setlists/order, Native Guide files/sidecars and the installed user Guide pack. Payload files are validated using byte size + SHA-256 before restore.

Backups are explicit snapshots, not continuous Drive synchronization.

### Safe session recovery

StageGrid stores a versioned performance-session snapshot. A valid previous song/setlist context can be restored after process death, but recovered sessions **always load stopped**. Session recovery never starts emitting audio automatically.

## Build

Requirements:

- Android Gradle Plugin 9.2.1
- Gradle 9.5.1
- JDK 17+
- compileSdk / targetSdk 37
- NDK 28.2.13676358
- CMake 3.22.1

Windows:

```bat
git switch main
git pull
gradlew.bat testDebugUnitTest assembleDebug
```

Debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Known limitations of alpha10.3

- Automatic Guide recognition is explicitly **experimental**. Manual sections are the reliable path and now generate their own SECTION cues.
- English acoustic matching still depends on the installed cue sample pack being acoustically related enough to the source Guide voice; it is not arbitrary speech recognition.
- Alpha10.3 deliberately favors precision over inventing a SECTION label. A very uncertain English call may be omitted instead of shown incorrectly.
- COUNT cues are short and remain a dedicated beta-polish target; SECTION recognition must not be destabilized merely to increase count recall.
- A custom manual section name that has no equivalent SECTION sample in the installed Guide pack cannot produce spoken audio until a compatible sample/key exists.
- Recognition diagnostics are persisted in the Guide sidecar; final user-facing diagnostic presentation/export is beta polish.
- Full arbitrary virtual arrangement graphs remain planned for 0.5.
- Setlist preload is not gapless dual-engine preload/crossfade.
- USB routing is currently stereo; arbitrary 4/8/custom output matrices are planned for 0.4.
- AAC/M4A/FLAC/OGG expansion, waveform editing, tempo/pitch DSP, MIDI, pads, automation and LTC are later milestones.
- Physical-device/high-track-count qualification is still required before StageGrid is considered stage-ready.

See [`docs/STATUS.md`](docs/STATUS.md) for the exact source boundary and [`docs/ROADMAP.md`](docs/ROADMAP.md) for planned milestones.

## Release history

### 0.2.0-alpha10.3

**Guide fallback + English recognition hardening** — manual section maps now generate authoritative Native Guide SECTION cues; automatic recognition is explicitly experimental; English-only multi-band acoustic second-stage matching, ambiguous short-label penalty, anti-collapse guard, persisted recognition diagnostics and generic Verse numbering remain available for continued testing.

### 0.2.0-alpha10.2

**English source-language isolation** — bounded language probe, language-specific matching, stricter English ambiguity margin and reduced repeated recognition work.

### 0.2.0-alpha10.1

**Recognition hardening** — adaptive candidate discovery, phase-robust energy fingerprints, wider timing tolerance and stable Click-train grid anchoring.

### 0.2.0-alpha10

**Beta-readiness integration sprint** — safe session recovery, in-place Guide reanalysis/persistent cache and richer arrangement-aware Guide phrases.

### 0.2.0-alpha07

**Library lifecycle + Setlist Live** — safe song deletion, Previous/Next and bounded next-song cache warming.

### 0.2.0-alpha06

**Portable backup/restore + first arrangement-aware Guide cue**.

### 0.2.0-alpha05 / alpha05.1

**Double-buffered section transitions + section-boundary policy**.

### 0.2.0-alpha04 / alpha04.1 / alpha04.2

**Native Guide foundation, delayed section recovery, per-song Guide language and import progress/performance work**.

### 0.2.0-alpha01 → alpha03

**Musical Grid, simplified live UX and native count-in**.

### 0.1.2 / 0.1.3

**Shared-clock local playback MVP + Native Click/stereo routing**.

## Local data and privacy

- No StageGrid account required.
- No analytics SDK.
- No ads.
- No broad storage permission.
- Imported song audio and Guide samples remain local unless the user explicitly creates a backup through a selected provider.
- StageGrid does not receive Google Drive credentials; Drive access is handled by Android's document provider.

## License

StageGrid-owned source is licensed under MIT. Third-party components retain their own licenses. See [`LICENSE`](LICENSE) and [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
