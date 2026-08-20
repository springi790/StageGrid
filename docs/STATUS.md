# Implementation status — 0.2.0-alpha10.3

This document describes what exists in source. Planned work is listed separately and is not presented as implemented.

## Implemented in source

### Library / import

- Room library with Song, Track, Section, Setlist and SetlistSong records.
- ZIP, folder and multi-file import through Android Storage Access Framework.
- Linked document-provider folders, including Google Drive when exposed by Android.
- WAV playback plus one-time MP3 → PCM WAV normalization.
- Import percentage, stage and current-file detail.
- Post-import metadata editing.
- Safe local multitrack deletion with confirmation, native unload, staged files and rollback attempt around Room deletion.

### Portable backup / restore

- Manual self-contained `.stagebackup` creation through Android SAF.
- Local/removable/compatible Drive-provider destinations.
- App-private songs/audio, Room metadata, sections, setlists/order, Guide sidecars/generated files and the installed user Guide pack.
- Portable path reconstruction across devices.
- Byte-size + SHA-256 payload verification.
- Staged/validated restore before library installation.
- Unrelated local library records preserved when restoring a backup.

### Shared-clock native audio

- One Oboe stereo output stream and authoritative native transport clock.
- Streaming decoder threads with preallocated SPSC buffers.
- Two decoder banks per track for prepared Loop/section transitions.
- Synchronized all-track realtime handoff and stale-path rejection.
- Per-track volume/mute/solo/pan and `L / L+R / R` routing.
- Native Click with 1/4, 1/8, 1/8T and 1/16 subdivisions.
- Native 1/2-bar section count-in.
- Basic Android/USB stereo output-device selection.
- Underrun/callback/path diagnostics.

### Musical Grid / sections

- BPM/time-signature/grid-offset model.
- Bar/beat display and snap utilities.
- Visual/manual Section Editor.
- Automatic Guide-derived section proposals.
- Section Loop / Exit Loop.
- Manual section choices wait for the current explicit `endMs` and enter the requested section at its explicit `startMs`.

### Native Guide foundation

- User-installed local Guide cue packs; no bundled third-party Guide audio.
- Spanish, English, French and Portuguese layouts when present.
- Structured SECTION, COUNT and DYNAMIC events.
- `native-guide-events.json` sidecar.
- App-generated `StageGrid Native Guide.wav` on the shared playback clock.
- Original imported Guide retained muted after successful reconstruction.
- Automatic editable section proposals and delayed recovery after BPM is supplied.
- Per-song output-language switching without stem reimport/reanalysis.
- In-place reanalysis from the retained original Guide without reconverting other stems.
- Persistent energy-fingerprint cache.
- Arrangement-aware relocation of the selected destination's original lead phrase containing SECTION/COUNT/DYNAMIC calls.

### Alpha10.1 / alpha10.2 recognition hardening

- Phase-robust per-channel energy envelope.
- Strong + relaxed candidate discovery for uneven Guide gain.
- Local onset recovery for compressed/continuous Guide audio.
- Wider template timing tolerance.
- Stable Click-train selection instead of blindly choosing one early transient.
- Bounded source-language probe.
- When source language is confidently detected, full matching is restricted to that language.
- Spanish alpha recognition thresholds/path preserved after field feedback showed it reliable.

### Alpha10.3 English Guide structure hardening

- English alone receives an additional decimated multi-band acoustic fingerprint after source-language detection.
- The acoustic fingerprint keeps the 10 ms timeline while describing total energy plus four coarse relative speech-band energies.
- English matching combines temporal envelope correlation with acoustic/spectral movement rather than relying on RMS shape alone.
- `Vamp`, `Rap`, `Tag` and `Solo` receive an ambiguity penalty in English because short labels were observed becoming false defaults.
- If an English song's SECTION recognition collapses predominantly into one of those short labels, weak repetitions are discarded instead of generating a misleading section map.
- Spanish and other languages do not pay the English acoustic-analysis cost and keep their prior recognition path.
- Repeated generic Verse calls can be numbered by occurrence when the recognized key has no explicit number.
- Candidate-level diagnostics are persisted in Native Guide sidecar v2: best key, second key, scores, accepted/rejected state and rejection reason.
- Existing sidecar v1 files remain readable; missing diagnostics simply produce an empty diagnostic list.
- JVM tests cover English cues with identical amplitude envelopes but different acoustic content, plus repeated generic Verse numbering.

### Performance-session recovery

- Versioned app-private session snapshot.
- Periodic song/position/Click/Guide/count-in/master/Setlist context persistence.
- Missing references rejected safely.
- Recovered sessions always load stopped; StageGrid never auto-plays because of session recovery.

### Setlist Live

- Current/next context, Previous/Next and Exit Setlist controls.
- Destination songs load stopped.
- Bounded next-song filesystem-cache warming.
- No hidden claim of gapless dual-engine playback.

### UX / live operation

- Simplified Player/Mixer terminology.
- Common routing presets.
- Foreground service and MediaSession/notification controls.
- Audio focus handling.
- LIVE keep-screen-on and Performance Lock.
- Spanish and English UI resources for current exposed functionality.

## Implemented but not yet stage-qualified

- Alpha10.3 English acoustic discrimination across a broad real-world set of Guide voices/encodes.
- COUNT recognition quality, especially short spoken numbers.
- High-stem-count double-buffered Loop/section transitions on representative physical phones/tablets.
- Dynamic multi-cue Guide relocation under rapid repeated live destination changes.
- Guide reanalysis and persistent-cache invalidation across multiple real sample packs.
- Session recovery after actual Android process death/reboot across OEMs.
- Backup/restore under low storage and real Drive/local/removable providers.
- Setlist Live repeated song changes during a real performance.
- USB stereo reconnect/output selection across representative interfaces.

## Deliberately not finished

- General-purpose speech-to-text Guide recognition.
- Full arbitrary virtual arrangement graph.
- Global relocation of every Guide event across arbitrary arrangement nodes.
- Gapless dual-song decoder graph/crossfade.
- AAC/M4A/FLAC/OGG expanded decoder pipeline.
- Waveform peak cache/editor and storage cache manager.
- Arbitrary 4/8/custom multichannel USB matrix.
- Tempo/time-stretch and pitch-shift DSP.
- MIDI USB/BLE, MIDI Learn and MIDI Clock.
- Pads, automation and SMPTE/LTC.
- Full `.stagepack` interchange semantics and LAN remote.
- Final onboarding/accessibility pass.

## Beta readiness

`0.2.0-alpha10.3` remains a pre-beta field-feedback build because English SECTION recognition exposed a release-blocking defect in alpha10.1/alpha10.2.

If representative failing English songs stop collapsing into false short labels while previously-good Spanish songs remain stable, the next planned release is `0.2.0-beta01`.

Beta01 should focus on onboarding, accessibility, recoverable errors, user-facing recognition diagnostics and COUNT-specific polish rather than another large audio subsystem.

Beta02 should focus on qualification/stability: high-track-count stress, repeated live transitions, process death, backup/restore failure cases, Setlist Live and USB stereo behavior.

## Qualification status

A successful CI build is not equivalent to stage qualification. Stable 0.2 requires representative physical Android hardware and prolonged live-use validation of synchronization, Guide behavior, backups, section transitions, Setlist Live and recovery safety.
