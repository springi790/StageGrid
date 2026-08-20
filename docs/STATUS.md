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
- malformed sidecars with duplicate node IDs are rejected before they can reach Compose lazy-list keys.
- graph reconciliation removes deleted-section references and appends new sections.
- node order can be changed live and persists.
- repeat counts support finite repetition and infinite repetition.
- infinite nodes expose boundary-safe Exit intent.
- stopped nodes can request 0/1/2-bar pre-roll.
- node pre-roll is temporary and does not overwrite the user's general count-in preference.
- arrangement execution delegates timing to the existing native synchronized Loop/section path rather than introducing a Compose/UI clock.
- activating Arrangement while stopped is transport-safe: it arms the active node without automatically seeking/queueing the next 1x node.
- active-node scheduling begins when real playback begins or when a node pre-roll/count-in finishes.
- arrangement state consumes Player updates sequentially so transport ticks cannot cancel sidecar initialization.

## Live Workspace implemented

- Live Workspace replaces the generic Player as the primary performance screen.
- phone layout keeps waveform, NOW/NEXT, section rail and transport on the main surface.
- phone Quick Mix, Arrangement and Setlist use bottom sheets.
- tablet layout uses a 720 dp breakpoint and persistent right-side Quick Mix/Setlist workspace.
- queued arrangement node, loop, crossfade and preloaded-song state are visible.
- large Play/Pause, Stop and Stop All controls remain on the main performance surface.
- Click, Guide and master level are directly accessible.
- Quick Mix exposes per-track volume/mute/solo.
- Material3 bottom-sheet use has an explicit experimental API opt-in where required.

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
- silent promotion does not request audio focus or start foreground playback merely because Next was pressed;
- silent promotion publishes `READY`/`PAUSED` immediately rather than a transient false `PLAYING` state;
- old deck is paused/unloaded after promotion;
- PlayerState swaps to the already-prepared song without invoking a second normal `loadSong` path;
- if standby preparation/promotion fails, Setlist Live retains a normal safe-load fallback.

## Important dual-deck boundary

The two songs do not share a musical timeline. Each native engine owns the shared timeline for its own stems. Crossfade overlaps two independent song streams at the master-output level.

Some Android devices/HALs may not allow two simultaneous low-latency streams to the requested device. This is a hardware qualification item, not something CI can prove.

## Guide + arrangement boundary

Existing destination Guide phrase preparation continues to run through the synchronized section transition path. Arrangement node changes ultimately queue authored section destinations through that same path, so destination SECTION/COUNT/DYNAMIC material can follow the virtual order without moving Guide/audio work into Compose.

`ArrangementNode.guideEnabled` is stored as node metadata for future finer-grained per-node Guide policy; 0.5 does not expose an additional node-level Guide switch in the fast workspace.

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
- live reorder;
- repeat/pre-roll bounds.

A reorder defect was found during this check: the moved list retained old `order` values and normalization restored the previous order. The implementation was corrected to renumber nodes before normalization. The final executable result was:

```text
StageGrid 0.5 arrangement runtime checks: PASS
```

Additional source hardening found and corrected:

- stopped Arrangement activation previously risked queuing the next 1x node;
- stopped/paused deck promotion previously published a transient PLAYING state/requested focus unnecessarily;
- node pre-roll previously left the session count-in setting changed;
- `collectLatest` could cancel arrangement sidecar initialization on a fast Player tick;
- duplicate persisted node IDs were not rejected.

Compose scope use was reviewed: `Modifier.weight()` remains scope-provided by RowScope/ColumnScope; no invalid top-level weight import is retained.

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
