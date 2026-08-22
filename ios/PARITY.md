# StageGrid iOS/iPadOS parity — 0.7.0-alpha02

Base Android validated by the user: `0.7.0-alpha05.4` (`feature/0.7.0-alpha05`).

Legend:
- **PORTADO**: source implementation exists on iOS/iPadOS.
- **VALIDAR**: implementation exists but needs Xcode/device validation before stage use.
- **HARDWARE**: behavior depends on the actual iPad/interface/route and cannot be certified in Simulator.

| Area | Android | iOS/iPadOS alpha02 | Notes |
|---|---|---|---|
| Native UI | Compose | **PORTADO** SwiftUI | iPhone + iPad adaptive tabs/live surface |
| Splash | Yes | **PORTADO** | Animated StageGrid mark |
| First-run quick setup | Yes | **PORTADO** | Live/lock/click/count/guide source/language |
| Library | Yes | **PORTADO** | Local JSON catalog + Files document providers |
| Multi-file stem import | Yes | **PORTADO / VALIDAR** | AVAudioFile must validate every compressed format used in production |
| ZIP stem import | Yes | **PORTADO** | ZIPFoundation |
| Song metadata | Yes | **PORTADO** | title/artist/BPM/key/signature/grid offset/notes |
| Waveform cache | Yes | **PORTADO / VALIDAR** | cached peak overview + section markers + seek |
| Multitrack playback | Native Oboe | **PORTADO / VALIDAR** AVAudioEngine | common host-time scheduling |
| Play/Pause/Stop/Stop All | Yes | **PORTADO** | global mini transport included |
| Background audio | Yes | **PORTADO / HARDWARE** | AVAudioSession `.playback` + `UIBackgroundModes=audio` |
| Mixer volume | Yes | **PORTADO** | per stem |
| Mute / Solo | Yes | **PORTADO** | per stem |
| Pan | Yes | **PORTADO** | per stem |
| Master | Yes | **PORTADO** | main mixer |
| BPM / time stretch | Signalsmith | **PORTADO / VALIDAR** AVAudioUnitTimePitch | 75–150%, shared per-deck ratio |
| Pitch / tonalidad | Signalsmith | **PORTADO / VALIDAR** AVAudioUnitTimePitch | ±12 semitones; Guide/Click pitch remains original |
| Click | Native | **PORTADO** | generated bar buffer, 1/4–1/16 subdivisions |
| Count-in | Yes | **PORTADO** | 0–2 bars; spoken Guide count still requires end-to-end stage validation |
| Manual sections | Yes | **PORTADO** | add/jump/delete, authored start/end |
| Quantized section transition | Yes | **PORTADO / VALIDAR** | second deck + short smooth handoff |
| Section microcut protection | ~4ms bank handoff | **PORTADO / VALIDAR** | prepared second graph + ~6ms smooth handoff |
| Cue Auto | Yes | **PORTADO / VALIDAR** | same installed Guide Pack format |
| Cue + 2/3/4 | Yes | **PORTADO / VALIDAR** | sample scheduling on musical grid |
| Guide source Original/Cue | Yes | **PORTADO** | Mixer: `Off · Guía` / `On · Cue Auto` |
| Guide Pack ZIP | Yes | **PORTADO** | same Spanish/English/French/Portuguese folder parser |
| Native Guide local analysis | Yes Beta | **PORTADO / VALIDAR** | local 10 ms energy fingerprints; no cloud |
| Native Guide inferred sections | Yes | **PORTADO / VALIDAR** | bar-grid snap |
| Arrangement | Yes | **PORTADO / VALIDAR** | reorder/repeat finite/∞/pre-roll/cue/exit at boundary |
| Setlists CRUD | Yes | **PORTADO** | add/remove/reorder/delete |
| Setlist Live | Yes | **PORTADO / VALIDAR** | active + standby, preload + 700 ms crossfade |
| Session recovery | Yes | **PORTADO / VALIDAR** | restores stopped for safety |
| Backup/restore | Yes | **PORTADO / VALIDAR** | `.stagebackup` via ZIPFoundation |
| MIDI discovery/monitor | 0.7 started | **PORTADO / VALIDAR** CoreMIDI | USB/CoreMIDI endpoints |
| MIDI Learn | Planned/current work | **PORTADO / VALIDAR** | transport, songs, sections, track mute/solo |
| MIDI Clock OUT | Planned/current work | **PORTADO / HARDWARE** | 24 PPQN + Start/Stop |
| Output channel negotiation | 2/4/6/8 | **PORTADO / HARDWARE** | AVAudioSession requests 2/4/6/8 and reports actual route |
| Per-track output bus preference | 1/2..7/8 | **PORTADO (model/UI)** | preferences persist and unavailable buses show fallback |
| Discrete physical multi-out matrix | Native source implementation | **HARDWARE / OPEN** | Must be completed/qualified against target USB interface before claiming 1:1 multi-out parity |
| Output test tone per channel | Yes | **OPEN** | depends on the final discrete output path |
| Android-specific Oboe diagnostics | Yes | N/A | iOS uses AVAudioSession/AVAudioEngine diagnostics instead |

## Release gate for a stage-ready iPad build

Do **not** call an iOS build stage-ready until all of these pass on the actual device/interface:

1. Cold launch, onboarding, re-launch and safe session recovery.
2. 10+ stem song for at least 30 minutes with no drift or dropouts.
3. BPM 90% / 110% and pitch ±2 with Click + Guide synchronized.
4. Repeated section changes over sustained audio with no audible click/drop.
5. Cue Auto section name and optional count in the expected beats.
6. Arrangement finite repeat, infinite repeat + Exit, and pre-roll.
7. Setlist Live transitions through at least five songs.
8. Screen lock/background/resume while audio is running.
9. MIDI controller learn/reconnect and Clock if it is part of the performance rig.
10. Every required physical output bus verified with the exact USB interface/cabling.
