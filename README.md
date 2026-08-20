# StageGrid

StageGrid is a native Android, local-first multitrack player for live performance. Stems, Native Click, Native Guide, sections and live arrangements stay tied to a shared native audio timeline instead of independent Android media players.

> **Current development release: `0.5.0-alpha05` — Arrangement Engine + Live Workspace integration alpha.**
>
> This build intentionally collapses the complete planned 0.5 feature sprint into the final alpha. Physical USB qualification from 0.4 remains deferred and is not implied by moving development forward.

## New in 0.5.0-alpha05

### Live Workspace

The normal performance path is now centered on a responsive Live Workspace instead of a generic tab-style Player.

**Phone**

- waveform, NOW/NEXT, sections and transport stay on the main surface;
- Quick Mix, Arrangement and Setlist open as bottom sheets;
- large Play/Pause, Stop and Stop All remain reachable without navigating away;
- Click, Guide, master level, queued section and preload status stay visible.

**Tablet**

- performance surface remains on the left;
- Quick Mix and Setlist can remain visible simultaneously on the right;
- layout switches automatically at a 720 dp width boundary.

The existing detailed Player is retained as **Advanced** for section editing, Native Guide language/reanalysis, detailed count-in, Click subdivision/routing and other configuration. Advanced disappears under Performance Lock so stage operation stays uncluttered.

### Virtual arrangement graph

Each song can now have a persistent arrangement sidecar:

```text
library/<song-id>/arrangement.json
```

An arrangement is a sequence of stable section nodes. Each node can currently define:

- order;
- finite repetition (`1x`, `2x`, `4x`, internally up to 16x);
- infinite repetition (`∞`);
- 0/1/2-bar pre-roll;
- Guide-enabled metadata reserved for node-aware Guide policy.

The graph is independent of the WAV file order. Reordering a node changes the live path without modifying audio files.

Arrangement execution reuses StageGrid's existing synchronized section/loop path preparation. The UI never becomes the audio clock.

### Boundary-safe live arrangement behavior

- finite repeats use the existing synchronized section loop path;
- infinite nodes continue until **Exit at boundary** is requested;
- Exit disables the active loop and queues the next node at the authored section boundary;
- tapping another arrangement node queues that destination through the existing prepared section-jump path;
- a stopped node with pre-roll uses Native Click count-in before its section begins.

### Real next-song preload

Setlist Live no longer treats reading a small part of each file as a complete preload.

`NativeAudioEngine` now owns two native engine handles:

```text
ACTIVE deck   → current song
STANDBY deck  → fully loaded next song
```

The standby deck prepares its WAV readers, decoder workers, mixer state, Click/Guide state and output configuration while the active song remains available.

When Next is pressed:

- if the current song is playing and the standby deck is ready, the streams overlap and master gains crossfade;
- if transport is stopped/paused, the prepared deck is promoted silently and remains stopped;
- if real preload is unavailable on that Android/device combination, Setlist Live can fall back to the normal safe song-load path.

Default Setlist Live crossfade: **700 ms**.

Two simultaneous low-latency streams are device/HAL dependent. A successful CI build does not prove that every phone/interface permits the dual-deck overlap path.

### Arrangement backup behavior

Arrangement sidecars live inside each StageGrid song directory, and `.stagebackup` already includes song-directory payloads. No destructive backup-format migration is required for 0.5.

### Version

- `versionName`: **`0.5.0-alpha05`**
- `versionCode`: **33**
- debug watermark: **`StageGrid 0.5.0-alpha05 • DEBUG`**

## Inherited 0.4 output layer

0.5 retains the 0.4 multichannel source implementation:

- Android/USB output discovery;
- 2/4/6/8-channel Oboe negotiation;
- persistent stereo-pair buses `1/2`, `3/4`, `5/6`, `7/8`;
- L / L+R / R routing inside a bus;
- 4/6/8-out presets and custom routing;
- Native Click/Guide bus routing;
- numbered output test tone;
- stereo fallback and reconnect handling;
- Room v3 migration and backup-compatible `outputBus` state.

**0.4 USB hardware qualification is still pending.** The user can validate it later when a suitable multichannel interface is available.

## Inherited 0.3 media layer

- WAV/MP3/M4A/AAC/FLAC/OGG import policy;
- import-time normalization of non-WAV sources to playback-ready PCM WAV;
- versioned waveform peak cache;
- Player waveform with shared-clock playhead and section markers;
- storage accounting and safe regenerable-cache cleanup.

## Architecture rule

Realtime audio remains isolated from UI, Room, SAF and media decoding:

```text
Compose Live Workspace / Advanced
              ↓
AudioEngineController
              ↓ JNI
NativeAudioEngine
   ├─ active deck
   └─ standby deck
              ↓
Oboe / AAudio output stream(s)
```

Within one song, all stems still share that song engine's authoritative output-frame clock. Cross-song crossfade deliberately overlaps two independent song engines; it does not pretend two unrelated songs share one musical timeline.

## GitHub debug APK

The `Android CI` workflow runs for `feature/**` pushes, executes unit tests + `assembleDebug`, and uploads:

```text
stagegrid-debug-apk
```

GitHub debug builds use the stable StageGrid debug key, so a newer CI debug APK can update a previous CI debug APK without uninstalling it.

## Qualification boundary

You can test most 0.5 behavior without a USB interface:

- Live Workspace phone layout;
- arrangement reorder/repeat/infinite Exit;
- pre-roll;
- Setlist real preload/crossfade on built-in audio;
- stopped Next must remain silent;
- Advanced access and legacy Player tools;
- backup/restore with arrangement sidecar;
- ordinary stereo playback and diagnostics.

Still deferred until suitable hardware is available:

- physical 4/6/8-output order;
- multichannel USB presets;
- USB disconnect/reconnect qualification;
- dual-deck crossfade through a multichannel external interface.

See [`docs/ROADMAP.md`](docs/ROADMAP.md), [`docs/STATUS.md`](docs/STATUS.md) and [`VALIDATION.md`](VALIDATION.md).

## Next feature version

`0.6` remains the DSP milestone: tempo/time-stretch and pitch-shift abstractions with latency compensation and synchronization-safe bypass behavior.
