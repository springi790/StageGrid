# StageGrid iOS/iPadOS — 0.7.0-alpha01

Native SwiftUI + AVFoundation port based on Android `0.7.0-alpha05.4`.

## What works in this first port

- iPhone and iPad adaptive SwiftUI shell.
- Animated StageGrid splash.
- First-run quick setup.
- Local multi-file audio import using the system document picker.
- Local JSON library in the app Documents directory.
- Synchronized multitrack playback with one shared AVAudioEngine start time.
- Per-track volume, pan, mute and solo.
- Master volume.
- Click generated locally and kept on the same tempo ratio as the stems.
- Original Guide track enable/disable.
- Live BPM control from 75% to 150% of the song base BPM.
- Tonality control ±12 semitones using AVAudioUnitTimePitch; Guide and Click are not pitch shifted.
- Manual sections and queued section transitions.
- Basic setlists.
- Background audio mode.

## Current alpha limitations

- The Android Native Guide analyzer and installed Guide Pack are not ported yet.
- Cue Auto speech/count samples are not yet implemented on iOS.
- Section jumps use a synchronized reschedule in this first alpha; the Android dual-bank path engine is not yet mirrored on iOS.
- CoreMIDI mapping/MIDI Learn is the next porting block.
- Multichannel USB routing currently follows the iOS system output route; per-bus Core Audio routing is not yet exposed in UI.
- Android Room/DataStore files are not directly shared with iOS. The iOS library is intentionally local JSON for the emergency alpha.

## Generate/open the Xcode project

The project definition is reproducible with XcodeGen.

```bash
cd ios
brew install xcodegen   # first time only
xcodegen generate
open StageGridIOS.xcodeproj
```

Choose the `StageGridIOS` scheme and an iPhone/iPad simulator or physical device.

## Physical iPhone/iPad

For a real device, open Signing & Capabilities in Xcode and select your Apple Development Team. The bundle identifier defaults to:

`dev.stagegrid.ios`

Change it if your Apple account requires a unique identifier.

## CI

`.github/workflows/ios.yml` generates the Xcode project on macOS, builds for a generic iOS Simulator with code signing disabled, and uploads `StageGrid-iOS-Simulator.zip`.
