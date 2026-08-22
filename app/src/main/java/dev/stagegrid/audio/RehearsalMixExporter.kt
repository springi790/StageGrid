package dev.stagegrid.audio

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.stagegrid.debug.StageGridDebugLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** UI-facing state for the offline rehearsal render. */
data class RehearsalMixExportState(
    val running: Boolean = false,
    val filePath: String? = null,
    val fileName: String? = null,
    val sizeBytes: Long = 0L,
    val error: String? = null,
)

/**
 * Creates a shareable stereo snapshot of the current StageGrid mixer.
 *
 * The native renderer opens independent WAV readers, so it never seeks, pauses or mutates the live
 * deck. Callers still block export during playback because offline DSP is deliberately CPU-heavy
 * and live audio always wins over convenience work.
 */
class RehearsalMixExporter(
    private val context: Context,
    private val nativeAudio: NativeAudioEngine,
) {
    fun export(snapshot: PlayerState): Result<File> = runCatching {
        val song = snapshot.song ?: error("Load a song before exporting a rehearsal mix.")
        require(snapshot.tracks.isNotEmpty()) { "This song has no tracks to export." }

        val exportRoot = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
        pruneOldExports(exportRoot)
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val safeTitle = sanitizeFileName(song.title).ifBlank { "StageGrid" }
        val destination = File(exportRoot, "$safeTitle-rehearsal-$timestamp.wav")
        destination.delete()

        val beatsPerBar = song.timeSignature.substringBefore('/').toIntOrNull()?.coerceIn(1, 32) ?: 4
        val renderError = nativeAudio.renderRehearsalMix(
            outputPath = destination.absolutePath,
            tracks = snapshot.tracks.toList(),
            bpm = song.bpm,
            beatsPerBar = beatsPerBar,
            gridOffsetMs = song.gridOffsetMs,
            masterVolume = snapshot.masterVolume,
            tempoRatio = snapshot.tempoRatio,
            pitchSemitones = snapshot.pitchSemitones,
            clickEnabled = snapshot.clickEnabled,
            guideEnabled = snapshot.guideEnabled,
            clickSubdivision = snapshot.clickSubdivision.subdivisionsPerBeat,
            clickRoute = snapshot.clickRoute,
        )
        if (renderError != null) {
            destination.delete()
            error(renderError)
        }
        require(destination.isFile && destination.length() > WAV_HEADER_BYTES) {
            "StageGrid did not create a valid rehearsal WAV."
        }
        StageGridDebugLog.io(
            "EXPORT",
            "REHEARSAL_MIX_FILE name=${destination.name} bytes=${destination.length()}",
        )
        destination
    }

    private fun pruneOldExports(root: File) {
        val files = root.listFiles()?.filter { it.isFile && it.extension.equals("wav", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        files.drop(MAX_CACHED_EXPORTS - 1).forEach { runCatching { it.delete() } }
    }

    private fun sanitizeFileName(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(72)

    companion object {
        private const val EXPORT_DIR = "rehearsal-exports"
        private const val MAX_CACHED_EXPORTS = 4
        private const val WAV_HEADER_BYTES = 44L

        fun shareIntent(context: Context, file: File): Intent {
            require(file.isFile) { "The rehearsal mix is no longer available." }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            return Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, file.nameWithoutExtension)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
}
