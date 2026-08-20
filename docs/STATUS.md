# Implementation status — 0.4.0-alpha05

This file describes implemented source, not hardware qualification claims.

## Release metadata

- Version: `0.4.0-alpha05`
- `versionCode`: `28`
- Development branch: `feature/0.4.0-alpha05`
- Alpha05 intentionally integrates the complete feature scope originally planned across the 0.4 alpha sequence.

## Implemented in 0.4

### Output-device capability and negotiation

- `AudioDeviceManager.OutputDevice` exposes advertised channel counts and a StageGrid preferred even channel count of 2/4/6/8.
- USB devices and higher-channel devices are prioritized in the output list.
- `NativeAudioEngine` requests the preferred count and attempts progressively lower even channel counts down to stereo when the requested stream cannot be opened.
- Exclusive and Shared Oboe modes are attempted.
- requested and actual opened channel counts are independent diagnostic values.
- `multichannelFallback` is exposed when Android/AAudio opens fewer channels than requested.

### Realtime output matrix

- The Oboe callback supports up to eight interleaved output channels.
- Per-frame mixing uses a fixed `std::array<float, 8>`; the change does not add file I/O or dynamic heap allocation to the steady-state callback.
- Tracks retain the existing `BOTH / LEFT / RIGHT` route semantics inside a selected stereo-pair bus.
- Buses are `1/2`, `3/4`, `5/6`, `7/8`.
- `BOTH` preserves stereo/pan inside the pair.
- `LEFT` and `RIGHT` downmix the source to mono and place it on the corresponding physical output inside the pair.
- If a stored bus does not exist on the currently negotiated stream, it safely folds to bus `1/2` rather than becoming silent.
- master gain is applied consistently across every negotiated output channel.

### Persistent routing

- `TrackEntity.outputBus` stores the per-track bus.
- Room schema is version 3.
- migration `2→3` adds `outputBus INTEGER NOT NULL DEFAULT 0`, preserving existing libraries on outputs `1/2`.
- generated Native Click has a separate `clickBus` stored in DataStore.
- Native Guide tracks use the same persistent bus/route model as other tracks.
- dynamically generated arrangement Guide phrases inherit the active Guide track bus and route.

### Mixer UX

- Existing all-stereo and two-channel stage-split workflows remain available.
- 4-out preset: Tracks `1/2`, Click `3`, Guide `4`.
- 6-out preset: Main tracks `1/2`, vocals `3/4`, Click `5`, Guide `6`.
- 8-out preset: rhythm `1/2`, instruments `3/4`, vocals/other groups `5/6`, Click `7`, Guide `8`.
- presets are disabled when the negotiated stream lacks the required channel count.
- advanced routing exposes only buses available on the actual opened stream.
- every playable track can choose bus plus `L / L+R / R` route.
- Native Click exposes an independent custom bus and route.

### Output testing and diagnostics

- Settings shows advertised device capability, requested channels, actual opened channels and sample rate.
- Settings exposes a per-output test action for the actual opened channel count.
- native test signal is a bounded short 880 Hz tone at low internal amplitude and is routed only to the requested physical channel.
- invalid channel requests are rejected instead of wrapping to another channel.
- output tests can run with no song loaded.

### Disconnect / reconnect behavior

- when the selected output disappears, StageGrid safely opens the Android default stereo output.
- playback is not automatically restarted after a live device-loss event.
- stored buses outside the fallback channel count fold to `1/2` so tracks are not silently lost.
- the preferred interface is retained in memory and restored when it returns.
- restoration first uses the Android device ID and additionally performs best-effort matching by product name/type when Android assigns a new ID after reconnect.

### Backup compatibility

- `.stagebackup` remains format version 1.
- new backups add optional `outputBus` to each track record.
- 0.4 restores the saved bus when present.
- pre-0.4 backups without that field restore to bus `1/2`.
- existing SHA-256 payload validation and staged rollback behavior are unchanged.

## Inherited 0.3 implementation

0.4 retains the 0.3 decoder/waveform/storage layer:

- WAV/MP3/M4A/AAC/FLAC/OGG import policy;
- import-time non-WAV normalization outside realtime;
- versioned waveform peak cache;
- Player/Section Editor waveform UI;
- storage accounting and regenerable-cache cleanup.

It also retains the 0.2 live foundation: shared transport clock, double-buffered section/Loop paths, Native Click/Guide, Musical Grid, editable sections, Setlist Live, stopped session recovery and `.stagebackup`.

## Validation already executed during 0.4 implementation

- pure Kotlin compilation/execution of `OutputBus` policy — PASS;
- verified 2/4/6/8 bus availability mapping;
- verified unknown stored bus codes fall back to `1/2`;
- verified UI physical labels for the last bus resolve to outputs `7/8`;
- GitHub workflow configuration was inspected and still triggers `testDebugUnitTest` + `assembleDebug` on `feature/**` pushes with the stable debug signing key.

The connector currently does not expose feature-push Actions run status as a check result, so this document does **not** claim that the latest full Android build has completed successfully unless a workflow result is observed separately.

## Physical Android validation still required

- real 4/8-channel USB interface negotiation;
- actual channel order versus manufacturer labeling;
- each physical output-test button;
- routing isolation/crosstalk between buses;
- 4/6/8 presets and custom routing;
- reconnect with the same and a newly assigned Android device ID;
- long/high-track-count multichannel playback and underrun load;
- section jumps/Loops/Native Guide while multichannel is active;
- Room v2→v3 migration on an existing installed library;
- `.stagebackup` old-format compatibility and 0.4 bus round trip.

## Deliberately not implemented yet

- virtual arrangement graph / true dual-song gapless crossfade — 0.5;
- tempo/time-stretch and pitch DSP — 0.6;
- MIDI USB/BLE and MIDI Clock — 0.7;
- pads, automation and SMPTE/LTC — 0.8;
- `.stagepack`, LAN remote and final tablet workspace — 0.9.

## Qualification status

`0.4.0-alpha05` is a development alpha. Successful compilation is necessary but does not establish multichannel hardware compatibility or stage readiness; Android HAL/interface behavior must be tested on physical equipment.
