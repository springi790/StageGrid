# StageGrid technical roadmap

This roadmap separates implemented source from future product intent.

Product rule for every milestone: **the basic live-performance workflow must remain understandable without requiring audio-engineering terminology.**

## Accelerated development policy

To reduce project overhead, feature versions may collapse several internal alpha steps into one **final integration alpha**. The integration build still implements the milestone scope and documents the physical-device validation that remains. Skipping intermediate tags never means skipping qualification gates.

## Delivered foundation

### 0.1 — shared-clock MVP

Delivered: local library/import, WAV playback, compressed-source normalization foundation, one Oboe transport clock, mixer, foreground playback, MediaSession, diagnostics, Native Click and stereo `L / L+R / R` routing.

### 0.2 — live workflow / sections / Native Guide

Delivered through `0.2.0-alpha10.2`: Musical Grid, editable sections, native count-in, Native Guide recognition/reconstruction/reanalysis, double-buffered section/Loop paths, `.stagebackup`, Setlist Live, stopped session recovery and recognition hardening.

### 0.3 — decoder / waveform / storage layer — DELIVERED AS `0.3.0-alpha05`

Delivered:

- WAV/MP3/M4A/AAC/FLAC/OGG import policy;
- non-WAV normalization through Android `MediaExtractor` / `MediaCodec` before realtime playback;
- versioned absolute-timeline waveform peak cache;
- Player and Section Editor waveform UI;
- storage accounting and safe regenerable-cache cleanup.

Release: `0.3.0-alpha05` / `versionCode 23`.

## 0.4 — multichannel USB routing — FEATURE COMPLETE IN ALPHA

### 0.4.0-alpha01–alpha05 — collapsed integration sprint — DELIVERED AS `0.4.0-alpha05`

Implemented source scope:

- USB/output devices expose advertised channel capability and StageGrid's preferred 2/4/6/8-channel request;
- Oboe output negotiation attempts the requested even channel count and safely falls back through lower counts to stereo;
- actual and requested channel counts are exposed separately in diagnostics;
- one realtime output matrix supports up to eight physical channels without file I/O or heap allocation in the audio callback;
- persistent stereo-pair buses: `1/2`, `3/4`, `5/6`, `7/8`;
- existing `L / L+R / R` routing remains the route **inside** the selected pair, so mono and stereo assignments share one model;
- unavailable buses fold safely to `1/2` rather than silently muting a track;
- per-track bus assignment persists through Room schema v3;
- generated Native Click has its own persistent output bus in DataStore;
- Native Guide and dynamic arrangement Guide cues follow the Guide track's bus + route;
- Mixer provides stereo, 4-out, 6-out and 8-out presets plus custom per-track bus/side routing;
- Settings displays device capability, negotiated stream channels and fallback state;
- a bounded low-level per-channel output-test tone is available from Settings;
- selected-interface loss falls back to Android stereo without automatically resuming playback;
- preferred USB interface is restored when it returns, including best-effort matching when Android assigns a new device ID;
- `.stagebackup` keeps the existing v1 container while optionally storing `outputBus`; older backups restore to bus `1/2` and new backups preserve 0.4 routing.

Release: `0.4.0-alpha05` / `versionCode 28`.

### 0.4 qualification gate

Physical hardware still decides what Android/AAudio actually exposes. Before treating 0.4 as stable, verify:

- at least one true 4-output and one true 8-output USB interface where available;
- reported versus negotiated channel counts and physical channel order;
- every output with the low-level test tone at a safe monitor/interface level;
- 4-out and 8-out presets plus custom bus routing;
- routing persistence after song reload/app restart and `.stagebackup` restore;
- interface unplug/replug while stopped and while playing;
- safe stereo fallback and manual Play requirement after a live disconnect;
- high-track-count playback/section transitions while multichannel output is active;
- underrun/callback-load behavior compared with stereo.

No further feature alpha is planned for 0.4 unless physical testing exposes a release-blocking defect.

## 0.5 — full arrangement engine — NEXT FEATURE VERSION

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

- tempo/time-stretch processor abstraction;
- pitch-shift processor abstraction;
- production library selection based on license/performance/device support;
- latency compensation across the shared stem clock;
- bypass/failure behavior that cannot desynchronize stems.

## 0.7 — MIDI

- Android MIDI USB/BLE discovery;
- MIDI Learn/mapping;
- timeline MIDI cues;
- MIDI Clock tied to authoritative transport state;
- safe reconnect/device-loss behavior.

## 0.8 — pads, automation, timecode

- pad player;
- volume/pan/mute/bus automation;
- automation tied to the shared timeline/arrangement model;
- SMPTE/LTC output with an explicit main-output safety guard.

## 0.9 — portable projects + remote

- `.stagepack` project interchange beyond disaster-recovery `.stagebackup`;
- richer backup/provider workflows where field use justifies them;
- LAN HOST/REMOTE pairing and trusted-device controls;
- tablet-oriented split workspace;
- local-first authority when remote connectivity disappears.

## 1.0 qualification gate

CI success alone is not stage qualification. A 1.0 designation requires representative physical-hardware acceptance for synchronization, prolonged/high-track-count load, USB routing/reconnect, process death, backup recovery and every enabled 0.4–0.9 subsystem.
