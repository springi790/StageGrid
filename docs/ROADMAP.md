# StageGrid technical roadmap

This roadmap separates implemented source from future product intent.

Product rule for every milestone: **the basic live-performance workflow must remain understandable without requiring audio-engineering terminology.**

## Delivered foundation

### 0.1.2 / 0.1.3 — shared-clock MVP

Delivered: local library/import, WAV playback, one-time MP3→PCM normalization, Oboe shared clock, mixer, foreground playback, MediaSession, diagnostics, Native Click and stereo `L / L+R / R` routing.

## 0.2 — section/workflow hardening + usability

### alpha01 → alpha03

Delivered:

- Musical Grid and bar/beat snapping;
- visual Section Editor;
- simplified live Player/Mixer UX;
- routing presets;
- native shared-clock section count-in.

### alpha04 / alpha04.1 / alpha04.2 — Native Guide foundation

Delivered:

- user-installed Guide cue packs;
- ES/EN/FR/PT layouts when present;
- SECTION/COUNT/DYNAMIC structured events;
- generated Native Guide WAV;
- automatic editable sections;
- delayed section recovery when BPM is supplied later;
- per-song Guide output language;
- import percentage/stage reporting and initial performance work.

### alpha05 / alpha05.1 — double-buffered section paths

Delivered:

- two decoder banks per track;
- background Loop/section preparation;
- synchronized all-track handoff;
- stale/late path rejection;
- manual changes wait for the current section's explicit end marker.

### alpha06 — portability + first arrangement-aware Guide layer

Delivered:

- portable `.stagebackup` through Android SAF;
- local/removable/Drive-provider destinations;
- SHA-256 validated restore;
- first arrangement-aware destination Guide cue.

### alpha07 — library lifecycle + Setlist Live

Delivered:

- safe local multitrack deletion;
- Setlist Live Previous/Next;
- stopped destination load;
- bounded next-song filesystem-cache warming.

### alpha08 / alpha09 / alpha10 — beta-readiness integration sprint

Delivered:

- safe versioned performance-session recovery with no autoplay;
- Guide reanalysis without stem reimport;
- persistent Guide energy-fingerprint cache;
- richer arrangement-aware destination phrases containing SECTION/COUNT/DYNAMIC calls.

### alpha10.1 — general recognition hardening

Delivered from physical-device feedback:

- phase-robust Guide energy analysis;
- adaptive candidate discovery;
- wider timing tolerance;
- stable Click-train grid anchoring.

### alpha10.2 — source-language isolation

Delivered after English false-positive feedback:

- bounded source-language probe;
- full matching restricted to the detected language when evidence is strong;
- Spanish recognition path kept stable;
- stricter English ambiguity margin;
- removal of an expensive second full rejected-candidate pass.

### alpha10.3 — English acoustic cue discrimination

Current pre-beta candidate:

- English-only second-stage multi-band acoustic fingerprint;
- combine temporal energy shape with coarse speech-band movement;
- ambiguity penalty for short English SECTION labels such as Vamp/Rap/Tag/Solo;
- anti-collapse guard when one short label dominates a song implausibly;
- persisted candidate-level recognition diagnostics;
- repeated generic Verse numbering by occurrence;
- regression tests where English cues share the same energy shape but have different acoustic content.

**Beta gate:** representative English songs that previously collapsed to `Vamp`/`Rap` must improve without regressing songs whose Spanish Guides already recognize correctly. COUNT polish is not allowed to destabilize SECTION recognition.

## 0.2 beta plan

### 0.2.0-beta01 — usability / field-feedback beta

Planned:

- first-run onboarding for import, Click/Guide, routing, sections, Setlist Live and backup/restore;
- accessibility / large-touch-target / contrast / small-screen checks;
- clearer recoverable errors and progress/cancellation behavior;
- user-facing recognition diagnostic summary/export using the alpha10.3 sidecar diagnostics;
- dedicated COUNT recognition policy and polish;
- fixes from physical-device feedback;
- no major new audio architecture unless testing exposes a blocker.

### 0.2.0-beta02 — qualification / stabilization

Planned:

- representative 16/32+ stem stress tests;
- repeated Loop/manual-section/dynamic-Guide tests;
- process-death/session-recovery tests;
- Setlist Live NEXT/PREV/warm-preload tests;
- real local/Drive-provider backup + restore validation;
- low-storage and forced-failure validation;
- USB stereo reconnect/output-selection validation;
- fix release-blocking beta regressions.

### 0.2.0 stable gate

Do not mark 0.2 stable until the core stereo live workflow, backups, section transitions, Native Guide behavior, Setlist Live and safe session recovery pass the physical-device qualification matrix.

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
