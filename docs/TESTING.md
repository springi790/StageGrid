# Testing StageGrid

## Tests executable in a normal Android development environment

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
./gradlew assembleDebug
```

`connectedDebugAndroidTest` requires a device/emulator. Audio quality/latency tests should use physical devices; emulators are not meaningful for low-latency performance qualification.

## Host native self-test

On Linux/macOS with a C++20 compiler:

```bash
./tools/run-native-selftest.sh
```

It validates:

1. generated PCM16 WAV parsing;
2. stereo SPSC write/read order;
3. deterministic master-output-frame → source-frame mapping for mixed source sample rates.

## 16-stem synchronization fixture

Generate a sparse test set:

```bash
python3 tools/generate_sync_fixture.py /tmp/stagegrid-sync
```

The script creates 16 mono 48 kHz/16-bit WAV files with impulses at 0, 30, 60, 120 and 300 seconds. The payload is created as sparse files where the host filesystem supports sparse allocation, so logical size is much larger than physical disk usage.

Qualification procedure on target hardware:

1. import all 16 stems as one song;
2. route/mix them at unity with no muting;
3. record the rendered output digitally or through a loopback interface;
4. verify impulse alignment at all five timestamps;
5. repeat after seeks and section path changes;
6. report maximum inter-stem sample offset and accumulated drift.

A production release target is zero accumulated timeline drift. Fixed decoder/filter latency must be common/compensated and must not grow with song duration.

## Stress test target

The full product specification calls for 32 simultaneous 48 kHz/24-bit 10-minute stems. Record at minimum:

- output device / negotiated sample rate;
- buffer burst and configured size;
- underrun count;
- native callback load;
- process memory;
- thermal state where the device exposes it;
- inter-stem drift at start/end.

The 0.1 source exposes diagnostics, but **this repository has not been hardware-qualified to claim 32-track reliability on arbitrary Android devices**.

## Import test matrix

Test at least:

- valid small ZIP;
- large ZIP within configured limit;
- corrupt ZIP;
- ZIP with no playable WAV;
- nested folders;
- Unicode and accented filenames;
- duplicate basenames;
- malformed/truncated WAV;
- mixed 44.1/48/96 kHz WAV stems;
- 8/16/24/32-bit PCM and 32-bit float WAV;
- malicious `../` ZIP entry;
- excessive file count / expansion limit.
