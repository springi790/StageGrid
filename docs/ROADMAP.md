# StageGrid technical roadmap

This roadmap separates implemented source from future product intent.

Product rule for every milestone: **the basic live-performance workflow must remain understandable without requiring audio-engineering terminology.**

## Delivered foundation

### 0.1.2 / 0.1.3 — shared-clock MVP

Delivered: local library/import, WAV playback, one-time MP3→PCM normalization, Oboe shared clock, mixer, foreground playback, MediaSession, diagnostics, Native Click and stereo `L / L+R / R` routing.

## 0.2 — section/workflow hardening + usability

The 0.2 development line delivered the Musical Grid, simplified live UX, native count-in, Native Guide recognition/reconstruction, automatic editable sections, double-buffered section paths, `.stagebackup`, Setlist Live, safe session recovery, Guide reanalysis/cache, arrangement-aware destination Guide phrases and recognition hardening through `0.2.0-alpha10.2`.

The previous beta qualification plan remains relevant even though active feature development has moved to 0.3. A 0.3 development build must not be interpreted as retroactively marking 0.2 hardware qualification complete.

Outstanding 0.2-style qualification work still includes representative physical-device/high-track-count stress, repeated transitions, process-death/session recovery, local/Drive backup/restore, low-storage/failure paths and USB stereo reconnect validation.

## 0.3 — expanded decoder/cache layer

### 0.3.0-alpha01 — Android compressed-audio import foundation — DELIVERED

Implemented:

- explicit import-format policy for WAV, MP3, M4A, AAC, FLAC and OGG;
- WAV remains the direct native playback/import path;
- MP3, M4A and AAC are normalized once during import through Android `MediaExtractor` / `MediaCodec`;
- compressed sources are written as 16-bit PCM RIFF/WAV before the realtime Oboe engine sees them;
- Android encoder delay/padding metadata is trimmed when exposed by the platform;
- MP3/M4A/AAC share one `PlatformAudioToWavDecoder` implementation;
- the old `Mp3ToWavDecoder` API remains as a compatibility facade;
- FLAC/OGG remain discoverable but explicitly non-playable until their production path is selected;
- import progress text is format-neutral while retaining the previous internal enum identifier for source compatibility;
- JVM coverage protects case-insensitive format recognition and the playable/planned boundary.

Release: `0.3.0-alpha01` / `versionCode 19`.

### 0.3.0-alpha02 — FLAC / OGG path

Planned:

- evaluate Android-platform decoding versus a bundled license-compatible decoder path;
- prefer the smallest maintainable implementation that preserves deterministic offline normalization;
- verify common FLAC PCM depths/sample rates and common OGG/Vorbis encodes;
- do not add codec work to the realtime callback;
- keep clear per-file recoverable errors when a device cannot decode a source.

### 0.3.0-alpha03 — waveform peak cache

Planned:

- generate compact waveform peak data outside the audio thread;
- use deterministic cache identity tied to the normalized local playback file;
- support regeneration after cache loss/corruption without touching the imported source;
- bound memory use for long songs/high stem counts;
- initial cache format should be versioned for future evolution.

### 0.3.0-alpha04 — waveform UI

Planned:

- render the cached waveform in Player and/or Section Editor without decoding the full source on the UI thread;
- synchronized playhead tied to the shared transport clock;
- section boundaries/selection remain readable on small screens;
- waveform display must remain optional rather than making the basic live workflow more complex.

### 0.3.0-alpha05 — storage accounting / cache manager

Planned:

- storage totals for imported playback audio, generated Guide material, waveform caches and other regenerable data;
- per-song storage visibility where useful;
- safe deletion of regenerable caches without removing the actual local song;
- cache eviction policy that never silently removes essential playback audio;
- low-storage handling and recovery tests.

### 0.3 beta / stabilization gate

Before treating the expanded decoder/cache layer as stable:

- verify representative MP3/M4A/AAC imports on physical Android devices/OEMs;
- validate timing/alignment across compressed stems and platform gapless metadata behavior;
- validate FLAC/OGG once introduced;
- stress waveform generation with long songs and 16/32+ stems;
- validate cache corruption/regeneration and low-storage behavior;
- rerun the existing live transition/Guide/backup/session qualification matrix to catch regressions.

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
