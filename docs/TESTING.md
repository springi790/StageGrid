# Testing StageGrid

## Automated Android build

GitHub Actions and a normal development machine run:

```bash
./gradlew testDebugUnitTest assembleDebug
```

A physical device/emulator can additionally run:

```bash
./gradlew connectedDebugAndroidTest
```

Emulators are not meaningful for low-latency or USB multichannel qualification.

## 0.4.0-alpha05 — required physical tests

### 1. Update / database migration

Install 0.4 over an existing 0.3 debug build signed by the stable CI key.

Verify:

- app upgrades without uninstalling;
- existing songs/setlists remain;
- old tracks initially route to outputs `1/2`;
- existing `L / L+R / R` choices remain intact.

This exercises Room migration `2→3` (`tracks.outputBus DEFAULT 0`).

### 2. Device negotiation

For each available USB interface:

1. connect it before or after opening StageGrid;
2. Settings → Audio outputs → select it;
3. record **advertised channels**, **requested channels**, **opened channels**, sample rate and buffer burst;
4. if requested > opened, confirm StageGrid reports multichannel fallback instead of failing silently.

Target matrix where hardware is available:

| Interface capability | Expected StageGrid request |
| --- | ---: |
| stereo | 2 |
| 4 outputs | 4 |
| 6 outputs | 6 |
| 8+ outputs | 8 |

Android/HAL may legitimately expose fewer channels than the interface's marketing specification. Record what StageGrid actually opens.

### 3. Physical output order

Lower monitor/interface gain before testing. In Settings → Test outputs, tap outputs sequentially.

Verify the short low-level tone appears only on:

- button 1 → physical output 1;
- button 2 → physical output 2;
- ... through the negotiated channel count.

Stop and investigate if channel order does not match the interface labels. Do not compensate by guessing a custom matrix until the observed mapping is recorded.

### 4. Presets

With a multitrack containing Tracks + Guide:

**4-out**

- Tracks → 1/2 stereo;
- Native Click → 3;
- Guide → 4.

**6-out**

- Main tracks → 1/2;
- vocals → 3/4;
- Click → 5;
- Guide → 6.

**8-out**

- drums/bass → 1/2;
- guitar/keys/synth/pad → 3/4;
- vocals/strings/percussion/other → 5/6;
- Click → 7;
- Guide → 8.

Verify isolation: muting a physical output/bus externally should not reveal unexpected copies on another bus.

### 5. Custom routing persistence

Change several tracks to different bus + `L / L+R / R` combinations.

Verify the assignment survives:

1. leaving/re-entering Mixer;
2. loading a different song and returning;
3. force-closing/reopening StageGrid;
4. creating/restoring a new 0.4 `.stagebackup`.

Also restore a pre-0.4 backup if available: it should load safely with tracks on bus `1/2`.

### 6. Disconnect / reconnect

Run twice: once while stopped, once during playback.

1. select USB interface;
2. configure non-1/2 buses;
3. unplug interface;
4. verify StageGrid falls back to Android stereo and does not lose tracks;
5. during a live-disconnect case, verify sound does **not** auto-resume unexpectedly;
6. reconnect the same interface;
7. verify StageGrid restores the preferred output when Android exposes it again;
8. confirm the same routing assignments are still present.

### 7. Shared-clock regression under multichannel

With 16+ stems where practical:

- Play/Pause/Stop/Seek;
- Loop / Exit Loop;
- manual section jump at authored boundary;
- Native Click subdivisions;
- Native Guide on/off and a destination Guide phrase;
- Setlist Live Previous/Next;
- watch underruns and callback CPU load.

There must be no accumulating inter-stem drift simply because output channel count changed.

## Host native self-test

On Linux/macOS with a C++20 compiler:

```bash
./tools/run-native-selftest.sh
```

It validates the foundation WAV/SPSC/common-timeline path. It is useful regression coverage but does not emulate a real Android USB HAL.

## 16-stem synchronization fixture

```bash
python3 tools/generate_sync_fixture.py /tmp/stagegrid-sync
```

The fixture creates 16 mono 48 kHz/16-bit WAV stems with aligned impulses. Record a rendered output/loopback and verify impulse alignment at multiple timestamps before and after seek/section changes.

A production target is zero accumulated timeline drift. Fixed common latency can be measured/compensated; drift that grows with song duration is a release blocker.

## General import regression

Keep at least these cases in the regression set:

- WAV, MP3, M4A, AAC, FLAC, OGG;
- nested ZIP/folder import;
- Unicode filenames;
- malformed/truncated media;
- mixed 44.1/48/96 kHz source WAVs;
- 8/16/24/32-bit PCM and 32-bit float WAV;
- malicious `../` ZIP entry;
- excessive file count / expansion limit.
