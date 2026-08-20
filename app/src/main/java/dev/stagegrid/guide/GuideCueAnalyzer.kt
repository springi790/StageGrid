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
 * Alpha10.2 keeps the proven Spanish path intact but determines the source Guide language before
 * the expensive full match whenever confidence is sufficient. The full pass then compares only
 * against that language, reducing cross-language false positives and repeated work. English uses
 * a slightly larger ambiguity margin because field feedback showed short SECTION calls collapsing
 * into Vamp/Rap. If language evidence is weak, matching safely falls back to all installed samples.
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

    private data class AcceptanceThresholds(
        val minScore: Float,
        val minMargin: Float,
        val highScore: Float,
    )

    private data class LanguageEvidence(
        val language: String,
        val score: Double,
        val matches: Int,
        val strongest: Float,
    )

    private const val WINDOW_MS = 10
    private const val SEARCH_RADIUS_WINDOWS = 24 // +/-240 ms tolerates encoder/segment onset drift.
    private const val PRIMARY_MIN_SCORE = 0.80f
    private const val PRIMARY_MIN_MARGIN = 0.040f
    private const val PRIMARY_HIGH_SCORE = 0.94f
    private const val ENGLISH_MIN_SCORE = 0.82f
    private const val ENGLISH_MIN_MARGIN = 0.065f
    private const val ENGLISH_HIGH_SCORE = 0.96f
    private const val DEDUPE_WINDOW_MS = 180L
    private const val MAX_CANDIDATES = 2_400
    private const val INTRA_PHRASE_MERGE_WINDOWS = 24 // 240 ms: syllable gaps, not separate Guide calls.
    private const val CONTINUOUS_ACTIVITY_WINDOWS = 35 // 350 ms before local-onset recovery is allowed.
    private const val LANGUAGE_PROBE_MAX_CANDIDATES = 14
    private const val LANGUAGE_PROBE_MIN_MATCH = 0.72f
    private const val LANGUAGE_PROBE_MAX_SCORES = 6
    private const val LANGUAGE_PROBE_MIN_GAP = 0.035

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

        val sourceLanguage = inferSourceLanguage(guideLog, candidates, templates)
        val activeTemplates = sourceLanguage
            ?.let { language -> templates.filter { it.sample.language == language } }
            ?.takeIf { it.isNotEmpty() }
            ?: templates
        val thresholds = acceptanceThresholds(sourceLanguage)
        onProgress?.invoke(0.24f)

        val accepted = mutableListOf<DetectedCue>()
        candidates.forEachIndexed { index, candidate ->
            val evaluation = evaluateCandidate(guideLog, candidate, activeTemplates)
            evaluation.toCue(thresholds.minScore, thresholds.minMargin, thresholds.highScore)?.let(accepted::add)
            if (index % maxOf(1, candidates.size / 30) == 0 || index == candidates.lastIndex) {
                onProgress?.invoke(0.24f + 0.74f * ((index + 1f) / candidates.size.toFloat()))
            }
        }

        val deduped = dedupe(accepted)
        val dominant = sourceLanguage ?: dominantLanguage(deduped)
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

    private fun acceptanceThresholds(sourceLanguage: String?): AcceptanceThresholds =
        if (sourceLanguage == "en") {
            AcceptanceThresholds(ENGLISH_MIN_SCORE, ENGLISH_MIN_MARGIN, ENGLISH_HIGH_SCORE)
        } else {
            AcceptanceThresholds(PRIMARY_MIN_SCORE, PRIMARY_MIN_MARGIN, PRIMARY_HIGH_SCORE)
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
            val currentBest = best
            if (currentBest == null || match.score > currentBest.score) best = match
        }
        val chosen = best ?: return Evaluation(candidate, null, -1f)
        val chosenSemantic = "${chosen.template.sample.kind.name}:${chosen.template.sample.key}"
        var second = -1f
        for ((semantic, score) in semanticScores) {
            if (semantic != chosenSemantic && score > second) second = score
        }
        return Evaluation(candidate, chosen, second)
    }

    /**
     * Language selection is intentionally a small probe, not another full recognition pass.
     * SECTION words are preferred because short numeric/dynamic calls are weak language evidence.
     * Only a bounded, evenly distributed subset of candidates is tested.
     */
    private fun inferSourceLanguage(
        guide: FloatArray,
        candidates: List<Int>,
        templates: List<Template>,
    ): String? {
        val byLanguage = templates
            .filter { it.sample.kind == CueKind.SECTION }
            .groupBy { it.sample.language }
            .filterValues { it.size >= 3 }
        if (byLanguage.size <= 1) return byLanguage.keys.firstOrNull()

        val probeCandidates = sampleEvenly(candidates, LANGUAGE_PROBE_MAX_CANDIDATES)
        val evidence = byLanguage.mapNotNull { (language, languageTemplates) ->
            val scores = probeCandidates.mapNotNull { candidate ->
                evaluateCandidate(guide, candidate, languageTemplates).best?.score
            }.filter { it >= LANGUAGE_PROBE_MIN_MATCH }
                .sortedDescending()
                .take(LANGUAGE_PROBE_MAX_SCORES)
            if (scores.isEmpty()) return@mapNotNull null
            val strongest = scores.first()
            val matches = scores.size
            // Multiple moderate anchors are preferable to one accidental near-perfect match.
            val usable = matches >= 2 || strongest >= 0.93f
            if (!usable) return@mapNotNull null
            val average = scores.average()
            val supportBonus = minOf(matches, 4) * 0.0125
            LanguageEvidence(language, average + supportBonus, matches, strongest)
        }.sortedByDescending { it.score }

        val best = evidence.firstOrNull() ?: return null
        val second = evidence.getOrNull(1)
        if (second == null) return best.language
        val gap = best.score - second.score
        return best.language.takeIf {
            gap >= LANGUAGE_PROBE_MIN_GAP ||
                (best.matches >= 3 && best.strongest >= 0.90f && best.score >= second.score)
        }
    }

    private fun sampleEvenly(values: List<Int>, maxCount: Int): List<Int> {
        if (values.size <= maxCount) return values
        if (maxCount <= 1) return listOf(values.first())
        val last = values.lastIndex.toDouble()
        return (0 until maxCount)
            .map { index -> values[(index * last / (maxCount - 1)).toInt()] }
            .distinct()
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

        // A Guide phrase often contains 50-200 ms gaps between syllables. Treat those as one
        // candidate region; otherwise an internal syllable can be mistaken for a new cue.
        val strongThreshold = max(0.0025f, max(noise * 4.0f, peak * 0.045f))
        val relaxedThreshold = max(0.0015f, max(noise * 2.2f, peak * 0.012f))
        val candidates = linkedSetOf<Int>()
        candidates += runStarts(envelope, strongThreshold, mergeGapWindows = INTRA_PHRASE_MERGE_WINDOWS)
        candidates += runStarts(envelope, relaxedThreshold, mergeGapWindows = INTRA_PHRASE_MERGE_WINDOWS)

        // Local attack recovery is only useful when compression/noise has kept the Guide above the
        // relaxed floor for a sustained period. If there was a recent quiet gap, runStarts already
        // represents the phrase and adding each syllable would create false duplicate matches.
        val minimumRise = max(noise * 0.60f, peak * 0.0025f)
        var lastBelowRelaxed = -CONTINUOUS_ACTIVITY_WINDOWS
        for (i in 3 until envelope.size - 2) {
            if (envelope[i - 1] < relaxedThreshold) lastBelowRelaxed = i - 1
            val before = (envelope[i - 3] + envelope[i - 2] + envelope[i - 1]) / 3f
            val current = (envelope[i] + envelope[i + 1] + envelope[i + 2]) / 3f
            val ratio = current / (before + max(0.00001f, noise * 0.20f))
            val sustainedActivity = i - lastBelowRelaxed >= CONTINUOUS_ACTIVITY_WINDOWS
            if (
                sustainedActivity &&
                current >= relaxedThreshold &&
                current - before >= minimumRise &&
                ratio >= 1.30f
            ) {
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
