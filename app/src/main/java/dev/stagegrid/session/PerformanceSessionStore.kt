package dev.stagegrid.session

import dev.stagegrid.audio.ClickSubdivision
import dev.stagegrid.model.StereoRoute
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

class PerformanceSessionStore(filesDir: File) {
    data class Snapshot(
        val songId: String,
        val positionMs: Long,
        val clickEnabled: Boolean,
        val guideEnabled: Boolean,
        val clickSubdivision: ClickSubdivision,
        val clickRoute: StereoRoute,
        val countInBars: Int,
        val masterVolume: Float,
        val setlistActive: Boolean,
        val setlistId: String?,
        val setlistIndex: Int,
        val savedAtEpochMs: Long = System.currentTimeMillis(),
    )

    private val directory = File(filesDir, "session")
    private val snapshotFile = File(directory, "performance-session.json")

    @Synchronized
    fun write(snapshot: Snapshot) {
        directory.mkdirs()
        val temp = File(directory, ".performance-session-${System.nanoTime()}.tmp")
        val json = JSONObject()
            .put("version", VERSION)
            .put("songId", snapshot.songId)
            .put("positionMs", snapshot.positionMs.coerceAtLeast(0L))
            .put("clickEnabled", snapshot.clickEnabled)
            .put("guideEnabled", snapshot.guideEnabled)
            .put("clickSubdivision", snapshot.clickSubdivision.name)
            .put("clickRoute", snapshot.clickRoute.name)
            .put("countInBars", snapshot.countInBars.coerceIn(0, 2))
            .put("masterVolume", snapshot.masterVolume.coerceIn(0f, 1f).toDouble())
            .put("setlistActive", snapshot.setlistActive)
            .put("setlistId", snapshot.setlistId ?: JSONObject.NULL)
            .put("setlistIndex", snapshot.setlistIndex)
            .put("savedAtEpochMs", snapshot.savedAtEpochMs)

        FileOutputStream(temp).use { out ->
            out.write(json.toString(2).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (snapshotFile.exists() && !snapshotFile.delete()) {
            temp.delete()
            return
        }
        if (!temp.renameTo(snapshotFile)) {
            temp.copyTo(snapshotFile, overwrite = true)
            temp.delete()
        }
    }

    @Synchronized
    fun read(): Snapshot? {
        if (!snapshotFile.isFile) return null
        return runCatching {
            val json = JSONObject(snapshotFile.readText())
            if (json.optInt("version", -1) != VERSION) return@runCatching null
            val songId = json.optString("songId").trim()
            if (songId.isBlank()) return@runCatching null
            Snapshot(
                songId = songId,
                positionMs = json.optLong("positionMs", 0L).coerceAtLeast(0L),
                clickEnabled = json.optBoolean("clickEnabled", true),
                guideEnabled = json.optBoolean("guideEnabled", true),
                clickSubdivision = runCatching {
                    ClickSubdivision.valueOf(json.optString("clickSubdivision"))
                }.getOrDefault(ClickSubdivision.QUARTER),
                clickRoute = runCatching {
                    StereoRoute.valueOf(json.optString("clickRoute"))
                }.getOrDefault(StereoRoute.BOTH),
                countInBars = json.optInt("countInBars", 0).coerceIn(0, 2),
                masterVolume = json.optDouble("masterVolume", 1.0).toFloat().coerceIn(0f, 1f),
                setlistActive = json.optBoolean("setlistActive", false),
                setlistId = json.optString("setlistId").trim().takeIf { it.isNotBlank() && it != "null" },
                setlistIndex = json.optInt("setlistIndex", -1),
                savedAtEpochMs = json.optLong("savedAtEpochMs", 0L),
            )
        }.getOrNull()
    }

    @Synchronized
    fun clear() {
        snapshotFile.delete()
    }

    companion object {
        const val VERSION = 1
    }
}
