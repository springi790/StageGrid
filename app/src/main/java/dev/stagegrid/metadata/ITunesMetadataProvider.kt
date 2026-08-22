package dev.stagegrid.metadata

import dev.stagegrid.debug.StageGridDebugLog
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import org.json.JSONObject

/**
 * Broad public music-catalog search used as StageGrid's primary online metadata source.
 *
 * The iTunes Search API is particularly useful for worship/Christian catalog coverage where
 * community-edited databases can be sparse. It requires no user OAuth for catalog searches.
 */
class ITunesMetadataProvider : MetadataProvider {
    override suspend fun search(query: MetadataQuery): List<MetadataCandidate> {
        val title = MetadataText.cleanTitleHint(query.title)
        if (title.isBlank()) return emptyList()
        val artist = MetadataText.cleanArtistHint(query.artist)
        val combinedTerm = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
        val accumulated = mutableListOf<MetadataCandidate>()

        // First try the useful title+artist hint. Do not stop merely because a storefront returned
        // unrelated rows: only a title-relevant result is enough to stop broadening the search.
        for (country in COUNTRIES) {
            val results = request(combinedTerm, country, "TITLE_ARTIST")
            accumulated += results
            if (containsRelevantTitle(title, results)) return dedupe(accumulated)
        }

        // Packages from multitrack vendors often contain incomplete artist text (e.g. "Passion ft").
        // A title-only fallback lets StageGrid's own artist/duration confidence ranking recover the
        // correct catalog entry instead of declaring the catalog empty.
        if (artist.isNotBlank()) {
            for (country in COUNTRIES) {
                val results = request(title, country, "TITLE_ONLY")
                accumulated += results
                if (containsRelevantTitle(title, results)) return dedupe(accumulated)
            }
        }

        return dedupe(accumulated)
    }

    private fun containsRelevantTitle(title: String, candidates: List<MetadataCandidate>): Boolean =
        candidates.any { MetadataText.tokenContainmentSimilarity(title, it.title) >= TITLE_RELEVANCE_FLOOR }

    private fun dedupe(candidates: List<MetadataCandidate>): List<MetadataCandidate> = candidates
        .distinctBy { candidate ->
            candidate.providerId ?: "${MetadataText.normalize(candidate.title)}|${MetadataText.normalize(candidate.artist)}"
        }
        .take(MAX_RETURNED_CANDIDATES)

    private fun request(term: String, country: String, mode: String): List<MetadataCandidate> {
        val encoded = URLEncoder.encode(term, Charsets.UTF_8.name())
        val url = URL(
            "https://itunes.apple.com/search?term=$encoded&country=$country&media=music&entity=song&limit=$LIMIT",
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
            instanceFollowRedirects = true
        }
        return try {
            val code = connection.responseCode
            StageGridDebugLog.state("METADATA", "ITUNES_HTTP country=$country mode=$mode code=$code")
            if (code !in 200..299) return emptyList()
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val parsed = parse(JSONObject(text))
            StageGridDebugLog.state("METADATA", "ITUNES_RESULTS country=$country mode=$mode count=${parsed.size}")
            parsed
        } finally {
            connection.disconnect()
        }
    }

    internal fun parse(root: JSONObject): List<MetadataCandidate> {
        val results = root.optJSONArray("results") ?: return emptyList()
        return buildList {
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                if (!item.optString("kind").equals("song", ignoreCase = true)) continue
                val title = item.optString("trackName").trim()
                val artist = item.optString("artistName").trim()
                if (title.isBlank()) continue
                add(
                    MetadataCandidate(
                        title = title,
                        artist = artist,
                        album = item.optString("collectionName").takeIf { it.isNotBlank() },
                        durationMs = item.optLong("trackTimeMillis", -1L).takeIf { it > 0L },
                        artworkUrl = item.optString("artworkUrl100")
                            .takeIf { it.startsWith("http://") || it.startsWith("https://") }
                            ?.replace("100x100bb", "600x600bb"),
                        provider = "iTunes",
                        providerId = item.optLong("trackId", -1L).takeIf { it > 0L }?.toString(),
                        // Search results are ranked by the storefront; StageGrid still applies its
                        // own title/artist/duration confidence score before accepting a result.
                        providerScore = 82,
                    ),
                )
            }
        }
    }

    private companion object {
        val COUNTRIES = listOf("MX", "US")
        const val LIMIT = 24
        const val MAX_RETURNED_CANDIDATES = 48
        const val TITLE_RELEVANCE_FLOOR = 0.72f
        const val CONNECT_TIMEOUT_MS = 3_500
        const val READ_TIMEOUT_MS = 5_000
        const val USER_AGENT = "StageGrid/0.7 (https://github.com/springi790/StageGrid)"
    }
}
