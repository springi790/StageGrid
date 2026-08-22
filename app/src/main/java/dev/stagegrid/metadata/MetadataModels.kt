package dev.stagegrid.metadata

import java.text.Normalizer
import java.util.Locale

/** Portable metadata contract. Providers may come and go without touching Library/Compose. */
data class MetadataQuery(
    val title: String,
    val artist: String,
    val durationMs: Long? = null,
)

data class MetadataCandidate(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val artworkUrl: String? = null,
    val provider: String,
    val providerId: String? = null,
    val providerScore: Int? = null,
)

data class LocalMusicAnalysis(
    val bpm: Double? = null,
    val bpmConfidence: Float = 0f,
    val musicalKey: String? = null,
    val keyConfidence: Float = 0f,
)

data class MetadataEnrichment(
    val title: String,
    val artist: String,
    val bpm: Double?,
    val musicalKey: String?,
    val artworkPath: String?,
    val onlineCandidate: MetadataCandidate? = null,
    val onlineConfidence: Float = 0f,
    val localAnalysis: LocalMusicAnalysis = LocalMusicAnalysis(),
    val notes: List<String> = emptyList(),
)

interface MetadataProvider {
    suspend fun search(query: MetadataQuery): List<MetadataCandidate>
}

object MetadataText {
    fun normalize(value: String): String {
        val ascii = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
        return ascii
            .replace(Regex("\\([^)]*(live|remaster|version|edit|acoustic|instrumental)[^)]*\\)"), " ")
            .replace(Regex("\\[[^]]*(live|remaster|version|edit|acoustic|instrumental)[^]]*]"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    fun tokenSimilarity(a: String, b: String): Float {
        val aa = normalize(a)
        val bb = normalize(b)
        if (aa.isBlank() || bb.isBlank()) return 0f
        if (aa == bb) return 1f
        val at = aa.split(' ').filter { it.length > 1 }.toSet()
        val bt = bb.split(' ').filter { it.length > 1 }.toSet()
        if (at.isEmpty() || bt.isEmpty()) return 0f
        val intersection = at.intersect(bt).size.toFloat()
        val union = at.union(bt).size.toFloat().coerceAtLeast(1f)
        return intersection / union
    }

    /** Folder/ZIP names are commonly `Title - Artist`; return conservative hints only. */
    fun splitTitleArtist(value: String): Pair<String, String> {
        val cleaned = value.substringBeforeLast('.').trim()
        val separators = listOf(" - ", " – ", " — ", " | ")
        for (separator in separators) {
            val parts = cleaned.split(separator, limit = 2).map { it.trim() }
            if (parts.size == 2 && parts.all { it.isNotBlank() }) return parts[0] to parts[1]
        }
        return cleaned to ""
    }
}
