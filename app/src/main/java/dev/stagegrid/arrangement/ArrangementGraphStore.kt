package dev.stagegrid.arrangement

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Arrangement data is intentionally stored beside the song instead of in the audio callback or
 * transient UI state. Song directories are already included in portable StageGrid backups, so the
 * graph travels with the song without making older backup manifests incompatible.
 */
class ArrangementGraphStore(private val filesDir: File) {
    fun loadOrCreate(songId: String, default: ArrangementGraph): ArrangementGraph {
        val file = fileFor(songId)
        if (!file.isFile) {
            save(default)
            return default.normalized()
        }
        return runCatching { decode(file.readText(), songId).normalized() }
            .getOrElse {
                val recovered = default.normalized()
                save(recovered)
                recovered
            }
    }

    fun load(songId: String): ArrangementGraph? {
        val file = fileFor(songId)
        if (!file.isFile) return null
        return runCatching { decode(file.readText(), songId).normalized() }.getOrNull()
    }

    fun save(graph: ArrangementGraph) {
        val normalized = graph.normalized().copy(updatedAtEpochMs = System.currentTimeMillis())
        val target = fileFor(normalized.songId)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.tmp-${System.nanoTime()}")
        temp.writeText(encode(normalized).toString(2))
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun fileFor(songId: String): File = File(filesDir, "library/$songId/arrangement.json")

    private fun encode(graph: ArrangementGraph): JSONObject = JSONObject()
        .put("format", FORMAT)
        .put("version", VERSION)
        .put("songId", graph.songId)
        .put("updatedAtEpochMs", graph.updatedAtEpochMs)
        .put("nodes", JSONArray().apply {
            graph.nodes.forEach { node ->
                put(
                    JSONObject()
                        .put("id", node.id)
                        .put("sectionId", node.sectionId)
                        .put("label", node.label)
                        .put("order", node.order)
                        .put("repeatCount", node.repeatCount)
                        .put("preRollBars", node.preRollBars)
                        .put("guideEnabled", node.guideEnabled),
                )
            }
        })

    private fun decode(text: String, expectedSongId: String): ArrangementGraph {
        val root = JSONObject(text)
        require(root.optString("format") == FORMAT) { "Unsupported arrangement format" }
        require(root.optInt("version", -1) == VERSION) { "Unsupported arrangement version" }
        val songId = root.optString("songId")
        require(songId == expectedSongId) { "Arrangement belongs to a different song" }
        val nodesJson = root.optJSONArray("nodes") ?: JSONArray()
        require(nodesJson.length() <= MAX_NODES) { "Arrangement is too large" }
        val nodes = buildList {
            for (i in 0 until nodesJson.length()) {
                val item = nodesJson.getJSONObject(i)
                val id = item.optString("id").trim()
                val sectionId = item.optString("sectionId").trim()
                require(id.isNotBlank() && sectionId.isNotBlank()) { "Invalid arrangement node" }
                add(
                    ArrangementNode(
                        id = id,
                        sectionId = sectionId,
                        label = item.optString("label").take(96),
                        order = item.optInt("order", i),
                        repeatCount = item.optInt("repeatCount", 1),
                        preRollBars = item.optInt("preRollBars", 0),
                        guideEnabled = item.optBoolean("guideEnabled", true),
                    ),
                )
            }
        }
        return ArrangementGraph(
            songId = songId,
            nodes = nodes,
            updatedAtEpochMs = root.optLong("updatedAtEpochMs", 0L),
        )
    }

    private companion object {
        const val FORMAT = "stagegrid-arrangement"
        const val VERSION = 1
        const val MAX_NODES = 256
    }
}
