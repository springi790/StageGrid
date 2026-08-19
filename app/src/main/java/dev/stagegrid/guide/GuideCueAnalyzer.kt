package dev.stagegrid.guide

import dev.stagegrid.guide.GuidePackManager.CueKind
import dev.stagegrid.guide.GuidePackManager.GuideSample
import dev.stagegrid.music.GridSnap
import dev.stagegrid.music.MusicalGrid
import java.io.File
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Import-time Guide recognizer for sample-based Guide stems.
 *
 * It intentionally avoids generic speech recognition. Instead it compares the short-time RMS
 * fingerprint of the imported Guide stem with locally-installed Guide cue samples. This is fast,
 * offline, language-independent, and especially reliable when a Guide stem was assembled from the
 * same cue pack even after MP3/AAC style lossy encoding and gain changes.
 */
object GuideCueAnalyzer {
    data class DetectedCue(
        val key: String,
        val kind: CueKind,
        val language: String,
        val cueMs: Long,
        val confidence: Float,
    )

    data class Result(
        val cues: List<DetectedCue>,
        val dominantLanguage: String?,
        val candidateCount: Int,
    )

    data class SectionProposal(
        val key: String,
        val name: String,
        val startMs: Long,
        val confidence: Float,
    )

    private data class Template(
        val sample: GuideSample,
        val fingerprint: FloatArray,
        val trimStartWindows: Int,
    )

    private data class TemplateCache(
        val signature: Long,
        val templates: List<Template>,
    )

    private data class Match(val template: Template, val score: Float, val activeStart: Int)

    private const val WINDOW_MS = 10
    private const val SEARCH_RADIUS_WINDOWS = 12 // +/-120 ms
    private const val MIN_SCORE = 0.82f
    private const val MIN_MARGIN = 0.055f

    @Volatile
    private var templateCache: TemplateCache? = null

    /** Prepares the installed pack once so the first song import does not rebuild every sample fingerprint. */
    fun prepare(samples: List<GuideSample>): Int = templatesFor(samples).size

    fun analyze(guideFile: File, samples: List<GuideSample>): Result {
        if (samples.isEmpty()) return Result(emptyList(), null, 0)
        val guideEnvelope = GuideAudio.rmsEnvelope(guideFile, WINDOW_MS)
        if (guideEnvelope.size < 8) return Result(emptyList(), null, 0)
        val guideLog = FloatArray(guideEnvelope.size) { i -> compress(guideEnvelope[i]) }
        val candidates = findCandidates(guideEnvelope)
        if (candidates.isEmpty()) return Result(emptyList(), null, 0)

        val templates = templatesFor(samples)
        if (templates.isEmpty()) return Result(emptyList(), null, candidates.size)

        val accepted = mutableListOf<DetectedCue>()
        for (candidate in candidates) {
            var best: Match? = null
            var secondScore = -1f
            for (template in templates) {
                val match = bestMatchAround(guideLog, candidate, template) ?: continue
                val currentBest = best
                if (currentBest == null || match.score > currentBest.score) {
                    if (currentBest != null) secondScore = max(secondScore, currentBest.score)
                    best = match
                } else {
                    secondScore = max(secondScore, match.score)
                }
            }
            val chosen = best ?: continue
            val margin = chosen.score - secondScore
            if (chosen.score < MIN_SCORE || (margin < MIN_MARGIN && chosen.score < 0.96f)) continue
            val sampleStartWindow = chosen.activeStart - chosen.template.trimStartWindows
            val cueMs = (sampleStartWindow.coerceAtLeast(0) * WINDOW_MS).toLong()
            accepted += DetectedCue(
                key = chosen.template.sample.key,
                kind = chosen.template.sample.kind,
                language = chosen.template.sample.language,
                cueMs = cueMs,
                confidence = chosen.score.coerceIn(0f, 1f),
            )
        }

        val deduped = accepted.sortedBy { it.cueMs }.fold(mutableListOf<DetectedCue>()) { out, cue ->
            val previous = out.lastOrNull()
            if (previous != null && cue.cueMs - previous.cueMs < 140L) {
                if (cue.confidence > previous.confidence) out[out.lastIndex] = cue
            } else {
                out += cue
            }
            out
        }
        val dominant = deduped.groupingBy { it.language }.eachCount().maxByOrNull { it.value }?.key
        return Result(deduped, dominant, candidates.size)
    }

    fun inferSections(
        result: Result,
        bpm: Double?,
        timeSignature: String,
        gridOffsetMs: Long,
        durationMs: Long,
        localeLanguage: String = Locale.getDefault().language,
    ): List<SectionProposal> {
        val grid = MusicalGrid.from(bpm, timeSignature, gridOffsetMs) ?: return emptyList()
        val leadMs = grid.barDurationMs.roundToLong()
        val proposals = result.cues
            .asSequence()
            .filter { it.kind == CueKind.SECTION }
            .map { cue ->
                val marker = grid.snap(cue.cueMs + leadMs, GridSnap.BAR)
                SectionProposal(
                    key = cue.key,
                    name = friendlySectionName(cue.key, localeLanguage),
                    startMs = marker,
                    confidence = cue.confidence,
                )
            }
            .filter { it.startMs in 0 until durationMs }
            .sortedBy { it.startMs }
            .toList()

        if (proposals.isEmpty()) return emptyList()
        val unique = mutableListOf<SectionProposal>()
        for (proposal in proposals) {
            val existingIndex = unique.indexOfLast { it.startMs == proposal.startMs }
            if (existingIndex >= 0) {
                if (proposal.confidence > unique[existingIndex].confidence) unique[existingIndex] = proposal
            } else {
                unique += proposal
            }
        }
        return unique
    }

    private fun templatesFor(samples: List<GuideSample>): List<Template> {
        if (samples.isEmpty()) return emptyList()
        val signature = sampleSignature(samples)
        templateCache?.takeIf { it.signature == signature }?.let { return it.templates }
        return synchronized(this) {
            templateCache?.takeIf { it.signature == signature }?.let { return@synchronized it.templates }
            val built = samples.mapNotNull(::buildTemplate)
            templateCache = TemplateCache(signature, built)
            built
        }
    }

    private fun sampleSignature(samples: List<GuideSample>): Long {
        var hash = 1125899906842597L
        for (sample in samples) {
            hash = 31L * hash + sample.language.hashCode()
            hash = 31L * hash + sample.key.hashCode()
            hash = 31L * hash + sample.kind.ordinal
            hash = 31L * hash + sample.file.absolutePath.hashCode()
            hash = 31L * hash + sample.file.length()
            hash = 31L * hash + sample.file.lastModified()
        }
        return hash
    }

    private fun buildTemplate(sample: GuideSample): Template? {
        val envelope = runCatching { GuideAudio.rmsEnvelope(sample.file, WINDOW_MS) }.getOrNull() ?: return null
        if (envelope.size < 4) return null
        val peak = envelope.maxOrNull() ?: return null
        if (peak < 0.003f) return null
        val threshold = max(0.003f, peak * 0.06f)
        val first = envelope.indexOfFirst { it >= threshold }.takeIf { it >= 0 } ?: return null
        val last = envelope.indexOfLast { it >= threshold }.takeIf { it >= first } ?: return null
        val start = (first - 2).coerceAtLeast(0)
        val endExclusive = (last + 3).coerceAtMost(envelope.size)
        val compressed = FloatArray(endExclusive - start) { i -> compress(envelope[start + i]) }
        normalizeInPlace(compressed)
        if (compressed.size < 4) return null
        return Template(sample, compressed, start)
    }

    private fun bestMatchAround(guide: FloatArray, candidate: Int, template: Template): Match? {
        val n = template.fingerprint.size
        var bestScore = -1f
        var bestStart = -1
        val from = (candidate - SEARCH_RADIUS_WINDOWS).coerceAtLeast(0)
        val to = (candidate + SEARCH_RADIUS_WINDOWS).coerceAtMost(guide.size - n)
        if (to < from) return null
        for (start in from..to) {
            var sum = 0.0
            var sumSq = 0.0
            for (i in 0 until n) {
                val value = guide[start + i].toDouble()
                sum += value
                sumSq += value * value
            }
            val mean = sum / n
            val variance = (sumSq / n) - mean * mean
            if (variance <= 1e-10) continue
            val std = sqrt(variance)
            var dot = 0.0
            for (i in 0 until n) {
                dot += template.fingerprint[i] * (guide[start + i] - mean).toFloat()
            }
            val score = (dot / (n * std)).toFloat().coerceIn(-1f, 1f)
            if (score > bestScore) {
                bestScore = score
                bestStart = start
            }
        }
        return if (bestStart >= 0) Match(template, bestScore, bestStart) else null
    }

    private fun findCandidates(envelope: FloatArray): List<Int> {
        val peak = envelope.maxOrNull() ?: return emptyList()
        if (peak < 0.004f) return emptyList()
        val sorted = envelope.sorted()
        val floorCount = max(1, sorted.size / 5)
        val noise = sorted.take(floorCount).average().toFloat()
        val threshold = max(0.004f, max(noise * 5f, peak * 0.055f))

        val runs = mutableListOf<IntRange>()
        var i = 0
        while (i < envelope.size) {
            if (envelope[i] < threshold) {
                i++
                continue
            }
            val start = i
            while (i < envelope.size && envelope[i] >= threshold) i++
            runs += start until i
        }
        if (runs.isEmpty()) return emptyList()

        val merged = mutableListOf<IntRange>()
        for (run in runs) {
            val previous = merged.lastOrNull()
            if (previous != null && run.first - previous.last <= 12) {
                merged[merged.lastIndex] = previous.first..run.last
            } else {
                merged += run
            }
        }
        return merged.filter { it.last - it.first + 1 >= 3 }.map { it.first }
    }

    private fun compress(value: Float): Float = ln(1.0 + value.coerceAtLeast(0f) * 50.0).toFloat()

    private fun normalizeInPlace(values: FloatArray) {
        if (values.isEmpty()) return
        val mean = values.average().toFloat()
        var sumSq = 0.0
        for (value in values) {
            val centered = value - mean
            sumSq += centered * centered
        }
        val std = sqrt(sumSq / values.size).toFloat().coerceAtLeast(1e-6f)
        for (i in values.indices) values[i] = (values[i] - mean) / std
    }

    private fun friendlySectionName(key: String, language: String): String {
        val match = Regex("^([a-z_]+?)(?:_(\\d+))?$").matchEntire(key)
        val base = match?.groupValues?.getOrNull(1) ?: key
        val number = match?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }
        val es = language.lowercase(Locale.ROOT).startsWith("es")
        val name = if (es) {
            when (base) {
                "intro" -> "Intro"
                "verse" -> "Verso"
                "pre_chorus" -> "Pre-coro"
                "chorus" -> "Coro"
                "post_chorus" -> "Post-coro"
                "bridge" -> "Puente"
                "interlude" -> "Interludio"
                "instrumental" -> "Instrumental"
                "breakdown" -> "Baja intensidad"
                "refrain" -> "Refrán"
                "tag" -> "Repetir"
                "vamp" -> "Vamp"
                "solo" -> "Solo"
                "rap" -> "Rap"
                "exhortation" -> "Exhortación"
                "ending" -> "Final"
                "outro" -> "Outro"
                "acapella" -> "A capella"
                "turnaround" -> "Vuelta"
                else -> base.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.getDefault()) }
            }
        } else {
            base.replace('_', ' ').split(' ').joinToString(" ") { word ->
                word.replaceFirstChar { it.titlecase(Locale.getDefault()) }
            }
        }
        return if (number == null) name else "$name $number"
    }
}
