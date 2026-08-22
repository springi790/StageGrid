package dev.stagegrid.metadata

import android.content.Context
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.stagegrid.data.LibraryRepository
import dev.stagegrid.debug.StageGridDebugLog
import dev.stagegrid.model.SongEntity
import dev.stagegrid.settings.AppSettingsRepository
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * One-way artwork migration for songs that already existed before automatic metadata artwork.
 *
 * This component is deliberately incapable of changing musical metadata. The only database field
 * it writes is [SongEntity.artworkPath], and it re-reads the latest song immediately before that
 * write so concurrent edits to BPM/key/title/etc. are preserved.
 */
class ArtworkBackfillManager(
    private val context: Context,
    private val repository: LibraryRepository,
    private val settings: AppSettingsRepository,
    private val provider: MetadataProvider = PriorityMetadataProvider(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateFile = File(context.filesDir, "metadata/artwork-backfill-v1.json")
    private val processStartEpochMs = System.currentTimeMillis()

    fun start() {
        scope.launch {
            // Keep startup/UI/audio initialization ahead of optional catalog network traffic.
            delay(START_DELAY_MS)
            repeat(NETWORK_RETRY_COUNT) { attempt ->
                if (hasActiveInternetRoute()) {
                    runCatching { runPass() }
                        .onFailure {
                            StageGridDebugLog.state(
                                "METADATA",
                                "ARTWORK_BACKFILL_FAILED error=${it.javaClass.simpleName}",
                            )
                        }
                    return@launch
                }
                if (attempt < NETWORK_RETRY_COUNT - 1) delay(NETWORK_RETRY_DELAY_MS)
            }
            StageGridDebugLog.state("METADATA", "ARTWORK_BACKFILL_SKIP reason=offline")
        }
    }

    private suspend fun runPass() {
        val preferences = settings.settings.first()
        if (!preferences.metadataOnlineLookupEnabled || !preferences.metadataArtworkEnabled) {
            StageGridDebugLog.state("METADATA", "ARTWORK_BACKFILL_SKIP reason=disabled")
            return
        }

        // Snapshot only songs that existed before this process started. New imports already run
        // through MetadataAwareSongImporter and therefore do not need this migration.
        val candidates = repository.songs.first().filter { song ->
            val artworkPath = song.artworkPath
            song.importedAtEpochMs < processStartEpochMs &&
                (artworkPath.isNullOrBlank() || !File(artworkPath).isFile)
        }
        if (candidates.isEmpty()) return

        val attempts = readAttempts().toMutableMap()
        val now = System.currentTimeMillis()
        StageGridDebugLog.state("METADATA", "ARTWORK_BACKFILL_START songs=${candidates.size}")

        var installed = 0
        for (song in candidates) {
            val lastAttempt = attempts[song.id] ?: 0L
            if (now - lastAttempt < RETRY_COOLDOWN_MS) continue

            val result = runCatching { findAndInstallArtwork(song) }
            result.onFailure {
                // Transport/provider failures are not persisted as an attempt, so a later app
                // launch can retry instead of waiting through the cooldown.
                StageGridDebugLog.state(
                    "METADATA",
                    "ARTWORK_BACKFILL_ITEM_FAILED song=${song.id} error=${it.javaClass.simpleName}",
                )
            }
            if (result.isSuccess) {
                attempts[song.id] = System.currentTimeMillis()
                writeAttempts(attempts)
                if (result.getOrDefault(false)) installed++
            }

            // Serial throttling keeps catalog traffic and storage work unobtrusive during playback.
            delay(BETWEEN_SONGS_MS)
        }

        StageGridDebugLog.state(
            "METADATA",
            "ARTWORK_BACKFILL_COMPLETE candidates=${candidates.size} installed=$installed",
        )
    }

    private suspend fun findAndInstallArtwork(song: SongEntity): Boolean {
        val title = MetadataText.cleanTitleHint(song.title)
        val artist = MetadataText.cleanArtistHint(song.artist)
        if (title.isBlank()) return false

        StageGridDebugLog.state(
            "METADATA",
            "ARTWORK_BACKFILL_QUERY song=${song.id} title=$title artist=$artist",
        )

        val query = MetadataQuery(title = title, artist = artist, durationMs = song.durationMs)
        val ranked = provider.search(query)
            .asSequence()
            .filter { !it.artworkUrl.isNullOrBlank() }
            .map { candidate -> candidate to score(query, candidate) }
            .sortedByDescending { it.second }
            .toList()

        val selected = ranked.firstOrNull { it.second >= MATCH_THRESHOLD } ?: run {
            ranked.firstOrNull()?.let { best ->
                StageGridDebugLog.state(
                    "METADATA",
                    "ARTWORK_BACKFILL_REJECT song=${song.id} confidence=${(best.second * 100).toInt()} title=${best.first.title} artist=${best.first.artist}",
                )
            }
            return false
        }

        val destination = downloadArtwork(
            source = selected.first.artworkUrl ?: return false,
            songId = song.id,
        ) ?: return false

        // Critical invariant: re-read current data and alter artworkPath only. Do not reconstruct the
        // SongEntity from the old startup snapshot, which could overwrite later user edits.
        val current = repository.getSong(song.id) ?: run {
            destination.delete()
            return false
        }
        val currentArtwork = current.artworkPath?.let { File(it) }
        if (currentArtwork != null && currentArtwork.isFile) {
            destination.delete()
            return false
        }
        repository.updateSong(current.copy(artworkPath = destination.absolutePath))

        StageGridDebugLog.state(
            "METADATA",
            "ARTWORK_BACKFILL_APPLIED song=${song.id} provider=${selected.first.provider} confidence=${(selected.second * 100).toInt()}",
        )
        return true
    }

    private fun score(query: MetadataQuery, candidate: MetadataCandidate): Float {
        val title = MetadataText.tokenSimilarity(query.title, candidate.title)
        val artist = if (query.artist.isBlank()) {
            0.72f
        } else {
            MetadataText.tokenContainmentSimilarity(query.artist, candidate.artist)
        }
        val duration = durationScore(query.durationMs, candidate.durationMs)
        val providerScore = ((candidate.providerScore ?: 70).coerceIn(0, 100) / 100f)
        return (title * 0.58f + artist * 0.24f + duration * 0.10f + providerScore * 0.08f)
            .coerceIn(0f, 1f)
    }

    private fun durationScore(expected: Long?, candidate: Long?): Float {
        if (expected == null || candidate == null || expected <= 0L || candidate <= 0L) return 0.60f
        val delta = kotlin.math.abs(expected - candidate)
        return when {
            delta <= 3_000L -> 1f
            delta <= 7_000L -> 0.80f
            delta <= 15_000L -> 0.45f
            else -> 0.10f
        }
    }

    private fun downloadArtwork(source: String, songId: String): File? = runCatching {
        val connection = (URL(source).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "image/*")
            setRequestProperty("User-Agent", USER_AGENT)
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode !in 200..299) return@runCatching null
            val contentType = connection.contentType.orEmpty().lowercase()
            if (!contentType.startsWith("image/")) return@runCatching null

            val extension = when {
                "png" in contentType -> "png"
                "webp" in contentType -> "webp"
                else -> "jpg"
            }
            val root = File(context.filesDir, "library/$songId").apply { mkdirs() }
            val temporary = File(root, ".artwork-backfill-$extension.tmp")
            temporary.delete()
            try {
                var total = 0L
                BufferedInputStream(connection.inputStream).use { input ->
                    BufferedOutputStream(FileOutputStream(temporary)).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            total += read
                            require(total <= MAX_ARTWORK_BYTES) { "Artwork exceeds size limit" }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (temporary.length() <= 0L || BitmapFactory.decodeFile(temporary.absolutePath) == null) {
                    temporary.delete()
                    return@runCatching null
                }

                val destination = File(root, "artwork.$extension")
                if (destination.exists()) destination.delete()
                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }
                destination
            } catch (t: Throwable) {
                temporary.delete()
                throw t
            }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun hasActiveInternetRoute(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return true
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun readAttempts(): Map<String, Long> = runCatching {
        if (!stateFile.isFile) return@runCatching emptyMap()
        val root = JSONObject(stateFile.readText())
        val attempts = root.optJSONObject("attempts") ?: return@runCatching emptyMap()
        buildMap {
            val keys = attempts.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                attempts.optLong(id, 0L).takeIf { it > 0L }?.let { put(id, it) }
            }
        }
    }.getOrDefault(emptyMap())

    private fun writeAttempts(attempts: Map<String, Long>) {
        runCatching {
            stateFile.parentFile?.mkdirs()
            val values = JSONObject()
            attempts.forEach { (id, timestamp) -> values.put(id, timestamp) }
            stateFile.writeText(
                JSONObject()
                    .put("version", 1)
                    .put("attempts", values)
                    .toString(),
            )
        }
    }

    private companion object {
        const val START_DELAY_MS = 2_500L
        const val NETWORK_RETRY_DELAY_MS = 60_000L
        const val NETWORK_RETRY_COUNT = 10
        const val BETWEEN_SONGS_MS = 3_250L
        const val RETRY_COOLDOWN_MS = 24L * 60L * 60L * 1_000L
        const val MATCH_THRESHOLD = 0.84f
        const val CONNECT_TIMEOUT_MS = 3_500
        const val READ_TIMEOUT_MS = 6_000
        const val MAX_ARTWORK_BYTES = 8L * 1024L * 1024L
        const val USER_AGENT = "StageGrid/0.7 (https://github.com/springi790/StageGrid)"
    }
}
