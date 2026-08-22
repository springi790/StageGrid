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
        val withoutFeaturing = value
            .replace(FEAT_PAREN_REGEX, " ")
            .replace(FEAT_BRACKET_REGEX, " ")
        val ascii = Normalizer.normalize(withoutFeaturing, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
        return ascii
            .replace(Regex("\\([^)]*(live|en vivo|remaster|version|edit|acoustic|acustic|instrumental)[^)]*\\)"), " ")
            .replace(Regex("\\[[^]]*(live|en vivo|remaster|version|edit|acoustic|acustic|instrumental)[^]]*]"), " ")
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

    /**
     * Artist credits often expand online ("Passion" -> "Passion & Kristian Stanfill").
     * For metadata matching, a complete shorter credit contained in the larger credit is strong
     * evidence and should not be penalized like unrelated extra title words.
     */
    fun tokenContainmentSimilarity(a: String, b: String): Float {
        val aa = normalize(a)
        val bb = normalize(b)
        if (aa.isBlank() || bb.isBlank()) return 0f
        if (aa == bb) return 1f
        val at = aa.split(' ').filter { it.length > 1 }.toSet()
        val bt = bb.split(' ').filter { it.length > 1 }.toSet()
        if (at.isEmpty() || bt.isEmpty()) return 0f
        val intersection = at.intersect(bt).size.toFloat()
        val smaller = minOf(at.size, bt.size).toFloat().coerceAtLeast(1f)
        return intersection / smaller
    }

    /** Remove filename/package annotations before sending hints to an online catalog. */
    fun cleanTitleHint(value: String): String {
        var cleaned = value.substringBeforeLast('.').trim()
        cleaned = cleaned.replace(TRAILING_KEY_REGEX, "").trim()
        cleaned = cleaned.replace(FEAT_PAREN_REGEX, " ").replace(FEAT_BRACKET_REGEX, " ")
        return cleaned.replace(Regex("\\s+"), " ").trim(' ', '-', '–', '—', '|')
    }

    fun cleanArtistHint(value: String): String {
        var cleaned = value.trim()
        // A source package can be truncated to e.g. "Passion ft". Never send the dangling token.
        cleaned = cleaned.replace(DANGLING_FEAT_REGEX, "").trim()
        return cleaned.replace(Regex("\\s+"), " ").trim(' ', '-', '–', '—', '|', '&', ',')
    }

    /** Folder/ZIP names are commonly `Title - Artist`; return conservative, cleaned hints only. */
    fun splitTitleArtist(value: String): Pair<String, String> {
        val cleaned = value.substringBeforeLast('.').trim()
        val separators = listOf(" - ", " – ", " — ", " | ")
        for (separator in separators) {
            val parts = cleaned.split(separator, limit = 2).map { it.trim() }
            if (parts.size == 2 && parts.all { it.isNotBlank() }) {
                return cleanTitleHint(parts[0]) to cleanArtistHint(parts[1])
            }
        }
        return cleanTitleHint(cleaned) to ""
    }

    private val FEAT_PAREN_REGEX = Regex(
        "(?i)\\s*[\\(（]\\s*(feat(?:uring)?|ft\\.?|con|with)\\b[^\\)）]*[\\)）]",
    )
    private val FEAT_BRACKET_REGEX = Regex(
        "(?i)\\s*\\[\\s*(feat(?:uring)?|ft\\.?|con|with)\\b[^]]*]",
    )
    private val DANGLING_FEAT_REGEX = Regex(
        "(?i)\\s+(feat(?:uring)?\\.?|ft\\.?|con|with)\\s*$",
    )
    private val TRAILING_KEY_REGEX = Regex(
        "(?i)\\s*[\\(\\[]\\s*(?:key\\s*(?:of)?\\s*)?(?:[A-G](?:#|b)?(?:m|maj|min|major|minor)?|Do|Re|Mi|Fa|Sol|La|Si)(?:\\s*(?:major|minor|mayor|menor))?\\s*[\\)\\]]\\s*$",
    )
}
