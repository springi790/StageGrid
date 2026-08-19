# StageGrid technical roadmap

This roadmap intentionally separates real implementation from product intent.

A product-level rule applies to every milestone: **the basic live-performance workflow must be understandable without requiring audio-engineering terminology.** Technical controls remain available, but advanced concepts should be progressively disclosed instead of dominating the default UI.

## 0.1.2 — shared-clock local WAV + MP3 MVP

Delivered in source: local import/library, shared-clock Oboe stereo engine, WAV playback, import-time MP3→PCM normalization, mixer controls, click/guide, imported sections + live path operations, setlists, foreground playback, output-device selection, LIVE/Performance Lock and diagnostics.

## 0.2 — section/workflow hardening + usability

### 0.2.0-alpha01 — musical grid foundation

- deterministic bar/beat musical grid from BPM, time signature and grid offset;
- visual/manual section editor with musical-grid snapping;
- section persistence and player bar/beat readout.

### 0.2.0-alpha02 — simplified live UX

- plain-language Player focused on current section, next section and transport;
- keep **Edit sections** visible as a first-class Player action;
- hide grid offsets, manual routing and other technical controls behind advanced options;
- human-readable click subdivisions;
- quick routing presets for common stereo stage setups;
- friendly track-type names instead of internal enum values;
- simplified section editing based on the playhead, with precise bar/beat editing kept optional;
- clearer visual hierarchy and contextual explanations.

### 0.2.0-alpha03 — quantized sections + count-in

- live section jumps queue to the **next musical bar boundary** when a valid Musical Grid exists;
- fallback to the current section boundary when a valid grid is unavailable;
- selectable section count-in: off, 1 bar or 2 bars;
- sample-clock count-in generated in the native engine rather than with UI timers/coroutines;
- virtual negative-time pre-roll allows count-in even for a section beginning at the start of the song;
- imported stems are gated during count-in and enter together at the target frame;
- count-in choice is persisted in DataStore;
- Player clearly shows the queued next-bar section and active count-in target.

### 0.2.0-alpha04 — native Guide recognition + automatic section proposals

- install a user-supplied/licensed Guide sample ZIP through Android's document picker; sample audio is not bundled in the StageGrid repository or APK;
- recognize supported Guide sample-pack languages (currently ES/EN/FR/PT when present in the installed pack);
- import-time, fully offline template matching of a song's Guide stem against the installed cue samples;
- recognize section, count and dynamic Guide calls without depending on cloud speech recognition;
- persist recognized calls as `native-guide-events.json` beside the imported song;
- render a clean app-generated `StageGrid Native Guide.wav` from recognized cue events and the selected output language;
- retain the original imported Guide as a muted reference when native reconstruction succeeds;
- allow Guide output language to follow recognition automatically or be forced to an installed language;
- when no explicit `song.json` section map exists and BPM/grid data is valid, infer section starts from recognized section calls and snap them to the Musical Grid;
- keep automatically generated sections editable in the existing Section Editor;
- template matching and rendering run at import time, never in the Oboe real-time callback.

### 0.2.0-alpha04.1 — delayed section recovery

- reuse persisted native Guide cue events when BPM/time-signature metadata is supplied after import;
- rebuild automatic section proposals without re-analyzing Guide audio;
- recover already-imported songs when loaded;
- replace only the untouched `Full Song` placeholder so manual section maps are protected.

### 0.2.0-alpha04.2 — per-song Guide language + import visibility/performance

- allow an already-processed song to switch its native Guide between installed ES/EN/FR/PT languages from Player;
- regenerate only the native Guide from persisted cue events; do not re-import stems or re-run recognition;
- persist each song's selected Guide output language in its sidecar;
- stage the regenerated Guide in a temporary file and swap it only while transport/count-in is inactive;
- show import percentage plus current pipeline stage and current file/detail;
- expose MP3 decode progress from MediaCodec timestamps and WAV/local-copy progress from bytes copied;
- cache the installed Guide sample index and Guide template fingerprints in memory;
- pre-warm Guide templates when a pack is installed;
- enlarge buffered local I/O and optimize native Guide rendering for long silent blocks.

### 0.2.0-alpha05 — double-buffered live path hardening

- split each track's existing decoder look-ahead budget into two preallocated SPSC banks;
- keep the active bank feeding Oboe while decoder threads prepare a changed Loop or scheduled-jump path in the inactive bank;
- publish path configurations by bank generation rather than clearing the currently audible buffer;
- align the inactive bank using the monotonic output-frame count accumulated while it was being prepared;
- atomically hand all tracks to the prepared bank from the realtime callback only after every track reports the same ready generation;
- reject a replacement if it misses the conservative point where old and new paths could first diverge;
- expose successful path swaps, missed safe windows and pending-path state through native diagnostics;
- give quantized section taps a 180 ms preparation margin; when the immediate bar line is too close, queue the following bar instead of forcing an unsafe last-millisecond handoff;
- keep generated native Guide audio in the same shared-clock bank handoff as every other stem.

The native Guide event sidecar remains the foundation for the later arrangement-aware Guide engine. Alpha05 keeps the already-rendered Guide audio synchronized through the same timeline path as the stems, but it does not yet synthesize/move a spoken pre-section cue when an arbitrary future ReOrder changes where that cue should occur.

### Remaining 0.2 work

- arrangement-aware relocation/synthesis of native Guide events after live section reorder/path changes;
- re-analyze an already imported song against a newly installed Guide pack without requiring a fresh song import;
- persistent on-disk Guide fingerprint cache/index if physical-device profiling shows first-analysis preparation remains significant;
- setlist live NEXT/PREV song transport and next-song preload;
- persisted/restorable performance session without auto-emitting audio after a crash;
- first-run onboarding for import, Click/Guide, routing and sections;
- additional accessibility/large-touch-target pass for live use;
- physical-device stress validation of double-buffered loops/jumps/count-in/native Guides under high track counts;
- physical-device import profiling to identify the next highest-cost stages after the alpha04.2 optimizations.

## 0.3 — expanded decoder/cache layer

- extend the existing import-time MediaCodec normalization beyond MP3 to AAC/M4A where platform support is suitable;
- evaluate a license-compatible FLAC/OGG path and preserve deterministic PCM caching;
- waveform peak-cache generation outside the audio thread;
- storage-manager accounting and cache eviction.

## 0.4 — multichannel USB routing

- negotiate multichannel stream/channel masks where Android/device HAL exposes them;
- bus model and output matrix;
- stereo, stereo+click/guide, 4-out, 8-out and custom presets;
- safe output test generator;
- reconnect/fallback state machine;
- keep common configurations preset-driven so users do not need to understand channel masks or bus terminology.

## 0.5 — arrangement engine

- virtual arrangement graph independent of WAV files;
- bar-aware loops, finite/infinite loop state, exit-loop-at-boundary;
- live reorder and pre-roll;
- native Guide events attached to arrangement/section nodes rather than a fixed rendered timeline;
- gapless next-song transition/crossfade architecture.

## 0.6 — DSP

- `TempoProcessor` and `PitchShiftProcessor` abstractions;
- production library selection only after license/performance evaluation;
- latency compensation and shared processing clock for every stem.

## 0.7 — MIDI

- Android MIDI USB/BLE discovery;
- MIDI Learn/mapping;
- timeline MIDI cues;
- multi-bus output;
- MIDI Clock tied to transport state.

## 0.8 — pads, automation, timecode

- 12-key PadPlayer;
- volume/pan/mute/bus automation curves;
- dedicated LTC output with explicit main-output safety guard.

## 0.9 — portable projects and remote

- `.stagepack` export/import;
- library backup;
- LAN HOST/REMOTE pairing with PIN and trusted-device list.

## 1.0 qualification gate

Do not call the project stage-ready until the acceptance test, synchronization test, USB routing test, crash/reconnect test and representative 16/32-track device stress matrix all pass on physical hardware.

The 1.0 usability gate also requires that a new user can import a song, start playback, enable/disable Click and Guide, choose a common stereo routing preset, and navigate sections without needing to understand internal audio-engineering terminology.
