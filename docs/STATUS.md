# Implementation status — 0.2.0-alpha05.1

## Implemented in source

### Library and import

- Local Room library with UUID song IDs and separate Song/Track/Section/Setlist records.
- ZIP import without modifying the original archive.
- Folder and multiple-file import through Android Storage Access Framework.
- Persistent linked cloud-folder browsing through Android document providers, including Google Drive when exposed by the device.
- ZIP-slip protection plus bounded nesting, file count and expanded size.
- WAV/MP3 stem detection/classification; WAV metadata extraction and offline MP3-to-PCM normalization using Android MediaCodec.
- Optional `song.json` metadata + section import.
- Post-import/later-editable title, artist, BPM, key, time signature, grid offset and notes.
- Import UI exposes an overall percentage, current pipeline stage and current file/detail where useful.
- MP3 decoder progress is derived from codec presentation timestamps; WAV/local-copy work reports byte progress.
- Local audio copy/decode buffers are enlarged to reduce small I/O operations.

### Shared-clock audio

- Shared-clock native multitrack PCM/WAV playback through one Oboe output stream.
- Streaming decoder threads + preallocated SPSC buffers; no disk I/O/Room/UI/Guide recognition in the real-time callback.
- Deterministic source-rate mapping to a common output timeline.
- Play, pause, stop, seek and master volume.
- Per-track volume, mute, solo, pan and two-channel output route (`L`, `L+R`, `R`) persisted to Room.
- Audio-device enumeration and stereo device selection, including compatible USB endpoints.
- Foreground playback service, MediaSession, audio focus, LIVE mode and Performance Lock.
- Diagnostics for sample rate, burst size, underruns, loaded tracks and callback load.

### Double-buffered live path engine — alpha05

- Each loaded track owns two preallocated SPSC decoder banks.
- The currently active bank remains available to the Oboe callback while an inactive bank prepares a changed Loop/jump timeline.
- Decoder threads prioritize keeping the active bank above a low-water mark while filling the inactive replacement bank in parallel.
- Replacement banks use an immutable local copy of the published path state for that bank generation.
- A monotonically increasing output-frame counter aligns the prepared bank with the frames that elapsed while it was being built.
- The realtime callback activates every track's prepared bank together only after all tracks report the same ready generation and enough aligned data is available.
- Bank activation uses atomic state plus ring-buffer consumer advancement; no filesystem access, WAV seeking, allocation, Room call, UI work or blocking mutex is added to the realtime callback.
- A conservative first-divergence window prevents a prepared path from being activated after the old and new timelines could already have diverged.
- Stale/late prepared changes are cancelled and counted instead of being applied after their safe handoff window.
- Native diagnostics expose successful path swaps, missed safe windows and whether a path change is pending.
- The two one-second-at-48-kHz banks use approximately the same ring-buffer memory budget as the previous single two-second bank.

### Native Click and Musical Grid

- Imported Click is reference-only for grid analysis.
- First strong Click transient can establish `gridOffsetMs`.
- Native sample-clock Click whenever BPM is known.
- 1/4, 1/8, 1/8T and 1/16 subdivisions.
- Native Click output route: `L`, `L+R` or `R`.
- Deterministic milliseconds ↔ bar/beat conversion.
- Beat/bar snapping and Player musical-position readout.
- Musical Grid remains authoritative for section editing/snapping, Click timing, count-in and bar/beat display.
- Click subdivision/route and section count-in preferences persisted with DataStore.

### Sections and live navigation — alpha05.1

- `song.json` section import.
- Visual/manual Section Editor with friendly playhead actions plus optional precise bar/beat editing.
- Edit Sections remains visible directly in the Player while stopped with a valid grid.
- Section loop / Exit Loop.
- A manual live section selection queues to the **explicit end marker of the current section**, not to an internal bar line.
- The requested destination enters at its own explicit section start marker.
- Internal Musical Grid bar boundaries no longer shorten a manual section merely because the next bar occurs before the authored section end.
- If the controller observes that the current section end is already behind the native/UI position, it falls back to the requested destination immediately instead of scheduling against a stale boundary.
- Loop and section-jump path changes use the double-buffered native handoff while transport is active.
- Native 1- or 2-bar count-in/pre-roll.
- Virtual negative-time count-in for sections that start at song frame zero.
- Imported stems are gated during count-in and enter together at the target frame.
- Persisted Guide events can recover automatic sections after BPM/grid metadata becomes available, while preserving manually edited section maps.
- JVM coverage verifies that a manual transition waits for the current section end and ignores internal bar lines.

### Native Guide

- User-supplied Guide sample ZIP installation into app-private storage; third-party sample audio is not bundled with StageGrid.
- Bounded Guide-pack extraction and WAV validation.
- Installed sample-pack language detection for ES/EN/FR/PT when present.
- Offline import-time Guide cue recognition using short-time audio fingerprints/templates rather than cloud speech recognition.
- Recognition of section/count/dynamic cues represented by the installed pack.
- Dominant Guide language detection.
- Default output language preference for new imports: Auto/ES/EN/FR/PT when installed.
- Recognized structured events persisted to `native-guide-events.json`.
- App-generated PCM `StageGrid Native Guide.wav` using recognized events and installed cue samples.
- Original imported Guide retained as a muted reference after successful native reconstruction.
- Per-song Guide language can be changed from Player after recognition without re-importing stems or re-running recognition.
- Per-song output language is persisted in `native-guide-events.json`.
- Guide re-render is disabled during active playback/count-in and uses a temporary file before swapping the active generated Guide.
- Native Guide render progress is exposed to Player.
- Installed Guide sample file index is cached in memory.
- Guide cue fingerprints/templates are cached and pre-warmed when a pack is installed.
- Automatic section proposals from recognized section cues when no explicit manifest section map exists and BPM/grid data is valid.
- Generated native Guide audio is a normal shared-clock track and participates in the same alpha05 decoder-bank handoff as other stems.

### Setlists / UX

- Basic local setlist create/add/remove/load flows.
- Simplified Player and Mixer language for non-technical users.
- Common stereo routing presets.
- Spanish and English UI resource sets.

## Implemented, but not yet claimed as stage-validated

- Double-buffered Loop/jump handoff is implemented and compiled in CI, but a broad physical-device/high-track-count stress matrix is still required before claiming glitch-free stage qualification.
- A manual section change no longer moves to a later bar for preparation safety. An extremely late tap can therefore miss the inactive-bank safe preparation window; stale audio is rejected rather than applied after the authored section boundary.
- Native Guide recognition is designed for Guide stems assembled from cues matching the installed sample pack. It is not generic speech-to-text for arbitrary spoken recordings.
- Native Guide events are persisted structurally, but the rendered Guide still follows the original timeline. Arrangement-aware relocation/synthesis of the spoken pre-section cue after arbitrary live ReOrder is not complete.
- Guide fingerprint caching is currently in-memory; after a full process restart the first Guide analysis may pay template preparation cost again.
- Import percentages represent weighted pipeline progress. ZIP/folder staging cannot always know total expanded work before traversal, so early staging percentages are stage-weighted rather than exact byte completion.
- Mixed-source-rate playback uses deterministic linear interpolation against the master timeline; this is not the final mastering-grade resampler.
- USB output-device selection is real, but the current stream is stereo only.

## Deliberately not exposed as finished

- Arrangement-aware native Guide event relocation after arbitrary live ReOrder.
- In-place native Guide re-analysis for songs imported before installing/changing a Guide pack.
- Persistent on-disk Guide fingerprint cache/index.
- Waveform cache/editor.
- Time stretching / tempo change.
- Pitch shifting / transposition.
- Advanced multichannel USB routing (4/8/10+ discrete outputs).
- Setlist Live NEXT/PREV with next-song preload and gapless transition architecture.
- Persisted/restorable performance session.
- MIDI input/output/mapping/clock/cues.
- SMPTE/LTC.
- Automation editor.
- Pad player.
- Full Stagepack backup/export/import.
- LAN remote control.
- Tablet split Player + Mixer workspace.
- Compressed-audio decoding beyond MP3: AAC/M4A/FLAC/OGG.
- First-run onboarding and final accessibility/large-touch-target pass.

These are architectural extension points, not fake buttons.

## Qualification status

StageGrid `0.2.0-alpha05.1` is a development alpha, not a stage-ready 1.0 release. CI validates unit tests and debug assembly, but final qualification still requires representative physical Android devices, high track counts, USB reconnect/routing tests, live path stress tests, import-performance profiling and crash/session-recovery validation.
