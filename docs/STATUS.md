# Implementation status — 0.5.0-alpha05

This document records source implementation, not hardware qualification.

## Release metadata

- version: `0.5.0-alpha05`
- versionCode: `33`
- branch: `feature/0.5.0-alpha05`
- based directly on `feature/0.4.0-alpha05`

## Arrangement engine implemented

- `ArrangementGraph` and stable `ArrangementNode` model.
- persistent `library/<song>/arrangement.json` sidecar.
- invalid/missing sidecars recover to the current authored section order.
- graph reconciliation removes deleted-section references and appends new sections.
- node order can be changed live and persists.
- repeat counts support finite repetition and infinite repetition.
- infinite nodes expose boundary-safe Exit intent.
- stopped nodes can request 0/1/2-bar pre-roll.
- arrangement execution delegates timing to the existing native synchronized Loop/section path rather than introducing a Compose/UI clock.

## Live Workspace implemented

- Live Workspace replaces the generic Player as the primary performance screen.
- phone layout keeps waveform, NOW/NEXT, section rail and transport on the main surface.
- phone Quick Mix, Arrangement and Setlist use bottom sheets.
- tablet layout uses a 720 dp breakpoint and persistent right-side Quick Mix/Setlist workspace.
- queued arrangement node, loop, crossfade and preloaded-song state are visible.
- large Play/Pause, Stop and Stop All controls remain on the main performance surface.
- Click, Guide and master level are directly accessible.
- Quick Mix exposes per-track volume/mute/solo.

## Advanced compatibility surface

The previous detailed Player remains available as **Advanced** outside Performance Lock. It retains:

- Section Editor entry;
- manual section controls;
- detailed count-in;
- Click subdivisions and route;
- per-song Native Guide language switching;
- Native Guide reanalysis;
- detailed legacy Player diagnostics/workflow.

Performance Lock exposes only Live Workspace, Mixer and Settings.

## Real Setlist preload implemented

`NativeAudioEngine` now owns two native engine handles.

- active deck: current song;
- standby deck: next prepared song.

Standby preparation performs real native song loading and applies:

- track mixer state;
- output route/bus;
- Click state/subdivision/route/bus;
- Guide enable state;
- current selected output device/channel request.

Setlist Live marks `nextReady` only when the standby native deck is actually prepared.

## Cross-song handoff implemented

- playing active deck: standby starts muted and master gains crossfade;
- default Setlist crossfade: 700 ms;
- stopped/paused active deck: standby ownership swaps silently and remains stopped;
- old deck is paused/unloaded after promotion;
- PlayerState swaps to the already-prepared song without invoking a second normal `loadSong` path;
- if standby preparation/promotion fails, Setlist Live retains a normal safe-load fallback.

## Important dual-deck boundary

The two songs do not share a musical timeline. Each native engine owns the shared timeline for its own stems. Crossfade overlaps two independent song streams at the master-output level.

Some Android devices/HALs may not allow two simultaneous low-latency streams to the requested device. This is a hardware qualification item, not something CI can prove.

## Inherited 0.4 implementation

- 2/4/6/8 output negotiation;
- OutputBus 1/2, 3/4, 5/6, 7/8;
- L/L+R/R within a bus;
- multichannel presets/custom routing;
- output-test tone;
- stereo fallback/reconnect;
- Room v3 outputBus migration;
- backup-compatible routing state.

Physical multichannel USB qualification remains pending.

## Inherited 0.3 implementation

- WAV/MP3/M4A/AAC/FLAC/OGG import handling;
- import-time compressed normalization;
- waveform peak cache/UI;
- storage/cache manager.

## Validation executed during 0.5 implementation

Pure arrangement runtime checks were executed with the local Kotlin compiler. They covered:

- finite repeat;
- infinite repeat + Exit;
- terminal node completion;
- live reorder.

A reorder defect was found during this check: the moved list retained old `order` values and normalization restored the previous order. The implementation was corrected to renumber nodes before normalization. The check then passed.

Compose scope use was reviewed against current Android Compose documentation. `Modifier.weight()` remains scope-provided by RowScope/ColumnScope; no invalid top-level `weight` import is retained.

## Still requires Android/physical testing

- complete `testDebugUnitTest assembleDebug` result from GitHub Actions for the final branch head;
- actual phone/tablet layout and touch ergonomics;
- arrangement boundary behavior during real audio playback;
- repeated finite/infinite loops over long songs;
- pre-roll timing;
- real dual-deck preload on target phone;
- crossfade underruns/thermal behavior;
- process/session recovery around arrangement and Setlist Live;
- backup/restore of arrangement sidecar;
- all outstanding 0.4 USB multichannel qualification.

## Next planned feature version

0.6: tempo/time-stretch and pitch-shift DSP with latency compensation and synchronization-safe bypass behavior.
