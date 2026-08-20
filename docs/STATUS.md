# Implementation status — 0.3.0-alpha01

This document describes what exists in source. It intentionally does not promote planned work to implemented status.

## Implemented in 0.3.0-alpha01

### Expanded import format policy

- Import discovery recognizes WAV, MP3, M4A, AAC, FLAC and OGG extensions case-insensitively.
- WAV, MP3, M4A and AAC are currently marked playable.
- FLAC and OGG are deliberately detectable-but-not-playable so the user receives a clear warning rather than silent omission.
- The format policy lives in `ImportAudioFormat` and has JVM regression coverage.

### Shared Android compressed-audio normalizer

- `PlatformAudioToWavDecoder` uses Android `MediaExtractor` + `MediaCodec` only during import.
- The decoder chooses the first audio track exposed by the selected source/container.
- MP3, M4A and AAC use this shared path and normalize to 16-bit PCM RIFF/WAV.
- Decoder output supports Android PCM16 directly and converts Android PCM-float output to PCM16.
- Output sample rate is validated from 8 kHz through 384 kHz.
- Output channel count is validated from 1 through 8 channels for the import cache.
- Classic RIFF data-size bounds are enforced; oversized decoded output fails instead of writing an invalid WAV.
- Android `encoder-delay` / `encoder-padding` metadata is applied when available.
- Decode progress is based on presentation time when the source reports a duration.
- Temporary PCM and partial destination files are cleaned up on failure.
- Codec/extractor release is guarded during failure cleanup.
- `Mp3ToWavDecoder` remains as a compatibility facade over the new shared decoder.

### SongImporter integration

- WAV sources copy directly into the app-private playback library and are parsed normally.
- MP3/M4A/AAC sources are normalized before their `TrackEntity` is created.
- Track names/classification continue to use the original source name, while playback paths point at the normalized local WAV.
- Click and Guide reference analysis operates on the normalized WAV, preserving the existing downstream analyzers.
- Import warnings summarize normalized compressed formats/counts.
- Import warnings summarize detected-but-not-playable formats/counts.
- Gapless trim warnings now apply generically to compressed sources instead of only MP3.
- The playable import limit remains 64 tracks.
- Folder import uses the shared detected-extension policy.

### Release metadata / UX copy

- App version is `0.3.0-alpha01` with `versionCode 19`.
- Import progress copy now describes compressed-audio conversion rather than MP3 specifically.
- The old `DECODING_MP3` enum identifier is retained internally for source/UI compatibility in this alpha even though it now represents MP3/M4A/AAC normalization.

## Inherited implementation from the 0.2 line

### Library / lifecycle

- Room library with Song, Track, Section, Setlist and SetlistSong records.
- ZIP, folder and multi-file import through Android Storage Access Framework.
- Linked document-provider folder browsing, including Google Drive when exposed by Android.
- Import percentage, stage and current-file detail.
- Post-import metadata editing.
- Safe local multitrack deletion with confirmation and staged rollback behavior.

### Portable backup / restore

- Manual `.stagebackup` creation through Android SAF.
- Portable song/track/section/setlist/Guide state.
- Byte-size + SHA-256 payload validation.
- Staged restore and device-specific path reconstruction.
- Installed user-supplied Guide pack included in backup snapshots.

### Shared-clock audio / live path engine

- One Oboe stereo stream and one master transport clock.
- Streaming decoder threads with preallocated SPSC buffers.
- Two decoder banks per track for prepared Loop/section path changes.
- Synchronized all-track handoff after readiness/alignment checks.
- Stale/late prepared swaps are rejected and exposed through diagnostics.
- Per-track volume/mute/solo/pan and `L / L+R / R` routing.
- Native sample-clock Click with subdivisions and routing.
- Native 1/2-bar count-in.
- Basic Android/USB stereo output-device selection.

### Musical Grid / sections

- BPM/time-signature/grid-offset Musical Grid.
- Bar/beat display and snap utilities.
- Visual/manual Section Editor.
- Automatic Guide-derived section proposals.
- Section Loop / Exit Loop.
- Manual section choices wait for the current section's explicit `endMs` and enter the destination at its explicit `startMs`.

### Native Guide

- User-installed local cue packs; StageGrid bundles no third-party Guide audio.
- ES/EN/FR/PT layouts when present.
- Offline sample/template matching.
- Structured SECTION, COUNT and DYNAMIC events.
- Generated `StageGrid Native Guide.wav` on the shared timeline.
- Original Guide retained muted after successful reconstruction.
- Per-song Guide output-language switching.
- Phase-robust fingerprinting, adaptive candidate discovery and source-language-first matching.
- English ambiguity hardening while preserving the reliable Spanish path.
- Persistent Guide fingerprint cache.
- In-place Guide reanalysis from the retained original Guide.
- Arrangement-aware destination lead-bar phrase containing SECTION/COUNT/DYNAMIC cues where samples exist.

### Session recovery / Setlist Live

- Versioned app-private performance-session snapshot.
- Recovery validates referenced song/setlist state and always loads stopped.
- Setlist Live Previous/Next navigation.
- Destination song loads stopped.
- Bounded next-song filesystem-cache warming.

## Validation performed for 0.3.0-alpha01 in the current implementation environment

- `ImportAudioFormat` compiled with local `kotlinc` and executable policy checks passed for M4A/AAC recognition, normalization flags, FLAC planned-state behavior and the detected-extension set.
- `PlatformAudioToWavDecoder` syntax/types compiled with `kotlinc` against minimal Android media API stubs and a `WavMetadataReader` stub.
- This static/stub validation does **not** claim that an Android `MediaCodec` actually decoded a representative M4A/AAC file.

## Implemented but not yet qualified on Android hardware

- Representative MP3/M4A/AAC import across multiple Android devices/OEM codec stacks.
- M4A containers with different AAC profiles/metadata layouts.
- Raw/common AAC source variants exposed through `MediaExtractor`.
- Encoder delay/padding behavior for real MP3/AAC exports and cross-stem timing.
- Long/high-channel compressed imports close to RIFF output limits.
- Existing 0.2 high-track-count transitions, Guide phrases, session recovery, backup/restore and USB stereo reconnect behavior after the 0.3 importer change.

## Deliberately not exposed as finished

- FLAC playback/import normalization.
- OGG/Vorbis playback/import normalization.
- Waveform peak-cache generation.
- Waveform rendering/editor integration.
- Storage accounting and cache manager/eviction.
- Full arbitrary virtual arrangement graph.
- Gapless dual-song decoder graph, automatic handoff and crossfade.
- Arbitrary 4/8/custom multichannel USB routing matrix.
- Tempo/time-stretch and pitch-shift DSP.
- MIDI USB/BLE, MIDI Learn and MIDI Clock.
- Pads, automation and SMPTE/LTC.
- Full `.stagepack` interchange semantics.
- LAN remote and final tablet workspace.

## 0.3 next step

The next planned milestone is `0.3.0-alpha02`: choose and validate the FLAC/OGG import-normalization path without adding codec work to the realtime callback.

`0.3.0-alpha03` then begins the versioned waveform peak-cache layer, followed by waveform UI and storage/cache management.

## Qualification status

StageGrid `0.3.0-alpha01` is a development alpha. A successful JVM/static check or CI build is not equivalent to live-stage qualification. Representative physical Android hardware and prolonged live-use validation remain required.
