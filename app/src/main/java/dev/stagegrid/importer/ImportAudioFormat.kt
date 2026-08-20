package dev.stagegrid.importer

import java.util.Locale

/**
 * Import-time audio format policy.
 *
 * StageGrid keeps compressed decoding out of the realtime engine. Formats marked
 * [normalizeToWav] are decoded once during import into the app-private PCM WAV cache.
 * FLAC/OGG stay discoverable so the importer can report them clearly while their
 * production path is evaluated for a later 0.3 milestone.
 */
enum class ImportAudioFormat(
    val extension: String,
    val label: String,
    val playable: Boolean,
    val normalizeToWav: Boolean,
) {
    WAV("wav", "WAV", playable = true, normalizeToWav = false),
    MP3("mp3", "MP3", playable = true, normalizeToWav = true),
    M4A("m4a", "M4A", playable = true, normalizeToWav = true),
    AAC("aac", "AAC", playable = true, normalizeToWav = true),
    FLAC("flac", "FLAC", playable = false, normalizeToWav = false),
    OGG("ogg", "OGG", playable = false, normalizeToWav = false),
    ;

    companion object {
        val detectedExtensions: Set<String> = entries.mapTo(linkedSetOf()) { it.extension }
        val playableExtensions: Set<String> = entries.filter { it.playable }.mapTo(linkedSetOf()) { it.extension }

        fun fromFileName(fileName: String): ImportAudioFormat? {
            val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return entries.firstOrNull { it.extension == extension }
        }
    }
}
