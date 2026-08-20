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
 * Alpha10.3 keeps Spanish on the proven envelope path and adds a second-stage acoustic fingerprint
 * only when the source Guide is confidently English. The acoustic pass combines temporal energy
 * with coarse speech-band movement, which is substantially more discriminative than RMS shape alone
 * for short words such as Verse/Vamp/Rap. Ambiguous short SECTION labels are penalized rather than
 * becoming an implicit fallback, and suspicious repeated Vamp/Rap/Tag/Solo collapses are guarded.
 */
object GuideCueAnalyzer {
    data class DetectedCue(
        val key: String,
        val kind: CueKind,
        val language: String,
        val cueMs: Long,
        val confidence: Float,
    )

    data class MatchDiagnostic(
        val cueMs: Long,
        val bestKey: String?,
        val bestKind: CueKind?,
        val bestLanguage: String?,
        val bestScore: Float,
        val secondKey: String?,
        val secondScore: Float,
        val accepted: Boolean,
        val reason: String,
    )

    data class Result(
        val cues: List<DetectedCue>,
        val dominantLanguage: String?,
        val candidateCount: Int,
        val diagnostics: List<MatchDiagnostic> = emptyList(),
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

    private data class AcousticTemplate(
        val sampleIdentity: String,
        val fingerprint: FloatArray,
        val windowCount: Int,
    )

    private data class AcousticTemplateCache(
        val signature: Long,
        val templates: Map<String, AcousticTemplate>,
    )

    private data class Match(
        val template: Template,
        val score: Float,
        val activeStart: Int,
        val envelopeScore: Float = score,
        val acousticScore: Float? = null,
    )

    private data class Evaluation(
        val candidate: Int,
        val best: Match?,
        val secondSemanticScore: Float,
        val secondSemanticKey: String?,
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
    private const val ENGLISH_ENVELOPE_MIN_SCORE = 0.82f
    private const val ENGLISH_ENVELOPE_MIN_MARGIN = 0.065f
    private const val ENGLISH_ENVELOPE_HIGH_SCORE = 0.96f
    private const val ENGLISH_ACOUSTIC_MIN_SCORE = 0.78f
    private const val ENGLISH_ACOUSTIC_MIN_MARGIN = 0.050f
    private const val ENGLISH_ACOUSTIC_HIGH_SCORE = 0.94f
    private const val ENVELOPE_WEIGHT = 0.42f
    private const val ACOUSTIC_WEIGHT = 0.58f
    private const val AMBIGUOUS_SHORT_SECTION_PENALTY = 0.035f
    private const val DEDUPE_WINDOW_MS = 180L
    private const val MAX_CANDIDATES = 2_400
    private const val MAX_DIAGNOSTICS = 96
    private const val INTRA_PHRASE_MERGE_WINDOWS = 24 // 240 ms: syllable gaps, not separate Guide calls.
    private const val CONTINUOUS_ACTIVITY_WINDOWS = 35 // 350 ms before local-onset recovery is allowed.
    private const val LANGUAGE_PROBE_MAX_CANDIDATES = 14
    private const val LANGUAGE_PROBE_MIN_MATCH = 0.72f
    private const val LANGUAGE_PROBE_MAX_SCORES = 6
    private const val LANGUAGE_PROBE_MIN_GAP = 0.035
    private const val COLLAPSE_MIN_SECTION_COUNT = 3
    private const val COLLAPSE_SHARE = 0.60
    private const val COLLAPSE_KEEP_SCORE = 0.94f

    private val ambiguousShortSections = setOf("vamp", "rap", "tag", "solo")

    @Volatile
    private var templateCache: TemplateCache? = null

    @Volatile
    private var acousticTemplateCache: AcousticTemplateCache? = null

    @Volatile
    private var persistentCacheFile: File? = null

    fun configurePersistentCache(file: File) {
        persistentCacheFile = file
    }

    fun invalidateMemoryCache() {
        templateCache = null
        acousticTemplateCache = null
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

        // Spanish and other languages keep the alpha10.2 matcher unchanged. English alone gets the
        // additional acoustic discriminator because that is where physical-device feedback exposed
        // short-word collapse.
        val guideAcoustic = if (sourceLanguage == "en") {
            runCatching { GuideAcousticFingerprint.read(guideFile, WINDOW_MS) }.getOrNull()
        } else {
            null
        }
        val acousticTemplates = if (guideAcoustic != null) acousticTemplatesFor(activeTemplates) else emptyMap()
        val acousticEnabled = guideAcoustic != null && acousticTemplates.isNotEmpty()
        val thresholds = acceptanceThresholds(sourceLanguage, acousticEnabled)
        onProgress?.invoke(if (acousticEnabled) 0.30f else 0.24f)

        val accepted = mutableListOf<DetectedCue>()
        val diagnostics = mutableListOf<MatchDiagnostic>()
        val progressStart = if (acousticEnabled) 0.30f else 0.24f
        val progressSpan = 0.68f
        candidates.forEachIndexed { index, candidate ->
            val evaluation = evaluateCandidate(
                guide = guideLog,
                guideAcoustic = guideAcoustic,
                candidate = candidate,
                templates = activeTemplates,
                acousticTemplates = acousticTemplates,
                useEnglishAcoustic = sourceLanguage == "en" && acousticEnabled,
            )
            val cue = evaluation.toCue(thresholds.minScore, thresholds.minMargin, thresholds.highScore)
            if (cue != null) accepted += cue
            diagnostics += evaluation.toDiagnostic(cue, thresholds)
            if (index % maxOf(1, candidates.size / 30) == 0 || index == candidates.lastIndex) {
                onProgress?.invoke(progressStart + progressSpan * ((index + 1f) / candidates.size.toFloat()))
            }
        }

        val deduped = dedupe(accepted)
        val guarded = if (sourceLanguage == "en") guardEnglishSectionCollapse(deduped) else deduped
        val dominant = sourceLanguage ?: dominantLanguage(guarded)
        val compactDiagnostics = diagnostics
            .sortedWith(compareByDescending<MatchDiagnostic> { it.accepted }.thenByDescending { it.bestScore })
            .take(MAX_DIAGNOSTICS)
            .sortedBy { it.cueMs }
        onProgress?.invoke(1f)
        return Result(guarded, dominant, candidates.size, compactDiagnostics)
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
        return numberGenericVerses(unique, localeLanguage)
    }

    private fun acceptanceThresholds(sourceLanguage: String?, acousticEnabled: Boolean): AcceptanceThresholds =
        when {
            sourceLanguage == "en" && acousticEnabled ->
                AcceptanceThresholds(ENGLISH_ACOUSTIC_MIN_SCORE, ENGLISH_ACOUSTIC_MIN_MARGIN, ENGLISH_ACOUSTIC_HIGH_SCORE)
            sourceLanguage == "en" ->
                AcceptanceThresholds(ENGLISH_ENVELOPE_MIN_SCORE, ENGLISH_ENVELOPE_MIN_MARGIN, ENGLISH_ENVELOPE_HIGH_SCORE)
            else -> AcceptanceThresholds(PRIMARY_MIN_SCORE, PRIMARY_MIN_MARGIN, PRIMARY_HIGH_SCORE)
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

    private fun Evaluation.toDiagnostic(cue: DetectedCue?, thresholds: AcceptanceThresholds): MatchDiagnostic {
        val chosen = best
        val margin = if (chosen == null) -1f else chosen.score - secondSemanticScore
        val reason = when {
            cue != null -> "accepted"
            chosen == null -> "no_match"
            chosen.score < thresholds.minScore -> "low_score"
            margin < thresholds.minMargin && chosen.score < thresholds.highScore -> "ambiguous"
            else -> "rejected"
        }
        return MatchDiagnostic(
            cueMs = candidate * WINDOW_MS.toLong(),
            bestKey = chosen?.template?.sample?.key,
            bestKind = chosen?.template?.sample?.kind,
            bestLanguage = chosen?.template?.sample?.language,
            bestScore = chosen?.score ?: -1f,
            secondKey = secondSemanticKey,
            secondScore = secondSemanticScore,
            accepted = cue != null,
            reason = reason,
        )
    }

    private fun evaluateCandidate(
        guide: FloatArray,
        guideAcoustic: GuideAcousticFingerprint.Series?,
        candidate: Int,
        templates: List<Template>,
        acousticTemplates: Map<String, AcousticTemplate>,
        useEnglishAcoustic: Boolean,
    ): Evaluation {
        var best: Match? = null
        val semanticScores = HashMap<String, Float>()
        for (template in templates) {
            val match = if (useEnglishAcoustic && guideAcoustic != null) {
                val acoustic = acousticTemplates[sampleIdentity(template.sample)]
                if (acoustic != null) {
                    bestEnglishMatchAround(guide, guideAcoustic, candidate, template, acoustic)
                } else {
                    bestMatchAround(guide, candidate, template)
                }
            } else {
                bestMatchAround(guide, candidate, template)
            } ?: continue

            val semantic = "${template.sample.kind.name}:${template.sample.key}"
            val previous = semanticScores[semantic]
            if (previous == null || match.score > previous) semanticScores[semantic] = match.score
            val currentBest = best
            if (currentBest == null || match.score > currentBest.score) best = match
        }
        val chosen = best ?: return Evaluation(candidate, null, -1f, null)
        val chosenSemantic = "${chosen.template.sample.kind.name}:${chosen.template.sample.key}"
        var second = -1f
        var secondKey: String? = null
        for ((semantic, score) in semanticScores) {
            if (semantic != chosenSemantic && score > second) {
                second = score
                secondKey = semantic.substringAfter(':')
            }
        }
        return Evaluation(candidate, chosen, second, secondKey)
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
                evaluateCandidate(
                    guide = guide,
                    guideAcoustic = null,
                    candidate = candidate,
                    templates = languageTemplates,
                    acousticTemplates = emptyMap(),
                    useEnglishAcoustic = false,
                ).best?.score
            }.filter { it >= LANGUAGE_PROBE_MIN_MATCH }
                .sortedDescending()
                .take(LANGUAGE_PROBE_MAX_SCORES)
            if (scores.isEmpty()) return@mapNotNull null
            val strongest = scores.first()
            val matches = scores.size
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

    /**
     * A whole song becoming Vamp/Rap/Tag/Solo is a known failure signature, not a plausible default.
     * Preserve high-confidence occurrences, but discard low-confidence repetitions instead of
     * manufacturing a section map from one short English template.
     */
    private fun guardEnglishSectionCollapse(cues: List<DetectedCue>): List<DetectedCue> {
        val sections = cues.filter { it.kind == CueKind.SECTION }
        if (sections.size < COLLAPSE_MIN_SECTION_COUNT) return cues
        val dominant = sections.groupingBy { it.key }.eachCount().maxByOrNull { it.value } ?: return cues
        if (dominant.key !in ambiguousShortSections) return cues
        if (dominant.value.toDouble() / sections.size.toDouble() < COLLAPSE_SHARE) return cues

        val repeated = sections.filter { it.key == dominant.key }
        val strong = repeated.filter { it.confidence >= COLLAPSE_KEEP_SCORE }
        val keepFallback = if (strong.isEmpty()) repeated.maxByOrNull { it.confidence } else null
        return cues.filter { cue ->
            cue.kind != CueKind.SECTION || cue.key != dominant.key || cue in strong || cue == keepFallback
        }
    }

    private fun numberGenericVerses(proposals: List<SectionProposal>, localeLanguage: String): List<SectionProposal> {
        val genericVerseCount = proposals.count { canonicalBaseKey(it.key) == "verse" && !hasExplicitNumber(it.key) }
        if (genericVerseCount <= 1) return proposals
        var occurrence = 0
        val spanish = localeLanguage.lowercase(Locale.ROOT).startsWith("es")
        return proposals.map { proposal ->
            if (canonicalBaseKey(proposal.key) == "verse" && !hasExplicitNumber(proposal.key)) {
                occurrence++
                proposal.copy(name = if (spanish) "Verso $occurrence" else "Verse $occurrence")
            } else {
                proposal
            }
        }
    }

    private fun canonicalBaseKey(key: String): String = key.replace(Regex("_\\d+$"), "")
    private fun hasExplicitNumber(key: String): Boolean = Regex("_\\d+$").containsMatchIn(key)

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

    private fun acousticTemplatesFor(templates: List<Template>): Map<String, AcousticTemplate> {
        val englishTemplates = templates.filter { it.sample.language == "en" }
        if (englishTemplates.isEmpty()) return emptyMap()
        val signature = sampleSignature(englishTemplates.map { it.sample })
        acousticTemplateCache?.takeIf { it.signature == signature }?.let { return it.templates }
        return synchronized(this) {
            acousticTemplateCache?.takeIf { it.signature == signature }?.let { return@synchronized it.templates }
            val built = buildMap {
                for (template in englishTemplates) {
                    val series = runCatching { GuideAcousticFingerprint.read(template.sample.file, WINDOW_MS) }.getOrNull()
                        ?: continue
                    val fingerprint = GuideAcousticFingerprint.sliceAndNormalize(
                        series = series,
                        startWindow = template.trimStartWindows,
                        windowCount = template.fingerprint.size,
                    ) ?: continue
                    val identity = sampleIdentity(template.sample)
                    put(identity, AcousticTemplate(identity, fingerprint, template.fingerprint.size))
                }
            }
            acousticTemplateCache = AcousticTemplateCache(signature, built)
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
            val score = envelopeScoreAt(guide, start, template.fingerprint) ?: continue
            if (score > bestScore) {
                bestScore = score
                bestStart = start
            }
        }
        return if (bestStart >= 0) Match(template, bestScore, bestStart) else null
    }

    private fun bestEnglishMatchAround(
        guide: FloatArray,
        acousticGuide: GuideAcousticFingerprint.Series,
        candidate: Int,
        template: Template,
        acousticTemplate: AcousticTemplate,
    ): Match? {
        val n = template.fingerprint.size
        if (guide.size < n || acousticTemplate.windowCount != n) return null
        var bestScore = -1f
        var bestStart = -1
        var bestEnvelope = -1f
        var bestAcoustic: Float? = null
        val from = (candidate - SEARCH_RADIUS_WINDOWS).coerceAtLeast(0)
        val to = (candidate + SEARCH_RADIUS_WINDOWS).coerceAtMost(minOf(guide.size, acousticGuide.windows) - n)
        if (to < from) return null
        for (start in from..to) {
            val envelopeScore = envelopeScoreAt(guide, start, template.fingerprint) ?: continue
            val acousticScore = GuideAcousticFingerprint.correlation(
                guide = acousticGuide,
                startWindow = start,
                template = acousticTemplate.fingerprint,
                windowCount = n,
            ) ?: continue
            var score = envelopeScore * ENVELOPE_WEIGHT + acousticScore * ACOUSTIC_WEIGHT
            if (
                template.sample.kind == CueKind.SECTION &&
                canonicalBaseKey(template.sample.key) in ambiguousShortSections
            ) {
                score -= AMBIGUOUS_SHORT_SECTION_PENALTY
            }
            score = score.coerceIn(-1f, 1f)
            if (score > bestScore) {
                bestScore = score
                bestStart = start
                bestEnvelope = envelopeScore
                bestAcoustic = acousticScore
            }
        }
        return if (bestStart >= 0) {
            Match(template, bestScore, bestStart, envelopeScore = bestEnvelope, acousticScore = bestAcoustic)
        } else {
            null
        }
    }

    private fun envelopeScoreAt(guide: FloatArray, start: Int, fingerprint: FloatArray): Float? {
        val n = fingerprint.size
        if (start < 0 || start + n > guide.size || n < 2) return null
        var sum = 0.0
        var sumSq = 0.0
        for (i in 0 until n) {
            val value = guide[start + i].toDouble()
            sum += value
            sumSq += value * value
        }
        val mean = sum / n
        val variance = (sumSq / n) - mean * mean
        if (variance <= 1e-10) return null
        val std = sqrt(variance)
        var dot = 0.0
        for (i in 0 until n) dot += fingerprint[i] * (guide[start + i] - mean).toFloat()
        return (dot / (n * std)).toFloat().coerceIn(-1f, 1f)
    }

    private fun findCandidates(envelope: FloatArray): List<Int> {
        val peak = envelope.maxOrNull() ?: return emptyList()
        if (peak < 0.0015f) return emptyList()
        val sorted = envelope.sorted()
        val floorCount = max(1, sorted.size / 5)
        val noise = sorted.take(floorCount).average().toFloat()

        val strongThreshold = max(0.0025f, max(noise * 4.0f, peak * 0.045f))
        val relaxedThreshold = max(0.0015f, max(noise * 2.2f, peak * 0.012f))
        val candidates = linkedSetOf<Int>()
        candidates += runStarts(envelope, strongThreshold, mergeGapWindows = INTRA_PHRASE_MERGE_WINDOWS)
        candidates += runStarts(envelope, relaxedThreshold, mergeGapWindows = INTRA_PHRASE_MERGE_WINDOWS)

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
