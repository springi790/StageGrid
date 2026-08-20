# Implementation status — 0.2.0-alpha10.1

This document describes what exists in source. It intentionally does not promote planned work to implemented status.

## Implemented in source

### Library / import

- Room library with Song, Track, Section, Setlist and SetlistSong records.
- ZIP, folder and multi-file import through Android Storage Access Framework.
- Linked document-provider folder browsing, including Google Drive when exposed by Android.
- WAV playback plus one-time MP3 → PCM WAV normalization.
- Import percentage, stage and current-file detail.
- Post-import metadata editing.
- Safe local multitrack deletion with confirmation.
- Loaded songs are unloaded before deletion so native WAV readers release their files.
- Song folders are staged before Room deletion and restoration is attempted if the database operation fails.
- Room cascades remove deleted-song tracks, sections and setlist references.
- External `.stagebackup` files are not modified by local deletion.

### Portable backup / restore

- Manual `.stagebackup` creation from Settings.
- Destination chosen with Android SAF: local folders, compatible removable storage and providers such as Google Drive when exposed by Android.
- Complete app-private song directories are archived, including normalized stems, generated Guide files and Guide sidecars.
- Room song/track/section/setlist/setlist-song state is serialized.
- The installed user-supplied Guide pack is included.
- Device-specific absolute track paths are converted to portable song-relative paths and rebuilt on restore.
- Every payload file has byte-size + SHA-256 validation metadata.
- Restore stages, safely extracts and validates the declared file set before installing data.
- Matching stable IDs are replaced/merged while unrelated local library records are preserved.
- Native WAV readers are unloaded before restore replaces local song files.
- Backup/restore exposes percentage, stage and current detail.

### Shared-clock audio / live path engine

- One Oboe stereo stream and one master transport clock.
- Streaming decoder threads with preallocated SPSC buffers.
- Two decoder banks per track for prepared Loop/section path changes.
- All-track realtime bank handoff after readiness/alignment checks.
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
- Manual section choices wait for the explicit `endMs` of the current section.
- Requested destination enters at its explicit `startMs`.
- Edit Sections remains a first-class Player action.

### Native Guide recognition / reconstruction

- User-installed local Guide cue packs; StageGrid does not bundle third-party Guide audio.
- Supported ES/EN/FR/PT pack layouts when present in the installed ZIP.
- Offline sample/template matching.
- Structured SECTION, COUNT and DYNAMIC events in `native-guide-events.json`.
- App-generated `StageGrid Native Guide.wav`.
- Original imported Guide retained muted as a reference after successful reconstruction.
- Automatic section proposals and delayed section recovery after BPM becomes available.
- Per-song Native Guide language switching without reimporting stems or repeating recognition.

### Alpha10.1 recognition hardening

- Guide fingerprint envelopes accumulate per-channel energy before averaging, preventing stereo phase cancellation from erasing speech energy.
- Persistent fingerprint cache format is versioned to v2 and older fingerprints are rebuilt automatically.
- Candidate discovery combines strong and relaxed activity thresholds so one loud call is less likely to hide quieter calls elsewhere in the Guide.
- Local onset candidates are added for compressed/continuous Guide audio that does not fully return to silence between calls.
- Template search tolerance is widened from roughly ±120 ms to ±240 ms.
- Primary matching uses a semantic confidence margin so the same canonical cue in another installed language does not count as a conflicting label.
- A conservative second matching pass is allowed after the source Guide language can be inferred.
- Click-grid detection gathers multiple transient candidates and prefers the start of a stable periodic pulse train over an isolated early spike.
- If no stable Click train is found, the first valid candidate remains the fallback.
- JVM regression tests cover quiet anti-phase Guide recognition and an isolated transient before a valid Click train.

### Alpha09 Guide reanalysis / persistent cache

- Guide sample fingerprints can be persisted to app-private disk storage.
- The cache is keyed to the installed sample signature and checks sample identity, byte length and modification time before reuse.
- Pack replacement/restore invalidates the in-memory Guide index; mismatching persistent data is rebuilt.
- A loaded stopped song can expose **Reanalyze Guide** when its retained original Guide file and an installed Guide pack are available.
- Reanalysis reuses the original Guide file and does not reimport/reconvert the remaining stems.
- Reanalysis exposes progress.
- Existing Native Guide audio is staged/replaced and validated before reuse.
- Older songs without a Native Guide track can receive one if the song remains below the 64-track import limit.
- Untouched `Full Song`/automatic section maps can be refreshed.
- Manually renamed, resized, reordered or recolored section maps are protected from automatic replacement.

### Alpha10 arrangement-aware Guide phrase

- Manual destination selection still follows the current-section-boundary transition policy.
- StageGrid resolves an editable destination section back to its canonical Guide key when possible.
- It selects recognized SECTION, COUNT and DYNAMIC events from the destination section's original lead bar.
- If the target section call is absent from an older analysis, a destination section call can be synthesized for sample lookup.
- Output-language samples are preferred with detected-language fallback where available.
- Cue WAV loading/resampling happens off the realtime thread.
- Multiple destination cues are mixed into one immutable short mono buffer before native publication.
- The fixed rendered Guide is suppressed through the replacement phrase window to reduce conflicting calls.
- A cue that cannot fit completely in a very late transition window is skipped instead of being cut mid-speech.
- The prepared phrase is mixed on the same native master timeline as stems and Click.

### Alpha08 performance-session recovery

- Versioned app-private session snapshot file.
- Temp-file write + flush/fsync + replacement behavior.
- Snapshot records loaded song, approximate position, Click/Guide state, Click subdivision/route, count-in, master volume and Setlist Live context.
- Snapshot is periodically refreshed while a valid song is loaded.
- Startup validates that the referenced song/setlist still exists.
- Valid sessions restore available Player/Setlist context and load the song near the saved position.
- Missing song references cause the stale snapshot to be discarded.
- **Recovered sessions always load stopped/ready; StageGrid never auto-emits audio from session recovery.**

### Setlist Live

- Non-empty selected setlists can enter Setlist Live mode.
- Player shows setlist name, song position, current/next song, Previous/Next and Exit Setlist.
- NEXT/PREV stop/unload the old native song graph before a different song is loaded.
- Destination songs load stopped and never auto-play because of NEXT/PREV.
- The next song receives bounded OS filesystem-cache warming by reading the beginning of each normalized track on an IO dispatcher.
- No second native decoder graph is kept alive for alpha10.1 Setlist Live.
- Navigation index/boundary policy has deterministic JVM coverage.

### UX / live operation

- Simplified Player/Mixer terminology and common routing presets.
- Foreground service and MediaSession/notification controls.
- Audio focus handling.
- LIVE keep-screen-on mode and Performance Lock.
- Spanish and English UI resources for the current alpha features.

## Implemented but not yet stage-qualified

- Alpha10.1 Guide-recognition improvements across a broad set of real-world Guide encodes, gain structures and stereo layouts.
- Stable Click-train grid anchoring against real imported Click references with count-ins/noise/accent variation.
- Double-buffered Loop/section transitions under representative high stem counts.
- Alpha10 multi-cue arrangement-aware Guide phrases on physical devices under rapid repeated destination changes.
- Persistent Guide-cache speed/invalidations across real device storage/process-restart scenarios.
- In-place Guide reanalysis against multiple real sample packs and long Guide stems.
- Session recovery after actual Android process death/reboot across devices/OEMs.
- Safe deletion rollback under forced I/O/database failures and low-storage conditions.
- Backup/restore against real Drive/local/removable providers and low-storage/failure scenarios.
- Setlist Live warm preload and repeated song changes during a real performance.
- USB stereo device reconnect/output selection across representative interfaces.

## Deliberately not exposed as finished

- Full arbitrary virtual arrangement graph.
- Global relocation of every Guide event across arbitrary arrangement nodes; alpha10 relocates a destination lead-bar phrase for manual section choices.
- Gapless dual-song decoder graph, automatic handoff and crossfade.
- AAC/M4A/FLAC/OGG expanded playable pipeline.
- Waveform peak cache/editor and storage cache manager.
- Arbitrary 4/8/custom multichannel USB routing matrix.
- Tempo/time-stretch and pitch-shift DSP.
- MIDI USB/BLE, MIDI Learn and MIDI Clock.
- Pads, automation and SMPTE/LTC.
- Full `.stagepack` interchange semantics.
- LAN remote and final tablet workspace.
- First-run onboarding and final accessibility/large-touch-target pass.

## Beta readiness

`0.2.0-alpha10.1` is the current pre-beta candidate. It exists specifically because field feedback exposed recognition variability after the broad alpha10 sprint.

If representative failing songs improve without introducing false-positive Guide calls or incorrect grid origins, the next planned version is `0.2.0-beta01`.

Beta01 should focus on usability, onboarding, accessibility, recoverable errors and fixes from field feedback rather than adding another large subsystem.

Beta02 should focus on qualification/stability: high-track-count stress, repeated live transitions, process death, backups, low storage, Setlist Live and USB stereo behavior.

## Qualification status

StageGrid `0.2.0-alpha10.1` remains a development alpha. CI verifies unit tests and debug assembly, but a successful build is not equivalent to stage qualification. Stable 0.2 and later 1.0 gates require representative physical Android hardware and prolonged live-use validation.
