package dev.stagegrid.storage

import dev.stagegrid.waveform.WaveformPeakCache
import java.io.File

/** Filesystem accounting for StageGrid-owned app-private data. */
class StorageCacheManager(private val filesDir: File) {
    data class Snapshot(
        val libraryBytes: Long,
        val playbackAudioBytes: Long,
        val regenerableCacheBytes: Long,
        val guideBytes: Long,
        val otherBytes: Long,
        val songCount: Int,
    ) {
        val totalBytes: Long get() = libraryBytes + guideBytes + otherBytes
    }

    fun snapshot(): Snapshot {
        val library = File(filesDir, "library")
        var audioBytes = 0L
        var cacheBytes = 0L
        var songCount = 0
        library.listFiles()?.filter { it.isDirectory }?.forEach { songRoot ->
            songCount++
            audioBytes += directorySize(File(songRoot, "audio"))
            cacheBytes += directorySize(File(songRoot, "cache"))
        }

        val libraryBytes = directorySize(library)
        val guideBytes = directorySize(File(filesDir, "guide-packs"))
        val appPrivateRoot = filesDir.parentFile ?: filesDir
        val allBytes = directorySize(appPrivateRoot)
        val otherBytes = (allBytes - libraryBytes - guideBytes).coerceAtLeast(0L)

        return Snapshot(
            libraryBytes = libraryBytes,
            playbackAudioBytes = audioBytes,
            regenerableCacheBytes = cacheBytes,
            guideBytes = guideBytes,
            otherBytes = otherBytes,
            songCount = songCount,
        )
    }

    /** Deletes only data that StageGrid can recreate from retained playback WAV files. */
    fun clearRegenerableCaches(): Long {
        val library = File(filesDir, "library")
        var reclaimed = 0L
        library.listFiles()?.filter { it.isDirectory }?.forEach { songRoot ->
            reclaimed += WaveformPeakCache.clear(songRoot)
        }
        return reclaimed
    }

    private fun directorySize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf(::directorySize) ?: 0L
    }
}
