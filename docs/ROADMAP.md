# StageGrid technical roadmap

This roadmap separates implemented source from future product intent.

Product rule for every milestone: **the basic live-performance workflow must remain understandable without requiring audio-engineering terminology.**

## Accelerated development policy

To reduce project overhead, future feature versions may collapse several internal alpha steps into one **final integration alpha**. The integration build must still implement the complete milestone scope and document the physical-device tests that remain. Skipping intermediate alpha tags never means skipping validation gates.

## Delivered foundation

### 0.1.2 / 0.1.3 — shared-clock MVP

Delivered: local library/import, WAV playback, one-time MP3→PCM normalization, Oboe shared clock, mixer, foreground playback, MediaSession, diagnostics, Native Click and stereo `L / L+R / R` routing.

### 0.2 — section/workflow hardening + usability

Delivered through `0.2.0-alpha10.2`:

- Musical Grid and visual Section Editor;
- simplified live UX and routing presets;
- native count-in;
- Native Guide recognition/reconstruction;
- automatic editable sections;
- double-buffered live section/Loop paths;
- portable `.stagebackup`;
- Setlist Live;
- safe stopped session recovery;
- Guide fingerprint cache/reanalysis;
- arrangement-aware destination Guide phrases;
- recognition and Click-grid hardening.

The historical 0.2 hardware qualification matrix remains relevant even though feature development has moved forward.

## 0.3 — expanded decoder/cache layer — FEATURE COMPLETE IN ALPHA

### 0.3.0-alpha01 — decoder/import foundation — DELIVERED

- centralized import format policy;
- shared Android `MediaExtractor` / `MediaCodec` normalizer;
- MP3/M4A/AAC → PCM16 WAV;
- encoder delay/padding trimming when exposed;
- format-policy regression coverage.

### 0.3.0-alpha02–alpha05 — collapsed integration sprint — DELIVERED AS `0.3.0-alpha05`

Alpha05 integrates the remaining planned 0.3 scope:

- FLAC and OGG use the same import-time Android platform normalization boundary when a compatible device decoder is available;
- all supported non-WAV inputs normalize before the realtime engine sees them;
- versioned `WaveformPeakCache` with source-signature invalidation;
- absolute-timeline peak mapping for different-duration stems;
- lazy cache generation outside the realtime callback;
- Player waveform with shared-clock playhead, section markers and tap-to-seek;
- Section Editor waveform reference;
- storage accounting for library/audio/cache/Guide data;
- safe cache cleanup restricted to regenerable song cache directories;
- JVM waveform regression tests for timeline mapping, regeneration and eviction safety.

Release: `0.3.0-alpha05` / `versionCode 23`.

### 0.3 qualification gate

Before treating 0.3 as stable, test on physical Android hardware:

- MP3/M4A/AAC/FLAC/OGG across representative codec/container variants;
- compressed-stem timing/alignment and gapless metadata;
- long 16/32/64-track waveform generation;
- cache cleanup/regeneration and low-storage failures;
- Player waveform seek and small-screen behavior;
- inherited live transitions, Native Guide, Setlist Live, backup/restore, process-death recovery and USB stereo behavior.

No further feature alpha is planned for 0.3 unless testing reveals a release-blocking defect.

## 0.4 — multichannel USB routing — NEXT FEATURE VERSION

Complete milestone scope:

- negotiate multichannel Oboe streams/channel masks where Android/device HAL permits;
- introduce a logical bus model independent of physical channel number;
- output matrix from buses/tracks to hardware channels;
- preset-driven stereo, stereo + Click/Guide, 4-out and 8-out configurations;
- custom routing for advanced users;
- safe output test generator;
- reconnect/fallback state machine when the selected interface disappears or returns;
- preserve a simple musician-facing preset workflow so users do not need to understand Android channel masks.

Qualification focus: common USB interfaces, reconnect behavior, device-specific channel ordering and high-track-count load.

## 0.5 — full arrangement engine

Complete milestone scope:

- virtual arrangement graph independent of fixed WAV order;
- bar-aware finite/infinite loops and exit-loop-at-boundary;
- live reorder and pre-roll;
- Guide events attached to arrangement nodes;
- full count/dynamic relocation across arbitrary virtual paths;
- true dual-song preload;
- gapless handoff and crossfade architecture.

Qualification focus: repeated live edits, loops/exits, transition determinism, memory pressure and dual-song loading.

## 0.6 — DSP

Complete milestone scope:

- tempo/time-stretch processor abstraction;
- pitch-shift processor abstraction;
- production library selection based on license/performance/device support;
- latency compensation across the shared stem clock;
- bypass/failure behavior that cannot desynchronize stems.

Qualification focus: CPU/thermal load, latency, audio quality and synchronization under DSP changes.

## 0.7 — MIDI

Complete milestone scope:

- Android MIDI USB/BLE discovery;
- MIDI Learn/mapping;
- timeline MIDI cues;
- MIDI Clock tied to authoritative transport state;
- multi-bus MIDI output where appropriate;
- safe reconnect/device-loss behavior.

Qualification focus: controller compatibility, duplicate events, reconnect and transport-clock stability.

## 0.8 — pads, automation, timecode

Complete milestone scope:

- pad player;
- volume/pan/mute/bus automation;
- automation tied to the shared timeline/arrangement model;
- SMPTE/LTC output;
- explicit main-output safety guard so timecode cannot accidentally feed audience audio outputs.

Qualification focus: automation determinism, pad concurrency and timecode routing safety.

## 0.9 — portable projects + remote

Complete milestone scope:

- `.stagepack` project interchange beyond disaster-recovery `.stagebackup`;
- richer backup history/provider workflows where field use justifies them;
- LAN HOST/REMOTE pairing;
- trusted-device controls;
- tablet-oriented split workspace where useful;
- local-first behavior remains authoritative when remote connectivity disappears.

Qualification focus: project portability, pairing security, disconnect recovery and tablet/phone interoperability.

## 1.0 qualification gate

StageGrid is not stage-ready merely because CI builds. A 1.0 designation requires physical-hardware acceptance for synchronization, high-track-count load, USB routing/reconnect, process death, backup recovery, prolonged live use and the complete 0.4–0.9 feature set that remains enabled in the release candidate.

The usability gate also requires that a new user can import a song, play it, operate Click/Guide, choose common output routing, navigate sections/setlists and create/restore a backup without understanding the internal audio-engineering model.
