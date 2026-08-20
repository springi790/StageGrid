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

        val hasTargetSection = selected.any { it.kind == CueKind.SECTION && it.key == targetSectionKey }
        if (!hasTargetSection && targetSectionKey.isNotBlank()) {
            selected += SourceCue(targetSectionKey, CueKind.SECTION, analysis.dominantLanguage, 0L, 1f)
        }
        return selected
            .distinctBy { Triple(it.kind, it.key, it.offsetMs / 120L) }
            .sortedWith(compareBy<SourceCue> { it.offsetMs }.thenBy { it.kind.ordinal })
            .take(MAX_SEQUENCE_CUES)
    }

    private const val SOURCE_EARLY_TOLERANCE_MS = 180L
    private const val MAX_SEQUENCE_CUES = 12
}
