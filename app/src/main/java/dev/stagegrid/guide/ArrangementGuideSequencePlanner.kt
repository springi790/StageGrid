package dev.stagegrid.guide

import dev.stagegrid.guide.GuidePackManager.CueKind
import kotlin.math.roundToLong

/** Selects the Guide phrase that originally led into a destination section. */
object ArrangementGuideSequencePlanner {
    data class SourceCue(
        val key: String,
        val kind: CueKind,
        val language: String?,
        val offsetMs: Long,
        val confidence: Float,
    )

    fun select(
        analysis: GuideCueAnalyzer.Result,
        targetSectionStartMs: Long,
        targetSectionKey: String,
        barDurationMs: Double,
        beatsPerBar: Int = 4,
    ): List<SourceCue> {
        val bar = barDurationMs.takeIf { it.isFinite() && it > 0.0 }?.roundToLong() ?: return emptyList()
        val sourceStart = (targetSectionStartMs - bar).coerceAtLeast(0L)
        val sourceEnd = targetSectionStartMs.coerceAtLeast(sourceStart + 1L)
        val selected = analysis.cues
            .asSequence()
            .filter { cue -> cue.cueMs in (sourceStart - SOURCE_EARLY_TOLERANCE_MS).coerceAtLeast(0L) until sourceEnd }
            .filter { cue -> cue.kind == CueKind.SECTION || cue.kind == CueKind.COUNT || cue.kind == CueKind.DYNAMIC }
            .map { cue ->
                SourceCue(
                    key = cue.key,
                    kind = cue.kind,
                    language = cue.language,
                    offsetMs = (cue.cueMs - sourceStart).coerceAtLeast(0L),
                    confidence = cue.confidence,
                )
            }
            .sortedBy { it.offsetMs }
            .take(MAX_SEQUENCE_CUES)
            .toMutableList()

        completeSpokenCount(selected, analysis.dominantLanguage, bar, beatsPerBar)

        val hasTargetSection = selected.any { it.kind == CueKind.SECTION && it.key == targetSectionKey }
        if (!hasTargetSection && targetSectionKey.isNotBlank()) {
            selected += SourceCue(targetSectionKey, CueKind.SECTION, analysis.dominantLanguage, 0L, 1f)
        }
        return selected
            .distinctBy { Triple(it.kind, it.key, it.offsetMs / 120L) }
            .sortedWith(compareBy<SourceCue> { it.offsetMs }.thenBy { it.kind.ordinal })
            .take(MAX_SEQUENCE_CUES)
    }

    /**
     * Native Guide analysis can occasionally retain only one numeric count (for example "2") from
     * an otherwise normal lead-in. Once a spoken count is detected, make it deterministic and
     * musical: in 4/4 the generated sequence is 2, 3, 4 on beats 2, 3 and 4. Other meters follow
     * their displayed beat count. We do not add spoken counting to transitions that had no count.
     */
    private fun completeSpokenCount(
        selected: MutableList<SourceCue>,
        dominantLanguage: String?,
        barDurationMs: Long,
        beatsPerBar: Int,
    ) {
        val safeBeats = beatsPerBar.coerceIn(1, MAX_SPOKEN_BEATS)
        if (safeBeats < 2) return
        val detectedCounts = selected.filter { cue ->
            cue.kind == CueKind.COUNT && cue.key.toIntOrNull()?.let { it in 2..safeBeats } == true
        }
        if (detectedCounts.isEmpty()) return

        val language = detectedCounts.firstNotNullOfOrNull { it.language } ?: dominantLanguage
        selected.removeAll { cue ->
            cue.kind == CueKind.COUNT && cue.key.toIntOrNull()?.let { it in 2..safeBeats } == true
        }

        val beatDurationMs = barDurationMs.toDouble() / safeBeats.toDouble()
        for (beat in 2..safeBeats) {
            selected += SourceCue(
                key = beat.toString(),
                kind = CueKind.COUNT,
                language = language,
                offsetMs = ((beat - 1) * beatDurationMs).roundToLong().coerceIn(0L, (barDurationMs - 1L).coerceAtLeast(0L)),
                confidence = 1f,
            )
        }
    }

    private const val SOURCE_EARLY_TOLERANCE_MS = 180L
    private const val MAX_SEQUENCE_CUES = 12
    private const val MAX_SPOKEN_BEATS = 8
}
