package dev.stagegrid.metadata

import dev.stagegrid.debug.StageGridDebugLog
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Metadata-only provider backed by the documented MusicBrainz JSON search service.
 *
 * Search is deliberately progressive: artistname handles featured/joined artist credits better
 * than an exact combined credit; if that yields nothing, a title-only fallback lets StageGrid's
 * own confidence matcher decide whether any candidate is safe to apply.
 */
class MusicBrainzMetadataProvider : MetadataProvider {
    override suspend fun search(query: MetadataQuery): List<MetadataCandidate> {
        val title = query.title.trim()
        if (title.isBlank()) return emptyList()
        val artist = query.artist.trim()

        if (artist.isNotBlank()) {
            val exact = request(
                lucene = "recording:\"${escape(title)}\" AND artistname:\"${escape(artist)}\"",
                mode = "TITLE_ARTISTNAME",
            )
            if (exact.isNotEmpty()) return exact
            // MusicBrainz asks clients to stay around one request per second. Only retry when the
            // stricter search produced no candidates.
            delay(FALLBACK_DELAY_MS)
        }

        return request(
            lucene = "recording:\"${escape(title)}\"",
            mode = "TITLE_ONLY",
        )
    }

    private fun request(lucene: String, mode: String): List<MetadataCandidate> {
        val encoded = URLEncoder.encode(lucene, Charsets.UTF_8.name())
        val url = URL("https://musicbrainz.org/ws/2/recording/?query=$encoded&fmt=json&limit=12")
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
            StageGridDebugLog.state("METADATA", "MUSICBRAINZ_HTTP mode=$mode code=$code")
            if (code !in 200..299) return emptyList()
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val parsed = parse(JSONObject(text))
            StageGridDebugLog.state("METADATA", "MUSICBRAINZ_RESULTS mode=$mode count=${parsed.size}")
            parsed
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(root: JSONObject): List<MetadataCandidate> {
        val recordings = root.optJSONArray("recordings") ?: return emptyList()
        return buildList {
            for (index in 0 until recordings.length()) {
                val recording = recordings.optJSONObject(index) ?: continue
                val title = recording.optString("title").trim()
                if (title.isBlank()) continue
                val artist = artistCredit(recording)
                val releases = recording.optJSONArray("releases")
                val release = chooseRelease(releases)
                val releaseId = release?.optString("id")?.takeIf { it.isNotBlank() }
                val releaseGroupId = release
                    ?.optJSONObject("release-group")
                    ?.optString("id")
                    ?.takeIf { it.isNotBlank() }
                val artworkUrl = when {
                    releaseGroupId != null -> "https://coverartarchive.org/release-group/$releaseGroupId/front-500"
                    releaseId != null -> "https://coverartarchive.org/release/$releaseId/front-500"
                    else -> null
                }
                add(
                    MetadataCandidate(
                        title = title,
                        artist = artist,
                        album = release?.optString("title")?.takeIf { it.isNotBlank() },
                        durationMs = recording.optLong("length", -1L).takeIf { it > 0L },
                        artworkUrl = artworkUrl,
                        provider = "MusicBrainz",
                        providerId = recording.optString("id").takeIf { it.isNotBlank() },
                        providerScore = recording.optInt("score", -1).takeIf { it >= 0 },
                    ),
                )
            }
        }
    }

    private fun chooseRelease(releases: org.json.JSONArray?): JSONObject? {
        if (releases == null || releases.length() == 0) return null
        var first: JSONObject? = null
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            if (first == null) first = release
            if (release.optString("status").equals("Official", ignoreCase = true)) return release
        }
        return first
    }

    private fun artistCredit(recording: JSONObject): String {
        val credits = recording.optJSONArray("artist-credit") ?: return ""
        val out = StringBuilder()
        for (i in 0 until credits.length()) {
            val item = credits.optJSONObject(i) ?: continue
            val name = item.optString("name").ifBlank {
                item.optJSONObject("artist")?.optString("name").orEmpty()
            }
            if (name.isNotBlank()) out.append(name)
            val join = item.optString("joinphrase")
            if (join.isNotEmpty()) out.append(join)
        }
        return out.toString().trim()
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("/", "\\/")
        .replace(":", "\\:")

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3_500
        const val READ_TIMEOUT_MS = 5_000
        const val FALLBACK_DELAY_MS = 1_050L
        const val USER_AGENT = "StageGrid/0.7 (https://github.com/springi790/StageGrid)"
    }
}
