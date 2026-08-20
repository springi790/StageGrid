package dev.stagegrid.guide

import dev.stagegrid.guide.GuidePackManager.CueKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** Reads persisted native-Guide cue events without re-analyzing the source audio. */
object NativeGuideEventStore {
    data class StoredSectionProposal(
        val key: String,
        val name: String,
        val startMs: Long,
        val confidence: Float,
    )

    fun readAnalysis(file: File): GuideCueAnalyzer.Result? {
        val root = readRoot(file) ?: return null
        return runCatching {
            val cuesJson = root.optJSONArray("cues") ?: JSONArray()
            val cues = buildList {
                for (i in 0 until cuesJson.length()) {
                    val item = cuesJson.optJSONObject(i) ?: continue
                    val kind = runCatching { CueKind.valueOf(item.optString("kind")) }.getOrNull() ?: continue
                    val key = item.optString("key").trim()
                    val language = item.optString("language").trim()
                    val cueMs = item.optLong("cueMs", -1L)
                    val confidence = item.optDouble("confidence", 0.0).toFloat()
                    if (key.isBlank() || language.isBlank() || cueMs < 0L) continue
                    add(
                        GuideCueAnalyzer.DetectedCue(
                            key = key,
                            kind = kind,
                            language = language,
                            cueMs = cueMs,
                            confidence = confidence.coerceIn(0f, 1f),
                        ),
                    )
                }
            }
            if (cues.isEmpty()) return@runCatching null
            GuideCueAnalyzer.Result(
                cues = cues.sortedBy { it.cueMs },
                dominantLanguage = root.optNullableString("detectedLanguage"),
                candidateCount = root.optInt("candidateCount", cues.size),
                diagnostics = readDiagnostics(root),
            )
        }.getOrNull()
    }

    fun readDiagnostics(file: File): List<GuideCueAnalyzer.MatchDiagnostic> =
        readRoot(file)?.let(::readDiagnostics) ?: emptyList()

    private fun readDiagnostics(root: JSONObject): List<GuideCueAnalyzer.MatchDiagnostic> {
        val array = root.optJSONArray("diagnostics") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val bestKind = item.optNullableString("bestKind")?.let { value ->
                    runCatching { CueKind.valueOf(value) }.getOrNull()
                }
                add(
                    GuideCueAnalyzer.MatchDiagnostic(
                        cueMs = item.optLong("cueMs", 0L).coerceAtLeast(0L),
                        bestKey = item.optNullableString("bestKey"),
                        bestKind = bestKind,
                        bestLanguage = item.optNullableString("bestLanguage"),
                        bestScore = item.optDouble("bestScore", -1.0).toFloat(),
                        secondKey = item.optNullableString("secondKey"),
                        secondScore = item.optDouble("secondScore", -1.0).toFloat(),
                        accepted = item.optBoolean("accepted", false),
                        reason = item.optString("reason", "unknown"),
                    ),
                )
            }
        }.sortedBy { it.cueMs }
    }

    fun readOutputLanguage(file: File): String? = readRoot(file)?.optNullableString("outputLanguage")

    fun readDetectedLanguage(file: File): String? = readRoot(file)?.optNullableString("detectedLanguage")

    fun readSectionCueSource(file: File): String? = readRoot(file)?.optNullableString("sectionCueSource")

    fun readSectionProposals(file: File): List<StoredSectionProposal> {
        val root = readRoot(file) ?: return emptyList()
        val sections = root.optJSONArray("sections") ?: return emptyList()
        return buildList {
            for (i in 0 until sections.length()) {
                val item = sections.optJSONObject(i) ?: continue
                val key = item.optString("key").trim()
                val name = item.optString("name").trim()
                val startMs = item.optLong("startMs", -1L)
                if (key.isBlank() || startMs < 0L) continue
                add(
                    StoredSectionProposal(
                        key = key,
                        name = name,
                        startMs = startMs,
                        confidence = item.optDouble("confidence", 0.0).toFloat().coerceIn(0f, 1f),
                    ),
                )
            }
        }.sortedBy { it.startMs }
    }

    /**
     * Resolves an editable Room section back to the canonical Guide cue key saved at import time.
     * Start position is authoritative because users may rename a section after auto-detection.
     */
    fun findSectionKey(file: File, sectionStartMs: Long, sectionName: String): String? {
        val proposals = readSectionProposals(file)
        if (proposals.isEmpty()) return null
        proposals.firstOrNull { it.startMs == sectionStartMs }?.let { return it.key }
        proposals.minByOrNull { abs(it.startMs - sectionStartMs) }
            ?.takeIf { abs(it.startMs - sectionStartMs) <= SECTION_MATCH_TOLERANCE_MS }
            ?.let { return it.key }

        val normalizedName = canonicalName(sectionName)
        if (normalizedName.isBlank()) return null
        return proposals.firstOrNull {
            canonicalName(it.name) == normalizedName || canonicalName(it.key) == normalizedName
        }?.key
    }

    fun writeOutputLanguage(file: File, language: String) {
        if (!file.isFile || language.isBlank()) return
        runCatching {
            val root = JSONObject(file.readText())
            root.put("outputLanguage", language)
            file.writeText(root.toString(2))
        }
    }

    fun writeSectionProposals(file: File, proposals: List<GuideCueAnalyzer.SectionProposal>) {
        if (!file.isFile) return
        runCatching {
            val root = JSONObject(file.readText())
            val sections = JSONArray()
            proposals.forEach { section ->
                sections.put(
                    JSONObject()
                        .put("key", section.key)
                        .put("name", section.name)
                        .put("startMs", section.startMs)
                        .put("confidence", section.confidence.toDouble()),
                )
            }
            root.put("sections", sections)
            file.writeText(root.toString(2))
        }
    }

    fun markManualSectionCues(file: File) {
        if (!file.isFile) return
        runCatching {
            val root = JSONObject(file.readText())
            root.put("sectionCueSource", "manual")
            root.put("automaticRecognition", "experimental")
            file.writeText(root.toString(2))
        }
    }

    private fun readRoot(file: File): JSONObject? {
        if (!file.isFile) return null
        return runCatching { JSONObject(file.readText()) }.getOrNull()
    }

    private fun canonicalName(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')

    private fun JSONObject.optNullableString(key: String): String? =
        optString(key).trim().takeIf { it.isNotBlank() && it != "null" }

    private const val SECTION_MATCH_TOLERANCE_MS = 2_000L
}
