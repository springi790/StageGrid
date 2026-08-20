package dev.stagegrid.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.stagegrid.StageGridApplication
import dev.stagegrid.audio.AudioDeviceManager
import dev.stagegrid.audio.ClickSubdivision
import dev.stagegrid.audio.NativeAudioEngine
import dev.stagegrid.audio.PlayerState
import dev.stagegrid.backup.BackupProgress
import dev.stagegrid.backup.BackupResult
import dev.stagegrid.backup.BackupStage
import dev.stagegrid.backup.RestoreResult
import dev.stagegrid.guide.GuideCueAnalyzer
import dev.stagegrid.guide.GuidePackManager
import dev.stagegrid.guide.NativeGuideEventStore
import dev.stagegrid.guide.NativeGuideRenderer
import dev.stagegrid.importer.ImportProgress
import dev.stagegrid.importer.ImportStage
import dev.stagegrid.importer.SongImporter
import dev.stagegrid.importer.WavMetadataReader
import dev.stagegrid.model.SectionEntity
import dev.stagegrid.model.SetlistBundle
import dev.stagegrid.model.SetlistEntity
import dev.stagegrid.model.SongEntity
import dev.stagegrid.model.StereoRoute
import dev.stagegrid.settings.AppSettingsRepository
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StageGridViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as StageGridApplication

    val songs: StateFlow<List<SongEntity>> = app.repository.songs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val setlists: StateFlow<List<SetlistEntity>> = app.repository.setlists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val player: StateFlow<PlayerState> = app.audio.state
    val outputs: StateFlow<List<AudioDeviceManager.OutputDevice>> = app.audioDevices.outputs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings: StateFlow<AppSettingsRepository.Settings> = app.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettingsRepository.Settings())

    data class ImportUiState(
        val running: Boolean = false,
        val progress: ImportProgress = ImportProgress(0, ImportStage.PREPARING),
        val result: SongImporter.ImportResult? = null,
        val error: String? = null,
    )

    data class GuidePackUiState(
        val installing: Boolean = false,
        val status: GuidePackManager.Status = GuidePackManager.Status(false, 0, emptyList()),
        val error: String? = null,
    )

    data class NativeGuideUiState(
        val songId: String? = null,
        val available: Boolean = false,
        val currentLanguage: String? = null,
        val detectedLanguage: String? = null,
        val languages: List<String> = emptyList(),
        val eventCount: Int = 0,
        val rendering: Boolean = false,
        val renderPercent: Int = 0,
        val error: String? = null,
    )

    enum class BackupOperation { CREATE, RESTORE }

    data class BackupUiState(
        val running: Boolean = false,
        val operation: BackupOperation? = null,
        val progress: BackupProgress = BackupProgress(0, BackupStage.PREPARING),
        val backupResult: BackupResult? = null,
        val restoreResult: RestoreResult? = null,
        val error: String? = null,
    )

    data class LibraryActionUiState(
        val deletingSongId: String? = null,
        val error: String? = null,
    )

    data class SetlistLiveUiState(
        val active: Boolean = false,
        val setlistId: String? = null,
        val setlistName: String = "",
        val currentIndex: Int = -1,
        val totalSongs: Int = 0,
        val currentSongTitle: String? = null,
        val previousSongTitle: String? = null,
        val nextSongTitle: String? = null,
        val preloadingNext: Boolean = false,
        val nextReady: Boolean = false,
        val error: String? = null,
    ) {
        val hasPrevious: Boolean get() = active && currentIndex > 0
        val hasNext: Boolean get() = active && currentIndex >= 0 && currentIndex + 1 < totalSongs
    }

    private val _importState = MutableStateFlow(ImportUiState())
    val importState: StateFlow<ImportUiState> = _importState.asStateFlow()

    private val _guidePackState = MutableStateFlow(GuidePackUiState(status = app.guidePacks.status()))
    val guidePackState: StateFlow<GuidePackUiState> = _guidePackState.asStateFlow()

    private val _nativeGuideState = MutableStateFlow(NativeGuideUiState())
    val nativeGuideState: StateFlow<NativeGuideUiState> = _nativeGuideState.asStateFlow()

    private val _backupState = MutableStateFlow(BackupUiState())
    val backupState: StateFlow<BackupUiState> = _backupState.asStateFlow()

    private val _libraryActionState = MutableStateFlow(LibraryActionUiState())
    val libraryActionState: StateFlow<LibraryActionUiState> = _libraryActionState.asStateFlow()

    private val _selectedSetlist = MutableStateFlow<SetlistBundle?>(null)
    val selectedSetlist: StateFlow<SetlistBundle?> = _selectedSetlist.asStateFlow()

    private val _setlistLiveState = MutableStateFlow(SetlistLiveUiState())
    val setlistLiveState: StateFlow<SetlistLiveUiState> = _setlistLiveState.asStateFlow()
    private val setlistPreloadSerial = AtomicLong(0L)

    init {
        viewModelScope.launch {
            app.settings.settings.collectLatest { preferences ->
                app.audio.setClickSubdivision(preferences.clickSubdivision)
                app.audio.setClickRoute(preferences.clickRoute)
                app.audio.setCountInBars(preferences.countInBars)
            }
        }
    }

    fun importZip(uri: Uri) = runImport { progress -> app.importer.importZip(uri, progress) }
    fun importFolder(uri: Uri) = runImport { progress -> app.importer.importFolder(uri, progress) }
    fun importFiles(uris: List<Uri>) = runImport { progress -> app.importer.importFiles(uris, progress) }

    private fun runImport(block: suspend ((ImportProgress) -> Unit) -> SongImporter.ImportResult) {
        viewModelScope.launch {
            _importState.value = ImportUiState(running = true)
            try {
                val result = withContext(Dispatchers.IO) {
                    block { progress ->
                        _importState.value = _importState.value.copy(
                            running = true,
                            progress = progress,
                            result = null,
                            error = null,
                        )
                    }
                }
                _importState.value = ImportUiState(
                    running = false,
                    progress = ImportProgress(100, ImportStage.COMPLETE),
                    result = result,
                )
            } catch (t: Throwable) {
                _importState.value = ImportUiState(error = t.message ?: "Import failed")
            }
        }
    }

    fun createLibraryBackup(destinationTree: Uri) {
        if (!backupOperationAllowed()) return
        viewModelScope.launch {
            _backupState.value = BackupUiState(running = true, operation = BackupOperation.CREATE)
            try {
                val result = withContext(Dispatchers.IO) {
                    app.backupManager.createBackup(destinationTree) { progress ->
                        _backupState.value = _backupState.value.copy(
                            running = true,
                            operation = BackupOperation.CREATE,
                            progress = progress,
                            error = null,
                        )
                    }
                }
                _backupState.value = BackupUiState(
                    operation = BackupOperation.CREATE,
                    progress = BackupProgress(100, BackupStage.COMPLETE),
                    backupResult = result,
                )
            } catch (t: Throwable) {
                _backupState.value = BackupUiState(
                    operation = BackupOperation.CREATE,
                    error = t.message ?: "Backup failed",
                )
            }
        }
    }

    fun restoreLibraryBackup(backupUri: Uri) {
        if (!backupOperationAllowed()) return
        val previouslyLoadedSongId = player.value.song?.id
        viewModelScope.launch {
            exitSetlistLive()
            _backupState.value = BackupUiState(running = true, operation = BackupOperation.RESTORE)
            try {
                // Stop decoder threads before restored WAV files replace their app-private paths.
                withContext(Dispatchers.Default) { app.audio.unloadForLibraryRestore() }
                val result = withContext(Dispatchers.IO) {
                    app.backupManager.restoreBackup(backupUri) { progress ->
                        _backupState.value = _backupState.value.copy(
                            running = true,
                            operation = BackupOperation.RESTORE,
                            progress = progress,
                            error = null,
                        )
                    }
                }
                app.guidePacks.invalidateCache()
                withContext(Dispatchers.IO) {
                    runCatching { GuideCueAnalyzer.prepare(app.guidePacks.listSamples()) }
                }
                _guidePackState.value = GuidePackUiState(status = app.guidePacks.status())
                _selectedSetlist.value = null
                if (previouslyLoadedSongId != null && withContext(Dispatchers.IO) { app.repository.getSong(previouslyLoadedSongId) } != null) {
                    refreshNativeGuideState(previouslyLoadedSongId)
                    app.audio.loadSong(previouslyLoadedSongId)
                } else {
                    _nativeGuideState.value = NativeGuideUiState()
                }
                _backupState.value = BackupUiState(
                    operation = BackupOperation.RESTORE,
                    progress = BackupProgress(100, BackupStage.COMPLETE),
                    restoreResult = result,
                )
            } catch (t: Throwable) {
                // If validation fails before install, the library remains untouched. If a later
                // restore stage fails, LibraryBackupManager rolls back replaced song directories.
                if (previouslyLoadedSongId != null && withContext(Dispatchers.IO) { app.repository.getSong(previouslyLoadedSongId) } != null) {
                    app.audio.loadSong(previouslyLoadedSongId)
                }
                _backupState.value = BackupUiState(
                    operation = BackupOperation.RESTORE,
                    error = t.message ?: "Restore failed",
                )
            }
        }
    }

    private fun backupOperationAllowed(): Boolean {
        val p = player.value
        val busy = p.isPlaying || p.isCountingIn || importState.value.running || nativeGuideState.value.rendering || backupState.value.running
        if (!busy) return true
        _backupState.value = BackupUiState(error = "Stop playback and wait for current processing to finish before backup or restore.")
        return false
    }

    fun dismissBackupState() {
        if (!_backupState.value.running) _backupState.value = BackupUiState()
    }

    fun installGuidePack(uri: Uri) {
        viewModelScope.launch {
            _guidePackState.value = _guidePackState.value.copy(installing = true, error = null)
            try {
                val result = withContext(Dispatchers.IO) {
                    val installed = app.guidePacks.installZip(uri)
                    // Pay the one-time template preparation cost when the pack is installed rather
                    // than rebuilding hundreds of cue fingerprints during each song import.
                    GuideCueAnalyzer.prepare(app.guidePacks.listSamples())
                    installed
                }
                _guidePackState.value = GuidePackUiState(status = result.status)
                player.value.song?.id?.let { refreshNativeGuideState(it) }
            } catch (t: Throwable) {
                _guidePackState.value = _guidePackState.value.copy(
                    installing = false,
                    status = app.guidePacks.status(),
                    error = t.message ?: "Guide pack installation failed",
                )
            }
        }
    }

    fun refreshGuidePackStatus() {
        _guidePackState.value = _guidePackState.value.copy(status = app.guidePacks.status(), error = null)
    }

    fun dismissImportState() { _importState.value = ImportUiState() }

    fun loadSong(songId: String) {
        exitSetlistLive()
        loadSongInternal(songId, unloadCurrent = false)
    }

    private fun loadSongInternal(songId: String, unloadCurrent: Boolean) {
        viewModelScope.launch {
            if (unloadCurrent && player.value.song?.id != null && player.value.song?.id != songId) {
                withContext(Dispatchers.Default) { app.audio.unloadForLibraryRestore() }
            }
            withContext(Dispatchers.IO) {
                app.repository.getSong(songId)?.let { recoverNativeGuideSections(it) }
            }
            refreshNativeGuideState(songId)
            app.audio.loadSong(songId)
        }
    }

    fun playPause() = if (player.value.isPlaying) app.audio.pause() else app.audio.play()
    fun stop() = app.audio.stop()
    fun stopAll() = app.audio.stopAll()
    fun seekTo(ms: Long) = app.audio.seekTo(ms)
    fun toggleLoop() = app.audio.toggleCurrentSectionLoop()
    fun exitLoop() = app.audio.exitLoop()
    fun selectSection(section: SectionEntity) = app.audio.queueOrJumpSection(section)
    fun playCurrentSectionWithCountIn() = app.audio.playCurrentSectionWithCountIn()
    fun setMaster(value: Float) = app.audio.setMasterVolume(value)
    fun setClick(enabled: Boolean) = app.audio.setClickEnabled(enabled)
    fun setGuide(enabled: Boolean) = app.audio.setGuideEnabled(enabled)
    fun setClickSubdivision(subdivision: ClickSubdivision) {
        app.audio.setClickSubdivision(subdivision)
        viewModelScope.launch { app.settings.setClickSubdivision(subdivision) }
    }
    fun setClickRoute(route: StereoRoute) {
        app.audio.setClickRoute(route)
        viewModelScope.launch { app.settings.setClickRoute(route) }
    }
    fun setCountInBars(bars: Int) {
        val normalized = bars.coerceIn(0, 2)
        app.audio.setCountInBars(normalized)
        viewModelScope.launch { app.settings.setCountInBars(normalized) }
    }
    fun setNativeGuideLanguage(language: String) = viewModelScope.launch {
        app.settings.setNativeGuideLanguage(language)
    }

    /** Regenerates only the current song's native Guide from already-recognized events. */
    fun setSongNativeGuideLanguage(language: String) {
        val song = player.value.song ?: return
        if (player.value.isPlaying || player.value.isCountingIn || _nativeGuideState.value.rendering) return
        if (language !in _nativeGuideState.value.languages) return
        if (language == _nativeGuideState.value.currentLanguage) return

        viewModelScope.launch {
            _nativeGuideState.value = _nativeGuideState.value.copy(
                rendering = true,
                renderPercent = 0,
                error = null,
            )
            val error = withContext(Dispatchers.IO) {
                rerenderNativeGuide(song, language)
            }
            if (error == null) app.audio.loadSong(song.id)
            val refreshed = withContext(Dispatchers.IO) { buildNativeGuideState(song.id) }
            _nativeGuideState.value = refreshed.copy(error = error)
        }
    }

    fun setTrackVolume(index: Int, value: Float) = app.audio.setTrackVolume(index, value)
    fun setTrackMute(index: Int, value: Boolean) = app.audio.setTrackMute(index, value)
    fun setTrackSolo(index: Int, value: Boolean) = app.audio.setTrackSolo(index, value)
    fun setTrackPan(index: Int, value: Float) = app.audio.setTrackPan(index, value)
    fun setTrackOutputRoute(index: Int, route: StereoRoute) = app.audio.setTrackOutputRoute(index, route)
    fun setOutputDevice(id: Int) = app.audio.setOutputDevice(id)
    fun diagnostics(): NativeAudioEngine.Diagnostics = app.audio.diagnostics()

    fun saveSection(section: SectionEntity) {
        if (player.value.isPlaying) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { app.repository.saveSection(section) }
            if (player.value.song?.id == section.songId) app.audio.loadSong(section.songId)
        }
    }

    fun deleteSection(section: SectionEntity) {
        if (player.value.isPlaying) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { app.repository.deleteSection(section) }
            if (player.value.song?.id == section.songId) app.audio.loadSong(section.songId)
        }
    }

    fun deleteSong(song: SongEntity) {
        val busy = player.value.isPlaying || player.value.isCountingIn ||
            importState.value.running || backupState.value.running || nativeGuideState.value.rendering ||
            _libraryActionState.value.deletingSongId != null
        if (busy) {
            _libraryActionState.value = LibraryActionUiState(
                error = "Stop playback and wait for current processing to finish before deleting a multitrack.",
            )
            return
        }

        viewModelScope.launch {
            _libraryActionState.value = LibraryActionUiState(deletingSongId = song.id)
            val wasLoaded = player.value.song?.id == song.id
            val liveContainedSong = _setlistLiveState.value.active &&
                _selectedSetlist.value?.songs?.any { it.id == song.id } == true
            if (liveContainedSong) exitSetlistLive()
            if (wasLoaded) {
                withContext(Dispatchers.Default) { app.audio.unloadForLibraryRestore() }
                _nativeGuideState.value = NativeGuideUiState()
            }

            val error = withContext(Dispatchers.IO) { deleteSongFromStorageAndDatabase(song) }
            if (error == null) {
                val selectedId = _selectedSetlist.value?.setlist?.id
                if (selectedId != null) {
                    _selectedSetlist.value = withContext(Dispatchers.IO) { app.repository.getSetlistBundle(selectedId) }
                }
                _libraryActionState.value = LibraryActionUiState()
            } else {
                _libraryActionState.value = LibraryActionUiState(error = error)
            }
        }
    }

    fun dismissLibraryActionState() {
        if (_libraryActionState.value.deletingSongId == null) {
            _libraryActionState.value = LibraryActionUiState()
        }
    }

    private suspend fun deleteSongFromStorageAndDatabase(song: SongEntity): String? {
        val songRoot = File(app.filesDir, "library/${song.id}")
        val trashRoot = File(app.cacheDir, "song-delete-staging").apply { mkdirs() }
        val staged = File(trashRoot, "${song.id}-${System.nanoTime()}")
        var filesStaged = false

        try {
            if (songRoot.exists()) {
                staged.deleteRecursively()
                if (!songRoot.renameTo(staged)) {
                    songRoot.copyRecursively(staged, overwrite = true)
                    if (!staged.isDirectory || !songRoot.deleteRecursively()) {
                        staged.deleteRecursively()
                        return "StageGrid could not safely stage the local song files for deletion."
                    }
                }
                filesStaged = staged.exists()
                if (!filesStaged) return "StageGrid could not safely stage the local song files for deletion."
            }

            try {
                app.repository.deleteSong(song)
            } catch (t: Throwable) {
                if (filesStaged) restoreStagedSong(staged, songRoot)
                return t.message ?: "The song could not be removed from the library."
            }

            staged.deleteRecursively()
            return null
        } catch (t: Throwable) {
            if (filesStaged && !songRoot.exists()) restoreStagedSong(staged, songRoot)
            return t.message ?: "The song could not be deleted."
        }
    }

    private fun restoreStagedSong(staged: File, target: File) {
        if (!staged.exists() || target.exists()) return
        target.parentFile?.mkdirs()
        if (!staged.renameTo(target)) {
            staged.copyRecursively(target, overwrite = true)
            staged.deleteRecursively()
        }
    }

    fun saveSongMetadata(
        songId: String,
        title: String,
        artist: String,
        bpmText: String,
        key: String,
        timeSignature: String,
        gridOffsetMsText: String,
        notes: String,
        loadAfterSave: Boolean = false,
    ) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val current = app.repository.getSong(songId) ?: return@withContext null
                val bpm = bpmText.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 20.0..400.0 }
                val signature = timeSignature.trim().takeIf { it.matches(Regex("[1-9]\\d?/([1-9]\\d?)")) } ?: "4/4"
                val gridOffsetMs = gridOffsetMsText.trim().toLongOrNull()?.coerceIn(0L, 60_000L) ?: current.gridOffsetMs
                val updated = current.copy(
                    title = title.trim().ifBlank { current.title },
                    artist = artist.trim(),
                    bpm = bpm,
                    musicalKey = key.trim().ifBlank { null },
                    timeSignature = signature,
                    gridOffsetMs = gridOffsetMs,
                    notes = notes.trim(),
                )
                app.repository.updateSong(updated)
                val generatedSections = recoverNativeGuideSections(updated)
                MetadataSaveResult(updated, generatedSections)
            }
            if (result != null) {
                val shouldReload = loadAfterSave || (result.generatedSections && player.value.song?.id == result.song.id)
                if (shouldReload) {
                    refreshNativeGuideState(result.song.id)
                    app.audio.loadSong(result.song.id)
                }
            }
        }
    }

    private suspend fun refreshNativeGuideState(songId: String) {
        _nativeGuideState.value = withContext(Dispatchers.IO) { buildNativeGuideState(songId) }
    }

    private suspend fun buildNativeGuideState(songId: String): NativeGuideUiState {
        val bundle = app.repository.getSongBundle(songId) ?: return NativeGuideUiState(songId = songId)
        val nativeTrack = bundle.tracks.firstOrNull { it.name == NativeGuideRenderer.TRACK_NAME }
            ?: return NativeGuideUiState(songId = songId)
        if (!File(nativeTrack.filePath).isFile) return NativeGuideUiState(songId = songId)
        val eventFile = nativeGuideEventFile(songId)
        val analysis = NativeGuideEventStore.readAnalysis(eventFile)
            ?: return NativeGuideUiState(songId = songId)
        return NativeGuideUiState(
            songId = songId,
            available = true,
            currentLanguage = NativeGuideEventStore.readOutputLanguage(eventFile),
            detectedLanguage = analysis.dominantLanguage,
            languages = app.guidePacks.status().languages,
            eventCount = analysis.cues.size,
        )
    }

    private suspend fun rerenderNativeGuide(song: SongEntity, language: String): String? {
        val bundle = app.repository.getSongBundle(song.id) ?: return "Song data is no longer available."
        val nativeTrack = bundle.tracks.firstOrNull { it.name == NativeGuideRenderer.TRACK_NAME }
            ?: return "This song does not have a StageGrid Native Guide."
        val eventFile = nativeGuideEventFile(song.id)
        val analysis = NativeGuideEventStore.readAnalysis(eventFile)
            ?: return "Recognized Guide events are not available for this song."
        val samples = app.guidePacks.listSamples()
        if (samples.none { it.language == language }) return "The selected Guide language is not installed."

        val target = File(nativeTrack.filePath)
        val temp = File(target.parentFile, ".${target.name}.language-${System.nanoTime()}.wav")
        val rendered = try {
            NativeGuideRenderer.render(
                outputFile = temp,
                durationMs = song.durationMs,
                cues = analysis.cues,
                samples = samples,
                outputLanguage = language,
                onProgress = { progress ->
                    _nativeGuideState.value = _nativeGuideState.value.copy(
                        rendering = true,
                        renderPercent = (progress * 100f).roundToInt().coerceIn(0, 100),
                    )
                },
            )
        } catch (t: Throwable) {
            temp.delete()
            return t.message ?: "Native Guide could not be regenerated."
        } ?: run {
            temp.delete()
            return "No compatible Guide samples could be rendered in the selected language."
        }

        if (!replaceRenderedGuide(temp, target)) {
            temp.delete()
            return "StageGrid could not replace the current native Guide safely."
        }

        val metadata = runCatching { WavMetadataReader.read(target) }.getOrElse {
            return "The regenerated Guide could not be validated: ${it.message ?: "unknown error"}."
        }
        app.repository.updateTrack(
            nativeTrack.copy(
                channels = metadata.channels,
                sampleRate = metadata.sampleRate,
                bitDepth = metadata.bitDepth,
                durationMs = metadata.durationMs,
            ),
        )
        NativeGuideEventStore.writeOutputLanguage(eventFile, rendered.outputLanguage)
        return null
    }

    private fun replaceRenderedGuide(temp: File, target: File): Boolean {
        val parent = target.parentFile ?: return false
        val backup = File(parent, ".${target.name}.previous-${System.nanoTime()}")
        var backedUp = false
        try {
            if (target.exists()) {
                if (!target.renameTo(backup)) return false
                backedUp = true
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            if (!target.isFile || target.length() <= 44L) error("Rendered Guide is empty")
            if (backedUp) backup.delete()
            return true
        } catch (_: Throwable) {
            target.delete()
            if (backedUp) backup.renameTo(target)
            return false
        } finally {
            temp.delete()
            if (target.exists()) backup.delete()
        }
    }

    /**
     * Reuses cues already saved by alpha04 once a valid BPM/grid becomes available. The audio is
     * never re-analyzed here. Existing user-authored section maps are protected by the repository's
     * placeholder-only replacement guard.
     */
    private suspend fun recoverNativeGuideSections(song: SongEntity): Boolean {
        if (song.bpm == null || song.durationMs <= 0L) return false
        val eventFile = nativeGuideEventFile(song.id)
        val analysis = NativeGuideEventStore.readAnalysis(eventFile) ?: return false
        val proposals = GuideCueAnalyzer.inferSections(
            result = analysis,
            bpm = song.bpm,
            timeSignature = song.timeSignature,
            gridOffsetMs = song.gridOffsetMs,
            durationMs = song.durationMs,
        )
        if (proposals.isEmpty()) return false
        val sections = proposals.mapIndexed { index, proposal ->
            val end = proposals.getOrNull(index + 1)?.startMs ?: song.durationMs
            SectionEntity(
                songId = song.id,
                name = proposal.name,
                startMs = proposal.startMs,
                endMs = end.coerceAtLeast(proposal.startMs + 1L),
                sortOrder = index,
                colorArgb = AUTO_SECTION_COLORS[index % AUTO_SECTION_COLORS.size],
            )
        }
        val replaced = app.repository.replacePlaceholderSections(song.id, song.durationMs, sections)
        if (replaced) NativeGuideEventStore.writeSectionProposals(eventFile, proposals)
        return replaced
    }

    private fun nativeGuideEventFile(songId: String): File =
        File(app.filesDir, "library/$songId/native-guide-events.json")

    fun createSetlist(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val created = app.repository.createSetlist(name)
            loadSetlist(created.id)
        }
    }

    fun loadSetlist(id: String) {
        viewModelScope.launch {
            if (_setlistLiveState.value.active && _setlistLiveState.value.setlistId != id) exitSetlistLive()
            _selectedSetlist.value = withContext(Dispatchers.IO) { app.repository.getSetlistBundle(id) }
        }
    }

    fun addSongToSelectedSetlist(songId: String) {
        val id = _selectedSetlist.value?.setlist?.id ?: return
        viewModelScope.launch(Dispatchers.IO) {
            app.repository.addSongToSetlist(id, songId)
            loadSetlist(id)
        }
    }

    fun removeSongFromSelectedSetlist(songId: String) {
        val id = _selectedSetlist.value?.setlist?.id ?: return
        if (_setlistLiveState.value.active) exitSetlistLive()
        viewModelScope.launch(Dispatchers.IO) {
            app.repository.removeSongFromSetlist(id, songId)
            loadSetlist(id)
        }
    }

    fun startSelectedSetlistLive() {
        val bundle = _selectedSetlist.value ?: return
        val songIds = bundle.songs.map { it.id }
        val index = SetlistLiveNavigation.initialIndex(songIds, player.value.song?.id)
        if (index < 0) return
        _setlistLiveState.value = buildSetlistLiveState(bundle, index)
        loadSetlistLiveIndex(index)
    }

    fun setlistLiveNext() {
        val live = _setlistLiveState.value
        if (!live.active) return
        val bundle = _selectedSetlist.value ?: return
        val next = SetlistLiveNavigation.nextIndex(live.currentIndex, bundle.songs.size) ?: return
        loadSetlistLiveIndex(next)
    }

    fun setlistLivePrevious() {
        val live = _setlistLiveState.value
        if (!live.active) return
        val bundle = _selectedSetlist.value ?: return
        val previous = SetlistLiveNavigation.previousIndex(live.currentIndex, bundle.songs.size) ?: return
        loadSetlistLiveIndex(previous)
    }

    fun exitSetlistLive() {
        setlistPreloadSerial.incrementAndGet()
        _setlistLiveState.value = SetlistLiveUiState()
    }

    private fun loadSetlistLiveIndex(index: Int) {
        val bundle = _selectedSetlist.value ?: return
        val target = bundle.songs.getOrNull(index) ?: return
        setlistPreloadSerial.incrementAndGet()
        _setlistLiveState.value = buildSetlistLiveState(bundle, index)
        val unloadCurrent = player.value.song?.id != null && player.value.song?.id != target.id
        loadSongInternal(target.id, unloadCurrent = unloadCurrent)
        scheduleNextSongPreload(bundle, index)
    }

    private fun buildSetlistLiveState(bundle: SetlistBundle, index: Int): SetlistLiveUiState {
        val current = bundle.songs.getOrNull(index)
        return SetlistLiveUiState(
            active = current != null,
            setlistId = bundle.setlist.id,
            setlistName = bundle.setlist.name,
            currentIndex = index,
            totalSongs = bundle.songs.size,
            currentSongTitle = current?.title,
            previousSongTitle = bundle.songs.getOrNull(index - 1)?.title,
            nextSongTitle = bundle.songs.getOrNull(index + 1)?.title,
        )
    }

    private fun scheduleNextSongPreload(bundle: SetlistBundle, currentIndex: Int) {
        val next = bundle.songs.getOrNull(currentIndex + 1)
        val serial = setlistPreloadSerial.incrementAndGet()
        if (next == null) {
            _setlistLiveState.value = _setlistLiveState.value.copy(preloadingNext = false, nextReady = false)
            return
        }
        _setlistLiveState.value = _setlistLiveState.value.copy(preloadingNext = true, nextReady = false)
        viewModelScope.launch {
            // Give the native engine the I/O priority to finish opening the current song first.
            delay(SETLIST_PRELOAD_DELAY_MS)
            val ready = withContext(Dispatchers.IO) { warmNextSong(next.id) }
            if (serial != setlistPreloadSerial.get()) return@launch
            val current = _setlistLiveState.value
            if (!current.active || current.nextSongTitle != next.title) return@launch
            _setlistLiveState.value = current.copy(
                preloadingNext = false,
                nextReady = ready,
                error = if (ready) null else "The next song could not be prepared. It can still be loaded normally.",
            )
        }
    }

    /**
     * Warms the beginning of every normalized WAV into the OS file cache. This is intentionally a
     * bounded preload: alpha07 does not keep a second native decoder graph alive or auto-play audio.
     */
    private suspend fun warmNextSong(songId: String): Boolean {
        val bundle = app.repository.getSongBundle(songId) ?: return false
        if (bundle.tracks.isEmpty()) return false
        val buffer = ByteArray(SETLIST_PRELOAD_BUFFER_BYTES)
        for (track in bundle.tracks) {
            val file = File(track.filePath)
            if (!file.isFile || file.length() <= 44L) return false
            try {
                BufferedInputStream(FileInputStream(file), SETLIST_PRELOAD_BUFFER_BYTES).use { input ->
                    var remaining = SETLIST_PRELOAD_BYTES_PER_TRACK
                    while (remaining > 0) {
                        val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                        if (read <= 0) break
                        remaining -= read
                    }
                }
            } catch (_: Throwable) {
                return false
            }
        }
        return true
    }

    fun setLiveMode(enabled: Boolean) = viewModelScope.launch { app.settings.setLiveMode(enabled) }
    fun setPerformanceLock(enabled: Boolean) = viewModelScope.launch { app.settings.setPerformanceLock(enabled) }

    private data class MetadataSaveResult(
        val song: SongEntity,
        val generatedSections: Boolean,
    )

    companion object {
        private val AUTO_SECTION_COLORS = longArrayOf(
            0xFF5B8CFF, 0xFF2FBF9F, 0xFF9C6CFF, 0xFFF39C55, 0xFFE85D75, 0xFF4FB6E9,
        )
        private const val SETLIST_PRELOAD_DELAY_MS = 450L
        private const val SETLIST_PRELOAD_BYTES_PER_TRACK = 512 * 1024
        private const val SETLIST_PRELOAD_BUFFER_BYTES = 64 * 1024
    }
}
