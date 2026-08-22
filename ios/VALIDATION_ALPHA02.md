# StageGrid iOS/iPadOS 0.7.0-alpha02 — Device validation sheet

Use this sheet on the exact iPad/iPhone and audio/MIDI hardware planned for the event.

## A. Installation / startup

- [ ] Cold launch reaches animated splash.
- [ ] First-run setup completes and is not repeated on next launch.
- [ ] App reopens with safe stopped session recovery.
- [ ] Library and setlists persist after force quit.

## B. Core audio

Test with the heaviest real song available.

- [ ] Every imported stem opens and plays.
- [ ] 30 minute continuous playback has no drift/dropouts.
- [ ] Pause/resume remains phase aligned.
- [ ] Seek remains aligned.
- [ ] Stop and Stop All are immediate.
- [ ] Background/screen-lock playback works.
- [ ] Returning from another app does not restart or double audio.

## C. DSP

- [ ] Original BPM / original key is clean.
- [ ] 90% BPM, original key: Click + Guide stay aligned.
- [ ] 110% BPM, original key: Click + Guide stay aligned.
- [ ] Original BPM, −2 semitones: Click + Guide stay aligned.
- [ ] Original BPM, +2 semitones: Click + Guide stay aligned.
- [ ] 90% +2 simultaneously has no audible drift.

## D. Sections / Guide

- [ ] Repeated manual section selections make no audible cut on sustained audio.
- [ ] Current / queued section display is correct.
- [ ] Cue Auto says the correct section.
- [ ] A counted 4/4 section produces section / 2 / 3 / 4 on the intended beats.
- [ ] Original Guide mode silences generated Cue Auto.
- [ ] Count-in 0/1/2 bars starts the target section correctly.

## E. Arrangement

- [ ] Reorder nodes.
- [ ] 2x, 4x and 16x repeat counts.
- [ ] Infinite repeat.
- [ ] Exit at next boundary.
- [ ] Pre-roll 0/1/2 bars.
- [ ] Cue metadata follows reordered destination.

## F. Setlist Live

- [ ] Start Setlist Live.
- [ ] Next song preloads before it is requested.
- [ ] Next while playing crossfades without silence.
- [ ] Next while stopped remains silent.
- [ ] Previous works.
- [ ] Five consecutive song changes succeed.
- [ ] Exiting Setlist Live clears standby deck.

## G. Native Guide

- [ ] Install the existing Android Guide Pack ZIP.
- [ ] Reanalysis progress moves through reference/templates/matching.
- [ ] No crash/OOM on the longest Guide track.
- [ ] Detected sections land on correct bars.
- [ ] Dynamic/section cues use the expected language.

## H. MIDI

- [ ] USB/Bluetooth MIDI device appears.
- [ ] Notes/CC/Program Change appear in monitor.
- [ ] Learn Play/Pause.
- [ ] Learn one section.
- [ ] Learn Mute/Solo.
- [ ] Mapping survives app restart/reconnection.
- [ ] MIDI Clock destination receives Start/24PPQN/Stop if required.

## I. USB audio / outputs

- [ ] Correct interface name appears.
- [ ] Requested 2/4/6/8 channel count matches actual granted channel count.
- [ ] Required sample rate is stable.
- [ ] Required output buses are verified physically one by one.

**Do not treat Simulator output behavior as hardware qualification.**

## Failure report format

For each issue capture:

- StageGrid version and commit.
- iPad/iPhone model + iPadOS/iOS version.
- Audio interface/model and connection method.
- Song stem count/sample rates.
- Exact action that caused the issue.
- Whether BPM/pitch/Cue/Arrangement/Setlist Live were active.
- Screenshot plus Xcode Console lines containing `StageGrid` when available.
