# StageGrid Visual System — Neon Slate

StageGrid 0.5.1 introduces a visual foundation intended for live performance rather than generic mobile administration.

## Design intent

- Dark, low-glare stage-first canvas.
- Cyan = primary/action/playback focus.
- Mint = healthy/ready/safe state.
- Violet = special modes, advanced functions and Labs.
- Amber = queued, caution and temporary attention.
- Red = destructive, stop-all and actual errors only.
- Large rounded panels with subtle outlines instead of default Material cards.
- Strong title hierarchy and compact uppercase status labels.
- Phone and tablet must share the same visual language; tablet gains density, not a separate theme.

## Core palette

| Token | Value | Use |
|---|---|---|
| Canvas | `#070A0F` | App background |
| Surface | `#0E131C` | Base panel |
| Surface Raised | `#141B26` | Cards / modules |
| Surface Bright | `#1B2533` | Waveform / focused surfaces |
| Cyan | `#55DDF5` | Primary actions / playhead |
| Mint | `#75E6B5` | Ready / healthy |
| Violet | `#A88BFF` | Labs / special state |
| Amber | `#FFC66D` | Queue / warning |
| Danger | `#FF6B7D` | Stop / errors |

The canonical tokens live in `ui/theme/StageGridTheme.kt`.

## Reusable chrome

`ui/components/StageGridChrome.kt` provides:

- `StageGridScreenHeader`
- `StageGridPanel`
- `StageGridPill`
- `StageGridMetric`

New screens should use these components before introducing one-off card styles.

## Live waveform

`SongWaveformOverview` uses the visual system directly:

- played waveform = cyan,
- future waveform = muted neutral,
- section boundaries = violet,
- playhead = cyan,
- raised dark gradient surface.

It remains cache-backed and does not change the realtime audio callback.

## StageGrid Labs policy

Experimental features are opt-in and disabled by default.

### Native Guide (Beta)

Native Guide is the first StageGrid Labs feature.

Rules:

1. The user must explicitly enable `Native Guide (Beta)` in Settings → StageGrid Labs.
2. Imported Guide tracks are **not** experimental and remain independent from this switch.
3. Installed Native Guide packs remain on disk when the experiment is disabled.
4. While disabled, Native Guide sample resolution/language output is gated and Advanced hides Native Guide controls.
5. Enabling the feature does not imply production readiness; UI must retain the `EXPERIMENTAL`/Beta label.
6. Future Native Guide changes must preserve the ability to return to normal imported-Guide playback without deleting user data.

The persistent flag is `native_guide_experimental` in `AppSettingsRepository` and is mirrored into `NativeGuideFeatureGate` for lower-level helpers.

## Next visual passes

The same system should next be applied explicitly to:

1. Live Workspace transport and NOW/NEXT hierarchy.
2. Mixer channel strips and routing states.
3. Arrangement cards / drag-reorder affordances.
4. Setlists and Live Setlist transition view.
5. Bottom navigation / tablet navigation rail.

Do not replace performance semantics while styling these surfaces: visual refactors must preserve transport, arrangement and audio behavior.
