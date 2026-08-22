package dev.stagegrid.metadata

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import dev.stagegrid.data.LibraryRepository
import dev.stagegrid.debug.StageGridDebugLog
import dev.stagegrid.importer.ImportProgress
import dev.stagegrid.importer.ImportStage
import dev.stagegrid.importer.SongImporter
import dev.stagegrid.settings.AppSettingsRepository
import java.io.File
import kotlinx.coroutines.flow.first

/**
 * Keeps the proven stem/Guide import pipeline untouched, then enriches the already-persisted song.
 * Network/catalog failures are non-fatal and never roll back a successful audio import.
 */
class MetadataAwareSongImporter(
    private val context: Context,
    private val repository: LibraryRepository,
    private val settings: AppSettingsRepository,
    private val delegate: SongImporter,
    private val enricher: SongMetadataEnricher,
) {
    suspend fun importZip(
        uri: Uri,
        onProgress: ((ImportProgress) -> Unit)? = null,
    ): SongImporter.ImportResult = enrich(
        delegate.importZip(uri, bridgeProgress(onProgress)),
        onProgress,
    )

    suspend fun importFolder(
        treeUri: Uri,
        onProgress: ((ImportProgress) -> Unit)? = null,
    ): SongImporter.ImportResult = enrich(
        delegate.importFolder(treeUri, bridgeProgress(onProgress)),
        onProgress,
    )

    suspend fun importFiles(
        uris: List<Uri>,
        onProgress: ((ImportProgress) -> Unit)? = null,
    ): SongImporter.ImportResult = enrich(
        delegate.importFiles(uris, bridgeProgress(onProgress)),
        onProgress,
    )

    private suspend fun enrich(
        imported: SongImporter.ImportResult,
        onProgress: ((ImportProgress) -> Unit)?,
    ): SongImporter.ImportResult {
        val bundle = repository.getSongBundle(imported.songId) ?: return imported.also {
            onProgress?.invoke(ImportProgress(100, ImportStage.COMPLETE))
        }
        val appSettings = settings.settings.first()
        val network = networkState()
        // NET_CAPABILITY_VALIDATED is intentionally diagnostic only. Some VPN/private-DNS/captive
        // configurations can reach HTTPS while Android has not marked the network validated yet.
        val onlineAvailable = appSettings.metadataOnlineLookupEnabled && network.active
        StageGridDebugLog.state(
            "METADATA",
            "NETWORK active=${network.active} internet=${network.internet} validated=${network.validated} onlineAttempt=$onlineAvailable",
        )
        onProgress?.invoke(
            ImportProgress(
                percent = 96,
                stage = ImportStage.SAVING_LIBRARY,
                detail = if (onlineAvailable) "Buscando metadatos y analizando audio…" else "Analizando metadatos locales…",
            ),
        )

        val enrichment = runCatching {
            enricher.enrich(
                SongMetadataEnricher.Request(
                    songId = imported.songId,
                    seedTitle = bundle.song.title,
                    seedArtist = bundle.song.artist,
                    durationMs = bundle.song.durationMs,
                    existingBpm = bundle.song.bpm,
                    existingKey = bundle.song.musicalKey,
                    tracks = bundle.tracks,
                    songRoot = File(context.filesDir, "library/${imported.songId}"),
                    // A high-confidence catalog match may clean a ZIP/folder name. Existing BPM/key
                    // remain authoritative because the enricher never overwrites non-null values.
                    titleLocked = false,
                    artistLocked = false,
                    bpmLocked = bundle.song.bpm != null,
                    keyLocked = !bundle.song.musicalKey.isNullOrBlank(),
                    onlineEnabled = onlineAvailable,
                    localAnalysisEnabled = appSettings.metadataLocalAnalysisEnabled,
                    downloadArtwork = appSettings.metadataArtworkEnabled,
                ),
            )
        }.getOrElse {
            onProgress?.invoke(ImportProgress(100, ImportStage.COMPLETE))
            return imported.copy(
                warnings = imported.warnings + "Automatic metadata enrichment could not finish; you can edit the song manually.",
            )
        }

        val updated = bundle.song.copy(
            title = enrichment.title,
            artist = enrichment.artist,
            bpm = enrichment.bpm,
            musicalKey = enrichment.musicalKey,
            artworkPath = enrichment.artworkPath ?: bundle.song.artworkPath,
        )
        repository.updateSong(updated)
        onProgress?.invoke(ImportProgress(100, ImportStage.COMPLETE))
        return imported.copy(
            title = updated.title,
            artist = updated.artist,
            bpm = updated.bpm,
            key = updated.musicalKey,
            warnings = imported.warnings + enrichment.notes,
        )
    }

    private data class NetworkState(
        val active: Boolean,
        val internet: Boolean,
        val validated: Boolean,
    )

    private fun networkState(): NetworkState {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkState(false, false, false)
        val network = manager.activeNetwork ?: return NetworkState(false, false, false)
        val capabilities = manager.getNetworkCapabilities(network)
        return NetworkState(
            active = true,
            internet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        )
    }

    private fun bridgeProgress(
        downstream: ((ImportProgress) -> Unit)?,
    ): ((ImportProgress) -> Unit)? {
        if (downstream == null) return null
        return { progress ->
            // Reserve the last 5% for optional metadata work so the UI never appears frozen at 100%.
            if (progress.stage == ImportStage.COMPLETE) {
                downstream(ImportProgress(95, ImportStage.SAVING_LIBRARY, "Preparando metadatos…"))
            } else {
                downstream(progress.copy(percent = progress.percent.coerceAtMost(95)))
            }
        }
    }
}
