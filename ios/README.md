# StageGrid for iOS / iPadOS

Native SwiftUI + AVFoundation/CoreMIDI port of the Android StageGrid player.

Current iOS source version: **0.7.0-alpha02**  
Android parity baseline: **0.7.0-alpha05.4**

See [`PARITY.md`](PARITY.md) before using a build on stage.

## Generate the Xcode project

Requirements:
- macOS with a current Xcode installation
- XcodeGen (`brew install xcodegen`)

```bash
cd ios
xcodegen generate
open StageGridIOS.xcodeproj
```

The generated target is `StageGridIOS`, iOS/iPadOS 17+, iPhone + iPad.

## Run on a physical iPad/iPhone

1. Open `StageGridIOS.xcodeproj`.
2. Select the `StageGridIOS` target.
3. Signing & Capabilities → select your Apple Development Team.
4. Select the physical iPad/iPhone as destination.
5. Build/Run.

The simulator is useful for UI/library work, but it does **not** qualify low-latency audio, USB interfaces, background behavior, CoreMIDI hardware, or multichannel output.

## Current architecture

- `StageGridAudioEngine.swift` — AVAudioEngine dual-deck playback, DSP, click, cue voices, preload/crossfade and section handoff.
- `AppModel.swift` — session coordinator for Cue Auto, Arrangement, Setlist Live, Native Guide and MIDI actions.
- `GuidePackStore.swift` — installs/resolves the same Guide Pack ZIP structure used by Android.
- `NativeGuideAnalyzer.swift` — local fingerprint/template analysis; no cloud processing.
- `MidiManager.swift` — CoreMIDI monitor, Learn mappings and 24 PPQN Clock OUT.
- `BackupManager.swift` — `.stagebackup` create/restore.
- `LibraryStore.swift` — local songs/setlists plus ZIP/multi-file import.
- `WaveformView.swift` — cached waveform peak overview.

## Fast qualification sequence

For an urgent stage build, qualify in this order on the exact iPad/interface that will be used:

1. Import one known multitrack and play all stems for 15–30 minutes.
2. Verify Click/Guide at original BPM/key.
3. Test BPM 90% and 110%, then pitch −2/+2.
4. Hit section transitions repeatedly over sustained audio.
5. Enable Cue Auto and verify section name + 2/3/4 timing.
6. Test Arrangement repeats/∞/Exit.
7. Run a five-song Setlist Live with preload/crossfade.
8. Lock/unlock the iPad during playback.
9. Connect the intended MIDI controller and run Learn.
10. If using a multichannel interface, verify every physical output channel individually before stage use.

## CI

`.github/workflows/ios.yml` generates the Xcode project on macOS, builds the iOS Simulator target without code signing, and uploads `StageGrid-iOS-Simulator.zip`.

A successful simulator build is a source/build gate only; it is not hardware qualification.
