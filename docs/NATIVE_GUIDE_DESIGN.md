# Native Guide recognition design

StageGrid `0.2.0-alpha04` introduces an import-time native Guide pipeline. The goal is to turn a conventional rendered Guide stem into structured local cue events without requiring a cloud speech-recognition service.

## Sample-pack ownership

StageGrid does not ship third-party Guide audio inside the source repository or APK. A user installs a Guide sample ZIP they are licensed to use through Android's document picker. Compatible samples are copied to app-private storage and validated as WAV files.

The installer is bounded by entry count, sample count and expanded byte limits. It ignores click samples, Ableton sidecars and macOS metadata when they are not Guide cue WAVs.

## Recognition model

This feature is intentionally **template based**, not generic speech-to-text.

At import time:

1. StageGrid identifies the song's Guide stem through the existing stem classifier or `song.json` type metadata.
2. The Guide stem has already been normalized to a deterministic WAV path when necessary.
3. StageGrid computes a short-time RMS envelope in 10 ms windows.
4. Candidate spoken regions are located from the Guide energy envelope.
5. Every installed Guide cue sample has its active envelope normalized into a compact fingerprint.
6. Candidate regions are compared with those templates using normalized correlation with a small timing search radius.
7. Only sufficiently strong/unambiguous matches become native Guide events.

The implementation is robust to normal gain changes and lossy-compression differences because matching is based on the normalized temporal energy shape rather than exact PCM equality.

Recognition, sample-pack scanning and rendering all happen outside the Oboe real-time callback.

## Canonical cue keys and languages

Installed sample packs are normalized into canonical semantic keys such as:

```text
verse_1
chorus
bridge_2
pre_chorus
keys
drums_in
build
1
2
3
4
```

This allows a recognized cue in one language to be rebuilt using a matching cue in another installed language. The current sample-pack parser recognizes ES, EN, FR and PT layouts when present.

The output Guide language can be `auto` or an installed language. Auto prefers the language recognized from the source Guide, then the device language, then an available fallback.

## Native event sidecar

Recognized events are written to:

```text
native-guide-events.json
```

Each cue records:

- canonical key;
- cue kind (`SECTION`, `COUNT`, `DYNAMIC`);
- detected language;
- absolute cue time;
- recognition confidence.

The sidecar also records any automatic section proposals. Keeping these events structured is important for the future arrangement engine: a later version can attach Guide events to section nodes and relocate them when a live arrangement changes.

## Generated Guide audio

For alpha04, recognized events are rendered to:

```text
StageGrid Native Guide.wav
```

The renderer selects the corresponding installed sample in the requested output language, resamples short cue samples when needed, and writes a mono PCM16 WAV aligned to the song timeline. This generated Guide is loaded by the existing shared-clock multitrack engine just like other deterministic PCM assets.

When native reconstruction succeeds, the original imported Guide is retained but muted so it remains available as a reference/fallback.

## Automatic section proposals

Section Guide calls are interpreted as announcing the upcoming section one musical bar before the section marker. With a valid BPM, time signature and grid origin:

```text
recognized section cue time
        + one bar
             ↓
       snap to bar
             ↓
      section start
```

Automatic sections are only used when an explicit `song.json` section map is not already present. They remain normal editable `SectionEntity` records and should be reviewed in **Edit sections** before stage use.

If BPM/grid information is unavailable, StageGrid does not invent a bar duration. Native cue recognition can still succeed, but automatic section placement waits for a future re-analysis/rebuild workflow.

## Current boundary

`0.2.0-alpha04` stores the Guide as both structured events and a rendered native Guide WAV, but the rendered WAV follows the original timeline. Fully relocating Guide events after arbitrary live ReOrder/path changes is intentionally deferred until the double-buffered arrangement/path engine can update those events safely at musical boundaries.
