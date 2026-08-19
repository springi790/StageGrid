# Implementation status — 0.1.3 MVP

## Implemented in source

- Local Room library with UUID song IDs and separate Song/Track/Section/Setlist records.
- ZIP import without modifying the original archive.
- Folder import through Android Storage Access Framework.
- Multiple-file import through SAF.
- ZIP-slip protection plus bounded nesting, file count and expanded size.
- WAV/MP3 stem detection/classification; WAV metadata extraction and offline MP3-to-PCM normalization using Android MediaCodec.
- Optional `song.json` metadata + section import.
- Post-import and later-editable metadata: title, artist, BPM, key, time signature, grid offset and notes.
- Shared-clock native multitrack PCM/WAV playback through one Oboe output stream; imported MP3 stems are normalized to PCM before loading the engine.
- Streaming decoder threads + preallocated SPSC buffers; no disk I/O/Room/UI work in the real-time callback.
- Deterministic source-rate mapping to a common output timeline.
- Play, pause, stop, seek and master volume.
- Per-track volume, mute, solo, pan and two-channel output route (`L`, `L+R`, `R`) persisted to Room.
- Imported Click is reference-only for grid analysis; its first strong transient establishes the musical-grid origin.
- Native click whenever BPM is known, with 1/4, 1/8, 1/8T and 1/16 subdivisions.
- Native click output route: `L`, `L+R` or `R`; Guide uses the same per-track routing matrix.
- Click subdivision and click route preferences persisted with DataStore.
- Imported song sections, section loop, loop exit and queued section jump.
- Basic local setlist create/add/remove/load flows.
- Foreground playback service + Android MediaSession controls.
- Audio focus handling.
- LIVE keep-screen-on mode and Performance Lock UI.
- Android audio-device enumeration and stereo device selection, including compatible USB endpoints.
- Diagnostics: sample rate, burst size, underruns, tracks, playhead and callback CPU estimate.
- Spanish/English resource sets.

## Implemented, but not yet claimed as stage-validated

- Live loop/path changes rebuild decoder look-ahead. The logical behavior is implemented, but a broad physical-device stress matrix has not yet proven every boundary transition glitch-free.
- Mixed-source-rate playback uses deterministic linear interpolation against the master timeline. It avoids independent-player drift, but the resampler is not a final mastering-grade DSP implementation.
- USB device selection is real, but the MVP stream is stereo only. The new L/R matrix operates inside that stereo stream.

## Deliberately not exposed as finished

- Manual visual section editor (without `song.json`, the MVP creates one full-song section).
- Waveform cache/editor.
- Time stretching / tempo change.
- Pitch shifting / transposition.
- Advanced multichannel USB routing (4/8/10+ discrete outputs).
- Gapless setlist auto-next/crossfade and next-song preload.
- MIDI input/output/mapping/clock/cues.
- SMPTE/LTC.
- Automation editor.
- Pad player.
- Stagepack backup/import.
- LAN remote control.
- Tablet split Player + Mixer workspace.
- Compressed-audio decoding beyond MP3: AAC/M4A/FLAC/OGG.

These are architectural extension points, not fake buttons.

## Master acceptance-test status

The full 30-step product acceptance test from the master specification **does not pass yet**. The 0.1.3 source covers the local WAV/MP3 import/playback/mixer/click/guide core and basic section behavior when sections are supplied in `song.json`. It does not yet satisfy the required discrete USB routing step, manual section-authoring workflow, or the complete stage-hardening matrix. Therefore 0.1.3 must not be described as the finished product.
