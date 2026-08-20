# Implementation status — 0.2.0-alpha07

## Implemented in source

### Library / import

- Room library with Song, Track, Section, Setlist and SetlistSong records.
- ZIP, folder and multi-file import through Android Storage Access Framework.
- Linked document-provider folder browsing, including Google Drive when exposed by Android.
- WAV playback plus one-time MP3 → PCM WAV normalization.
- Import percentage/stage reporting, metadata editing and Native Guide analysis/reconstruction.
- Confirmed local multitrack deletion from Library.
- A loaded song is unloaded before deletion so native WAV readers no longer hold its files.
- Song files are first staged outside the live library path; if Room deletion fails, StageGrid attempts to restore the staged folder.
- Room foreign-key cascades remove the deleted song's tracks, sections and setlist references.
- External `.stagebackup` files are not modified by local-song deletion.

### Portable backup / restore — alpha06

- Manual `.stagebackup` snapshot creation from Settings.
- Backup destination selected with `ACTION_OPEN_DOCUMENT_TREE`; local folders, compatible USB storage and providers such as Google Drive are supported by the same SAF path.
- Complete app-private song directories are archived, including normalized stems, generated Guide files and `native-guide-events.json` sidecars.
- Room Song/Track/Section/Setlist/SetlistSong state is serialized into the backup manifest.
- The currently installed user-supplied Guide pack is included so restored songs can continue changing Guide languages.
- Absolute Android-private track paths are converted to song-relative paths in the archive and rebuilt for the new device during restore.
- Every payload file is declared with byte size + SHA-256.
- Restore first copies into cache, performs bounded zip-safe extraction, checks the exact declared file set, sizes and hashes, then installs files/Room state.
- Stable IDs are merged/replaced; unrelated local songs/setlists are not deliberately deleted by restore.
- Native song decoders are unloaded before restored WAV paths are replaced.
- Backup/restore exposes percentage, stage and current detail in the UI.

### Shared-clock audio / live path engine

- One Oboe stereo stream and one master transport clock.
- Streaming decoder threads + preallocated SPSC rings.
- Two decoder banks per track for live Loop/section path preparation.
- All-track realtime bank handoff after readiness/alignment checks.
- Stale late swaps are rejected and exposed through diagnostics.
- Per-track volume/mute/solo/pan and L/L+R/R routing.
- Native sample-clock Click with subdivisions and routing.

### Sections / live navigation

- Visual/manual Section Editor and automatic Guide-derived section proposals.
- Manual section choices wait for the explicit `endMs` of the current section.
- Destination enters at its explicit `startMs`.
- Section Loop / Exit Loop and native 1/2-bar count-in.
- Player copy now describes a queued manual change as waiting for the current section to end rather than the superseded next-bar policy.

### Native Guide — alpha06 arrangement layer

- User-installed ES/EN/FR/PT cue packs where those languages exist in the supplied ZIP.
- Offline sample/template recognition and structured `native-guide-events.json` persistence.
- Generated `StageGrid Native Guide.wav`, per-song Guide language switching and delayed section recovery after BPM is added.
- Persisted section proposals can resolve an edited Room section back to its canonical Guide cue key.
- On a manual live section choice, StageGrid can preload the selected destination's spoken section cue and schedule it relative to the current section end.
- Early choices target approximately one bar before the boundary; late choices move shortly after the tap only when enough useful time remains.
- The fixed generated Guide is temporarily suppressed around the replacement section-name call to prevent conflicting section names.
- Short cue PCM is loaded/resampled off the realtime thread and mixed against the native master timeline.
- JVM coverage exists for arrangement cue timing and section-boundary policy.

### Setlist Live — alpha07

- A selected non-empty setlist can enter **Setlist Live** mode.
- Player shows setlist name, current song, next song, position in setlist, Previous/Next and Exit Setlist controls.
- NEXT/PREV stop and unload the current native song graph before loading the destination song when changing songs.
- Destination songs are loaded stopped; StageGrid does not auto-emit audio after a NEXT/PREV action.
- The next song receives a bounded warm preload after current-song loading begins: StageGrid reads the first 512 KiB of each local normalized track into the operating-system file cache.
- Warm-preload work runs on an IO dispatcher and does not create a second native decoder graph.
- Player reports whether next-song warm preload is running or ready.
- Deterministic unit coverage exists for initial/current/previous/next setlist index policy.

### UX / live operation

- Simplified Player/Mixer terminology and routing presets.
- Foreground service, MediaSession, audio focus, LIVE mode and Performance Lock.
- Local setlists plus alpha07 Setlist Live navigation.
- Spanish and English UI strings.

## Implemented, but not yet stage-qualified

- Double-buffered transitions and arrangement-aware Guide section calls still require representative physical-device/high-track-count stress testing.
- The arrangement-aware Guide layer currently replaces/relocates the selected **section-name** call; it does not yet rebuild every count/dynamic cue for an arbitrary virtual arrangement.
- Extremely late section choices can intentionally skip their replacement spoken cue and can miss an inactive-bank safe handoff rather than applying stale audio.
- Backup/restore is a manual snapshot workflow, not continuous Drive synchronization.
- Guide recognition is sample-pack matching, not arbitrary speech-to-text.
- Guide fingerprint cache is currently in-memory per app process.
- Setlist alpha07 preload is OS file-cache warming, not gapless dual-engine preload/crossfade.
- Local deletion staging/rollback needs physical-device validation under low-storage and forced-failure conditions.
- USB device selection exists, but arbitrary multichannel routing is not implemented.

## Deliberately not exposed as finished

- Full arbitrary arrangement graph with relocation of all Guide/count/dynamic events.
- In-place Guide audio re-analysis after changing/installing a pack.
- Persistent on-disk Guide fingerprint cache.
- Gapless/overlapped next-song decoder graph and crossfade transitions.
- Persisted/restorable in-progress performance session.
- Waveform cache/editor.
- AAC/M4A/FLAC/OGG expansion.
- Tempo/time stretch and pitch shift.
- Advanced multichannel USB routing.
- MIDI, pads, automation and SMPTE/LTC.
- Full `.stagepack` project interchange semantics.
- LAN remote, tablet split workspace, onboarding and final accessibility pass.

## Qualification status

StageGrid `0.2.0-alpha07` is a development alpha. CI validates unit tests and debug assembly; stage qualification still requires physical Android devices, high stem counts, repeated section/Loop stress, USB reconnect/routing tests, Setlist Live NEXT/PREV and warm-preload validation, safe-deletion failure/low-storage tests, backup/restore testing against real Drive/local providers and crash/session-recovery validation.
