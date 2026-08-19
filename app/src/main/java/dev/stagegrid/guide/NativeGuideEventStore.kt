package dev.stagegrid.guide

import dev.stagegrid.guide.GuidePackManager.CueKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Reads persisted native-Guide cue events without re-analyzing the source audio. */
object NativeGuideEventStore {
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
            )
        }.getOrNull()
    }

    fun readOutputLanguage(file: File): String? = readRoot(file)?.optNullableString("outputLanguage")

    fun readDetectedLanguage(file: File): String? = readRoot(file)?.optNullableString("detectedLanguage")

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

    private fun readRoot(file: File): JSONObject? {
        if (!file.isFile) return null
        return runCatching { JSONObject(file.readText()) }.getOrNull()
    }

    private fun JSONObject.optNullableString(key: String): String? =
        optString(key).trim().takeIf { it.isNotBlank() && it != "null" }
}
