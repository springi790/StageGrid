package dev.stagegrid.importer

import java.util.Locale

/**
 * Import-time audio format policy.
 *
 * StageGrid keeps compressed/container decoding out of the realtime engine. Formats marked
 * [normalizeToWav] are decoded once during import into app-private PCM WAV before playback.
 * The 0.3 final-alpha policy uses Android's platform extractor/decoder for MP3, AAC/M4A and the
 * FLAC/OGG families when the device exposes a compatible decoder.
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
    FLAC("flac", "FLAC", playable = true, normalizeToWav = true),
    OGG("ogg", "OGG", playable = true, normalizeToWav = true),
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
