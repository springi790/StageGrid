# StageGrid validation record

This file separates checks actually executed from checks that still require GitHub's full Android build environment or physical hardware.

## Previously recorded foundation checks

- native WAV/SPSC/common-timeline self-test — **PASS**;
- 16-stem synchronization fixture generation — **PASS**;
- historical native C++/JNI stub compilation — **PASS** for the earlier stereo foundation;
- 0.3 import-format policy — **PASS**;
- 0.3 waveform absolute-timeline cache/regeneration — **PASS**;
- 0.3 storage-cache deletion safety — **PASS**.

## 0.4.0-alpha05 checks executed during this implementation

### OutputBus policy — PASS

The final `OutputBus` policy was compiled and executed with the local Kotlin/JVM compiler.

Verified:

- 2 channels exposes only `1/2`;
- 4 channels exposes `1/2`, `3/4`;
- 6 channels exposes `1/2`, `3/4`, `5/6`;
- 8 channels exposes all four buses through `7/8`;
- unknown persisted bus codes safely decode to `1/2`;
- physical UI labels remain one-based and `OUT_7_8` resolves to physical outputs 7 and 8.

Executable result:

```text
StageGrid 0.4 output bus checks: PASS
```

A matching JUnit regression file exists at `app/src/test/java/dev/stagegrid/model/OutputBusTest.kt`.

### Database compatibility — source inspection complete

Room was advanced to schema version 3 with migration:

```sql
ALTER TABLE tracks ADD COLUMN outputBus INTEGER NOT NULL DEFAULT 0
```

This is intentionally additive and defaults existing tracks to bus `1/2`. Real upgrade of an installed v2 database still requires Android-device validation.

### Backup compatibility — source inspection complete

Portable backup format remains version 1.

- new manifests write `outputBus`;
- restore reads `outputBus` with a bounded `0..3` value;
- missing field defaults to `0`, preserving pre-0.4 backups.

The existing payload size/SHA-256 validation path was left intact.

### CI workflow configuration — verified

`.github/workflows/android.yml` currently triggers on:

- `main` / `master` pushes;
- `feature/**` pushes;
- pull requests;
- manual dispatch.

The workflow prepares the stable StageGrid debug signing key and runs:

```bash
./gradlew testDebugUnitTest assembleDebug --stacktrace
```

then uploads `app-debug.apk` as `stagegrid-debug-apk` on success.

### Latest full Android build — not claimed here yet

The connected GitHub status action currently returns no normal commit-status entries for feature-branch pushes, and the available workflow-run lookup is limited to pull-request-triggered runs. Therefore this record does **not** invent a CI result for the latest 0.4 head.

The feature push does trigger the workflow; success/failure must be taken from the actual Android CI run/artifact in GitHub Actions.

## 0.4 code paths requiring full build validation

The full Android/NDK build must validate together:

- Kotlin ↔ JNI signatures for output bus, requested channels and output-test APIs;
- Oboe multichannel stream builder calls;
- Room v3 generated schema;
- Compose Mixer/Settings signature changes;
- ES/EN resource-key resolution;
- legacy one-argument output-device call compatibility;
- native/JNI linkage for the expanded diagnostics structure.

## Physical Android/USB validation required

### Channel negotiation

For a real USB interface record:

- advertised channel count;
- requested StageGrid channel count;
- actual opened channel count;
- sample rate;
- frames per burst;
- fallback state.

### Output test

At a safe monitor/interface level, verify each numbered test button reaches only the corresponding physical output.

### Routing matrix

Verify:

- 4-out preset: Tracks 1/2, Click 3, Guide 4;
- 6-out preset where relevant;
- 8-out preset where hardware allows;
- custom bus + `L / L+R / R` assignments;
- no unexpected duplication/crosstalk between buses.

### Persistence / portability

Verify custom track bus assignments survive:

- song reload;
- app restart;
- Room v2→v3 upgrade;
- new 0.4 `.stagebackup` create/restore;
- restore of a pre-0.4 backup, which should default to 1/2.

### Disconnect/reconnect

Test interface removal while stopped and during playback.

Expected safety behavior:

- fallback to Android stereo;
- no missing tracks solely because they were assigned to 3/4, 5/6 or 7/8;
- no automatic audible resume after a live stream-loss event;
- preferred interface restored on reconnect when Android exposes a matching device again.

### Performance regression

With representative high-track-count content:

- Play/Pause/Stop/Seek;
- Loop / Exit Loop;
- section jump;
- Click and Guide;
- Setlist transitions;
- compare underruns/callback load at stereo versus 4/8 channels;
- verify no accumulated inter-stem drift.

## Qualification statement

`0.4.0-alpha05` is feature-complete source for the 0.4 milestone, not a claim of universal USB interface compatibility. Android audio HALs, interface descriptors and physical channel order vary by device and must be qualified on hardware before StageGrid is called stage-ready.
