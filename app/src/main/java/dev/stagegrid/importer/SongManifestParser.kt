package dev.stagegrid.importer

import dev.stagegrid.model.TrackType
import org.json.JSONObject
import java.io.File

object SongManifestParser {
    data class ManifestSection(val name: String, val startMs: Long, val colorArgb: Long? = null)
    data class Manifest(
        val title: String? = null,
        val artist: String? = null,
        val bpm: Double? = null,
        val key: String? = null,
        val timeSignature: String? = null,
        val trackTypes: Map<String, TrackType> = emptyMap(),
        val sections: List<ManifestSection> = emptyList(),
    )

    fun parse(file: File?): Manifest {
        if (file == null || !file.exists()) return Manifest()
        return runCatching {
            val json = JSONObject(file.readText())
            val trackTypes = buildMap {
                val arr = json.optJSONArray("tracks")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val path = item.optString("file").substringAfterLast('/').substringAfterLast('\\')
                        val type = item.optString("type").uppercase()
                        val parsed = TrackType.entries.firstOrNull { it.name == type }
                        if (path.isNotBlank() && parsed != null) put(path.lowercase(), parsed)
                    }
                }
            }
            val sections = buildList {
                val arr = json.optJSONArray("sections")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val name = item.optString("name", "Section ${i + 1}")
                        val startSeconds = item.optDouble("start", Double.NaN)
                        if (!startSeconds.isNaN() && startSeconds >= 0.0) {
                            add(ManifestSection(name, (startSeconds * 1000.0).toLong()))
                        }
                    }
                }
            }.sortedBy { it.startMs }
            Manifest(
                title = json.optString("title").takeIf { it.isNotBlank() },
                artist = json.optString("artist").takeIf { it.isNotBlank() },
                bpm = json.optDouble("bpm", Double.NaN).takeIf { !it.isNaN() && it > 0.0 },
                key = json.optString("key").takeIf { it.isNotBlank() },
                timeSignature = json.optString("timeSignature").takeIf { it.matches(Regex("\\d+/\\d+")) },
                trackTypes = trackTypes,
                sections = sections,
            )
        }.getOrElse { Manifest() }
    }
}
