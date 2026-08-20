# StageGrid technical roadmap

This roadmap separates implemented source from future product intent.

Product rule for every milestone: **the basic live-performance workflow must remain understandable without requiring audio-engineering terminology.**

## Delivered foundation

### 0.1.2 / 0.1.3 — shared-clock MVP

Delivered: local library/import, WAV playback, one-time MP3→PCM normalization, Oboe shared clock, mixer, foreground playback, MediaSession, diagnostics, Native Click and stereo `L / L+R / R` routing.

## 0.2 — section/workflow hardening + usability

### 0.2.0-alpha01 — Musical Grid

- BPM/time-signature/grid-offset model;
- milliseconds ↔ bar/beat conversion and snapping;
- visual/manual Section Editor.

### 0.2.0-alpha02 — simplified live UX

- musician-friendly Player/Mixer terminology;
- visible Edit Sections action;
- routing presets;
- advanced technical options progressively disclosed.

### 0.2.0-alpha03 — native count-in

- shared-clock 1/2-bar count-in;
- synchronized stem entry;
- persisted count-in preference.

### 0.2.0-alpha04 / alpha04.1 / alpha04.2 — Native Guide foundation

Delivered:

- user-installed local Guide sample packs;
- ES/EN/FR/PT layouts when present;
- offline sample/fingerprint recognition;
- SECTION/COUNT/DYNAMIC structured events in `native-guide-events.json`;
- generated `StageGrid Native Guide.wav`;
- automatic editable section proposals;
- delayed section recovery when BPM is entered later;
- per-song Guide language switching without stem reimport;
- import percentage/stage reporting and first recognition-performance optimizations.

### 0.2.0-alpha05 / alpha05.1 — double-buffered section paths

Delivered:

- two decoder banks per track;
- background Loop/section-jump preparation;
- synchronized all-track realtime handoff;
- stale/late path rejection and diagnostics;
- manual destination choices wait for the current authored section `endMs` and enter at destination `startMs`.

### 0.2.0-alpha06 — portability + first arrangement-aware Guide cue

Delivered:

- self-contained `.stagebackup` through Android SAF;
- local/removable/Drive-provider destinations;
- portable song/setlist/Guide state with byte-size + SHA-256 validation;
- staged restore and path reconstruction;
- first arrangement-aware replacement of the selected destination section-name cue.

### 0.2.0-alpha07 — library lifecycle + Setlist Live

Delivered:

- safe local multitrack deletion with confirmation and staged file rollback;
- Setlist Live current/next context and Previous/Next navigation;
- destination songs load stopped;
- bounded next-song filesystem-cache warming;
- no hidden gapless/crossfade claim.

### 0.2.0-alpha08 — safe performance-session recovery

Delivered as part of the alpha10 integration sprint:

- versioned app-private performance-session snapshots;
- temp/write/fsync/replace persistence;
- loaded song and approximate position recovery;
- Click/Guide, subdivision/route, count-in and master context recovery;
- Setlist Live context recovery when the referenced setlist/song still exists;
- missing/deleted references are discarded safely;
- **recovered sessions always load stopped and never auto-play**.

### 0.2.0-alpha09 — Guide reanalysis + persistent fingerprint cache

Delivered as part of the alpha10 integration sprint:

- persist installed Guide sample fingerprints on disk;
- invalidate/rebuild the cache when the installed/restored pack signature changes;
- reanalyze a song from its retained original Guide track without reimporting/reconverting stems;
- regenerate the Native Guide with visible progress;
- create a Native Guide track for an older import when capacity allows;
- refresh an untouched automatic section map;
- preserve manually renamed/resized/reordered/recolored sections.

### 0.2.0-alpha10 — richer arrangement-aware Guide phrase

Delivered:

- inspect the destination section's originally recognized lead bar;
- relocate matching SECTION, COUNT and DYNAMIC calls for a manual live section choice;
- prefer the song's selected output language and fall back to the detected language where necessary;
- load/resample cue samples outside the realtime thread;
- compose one immutable short PCM phrase before publishing it to the native callback;
- suppress the fixed rendered Guide through the replacement phrase window;
- skip a cue that cannot fit completely before a late transition instead of cutting speech.

Alpha10 remains an event-aware linear-section layer. The **full arbitrary arrangement graph** is deliberately reserved for 0.5.

### 0.2.0-alpha10.1 — recognition hardening before beta

Delivered from physical-device feedback:

- phase-robust Guide fingerprint envelope that accumulates per-channel energy before averaging;
- persistent fingerprint-cache format v2 so previous downmix-derived fingerprints are rebuilt automatically;
- dual strong/relaxed Guide activity thresholds so a loud cue does not hide quieter calls;
- local-onset candidate recovery for compressed Guides that do not return fully to silence between calls;
- wider template timing search tolerance;
- conservative language-aware second matching pass after the source language can be inferred;
- semantic confidence margin ignores the same canonical cue duplicated in another language;
- Click-grid analysis prefers the beginning of a stable periodic pulse train instead of blindly accepting one isolated early transient;
- regression tests for quiet anti-phase Guide audio and an isolated spike before a valid Click train.

This is the final planned recognition-specific alpha before beta unless representative failing Guide stems expose another release-blocking matcher defect.

## 0.2 beta plan

### 0.2.0-beta01 — usability / field-feedback beta

Primary work after alpha10.1 feedback:

- first-run onboarding for import, Click/Guide, common routing, sections, Setlist Live and backup/restore;
- accessibility pass: large touch targets, labels/content descriptions, contrast/focus and small-screen layout checks;
- clearer recoverable error states and progress/cancellation policy where cancellation is safe;
- polish around session recovery, Guide reanalysis and Setlist Live based on physical-device feedback;
- no major new audio architecture unless testing exposes a blocker.

### 0.2.0-beta02 — qualification / stabilization

- representative physical-device 16/32+ stem stress tests;
- repeated Loop/manual-section/dynamic-Guide tests;
- process-death/session-recovery tests;
- Setlist Live NEXT/PREV/warm-preload tests;
- real local/Drive-provider backup + restore validation;
- low-storage and forced-failure validation for import, backup, restore and deletion;
- USB stereo reconnect/output-selection validation;
- fix all release-blocking regressions found by beta feedback.

### 0.2.0 stable gate

Do not mark 0.2 stable until the core stereo live workflow, backups, section transitions, Native Guide behavior, Setlist Live and safe session recovery pass the beta qualification matrix on physical Android hardware.

## 0.3 — expanded decoder/cache layer

Planned:

- AAC/M4A import-time normalization where Android platform support is suitable;
- evaluate license-compatible FLAC/OGG paths;
- waveform peak-cache generation outside the audio thread;
- storage accounting and cache management/eviction.

## 0.4 — multichannel USB routing

Planned:

- negotiate multichannel streams/channel masks where Android/device HAL permits;
- bus model and output matrix;
- stereo, stereo+Click/Guide, 4-out, 8-out and custom presets;
- safe output test generator;
- reconnect/fallback state machine.

Common configurations should remain preset-driven so users do not need to understand channel masks.

## 0.5 — full arrangement engine

Planned:

- virtual arrangement graph independent of fixed WAV order;
- bar-aware finite/infinite loops and exit-loop-at-boundary;
- live reorder and pre-roll;
- Guide events attached to arrangement nodes instead of only the original linear timeline;
- full count/dynamic relocation across arbitrary virtual paths;
- true dual-song preload, gapless handoff and crossfade architecture.

## 0.6 — DSP

Planned:

- tempo/time-stretch processor abstraction;
- pitch-shift processor abstraction;
- production library choice after license/performance evaluation;
- latency compensation across the shared stem clock.

## 0.7 — MIDI

Planned:

- Android MIDI USB/BLE discovery;
- MIDI Learn/mapping;
- timeline MIDI cues;
- MIDI Clock tied to transport state;
- multi-bus MIDI output where appropriate.

## 0.8 — pads, automation, timecode

Planned:

- pad player;
- volume/pan/mute/bus automation;
- SMPTE/LTC output with explicit main-output safety guard.

## 0.9 — portable projects + remote

Planned:

- `.stagepack` project interchange beyond disaster-recovery `.stagebackup`;
- richer backup history/provider workflows if field use justifies them;
- LAN HOST/REMOTE pairing and trusted-device controls;
- tablet-oriented split workspace where useful.

## 1.0 qualification gate

StageGrid is not stage-ready merely because CI builds. A 1.0 designation requires physical-hardware acceptance for synchronization, high-track-count load, USB routing/reconnect, crashes/process death, backup recovery and prolonged live use.

The usability gate also requires that a new user can import a song, play it, operate Click/Guide, choose a common route, navigate sections/setlists and create/restore a backup without understanding the internal audio-engineering model.
