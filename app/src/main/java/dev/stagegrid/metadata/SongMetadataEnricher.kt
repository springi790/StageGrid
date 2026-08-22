package dev.stagegrid.metadata

import dev.stagegrid.debug.StageGridDebugLog
import dev.stagegrid.model.TrackEntity
import dev.stagegrid.model.TrackType
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class SongMetadataEnricher(
    private val provider: MetadataProvider = PriorityMetadataProvider(),
) {
    data class Request(
        val songId: String,
        val seedTitle: String,
        val seedArtist: String,
        val durationMs: Long,
        val existingBpm: Double?,
        val existingKey: String?,
        val tracks: List<TrackEntity>,
        val songRoot: File,
        val titleLocked: Boolean,
        val artistLocked: Boolean,
        val bpmLocked: Boolean,
        val keyLocked: Boolean,
        val onlineEnabled: Boolean,
        val localAnalysisEnabled: Boolean,
        val downloadArtwork: Boolean,
    )

    suspend fun enrich(request: Request): MetadataEnrichment {
        val notes = mutableListOf<String>()
        val parsed = if (request.seedArtist.isBlank()) MetadataText.splitTitleArtist(request.seedTitle) else request.seedTitle to request.seedArtist
        val query = MetadataQuery(parsed.first, parsed.second, request.durationMs)

        var online: MetadataCandidate? = null
        var onlineConfidence = 0f
        if (request.onlineEnabled && query.title.isNotBlank()) {
            val candidates = runCatching { provider.search(query) }
                .onFailure {
                    StageGridDebugLog.state("METADATA", "ONLINE_FAILED song=${request.songId} error=${it.javaClass.simpleName}")
                    notes += "Online metadata lookup was unavailable; import continued offline."
                }
                .getOrNull()

            if (candidates != null && candidates.isEmpty()) {
                StageGridDebugLog.state("METADATA", "ONLINE_EMPTY song=${request.songId} title=${query.title} artist=${query.artist}")
                notes += "No online catalog match was found; local analysis was used where possible."
            }

            candidates
                ?.map { candidate -> candidate to score(query, candidate) }
                ?.maxByOrNull { it.second }
                ?.let { (candidate, confidence) ->
                    online = candidate
                    onlineConfidence = confidence
                    StageGridDebugLog.state(
                        "METADATA",
                        "MATCH song=${request.songId} provider=${candidate.provider} confidence=${(confidence * 100).toInt()} title=${candidate.title} artist=${candidate.artist}",
                    )
                    if (confidence >= AUTO_APPLY_CONFIDENCE) {
                        notes += "Matched ${candidate.provider} at ${(confidence * 100).toInt()}% confidence."
                    } else {
                        notes += "A possible online match was found (${(confidence * 100).toInt()}%), but it was not auto-applied."
                    }
                }
        }

        val local = if (request.localAnalysisEnabled) analyzeLocal(request.tracks, request.existingBpm, request.existingKey) else LocalMusicAnalysis()
        local.bpm?.let { notes += "Local audio analysis detected ${formatBpm(it)} BPM (${(local.bpmConfidence * 100).toInt()}%)." }
        local.musicalKey?.let { notes += "Local audio analysis detected key $it (${(local.keyConfidence * 100).toInt()}%)." }

        val applyOnline = online != null && onlineConfidence >= AUTO_APPLY_CONFIDENCE
        val finalTitle = when {
            request.titleLocked -> request.seedTitle
            applyOnline -> online!!.title
            else -> parsed.first.ifBlank { request.seedTitle }
        }
        val finalArtist = when {
            request.artistLocked -> request.seedArtist
            applyOnline && online!!.artist.isNotBlank() -> online!!.artist
            else -> parsed.second.ifBlank { request.seedArtist }
        }
        val finalBpm = request.existingBpm ?: local.bpm
        val finalKey = request.existingKey ?: local.musicalKey

        val artworkPath = if (request.downloadArtwork && applyOnline) {
            online?.artworkUrl?.let { url ->
                downloadArtwork(url, request.songRoot).also { saved ->
                    if (saved == null) {
                        StageGridDebugLog.state("METADATA", "ARTWORK_MISSING song=${request.songId} provider=${online?.provider}")
                        notes += "The song matched online, but that catalog entry had no downloadable cover art."
                    }
                }
            }
        } else null

        val result = MetadataEnrichment(
            title = finalTitle.ifBlank { request.seedTitle },
            artist = finalArtist,
            bpm = finalBpm,
            musicalKey = finalKey,
            artworkPath = artworkPath,
            onlineCandidate = online,
            onlineConfidence = onlineConfidence,
            localAnalysis = local,
            notes = notes,
        )
        writeSidecar(request.songRoot, result)
        return result
    }

    private fun analyzeLocal(tracks: List<TrackEntity>, existingBpm: Double?, existingKey: String?): LocalMusicAnalysis {
        val clickTrack = tracks.firstOrNull { it.type == TrackType.CLICK.name }
        val musicalTempoTrack = tracks.firstOrNull { it.type == TrackType.DRUMS.name || it.type == TrackType.PERCUSSION.name }
            ?: tracks.firstOrNull { it.type == TrackType.OTHER.name }
        val keyTrack = tracks.firstOrNull { it.type == TrackType.KEYS.name }
            ?: tracks.firstOrNull { it.type == TrackType.GUITAR.name }
            ?: tracks.firstOrNull { it.type == TrackType.PAD.name }
            ?: tracks.firstOrNull { it.type == TrackType.OTHER.name }
            ?: tracks.firstOrNull { it.type == TrackType.VOCALS.name }
            ?: tracks.firstOrNull { it.type == TrackType.BASS.name }

        var bpm: Double? = null
        var bpmConfidence = 0f
        var tempoSource = "NONE"
        if (existingBpm == null) {
            val clickTempo = clickTrack?.let { ClickTempoAnalyzer.analyze(File(it.filePath)) }
            if (clickTempo != null) {
                bpm = clickTempo.bpm
                bpmConfidence = clickTempo.confidence
                tempoSource = "CLICK_PULSE"
                StageGridDebugLog.state(
                    "METADATA",
                    "CLICK_TEMPO bpm=${clickTempo.bpm} confidence=${clickTempo.confidence} pulses=${clickTempo.pulseCount} track=${clickTrack.name}",
                )
            } else {
                val generic = musicalTempoTrack?.let { LocalMusicAnalyzer.analyzeTempo(File(it.filePath)) }
                bpm = generic?.bpm
                bpmConfidence = generic?.confidence ?: 0f
                tempoSource = if (generic != null) "MUSIC_AUTOCORR" else "NONE"
            }
        }

        val key = if (existingKey.isNullOrBlank()) keyTrack?.let { LocalMusicAnalyzer.analyzeKey(File(it.filePath)) } else null
        StageGridDebugLog.state(
            "METADATA",
            "LOCAL bpm=$bpm bpmConfidence=$bpmConfidence tempoSource=$tempoSource key=${key?.key} keyConfidence=${key?.confidence}",
        )
        return LocalMusicAnalysis(
            bpm = bpm,
            bpmConfidence = bpmConfidence,
            musicalKey = key?.key,
            keyConfidence = key?.confidence ?: 0f,
        )
    }

    private fun score(query: MetadataQuery, candidate: MetadataCandidate): Float {
        val directTitle = MetadataText.tokenSimilarity(query.title, candidate.title)
        val directArtist = if (query.artist.isBlank()) 0.72f else MetadataText.tokenSimilarity(query.artist, candidate.artist)
        val reverseTitle = if (query.artist.isBlank()) 0f else MetadataText.tokenSimilarity(query.artist, candidate.title)
        val reverseArtist = if (query.artist.isBlank()) 0f else MetadataText.tokenSimilarity(query.title, candidate.artist)
        val direct = directTitle * 0.54f + directArtist * 0.26f
        val reverse = reverseTitle * 0.54f + reverseArtist * 0.26f
        val text = maxOf(direct, reverse)
        val duration = durationScore(query.durationMs, candidate.durationMs) * 0.12f
        val provider = ((candidate.providerScore ?: 70).coerceIn(0, 100) / 100f) * 0.08f
        return (text + duration + provider).coerceIn(0f, 1f)
    }

    private fun durationScore(expected: Long?, candidate: Long?): Float {
        if (expected == null || candidate == null || expected <= 0 || candidate <= 0) return 0.60f
        val delta = kotlin.math.abs(expected - candidate)
        return when {
            delta <= 2_500L -> 1f
            delta <= 5_000L -> 0.85f
            delta <= 10_000L -> 0.55f
            delta <= 20_000L -> 0.25f
            else -> 0f
        }
    }

    private fun downloadArtwork(source: String, songRoot: File): String? = runCatching {
        val connection = (URL(source).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4_000
            readTimeout = 7_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "StageGrid/0.7 (https://github.com/springi790/StageGrid)")
            instanceFollowRedirects = true
        }
        try {
            val response = connection.responseCode
            if (response !in 200..299) {
                StageGridDebugLog.state("METADATA", "ARTWORK_HTTP code=$response source=$source")
                return@runCatching null
            }
            val contentType = connection.contentType.orEmpty().lowercase()
            if (!contentType.startsWith("image/")) return@runCatching null
            val extension = when {
                "png" in contentType -> "png"
                "webp" in contentType -> "webp"
                else -> "jpg"
            }
            val file = File(songRoot, "artwork.$extension")
            BufferedInputStream(connection.inputStream).use { input ->
                BufferedOutputStream(FileOutputStream(file)).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        require(total <= MAX_ARTWORK_BYTES) { "Artwork exceeds size limit" }
                        output.write(buffer, 0, read)
                    }
                }
            }
            StageGridDebugLog.io("METADATA", "ARTWORK saved=${file.name} bytes=${file.length()}")
            file.absolutePath
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun writeSidecar(songRoot: File, result: MetadataEnrichment) {
        runCatching {
            val json = JSONObject()
                .put("version", 3)
                .put("provider", result.onlineCandidate?.provider)
                .put("providerId", result.onlineCandidate?.providerId)
                .put("confidence", result.onlineConfidence.toDouble())
                .put("title", result.title)
                .put("artist", result.artist)
                .put("bpm", result.bpm)
                .put("key", result.musicalKey)
                .put("artworkPath", result.artworkPath)
                .put("localBpmConfidence", result.localAnalysis.bpmConfidence.toDouble())
                .put("localKeyConfidence", result.localAnalysis.keyConfidence.toDouble())
                .put("timestampMs", System.currentTimeMillis())
            File(songRoot, "metadata-enrichment.json").writeText(json.toString(2))
        }
    }

    private fun formatBpm(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, value)

    private companion object {
        const val AUTO_APPLY_CONFIDENCE = 0.82f
        const val MAX_ARTWORK_BYTES = 8L * 1024L * 1024L
    }
}
