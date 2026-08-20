# StageGrid validation record

This file separates checks actually executed from checks that still require Android CI or physical hardware.

## Previously established checks

Earlier milestones already recorded passing host checks for:

- PCM WAV parsing;
- SPSC ordering;
- common-timeline source mapping;
- import-format policy;
- waveform peak-cache generation/regeneration;
- safe cache cleanup;
- OutputBus storage/availability policy.

These do not replace physical Android qualification.

## 0.5.0-alpha05 checks executed

### Arrangement runtime — PASS

The pure Kotlin arrangement model/runtime was compiled and exercised outside Android.

Verified:

- finite repeat remains on the current node until the configured count;
- finite repeat then advances to the next node;
- infinite repeat remains active without Exit;
- infinite repeat advances when Exit is requested;
- final node reports completion;
- live reorder preserves stable node IDs and the new order;
- repeat values clamp to the supported 1..16 range;
- pre-roll values clamp to 0..2 bars.

During this validation a real defect was found: moved nodes retained their old numeric `order`, causing graph normalization to undo the move. `ArrangementRuntime.move()` was corrected to renumber the list before normalization.

Final executable result:

```text
StageGrid 0.5 arrangement runtime checks: PASS
```

### Arrangement state/sidecar hardening — source review PASS

The runtime integration was reviewed for transport and persistence hazards. Fixes applied during the review:

- activating Arrangement while stopped no longer queues/seeks the next 1x node automatically;
- the active node is armed when normal playback actually begins, or after its count-in finishes;
- a node-specific pre-roll restores the previous session-wide count-in preference immediately after the launch captures its own bar count;
- Player state is collected sequentially instead of with `collectLatest`, preventing an 80 ms transport tick from cancelling `arrangement.json` initialization;
- malformed sidecars with duplicate node IDs are rejected and recovered from authored sections rather than reaching Compose with duplicate lazy-list keys.

These are source-policy/static checks; boundary timing still requires real playback on Android.

### Compose API review — PASS for reviewed API assumptions

The Live Workspace was reviewed against the Compose API assumptions used by the project:

- responsive `BoxWithConstraints` is used for the phone/tablet split;
- `Modifier.weight()` is used only inside RowScope/ColumnScope;
- no invalid explicit top-level `foundation.layout.weight` import is retained;
- Material3 bottom-sheet usage is explicitly opted into where required by the dependency version.

This is API/static review, not a full Android compilation claim.

### Dual-deck transport-safety review — source policy PASS

The dual-deck promotion path was reviewed so that:

- playing current song → standby starts muted and crossfades;
- paused/stopped current song → standby is promoted silently and remains stopped;
- a stopped/paused promotion does not request audio focus or start the foreground playback service merely because Next was pressed;
- PlayerState publishes `READY`/`PAUSED` immediately for a silent promotion instead of briefly claiming `PLAYING`;
- standby master is restored to the current user master level;
- old deck is paused/unloaded after promotion;
- failed preload/promotion does not remove the normal Setlist load fallback.

Physical dual-stream behavior still requires a device.

## GitHub Actions / APK

The repository workflow is configured to run automatically on `feature/**` pushes and execute:

```bash
./gradlew testDebugUnitTest assembleDebug --stacktrace
```

Successful builds upload:

```text
stagegrid-debug-apk
```

The connector used during implementation does not expose push-triggered workflow runs for this private branch reliably, so **the final 0.5 branch is not claimed as CI PASS here**. Check the `Android CI` run for `feature/0.5.0-alpha05` before installing the artifact.

## 0.5 tests you can perform without a USB interface

### Update / library preservation

1. Install the 0.5 debug APK over the existing stable-signed StageGrid debug APK.
2. Confirm the app updates without uninstalling.
3. Confirm existing songs, sections, setlists and mixer state remain present.

### Live Workspace — phone

1. Load a song.
2. Confirm the primary Player tab is now **Live Workspace**.
3. Verify title, waveform, time, NOW/NEXT and section chips are readable without opening another screen.
4. Verify Play/Pause, Stop, Stop All, Click, Guide and master are reachable quickly.
5. Open Quick Mix and verify track volume/mute/solo.
6. Open Arrangement and Setlist bottom sheets.
7. Rotate the phone and verify the UI remains usable with no clipped critical transport controls.

### Tablet / large-window layout

When a tablet or sufficiently large Android window is available:

1. confirm the layout switches around 720 dp;
2. confirm performance content remains left;
3. confirm Quick Mix/Setlist can remain visible on the right;
4. confirm waveform and section rail remain large enough for fast touches.

### Advanced compatibility

With Performance Lock disabled:

1. open **Advanced**;
2. verify the previous detailed Player remains functional;
3. open Section Editor;
4. verify Click subdivision/count-in controls;
5. if Native Guide is present, verify language/reanalysis controls remain accessible.

Enable Performance Lock and confirm Advanced/Library/Setlists disappear from stage navigation while Live Workspace, Mixer and Settings remain.

### Arrangement activation safety

1. Stop playback on a normal 1x arrangement node.
2. Press **Start Arrangement**.
3. Confirm the playhead does not jump to the next node merely from arming Arrangement.
4. Press Play and confirm the next node is only queued for the authored boundary once playback begins.

### Arrangement persistence

1. Load a song with at least three authored sections.
2. Open Arrangement.
3. Reorder nodes.
4. Leave/reopen the song or restart the app.
5. Confirm the new order remains.

### Finite repeat

1. Configure a section to 2x or 4x.
2. Start Arrangement.
3. Confirm the section repeats the requested number of times.
4. Confirm transition to the next node occurs at the authored section boundary.

### Infinite repeat / Exit

1. Configure one node to `∞`.
2. Start Arrangement.
3. Let it loop more than once.
4. Press **Exit at boundary** during the loop.
5. Confirm the current pass completes and the next node starts at its authored start.

### Pre-roll

1. Stop playback.
2. Note the existing Advanced count-in setting.
3. Configure an arrangement node with 1 or 2 bars pre-roll.
4. Select/start that node.
5. Confirm Click count-in occurs before imported stems enter.
6. Confirm the destination begins at the intended section boundary.
7. Return to Advanced and confirm the previous general count-in setting was not overwritten by the node pre-roll.

### Real Setlist preload / crossfade

Use two normal songs in a Setlist Live session.

1. Load/start the first song.
2. Wait until the UI reports `PRELOAD`/next ready.
3. Press Next while the first song is playing.
4. Confirm the next song begins through the prepared-deck handoff rather than a visible loading pause.
5. Listen for a short crossfade (~700 ms) and watch for underruns.
6. Repeat several times if possible.

Then test the safety case:

1. stop/pause the current song;
2. wait for next preload;
3. press Next;
4. confirm the next song becomes current **without starting audio automatically** and without a transient PLAYING state.

If the phone cannot keep two low-latency streams open, StageGrid may report preload failure and use the normal safe loading path. Record the phone model and error rather than treating that as proof of an arrangement failure.

### Backup round trip

1. change an arrangement order/repeat;
2. create `.stagebackup`;
3. restore it;
4. reopen the song;
5. confirm `arrangement.json` behavior/order remains.

## Deferred 0.4 + 0.5 external-interface tests

When a multichannel interface becomes available later, test:

- advertised/requested/opened channel counts;
- numbered physical output order;
- 4/6/8-out presets;
- custom bus routing;
- interface disconnect/reconnect;
- dual-deck preload/crossfade through the external interface;
- high-track-count underruns while multichannel + dual-deck systems are active.

## Release boundary

A green GitHub APK and successful phone tests are sufficient to continue accelerated feature development. They are not equivalent to final stage qualification; 1.0 still requires prolonged representative hardware acceptance.
