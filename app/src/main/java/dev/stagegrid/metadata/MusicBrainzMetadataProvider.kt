package dev.stagegrid.metadata

import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import org.json.JSONObject

/**
 * Metadata-only provider. It deliberately does not scrape TuneBat/SongBPM/Secuencias HTML.
 * MusicBrainz exposes a documented JSON web service and requires a meaningful User-Agent.
 */
class MusicBrainzMetadataProvider : MetadataProvider {
    override suspend fun search(query: MetadataQuery): List<MetadataCandidate> {
        val lucene = buildQuery(query)
        if (lucene.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(lucene, Charsets.UTF_8.name())
        val url = URL("https://musicbrainz.org/ws/2/recording/?query=$encoded&fmt=json&limit=8")
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
            if (code !in 200..299) return emptyList()
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            parse(JSONObject(text))
        } finally {
            connection.disconnect()
        }
    }

    private fun buildQuery(query: MetadataQuery): String {
        val title = escape(query.title.trim())
        val artist = escape(query.artist.trim())
        return when {
            title.isNotBlank() && artist.isNotBlank() -> "recording:\"$title\" AND artist:\"$artist\""
            title.isNotBlank() -> "recording:\"$title\""
            else -> ""
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
                val release = releases?.optJSONObject(0)
                val releaseId = release?.optString("id")?.takeIf { it.isNotBlank() }
                add(
                    MetadataCandidate(
                        title = title,
                        artist = artist,
                        album = release?.optString("title")?.takeIf { it.isNotBlank() },
                        durationMs = recording.optLong("length", -1L).takeIf { it > 0L },
                        artworkUrl = releaseId?.let { "https://coverartarchive.org/release/$it/front-500" },
                        provider = "MusicBrainz",
                        providerId = recording.optString("id").takeIf { it.isNotBlank() },
                        providerScore = recording.optInt("score", -1).takeIf { it >= 0 },
                    ),
                )
            }
        }
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

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {
        const val CONNECT_TIMEOUT_MS = 4_000
        const val READ_TIMEOUT_MS = 6_000
        const val USER_AGENT = "StageGrid/0.7 (https://github.com/springi790/StageGrid)"
    }
}
