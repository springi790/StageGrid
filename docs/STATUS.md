# Implementation status — 0.3.0-alpha05

This document describes what exists in source. It intentionally does not promote planned work to implemented status.

## Implemented in 0.3.0-alpha05

### Expanded import format policy

- Import discovery recognizes WAV, MP3, M4A, AAC, FLAC and OGG case-insensitively.
- WAV is retained/copied as the native playback source.
- MP3, M4A, AAC, FLAC and OGG are marked playable through import-time normalization.
- `ImportAudioFormat` is the centralized source-of-truth for detected/playable/normalization policy.
- JVM regression coverage checks the final 0.3 format set and normalization policy.

### Shared Android audio normalizer

- `PlatformAudioToWavDecoder` uses Android `MediaExtractor` + `MediaCodec` only during import.
- It selects the first audio track exposed by the source/container.
- Non-WAV imports normalize to 16-bit PCM RIFF/WAV before `TrackEntity` registration.
- Android PCM16 output is written directly; PCM-float output is converted to PCM16.
- output sample rate is validated between 8 kHz and 384 kHz.
- output channel count is validated between 1 and 8 channels.
- classic RIFF data-size bounds are enforced.
- Android `encoder-delay` / `encoder-padding` metadata is applied when exposed.
- decode progress is based on presentation time when duration metadata exists.
- temporary PCM/partial destination files are cleaned on failure.
- codec/extractor release is guarded during cleanup.
- `Mp3ToWavDecoder` remains as a compatibility facade over the shared decoder.

### SongImporter integration

- WAV sources continue to copy directly into app-private playback storage.
- all other supported 0.3 source formats normalize before the playable track path is saved.
- source stem names/classification remain based on the original file name.
- Click and Guide downstream analysis receives the normalized WAV path.
- compressed-source warnings summarize normalization counts/formats.
- the playable import limit remains 64 tracks.

### Waveform peak cache

- `WaveformPeakCache` creates a bounded song-level min/max overview from retained playback WAV files.
- waveform generation is outside the realtime callback and intended to run on an I/O dispatcher.
- peak buckets are mapped by absolute source time onto the longest song timeline; shorter stems are not stretched to fill the song.
- supported cache input covers PCM integer WAV and 32-bit IEEE-float WAV used by StageGrid.
- cache format has a magic/version header and a source signature.
- source name/length/mtime changes invalidate a stale cache.
- cache writes use a temporary file before replacement.
- cache path is `library/<song-id>/cache/waveform-overview.sgpk`.
- cache deletion is safe because playback WAV files remain the source of truth.
- JVM regression coverage includes different-duration stems, regeneration and safe cache removal.

### Waveform UI

- Player displays the cached waveform overview.
- Player waveform shows the current playhead and authored section boundaries.
- tapping the Player waveform seeks when the current transport policy permits seeking.
- Section Editor displays the same waveform as a read-only structural reference.
- waveform generation/loading is performed from Compose through `Dispatchers.IO`, not the audio callback.
- ES/EN copy and accessibility description are present.

### Storage accounting / cache management

- `StorageCacheManager` reports complete StageGrid-owned app-private usage.
- separate accounting is exposed for library bytes, playback-ready audio, regenerable cache, Guide resources and local song count.
- Settings displays this accounting.
- Settings can clear regenerable song caches.
- cache cleanup is scoped to `library/<song>/cache` only.
- cleanup does not remove retained playback audio, Room records, Guide content or external `.stagebackup` files.

### Release metadata

- app version: `0.3.0-alpha05`.
- `versionCode`: `23`.
- alpha05 intentionally integrates the work originally planned across 0.3 alpha02–alpha05.

## Inherited implementation from the 0.2 line

### Library / lifecycle

- Room library with Song, Track, Section, Setlist and SetlistSong records.
- ZIP, folder and multi-file import through Android SAF.
- linked document-provider folder browsing.
- post-import metadata editing.
- safe local song deletion with staged filesystem rollback around Room deletion.

### Portable backup / restore

- `.stagebackup` creation through Android SAF.
- portable song/track/section/setlist/Guide state.
- byte-size + SHA-256 payload validation.
- staged restore and app-private path reconstruction.
- installed user-supplied Guide pack included in snapshots.

### Shared-clock audio / live paths

- one Oboe stereo stream and one master transport clock.
- streaming WAV decoder workers with preallocated SPSC buffers.
- two decoder banks per track for prepared Loop/section path changes.
- synchronized all-track handoff after readiness/alignment checks.
- stale/late prepared swaps are rejected and diagnosed.
- per-track volume/mute/solo/pan and `L / L+R / R` routing.
- Native Click with subdivisions/routing and 1/2-bar section count-in.
- Android/USB stereo output-device selection.

### Musical Grid / sections

- BPM/time-signature/grid-offset Musical Grid.
- bar/beat display and snapping.
- visual/manual Section Editor.
- automatic Guide-derived section proposals.
- Loop / Exit Loop.
- manual section choices wait for current authored `endMs` and enter destination `startMs`.

### Native Guide

- user-installed local cue packs; no third-party Guide audio bundled.
- ES/EN/FR/PT layouts when present.
- offline sample/template matching.
- structured SECTION, COUNT and DYNAMIC events.
- generated `StageGrid Native Guide.wav` on the shared timeline.
- source-language probing, phase-robust fingerprints and English ambiguity hardening.
- persistent Guide fingerprint cache.
- in-place Guide reanalysis.
- arrangement-aware destination lead-bar phrases.

### Session recovery / Setlist Live

- versioned app-private performance-session snapshot.
- recovery validates references and always loads stopped.
- Setlist Live Previous/Next navigation.
- destination songs load stopped.
- bounded next-song filesystem-cache warming.

## Implemented but not yet qualified on Android hardware

- MP3/M4A/AAC/FLAC/OGG import across representative Android devices/OEM codec stacks.
- OGG containers using different platform-supported codecs (for example Vorbis/Opus where exposed).
- M4A/AAC profiles and metadata variations.
- real encoder delay/padding behavior and cross-stem timing.
- long/high-channel compressed imports close to classic RIFF limits.
- lazy waveform generation on long 16/32/64-track songs.
- waveform rendering/seek behavior on small/large screens and accessibility services.
- cache cleanup while songs have already-generated waveform UI state in memory.
- low-storage behavior during normalization/cache generation.
- inherited high-track-count live transitions, Guide phrases, process-death recovery, backup/restore and USB stereo reconnect after the 0.3 changes.

## Deliberately not exposed as finished

- arbitrary 4/8/custom multichannel USB routing matrix (0.4).
- full arbitrary virtual arrangement graph and true dual-song handoff/crossfade (0.5).
- tempo/time-stretch and pitch-shift DSP (0.6).
- MIDI USB/BLE, MIDI Learn and MIDI Clock (0.7).
- pads, automation and SMPTE/LTC (0.8).
- `.stagepack` interchange and LAN remote/tablet workspace (0.9).

## 0.3 completion boundary

All source work originally planned for the 0.3 expanded decoder/cache layer is now represented in `0.3.0-alpha05`:

1. expanded import-time decoding/normalization;
2. FLAC/OGG platform path;
3. waveform peak cache;
4. waveform UI;
5. storage accounting/cache eviction.

The remaining 0.3 work is qualification/fixes from real Android testing, not another planned feature alpha.

## Qualification status

StageGrid `0.3.0-alpha05` remains a development alpha. CI success and JVM/static validation are necessary but not equivalent to stage qualification. Representative physical Android hardware and prolonged live-use validation remain required.
