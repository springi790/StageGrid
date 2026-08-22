package dev.stagegrid.metadata

import android.content.Context
import android.net.Uri
import dev.stagegrid.data.LibraryRepository
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
        onProgress?.invoke(
            ImportProgress(
                percent = 96,
                stage = ImportStage.SAVING_LIBRARY,
                detail = "Buscando metadatos y analizando audio…",
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
                    onlineEnabled = appSettings.metadataOnlineLookupEnabled,
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
