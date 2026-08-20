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
 * Offline Guide recognizer for sample-based Guide stems.
 *
 * Alpha10.1 hardens candidate discovery for uneven gain/compression, uses phase-robust energy
 * fingerprints, widens timing tolerance and performs a conservative second pass once the source
 * language can be inferred. Fingerprints remain cached in memory/on disk.
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

    private data class Evaluation(
        val candidate: Int,
        val best: Match?,
        val secondSemanticScore: Float,
    )

    private const val WINDOW_MS = 10
    private const val SEARCH_RADIUS_WINDOWS = 24 // +/-240 ms tolerates encoder/segment onset drift.
    private const val PRIMARY_MIN_SCORE = 0.80f
    private const val PRIMARY_MIN_MARGIN = 0.040f
    private const val PRIMARY_HIGH_SCORE = 0.94f
    private const val RECOVERY_MIN_SCORE = 0.76f
    private const val RECOVERY_MIN_MARGIN = 0.055f
    private const val RECOVERY_HIGH_SCORE = 0.90f
    private const val DEDUPE_WINDOW_MS = 180L
    private const val MAX_CANDIDATES = 2_400

    @Volatile
    private var templateCache: TemplateCache? = null

    @Volatile
    private var persistentCacheFile: File? = null

    fun configurePersistentCache(file: File) {
        persistentCacheFile = file
    }

    fun invalidateMemoryCache() {
        templateCache = null
    }

    fun prepare(samples: List<GuideSample>): Int = templatesFor(samples).size

    fun analyze(
        guideFile: File,
        samples: List<GuideSample>,
        onProgress: ((Float) -> Unit)? = null,
    ): Result {
        if (samples.isEmpty()) return Result(emptyList(), null, 0)
        onProgress?.invoke(0.02f)

        val guideEnvelope = runCatching { GuideFingerprintEnvelope.read(guideFile, WINDOW_MS) }
            .getOrElse { return Result(emptyList(), null, 0) }
        if (guideEnvelope.size < 8) return Result(emptyList(), null, 0)
        val guideLog = FloatArray(guideEnvelope.size) { i -> compress(guideEnvelope[i]) }
        val candidates = findCandidates(guideEnvelope)
        if (candidates.isEmpty()) return Result(emptyList(), null, 0)
        onProgress?.invoke(0.12f)

        val templates = templatesFor(samples)
        if (templates.isEmpty()) return Result(emptyList(), null, candidates.size)
        onProgress?.invoke(0.18f)

        val accepted = mutableListOf<DetectedCue>()
        val rejected = mutableListOf<Evaluation>()
        candidates.forEachIndexed { index, candidate ->
            val evaluation = evaluateCandidate(guideLog, candidate, templates)
            val cue = evaluation.toCue(PRIMARY_MIN_SCORE, PRIMARY_MIN_MARGIN, PRIMARY_HIGH_SCORE)
            if (cue != null) accepted += cue else rejected += evaluation
            if (index % maxOf(1, candidates.size / 30) == 0 || index == candidates.lastIndex) {
                onProgress?.invoke(0.18f + 0.62f * ((index + 1f) / candidates.size.toFloat()))
            }
        }

        // Once a Guide language is clear, re-check only rejected candidates against that language.
        // This removes cross-language lookalikes from the margin calculation without globally
        // lowering thresholds and creating false positives.
        val languageHint = dominantLanguage(accepted) ?: inferLanguageFromRejected(rejected)
        if (languageHint != null && rejected.isNotEmpty()) {
            val languageTemplates = templates.filter { it.sample.language == languageHint }
            if (languageTemplates.isNotEmpty()) {
                rejected.forEachIndexed { index, prior ->
                    val evaluation = evaluateCandidate(guideLog, prior.candidate, languageTemplates)
                    evaluation.toCue(RECOVERY_MIN_SCORE, RECOVERY_MIN_MARGIN, RECOVERY_HIGH_SCORE)?.let(accepted::add)
                    if (index % maxOf(1, rejected.size / 20) == 0 || index == rejected.lastIndex) {
                        onProgress?.invoke(0.80f + 0.18f * ((index + 1f) / rejected.size.toFloat()))
                    }
                }
            }
        }

        val deduped = dedupe(accepted)
        val dominant = dominantLanguage(deduped)
        onProgress?.invoke(1f)
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

    private fun Evaluation.toCue(minScore: Float, minMargin: Float, highScore: Float): DetectedCue? {
        val chosen = best ?: return null
        val margin = chosen.score - secondSemanticScore
        if (chosen.score < minScore || (margin < minMargin && chosen.score < highScore)) return null
        val sampleStartWindow = chosen.activeStart - chosen.template.trimStartWindows
        return DetectedCue(
            key = chosen.template.sample.key,
            kind = chosen.template.sample.kind,
            language = chosen.template.sample.language,
            cueMs = sampleStartWindow.coerceAtLeast(0) * WINDOW_MS.toLong(),
            confidence = chosen.score.coerceIn(0f, 1f),
        )
    }

    private fun evaluateCandidate(guide: FloatArray, candidate: Int, templates: List<Template>): Evaluation {
        var best: Match? = null
        val semanticScores = HashMap<String, Float>()
        for (template in templates) {
            val match = bestMatchAround(guide, candidate, template) ?: continue
            val semantic = "${template.sample.kind.name}:${template.sample.key}"
            val previous = semanticScores[semantic]
            if (previous == null || match.score > previous) semanticScores[semantic] = match.score
            if (best == null || match.score > best!!.score) best = match
        }
        val chosen = best
        if (chosen == null) return Evaluation(candidate, null, -1f)
        val chosenSemantic = "${chosen.template.sample.kind.name}:${chosen.template.sample.key}"
        var second = -1f
        for ((semantic, score) in semanticScores) {
            if (semantic != chosenSemantic && score > second) second = score
        }
        return Evaluation(candidate, chosen, second)
    }

    private fun inferLanguageFromRejected(rejected: List<Evaluation>): String? {
        val plausible = rejected.mapNotNull { evaluation ->
            evaluation.best?.takeIf { it.score >= 0.70f }
        }
        if (plausible.isEmpty()) return null
        val byLanguage = plausible.groupBy { it.template.sample.language }
        val bestGroup = byLanguage.maxByOrNull { (_, matches) -> matches.sumOf { it.score.toDouble() } } ?: return null
        val matches = bestGroup.value
        return bestGroup.key.takeIf { matches.size >= 2 || matches.any { it.score >= 0.88f } }
    }

    private fun dominantLanguage(cues: List<DetectedCue>): String? =
        cues.groupingBy { it.language }.eachCount().maxByOrNull { it.value }?.key

    private fun dedupe(cues: List<DetectedCue>): List<DetectedCue> {
        val out = mutableListOf<DetectedCue>()
        for (cue in cues.sortedBy { it.cueMs }) {
            val nearbyIndex = out.indexOfLast { cue.cueMs - it.cueMs < DEDUPE_WINDOW_MS }
            if (nearbyIndex >= 0) {
                if (cue.confidence > out[nearbyIndex].confidence) out[nearbyIndex] = cue
            } else {
                out += cue
            }
        }
        return out
    }

    private fun templatesFor(samples: List<GuideSample>): List<Template> {
        if (samples.isEmpty()) return emptyList()
        val signature = sampleSignature(samples)
        templateCache?.takeIf { it.signature == signature }?.let { return it.templates }
        return synchronized(this) {
            templateCache?.takeIf { it.signature == signature }?.let { return@synchronized it.templates }

            val diskTemplates = persistentCacheFile?.let { cacheFile ->
                val entries = GuideFingerprintDiskCache.read(cacheFile, signature) ?: return@let null
                val samplesById = samples.associateBy(::sampleIdentity)
                val loaded = entries.mapNotNull { entry ->
                    val sample = samplesById[entryIdentity(entry)] ?: return@mapNotNull null
                    if (sample.file.length() != entry.fileLength || sample.file.lastModified() != entry.lastModified) return@mapNotNull null
                    Template(sample, entry.fingerprint, entry.trimStartWindows)
                }
                loaded.takeIf { it.size == entries.size && it.isNotEmpty() }
            }
            if (diskTemplates != null) {
                templateCache = TemplateCache(signature, diskTemplates)
                return@synchronized diskTemplates
            }

            val built = samples.mapNotNull(::buildTemplate)
            templateCache = TemplateCache(signature, built)
            persistentCacheFile?.let { cacheFile ->
                GuideFingerprintDiskCache.write(
                    cacheFile,
                    signature,
                    built.map { template ->
                        GuideFingerprintDiskCache.Entry(
                            language = template.sample.language,
                            key = template.sample.key,
                            kind = template.sample.kind,
                            absolutePath = template.sample.file.absolutePath,
                            fileLength = template.sample.file.length(),
                            lastModified = template.sample.file.lastModified(),
                            trimStartWindows = template.trimStartWindows,
                            fingerprint = template.fingerprint,
                        )
                    },
                )
            }
            built
        }
    }

    private fun sampleIdentity(sample: GuideSample): String =
        "${sample.language}|${sample.kind.name}|${sample.key}|${sample.file.absolutePath}"

    private fun entryIdentity(entry: GuideFingerprintDiskCache.Entry): String =
        "${entry.language}|${entry.kind.name}|${entry.key}|${entry.absolutePath}"

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
        val envelope = runCatching { GuideFingerprintEnvelope.read(sample.file, WINDOW_MS) }.getOrNull() ?: return null
        if (envelope.size < 4) return null
        val peak = envelope.maxOrNull() ?: return null
        if (peak < 0.002f) return null
        val threshold = max(0.002f, peak * 0.045f)
        val first = envelope.indexOfFirst { it >= threshold }.takeIf { it >= 0 } ?: return null
        val last = envelope.indexOfLast { it >= threshold }.takeIf { it >= first } ?: return null
        val start = (first - 3).coerceAtLeast(0)
        val endExclusive = (last + 4).coerceAtMost(envelope.size)
        val compressed = FloatArray(endExclusive - start) { i -> compress(envelope[start + i]) }
        normalizeInPlace(compressed)
        if (compressed.size < 4) return null
        return Template(sample, compressed, start)
    }

    private fun bestMatchAround(guide: FloatArray, candidate: Int, template: Template): Match? {
        val n = template.fingerprint.size
        if (guide.size < n) return null
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
        if (peak < 0.0015f) return emptyList()
        val sorted = envelope.sorted()
        val floorCount = max(1, sorted.size / 5)
        val noise = sorted.take(floorCount).average().toFloat()

        // Two activity thresholds avoid a loud phrase hiding quieter cues elsewhere in the stem.
        val strongThreshold = max(0.0025f, max(noise * 4.0f, peak * 0.045f))
        val relaxedThreshold = max(0.0015f, max(noise * 2.2f, peak * 0.012f))
        val candidates = linkedSetOf<Int>()
        candidates += runStarts(envelope, strongThreshold, mergeGapWindows = 10)
        candidates += runStarts(envelope, relaxedThreshold, mergeGapWindows = 6)

        // Compressed Guides sometimes never return fully below threshold between two calls. Detect
        // local energy attacks as additional candidate onsets in those long active regions.
        val minimumRise = max(noise * 0.60f, peak * 0.0025f)
        for (i in 3 until envelope.size - 2) {
            val before = (envelope[i - 3] + envelope[i - 2] + envelope[i - 1]) / 3f
            val current = (envelope[i] + envelope[i + 1] + envelope[i + 2]) / 3f
            val ratio = current / (before + max(0.00001f, noise * 0.20f))
            if (current >= relaxedThreshold && current - before >= minimumRise && ratio >= 1.30f) {
                candidates += i
            }
        }

        if (candidates.isEmpty()) return emptyList()
        return candidates.sorted().take(MAX_CANDIDATES)
    }

    private fun runStarts(envelope: FloatArray, threshold: Float, mergeGapWindows: Int): List<Int> {
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
            if (previous != null && run.first - previous.last <= mergeGapWindows) {
                merged[merged.lastIndex] = previous.first..run.last
            } else {
                merged += run
            }
        }
        return merged.filter { it.last - it.first + 1 >= 2 }.map { it.first }
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
