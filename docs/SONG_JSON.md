# StageGrid `song.json` v1

`song.json` is an optional, open interchange manifest. StageGrid never requires it just to import a folder/ZIP of WAV stems.

## Supported v1 fields in the 0.1 importer

```json
{
  "version": 1,
  "title": "Stage Demo",
  "artist": "Local Band",
  "bpm": 72,
  "key": "C",
  "timeSignature": "4/4",
  "tracks": [
    { "name": "Drums", "file": "Drums.wav", "type": "drums" }
  ],
  "sections": [
    { "name": "Intro", "start": 0.0 },
    { "name": "Verse", "start": 16.0 }
  ]
}
```

- `title`: optional string.
- `artist`: optional string.
- `bpm`: optional positive number.
- `key`: optional string; StageGrid does not force enharmonic spelling.
- `timeSignature`: optional `numerator/denominator` string.
- `tracks[].file`: basename used to match an imported file.
- `tracks[].type`: one of `other`, `drums`, `bass`, `guitar`, `keys`, `synth`, `strings`, `vocals`, `percussion`, `click`, `guide`, `pad`.
- `sections[].start`: seconds from the original master timeline.

Unknown fields are ignored so the manifest can grow without breaking the v1 importer.

## Section end calculation

Only starts are portable in v1. After import, StageGrid sorts section starts. Each section ends at the next section start; the final section ends at the longest playable stem duration.

## Future-compatible fields

The long-term format can add IDs, arrangement references, count-ins, guide cues, colors, MIDI cue files, automation assets and routing hints. These fields should be versioned and optional. A transport package (`.stagepack`) should place `manifest.json` next to `audio/`, `artwork/`, `midi/` and `automation/`; it is not implemented in 0.1.
