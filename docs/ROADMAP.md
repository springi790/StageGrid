# StageGrid technical roadmap

StageGrid uses an accelerated alpha policy: when practical, a feature version is implemented directly as its final integration alpha. Intermediate alpha tags can be skipped, but validation gates are never skipped.

## Delivered foundation

### 0.1–0.2

Shared-clock native multitrack playback, Room library, import, mixer, Native Click, Musical Grid, sections, Native Guide, live section transitions, Setlist Live, session recovery and portable `.stagebackup`.

### 0.3 — media/cache layer — FEATURE COMPLETE

Delivered as `0.3.0-alpha05`:

- WAV/MP3/M4A/AAC/FLAC/OGG import boundary;
- non-WAV normalization to playback WAV;
- waveform peak cache and UI;
- storage/cache manager.

### 0.4 — multichannel USB routing — FEATURE COMPLETE IN SOURCE

Delivered as `0.4.0-alpha05`:

- 2/4/6/8-channel output negotiation;
- logical stereo-pair output buses;
- routing matrix and presets;
- custom routing;
- output test tone;
- disconnect/fallback/reconnect handling;
- persistent Room/backup routing state.

**Physical USB qualification remains deferred** until representative multichannel hardware is available.

## 0.5 — arrangement engine + Live Workspace — FEATURE COMPLETE IN ALPHA

Delivered as `0.5.0-alpha05` / `versionCode 33`.

### Virtual arrangement

- persistent per-song `arrangement.json` sidecar;
- stable arrangement nodes referencing authored sections;
- live reorder without rewriting WAV files;
- finite repeat and infinite repeat;
- Exit at authored boundary;
- 0/1/2-bar pre-roll on stopped launches;
- existing synchronized section/loop engine remains authoritative;
- existing destination Guide phrase preparation remains available for section transitions.

### Real dual-song preload

- second native engine/deck;
- full next-song WAV reader/decoder-worker preparation;
- mixer/Click/Guide/output state applied before promotion;
- Setlist Live `nextReady` represents a real prepared deck rather than filesystem-cache warming;
- playing handoff uses a bounded master-gain crossfade;
- stopped/paused handoff remains silent;
- fallback to normal safe loading when dual-stream preparation is unavailable.

### Live Workspace UX

- performance-first Player replacement;
- responsive phone and tablet layouts;
- phone bottom sheets for Quick Mix, Arrangement and Setlist;
- persistent tablet side workspace;
- large transport and visible NOW/NEXT/QUEUED states;
- fast arrangement-node selection;
- Quick Mix volume/mute/solo;
- Performance Lock hides administration/Advanced surfaces;
- legacy detailed Player retained as Advanced for section editing, Native Guide and detailed Click/count-in controls.

### 0.5 qualification gate

Without an external interface, validate now:

- phone/tablet responsive layout;
- Play/Pause/Stop/Stop All;
- arrangement reorder persistence;
- finite and infinite repeat;
- Exit at boundary;
- stopped pre-roll;
- Setlist next-song preload;
- crossfade on built-in stereo audio;
- stopped Next remains silent;
- Advanced tools still work;
- arrangement survives app restart and `.stagebackup` round trip;
- ordinary stereo playback remains stable.

When USB hardware is available, add the outstanding 0.4 matrix plus dual-deck behavior through the external interface.

## 0.6 — DSP — FEATURE COMPLETE IN ALPHA

Final integration line: `0.6.0-alpha05.x`.

Delivered:

- production time-stretch/pitch-shift backend using Signalsmith Stretch;
- global per-deck tempo ratio and pitch controls rather than independent track clocks;
- background decoder-worker DSP, keeping heavy processing outside the Oboe callback;
- shared authoritative timeline across all stems;
- latency compensation using the effective Signalsmith input + output latency;
- synchronization-safe DSP reprime on tempo/pitch changes, seeks and path resets;
- generated Click remains locked to the musical timeline while tempo changes;
- Guide voice is protected from pitch transposition;
- DSP active/latency/CPU diagnostics and existing underrun diagnostics;
- manual Guide source mode with Original / Cue Auto selection;
- optional per-section spoken count, e.g. `Intro · 2 · 3 · 4`, sourced directly from installed Guide Pack samples without requiring Native Guide Beta.

Core on-device musical validation has confirmed working tempo changes, pitch changes and corrected synchronization. Long-duration thermal/load qualification and representative external-interface qualification remain part of the broader 1.0 acceptance gate.

## 0.7 — MIDI — NEXT

Planned complete milestone scope:

- Android MIDI USB/BLE discovery;
- stable device/port identity and reconnect handling;
- MIDI Learn/mapping for StageGrid actions;
- persistent per-device mappings;
- timeline MIDI cues tied to authored sections/arrangement state;
- MIDI Clock tied to the authoritative transport and effective tempo;
- Start/Continue/Stop transport messages where configured;
- device-loss safety so local audio transport remains authoritative.

## 0.8 — pads, automation, timecode

- pad player;
- volume/pan/mute/bus automation;
- automation tied to arrangement/timeline state;
- SMPTE/LTC output with strict routing safety.

## 0.9 — portable projects + remote

- `.stagepack` project interchange;
- richer portable project workflows;
- LAN HOST/REMOTE pairing;
- tablet/phone remote workspace;
- local engine remains authoritative through network loss.

## 1.0 qualification gate

1.0 requires physical acceptance for synchronization, high-track-count load, prolonged live use, USB routing/reconnect, dual-deck transitions, DSP, MIDI, automation/timecode, process recovery and project portability. CI success alone is never stage qualification.
