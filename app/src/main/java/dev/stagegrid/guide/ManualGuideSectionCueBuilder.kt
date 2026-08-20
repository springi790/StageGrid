package dev.stagegrid.guide

import dev.stagegrid.guide.GuidePackManager.CueKind
import dev.stagegrid.guide.GuidePackManager.GuideSample
import dev.stagegrid.model.SectionEntity
import dev.stagegrid.music.MusicalGrid
import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Builds deterministic Native Guide SECTION cues from user-edited song sections.
 *
 * This is the reliable fallback path when speech/template recognition is disabled or inaccurate:
 * the section map is authoritative and the Guide Pack is used only as a phrase library.
 */
object ManualGuideSectionCueBuilder {
    data class Result(
        val cues: List<GuideCueAnalyzer.DetectedCue>,
        val proposals: List<GuideCueAnalyzer.SectionProposal>,
        val missingSectionNames: List<String>,
    )

    fun build(
        sections: List<SectionEntity>,
        bpm: Double?,
        timeSignature: String,
        gridOffsetMs: Long,
        durationMs: Long,
        samples: List<GuideSample>,
        outputLanguage: String,
    ): Result {
        val grid = MusicalGrid.from(bpm, timeSignature, gridOffsetMs)
            ?: return Result(emptyList(), emptyList(), sections.map { it.name })
        val availableKeys = samples.asSequence()
            .filter { it.language == outputLanguage && it.kind == CueKind.SECTION }
            .map { it.key }
            .toSet()
        if (availableKeys.isEmpty()) return Result(emptyList(), emptyList(), sections.map { it.name })

        val leadMs = grid.barDurationMs.roundToLong().coerceAtLeast(1L)
        val sorted = sections
            .filter { it.startMs in 0 until durationMs }
            .filterNot { it.name.equals("Full Song", ignoreCase = true) }
            .sortedWith(compareBy<SectionEntity> { it.startMs }.thenBy { it.sortOrder })

        val cues = mutableListOf<GuideCueAnalyzer.DetectedCue>()
        val proposals = mutableListOf<GuideCueAnalyzer.SectionProposal>()
        val missing = mutableListOf<String>()

        for (section in sorted) {
            val key = resolveKey(section.name, availableKeys)
            if (key == null) {
                missing += section.name
                continue
            }
            val cueMs = (section.startMs - leadMs).coerceAtLeast(0L)
            cues += GuideCueAnalyzer.DetectedCue(
                key = key,
                kind = CueKind.SECTION,
                language = outputLanguage,
                cueMs = cueMs,
                confidence = 1f,
            )
            proposals += GuideCueAnalyzer.SectionProposal(
                key = key,
                name = section.name,
                startMs = section.startMs,
                confidence = 1f,
            )
        }
        return Result(cues.sortedBy { it.cueMs }, proposals.sortedBy { it.startMs }, missing.distinct())
    }

    internal fun resolveKey(sectionName: String, availableKeys: Set<String>): String? {
        val normalized = canonical(sectionName)
        if (normalized.isBlank()) return null
        if (normalized in availableKeys) return normalized

        val numbered = Regex("^(.+?)_(\\d+)$").matchEntire(normalized)
        val number = numbered?.groupValues?.getOrNull(2)
        val rawBase = numbered?.groupValues?.getOrNull(1) ?: normalized
        val base = ALIASES[rawBase] ?: rawBase

        if (number != null) {
            val numberedKey = "${base}_$number"
            if (numberedKey in availableKeys) return numberedKey
        }
        if (base in availableKeys) return base

        // Common editor shorthand. Only accept it when the resolved target exists in this pack.
        val shorthand = SHORTHAND[normalized]
        if (shorthand != null) {
            if (shorthand in availableKeys) return shorthand
            val shorthandNumbered = number?.let { "${shorthand}_$it" }
            if (shorthandNumbered != null && shorthandNumbered in availableKeys) return shorthandNumbered
        }
        return null
    }

    private fun canonical(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')

    private val ALIASES = mapOf(
        "introduccion" to "intro",
        "verse" to "verse",
        "verso" to "verse",
        "pre_coro" to "pre_chorus",
        "precoro" to "pre_chorus",
        "prechorus" to "pre_chorus",
        "coro" to "chorus",
        "post_coro" to "post_chorus",
        "postcoro" to "post_chorus",
        "postchorus" to "post_chorus",
        "puente" to "bridge",
        "interludio" to "interlude",
        "instrumental" to "instrumental",
        "baja_intensidad" to "breakdown",
        "bajada" to "breakdown",
        "refran" to "refrain",
        "repetir" to "tag",
        "exhortacion" to "exhortation",
        "final" to "ending",
        "fin" to "ending",
        "a_capella" to "acapella",
        "acapela" to "acapella",
        "vuelta" to "turnaround",
    )

    private val SHORTHAND = mapOf(
        "i" to "intro",
        "v" to "verse",
        "c" to "chorus",
        "pc" to "pre_chorus",
        "p" to "bridge",
        "b" to "bridge",
        "in" to "instrumental",
        "it" to "interlude",
        "f" to "ending",
    )
}
