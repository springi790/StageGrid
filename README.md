# StageGrid

StageGrid is a native Android, local-first multitrack player for live performance. Stems, Native Click, Native Guide and the musical timeline share one real-time audio clock instead of independent Android media players.

> **Current release: `0.2.0-alpha10.1` — recognition hardening before beta.**
>
> This hotfix keeps the alpha10 beta-readiness feature set and specifically improves Guide cue recognition and musical-grid start detection based on physical-device feedback.

## Product principle

**A musician should be able to use the basic live workflow without understanding audio-engineering terminology.** Common performance actions stay visible; technical controls remain progressively disclosed.

## New in 0.2.0-alpha10.1

### More tolerant Native Guide recognition

The recognizer no longer depends on one global activity threshold. It now combines strong and relaxed activity passes plus local onset detection, so one loud Guide phrase is less likely to hide quieter calls later in the same stem.

Fingerprint comparison now has a wider timing search window and a conservative language-aware recovery pass. Once the source Guide language is clear, rejected candidates can be checked again against that language only, reducing cross-language lookalikes in the confidence-margin calculation without globally accepting weak matches.

Guide fingerprint energy is now calculated per channel before channel averaging. This avoids stereo phase cancellation causing an otherwise valid Guide to appear nearly silent to the recognizer. The persistent fingerprint-cache format was bumped so old envelopes are rebuilt automatically.

### More reliable Musical Grid start

`ClickGridAnalyzer` no longer blindly trusts the first strong transient. It collects candidate Click onsets and prefers the beginning of a stable periodic pulse train. An isolated spike/noise transient before the real Click can therefore be rejected as the grid origin.

If no stable pulse train is available, the analyzer still falls back to the first valid Click candidate rather than failing the import.

App version: `0.2.0-alpha10.1` (`versionCode 17`).

## Included alpha10 beta-readiness work

### Safe performance-session recovery

StageGrid stores a small, versioned performance-session snapshot while a song is loaded. The snapshot includes the loaded song, approximate transport position, Click/Guide state, Click subdivision/route, count-in choice, master volume and active Setlist Live context.

On a later app start StageGrid validates that the referenced song/setlist still exists, restores the available context and loads the song at the latest saved position. **Recovered sessions never auto-play.**

### In-place Native Guide reanalysis + persistent fingerprints

A retained original Guide track can be analyzed again from Player after installing or replacing a Guide sample pack without reimporting/reconverting the multitrack stems.

```text
Imported song already on device
        ↓
install / replace Guide pack
        ↓
Player → Reanalyze Guide
        ↓
reuse original Guide reference
        ↓
recognize cues again
        ↓
regenerate StageGrid Native Guide.wav
        ↓
refresh untouched automatic sections when safe
```

If a section was renamed, resized, reordered or recolored manually, the user-authored section map is protected rather than silently replaced.

### Arrangement-aware section + count + dynamic Guide phrases

For a selected destination, StageGrid can relocate a phrase from that section's original lead bar containing SECTION, COUNT and DYNAMIC calls.

```text
Original lead into CHORUS
"Chorus" → "2" → "3" → "All in"

Live performance
VERSE is playing
      ↓
user selects CHORUS
      ↓
StageGrid prepares destination Guide phrase off-thread
      ↓
current VERSE reaches its authored end marker
      ↓
all stems jump together to CHORUS start
```

Cue WAV files are opened, decoded/resampled and mixed into an immutable short mono buffer outside the realtime callback. If a late choice does not leave room for an entire spoken sample, that sample is skipped rather than cut mid-word.

## Current live workflow

```text
Import ZIP / folder / audio files
        ↓
local normalized playback files
        ↓
Click → Musical Grid
Guide → optional local cue recognition
        ↓
automatic/editable sections
        ↓
Player / Mixer / Setlist Live
        ↓
manual section choice
        ↓
prepare destination audio + Guide phrase
        ↓
finish CURRENT authored section
        ↓
double-buffered synchronized handoff
        ↓
selected section start
```

A common stereo stage preset remains:

```text
Left  → Click + Guide
Right → Tracks
```

## Library, import and portability

Implemented:

- Kotlin / Jetpack Compose Android application.
- Room library for songs, tracks, sections and setlists.
- ZIP, folder and multi-file import through Android Storage Access Framework.
- Linked document-provider folder browsing, including Google Drive when exposed by Android.
- Imported audio copied into app-private storage for deterministic offline playback.
- ZIP-slip protection, bounded extraction and safe filenames.
- Optional `song.json` metadata/section markers.
- Post-import metadata editing.
- Import percentage, pipeline stage and current-file detail.
- Safe local multitrack deletion with confirmation and staged file rollback around the Room operation.
- Deleting a local song does not modify external `.stagebackup` snapshots.

### Audio formats currently playable

- WAV RIFF PCM: supported integer PCM depths handled by the parser.
- WAV 32-bit IEEE float where supported.
- MP3 through one-time Android `MediaExtractor` / `MediaCodec` normalization to PCM WAV during import.

MP3 decoding never runs in the Oboe callback. StageGrid does not independently strip musical silence from each stem because intentional rests/count-ins must preserve the common timeline.

## Native audio engine

Implemented:

- one native Oboe stereo output stream;
- one master output-frame playhead shared by every stem;
- streaming decoder threads and preallocated SPSC buffers;
- two decoder banks per track for prepared Loop/section paths;
- active audio continues while the inactive bank prepares a replacement path;
- synchronized all-track handoff after readiness/alignment checks;
- stale/unsafe prepared paths are rejected instead of being applied late;
- play, pause, stop and seek;
- master volume;
- per-track volume, mute, solo, pan and `L / L+R / R` route;
- native sample-clock Click with 1/4, 1/8, 1/8T and 1/16 subdivisions;
- native 1/2-bar section count-in;
- Android/USB stereo output-device selection;
- diagnostics for sample rate, burst size, underruns, callback load and path swaps/misses.

Manual section choices wait for the explicit `endMs` of the **current** section and then enter the requested section at its explicit `startMs`.

## Native Guide

StageGrid does not bundle third-party Guide audio. A user-supplied/licensed sample ZIP is installed locally through Android's document picker.

Supported pack language layouts currently include Spanish, English, French and Portuguese when present in the installed pack.

```text
Original Guide stem
       ↓
offline sample/fingerprint matching
       ↓
recognized structured events
       ├─ SECTION
       ├─ COUNT
       └─ DYNAMIC
       ↓
native-guide-events.json
       ↓
StageGrid Native Guide.wav
       ↓
optional arrangement-aware destination phrase
```

Recognition is sample/template matching, **not general-purpose speech-to-text**.

Implemented Native Guide behavior:

- local cue-pack installation;
- phase-robust fingerprint envelope and adaptive candidate discovery;
- offline recognition with language-aware recovery pass;
- structured event sidecar;
- generated Native Guide WAV on the same shared playback clock;
- original Guide retained muted as a reference after successful reconstruction;
- automatic editable section proposals;
- delayed section recovery when BPM is supplied after import;
- per-song output-language switching without stem reimport/reanalysis;
- in-place reanalysis from the retained original Guide;
- persistent Guide fingerprint cache;
- arrangement-aware relocation of a destination lead-bar phrase containing section/count/dynamic cues when matching samples exist.

## Setlist Live

A selected non-empty setlist can enter Setlist Live mode. Player shows current song, next song, setlist position, Previous/Next and Exit Setlist.

NEXT/PREV stop and unload the previous native song before loading the destination in a stopped state. StageGrid does not auto-play merely because the setlist was advanced.

The next song receives a bounded warm preload into the Android/Linux filesystem cache. This can reduce startup latency but is **not** a second native decoder graph, gapless transition or crossfade.

## Portable backup and restore

Settings can create a self-contained `.stagebackup` through Android SAF. The destination can be a local folder, compatible removable storage, Google Drive when exposed as a DocumentsProvider, or another writable provider.

A backup includes app-private song directories/audio, song/track/mixer metadata, sections, setlists/order, Native Guide sidecars/generated files and the currently installed user-supplied Guide pack.

Every payload file is declared with byte size + SHA-256. Restore stages and validates the complete archive before installing it, rebuilds device-specific app-private paths and preserves unrelated local library records.

Backups are explicit snapshots, not continuous/bidirectional Drive synchronization.

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

Windows build:

```bat
gradlew.bat testDebugUnitTest assembleDebug
```

Debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Keep a local checkout current with:

```bash
git switch main
git pull
```

## Local data and privacy

- No StageGrid account required.
- No analytics SDK.
- No ads.
- No broad storage permission.
- StageGrid does not upload song audio or Guide samples itself.
- Imported songs, session snapshots, Guide packs and fingerprint caches live in app-private storage.
- SAF access is limited to files/folders explicitly selected by the user.
- Drive access is performed by Android/Google Drive's document provider; StageGrid does not receive Drive credentials.

## Tests and CI

GitHub Actions runs `testDebugUnitTest` and `assembleDebug` before release PRs are merged into `main`.

Coverage includes stem classification, WAV parsing, Musical Grid conversion/snapping, manual section-boundary policy, Native Guide recognition/section inference, quiet anti-phase Guide recognition, Click stable-train selection, Guide arrangement timing/sequence selection, setlist navigation and native/JNI compilation.

Physical-device qualification is still required before StageGrid is considered stage-ready.

## Known limitations of 0.2.0-alpha10.1

- Guide recognition remains sample/template matching: a Guide generated from a substantially different voice/sample library can still fail or produce low confidence.
- The new relaxed candidate pass is deliberately conservative; unusual Guides that continuously contain other audio may still need further profiling with a representative failing Guide stem.
- Session recovery is approximately as recent as the latest periodic snapshot and always returns stopped; it is not sample-exact crash continuation.
- Guide reanalysis requires that the retained original Guide audio still exists locally.
- Arrangement-aware Guide relocation derives section/count/dynamic calls from the destination's original lead bar; a complete arbitrary virtual arrangement graph remains planned for 0.5.
- Extremely late live section choices can intentionally omit cues that cannot fit completely before the transition.
- Physical-device/high-track-count stress validation remains required for double-buffered transitions and dynamic Guide phrases.
- Setlist preload warms the OS file cache; it is not gapless dual-engine preload/crossfade.
- Backup/restore remains a manual snapshot workflow.
- USB device selection is implemented, but arbitrary 4/8/custom multichannel routing is not.
- AAC/M4A/FLAC/OGG playback expansion, waveform editing/cache management, tempo/pitch DSP, MIDI, pads, automation and SMPTE/LTC are later milestones.
- Onboarding, final accessibility/large-touch-target polish and beta qualification remain pending.

See [`docs/ROADMAP.md`](docs/ROADMAP.md) and [`docs/STATUS.md`](docs/STATUS.md).

## Release history

### 0.2.0-alpha10.1

**Recognition hardening before beta** — adaptive Guide candidate discovery, phase-robust fingerprints, language-aware recovery matching, wider timing tolerance and stable Click-train grid anchoring.

### 0.2.0-alpha10

**Beta-readiness sprint: session recovery + Guide reanalysis/cache + richer dynamic Guide phrases** — includes planned alpha08/alpha09/alpha10 work in one integration sprint.

### 0.2.0-alpha07

**Library lifecycle + Live Setlist** — safe local song deletion, Setlist Live Previous/Next and bounded next-song OS-cache warming.

### 0.2.0-alpha06

**Portable backup/restore + first arrangement-aware Guide layer** — `.stagebackup`, SHA-256 validation and live destination section-name Guide cue.

### 0.2.0-alpha05.1

**Manual section-boundary transition hotfix** — current section finishes at explicit `endMs`; requested destination enters at explicit `startMs`.

### 0.2.0-alpha05

**Double-buffered live path hardening** — two decoder banks per track, background path preparation, synchronized handoff and stale-path rejection.

### 0.2.0-alpha04.2

**Per-song Guide language + import progress/performance pass**.

### 0.2.0-alpha04.1

**Delayed Native Guide section recovery after BPM becomes available**.

### 0.2.0-alpha04

**Native Guide recognition + automatic editable section proposals**.

### 0.2.0-alpha03

**Native count-in + initial section transition scheduling**.

### 0.2.0-alpha02

**Simplified live UX and routing presets**.

### 0.2.0-alpha01

**Musical Grid + visual Section Editor**.

### 0.1.3

**Native Click + stereo routing**.

### 0.1.2

**Shared-clock local WAV + MP3 normalization MVP**.

## Release documentation policy

Every StageGrid alpha, beta or release updates this README with the current version, new functionality, implemented behavior, known limitations and release history. `docs/ROADMAP.md` describes what comes next; `docs/STATUS.md` defines the exact implementation boundary.

## License

StageGrid-owned source is licensed under MIT. Third-party components retain their own licenses. See [`LICENSE`](LICENSE) and [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
