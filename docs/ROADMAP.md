# StageGrid technical roadmap

This roadmap intentionally separates real implementation from product intent.

## 0.1.2 — shared-clock local WAV + MP3 MVP

Delivered in source: local import/library, shared-clock Oboe stereo engine, WAV playback, import-time MP3→PCM normalization, mixer controls, click/guide, imported sections + live path operations, setlists, foreground playback, output-device selection, LIVE/Performance Lock and diagnostics.

## 0.2 — section/workflow hardening

- visual/manual section editor with musical-grid snapping;
- section count-in/pre-roll;
- path-change double buffering so loop/reorder changes cannot starve the callback;
- setlist live NEXT/PREV song transport and next-song preload;
- persisted/restorable performance session without auto-emitting audio after a crash.

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
- reconnect/fallback state machine.

## 0.5 — arrangement engine

- virtual arrangement graph independent of WAV files;
- bar-aware loops, finite/infinite loop state, exit-loop-at-boundary;
- live reorder and pre-roll;
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
