# Implementation status — 0.2.0-alpha04

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

### Shared-clock audio

- Shared-clock native multitrack PCM/WAV playback through one Oboe output stream.
- Streaming decoder threads + preallocated SPSC buffers; no disk I/O/Room/UI/Guide recognition in the real-time callback.
- Deterministic source-rate mapping to a common output timeline.
- Play, pause, stop, seek and master volume.
- Per-track volume, mute, solo, pan and two-channel output route (`L`, `L+R`, `R`) persisted to Room.
- Audio-device enumeration and stereo device selection, including compatible USB endpoints.
- Foreground playback service, MediaSession, audio focus, LIVE mode and Performance Lock.
- Diagnostics for sample rate, burst size, underruns, loaded tracks and callback load.

### Native Click and Musical Grid

- Imported Click is reference-only for grid analysis.
- First strong Click transient can establish `gridOffsetMs`.
- Native sample-clock Click whenever BPM is known.
- 1/4, 1/8, 1/8T and 1/16 subdivisions.
- Native Click output route: `L`, `L+R` or `R`.
- Deterministic milliseconds ↔ bar/beat conversion.
- Beat/bar snapping and Player musical-position readout.
- Click subdivision/route and section count-in preferences persisted with DataStore.

### Sections and live navigation

- `song.json` section import.
- Visual/manual Section Editor with friendly playhead actions plus optional precise bar/beat editing.
- Edit Sections remains visible directly in the Player while stopped with a valid grid.
- Section loop / Exit Loop.
- Live section changes queue to the next musical bar when a valid grid exists, with section-boundary fallback otherwise.
- Native 1- or 2-bar count-in/pre-roll.
- Virtual negative-time count-in for sections that start at song frame zero.
- Imported stems are gated during count-in and enter together at the target frame.

### Native Guide — alpha04

- User-supplied Guide sample ZIP installation into app-private storage; third-party sample audio is not bundled with StageGrid.
- Bounded Guide-pack extraction and WAV validation.
- Installed sample-pack language detection for ES/EN/FR/PT when present.
- Offline import-time Guide cue recognition using short-time audio fingerprints/templates rather than cloud speech recognition.
- Recognition of section/count/dynamic cues represented by the installed pack.
- Dominant Guide language detection.
- Output language preference: Auto/ES/EN/FR/PT when installed.
- Recognized structured events persisted to `native-guide-events.json`.
- App-generated PCM `StageGrid Native Guide.wav` using recognized events and installed cue samples.
- Original imported Guide retained as a muted reference after successful native reconstruction.
- Automatic section proposals from recognized section cues when no explicit manifest section map exists and BPM/grid data is valid.
- Automatic sections remain editable in the normal Section Editor.
- JVM test coverage for Guide template matching and one-bar-ahead section inference.

### Setlists / UX

- Basic local setlist create/add/remove/load flows.
- Simplified Player and Mixer language for non-technical users.
- Common stereo routing presets.
- Spanish and English UI resource sets.

## Implemented, but not yet claimed as stage-validated

- Live loop/path/queued-jump changes rebuild decoder look-ahead. Logical behavior and CI builds pass, but a broad physical-device/high-track-count stress matrix has not yet proven every transition glitch-free.
- Native Guide recognition is designed for Guide stems assembled from cues matching the installed sample pack. It is not generic speech-to-text for arbitrary spoken recordings.
- Native Guide events are persisted structurally, but the alpha04 rendered Guide still follows the original timeline. Arrangement-aware Guide relocation after arbitrary live ReOrder is not complete.
- Mixed-source-rate playback uses deterministic linear interpolation against the master timeline; this is not the final mastering-grade resampler.
- USB output-device selection is real, but the current stream is stereo only.

## Deliberately not exposed as finished

- Double-buffered arrangement/path engine for hardened live ReOrder.
- In-place native Guide re-analysis for songs imported before installing/changing a Guide pack.
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

StageGrid `0.2.0-alpha04` is a development alpha, not a stage-ready 1.0 release. CI validates unit tests and debug assembly, but final qualification still requires representative physical Android devices, high track counts, USB reconnect/routing tests, live arrangement stress tests and crash/session-recovery validation.
