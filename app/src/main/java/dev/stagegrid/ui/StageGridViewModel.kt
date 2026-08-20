package dev.stagegrid.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.stagegrid.StageGridApplication
import dev.stagegrid.audio.AudioDeviceManager
import dev.stagegrid.audio.ClickSubdivision
import dev.stagegrid.audio.EngineState
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
import dev.stagegrid.model.TrackType
import dev.stagegrid.session.PerformanceSessionStore
import dev.stagegrid.settings.AppSettingsRepository
import java.io.File
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
        val canReanalyze: Boolean = false,
        val currentLanguage: String? = null,
        val detectedLanguage: String? = null,
        val languages: List<String> = emptyList(),
        val eventCount: Int = 0,
        val rendering: Boolean = false,
        val renderPercent: Int = 0,
        val reanalyzing: Boolean = false,
        val reanalyzePercent: Int = 0,
        val error: String? = null,
    )

    data class SessionRecoveryUiState(
        val recovered: Boolean = false,
        val songTitle: String? = null,
        val setlistName: String? = null,
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
    private val _sessionRecoveryState = MutableStateFlow(SessionRecoveryUiState())
    val sessionRecoveryState: StateFlow<SessionRecoveryUiState> = _sessionRecoveryState.asStateFlow()
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
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { GuideCueAnalyzer.prepare(app.guidePacks.listSamples()) }
        }
        viewModelScope.launch {
            restorePreviousSession()
            while (true) {
                delay(SESSION_SNAPSHOT_INTERVAL_MS)
                persistCurrentSession()
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
                        _importState.value = _importState.value.copy(running = true, progress = progress, result = null, error = null)
                    }
                }
                _importState.value = ImportUiState(running = false, progress = ImportProgress(100, ImportStage.COMPLETE), result = result)
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
                        _backupState.value = _backupState.value.copy(running = true, operation = BackupOperation.CREATE, progress = progress, error = null)
                    }
                }
                _backupState.value = BackupUiState(operation = BackupOperation.CREATE, progress = BackupProgress(100, BackupStage.COMPLETE), backupResult = result)
            } catch (t: Throwable) {
                _backupState.value = BackupUiState(operation = BackupOperation.CREATE, error = t.message ?: "Backup failed")
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
                withContext(Dispatchers.Default) { app.audio.unloadForLibraryRestore() }
                val result = withContext(Dispatchers.IO) {
                    app.backupManager.restoreBackup(backupUri) { progress ->
                        _backupState.value = _backupState.value.copy(running = true, operation = BackupOperation.RESTORE, progress = progress, error = null)
                    }
                }
                app.guidePacks.invalidateCache()
                withContext(Dispatchers.IO) { runCatching { GuideCueAnalyzer.prepare(app.guidePacks.listSamples()) } }
                _guidePackState.value = GuidePackUiState(status = app.guidePacks.status())
                _selectedSetlist.value = null
                if (previouslyLoadedSongId != null && withContext(Dispatchers.IO) { app.repository.getSong(previouslyLoadedSongId) } != null) {
                    refreshNativeGuideState(previouslyLoadedSongId)
                    app.audio.loadSong(previouslyLoadedSongId)
                } else {
                    withContext(Dispatchers.IO) { app.sessionStore.clear() }
                    _nativeGuideState.value = NativeGuideUiState()
                }
                _backupState.value = BackupUiState(operation = BackupOperation.RESTORE, progress = BackupProgress(100, BackupStage.COMPLETE), restoreResult = result)
            } catch (t: Throwable) {
                if (previouslyLoadedSongId != null && withContext(Dispatchers.IO) { app.repository.getSong(previouslyLoadedSongId) } != null) {
                    app.audio.loadSong(previouslyLoadedSongId)
                }
                _backupState.value = BackupUiState(operation = BackupOperation.RESTORE, error = t.message ?: "Restore failed")
            }
        }
    }

    private fun backupOperationAllowed(): Boolean {
        val p = player.value
        val nativeBusy = nativeGuideState.value.rendering || nativeGuideState.value.reanalyzing
        val busy = p.isPlaying || p.isCountingIn || p.crossfadeInProgress || importState.value.running || nativeBusy || backupState.value.running
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
                    GuideCueAnalyzer.prepare(app.guidePacks.listSamples())
                    installed
                }
                _guidePackState.value = GuidePackUiState(status = result.status)
                player.value.song?.id?.let { refreshNativeGuideState(it) }
            } catch (t: Throwable) {
                _guidePackState.value = _guidePackState.value.copy(installing = false, status = app.guidePacks.status(), error = t.message ?: "Guide pack installation failed")
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

    private fun loadSongInternal(songId: String, unloadCurrent: Boolean, initialPositionMs: Long = 0L) {
        viewModelScope.launch {
            if (unloadCurrent && player.value.song?.id != null && player.value.song?.id != songId) {
                withContext(Dispatchers.Default) { app.audio.unloadForLibraryRestore() }
            }
            withContext(Dispatchers.IO) { app.repository.getSong(songId)?.let { recoverNativeGuideSections(it) } }
            refreshNativeGuideState(songId)
            app.audio.loadSong(songId, initialPositionMs)
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

    fun setNativeGuideLanguage(language: String) = viewModelScope.launch { app.settings.setNativeGuideLanguage(language) }

    fun setSongNativeGuideLanguage(language: String) {
        val song = player.value.song ?: return
        val guideState = _nativeGuideState.value
        if (player.value.isPlaying || player.value.isCountingIn || guideState.rendering || guideState.reanalyzing) return
        if (language !in guideState.languages || language == guideState.currentLanguage) return

        viewModelScope.launch {
            _nativeGuideState.value = guideState.copy(rendering = true, renderPercent = 0, error = null)
            val position = player.value.positionMs
            val error = withContext(Dispatchers.IO) { rerenderNativeGuide(song, language) }
            if (error == null) app.audio.loadSong(song.id, position)
            val refreshed = withContext(Dispatchers.IO) { buildNativeGuideState(song.id) }
            _nativeGuideState.value = refreshed.copy(error = error)
        }
    }

    fun reanalyzeCurrentNativeGuide() {
        val song = player.value.song ?: return
        val guideState = _nativeGuideState.value
        if (player.value.isPlaying || player.value.isCountingIn || guideState.rendering || guideState.reanalyzing) return
        if (!guideState.canReanalyze) return
        val position = player.value.positionMs
        viewModelScope.launch {
            _nativeGuideState.value = guideState.copy(reanalyzing = true, reanalyzePercent = 0, error = null)
            val error = try {
                withContext(Dispatchers.IO) {
                    app.nativeGuideReanalyzer.reanalyze(
                        songId = song.id,
                        preferredLanguage = settings.value.nativeGuideLanguage,
                        onProgress = { percent ->
                            _nativeGuideState.value = _nativeGuideState.value.copy(
                                reanalyzing = true,
                                reanalyzePercent = percent.coerceIn(0, 100),
                                error = null,
                            )
                        },
                    )
                }
                null
            } catch (t: Throwable) {
                t.message ?: "Native Guide reanalysis failed."
            }
            val refreshed = withContext(Dispatchers.IO) { buildNativeGuideState(song.id) }
            _nativeGuideState.value = refreshed.copy(error = error)
            if (error == null) app.audio.loadSong(song.id, position)
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
        val guideBusy = nativeGuideState.value.rendering || nativeGuideState.value.reanalyzing
        val busy = player.value.isPlaying || player.value.isCountingIn || player.value.crossfadeInProgress || importState.value.running ||
            backupState.value.running || guideBusy || _libraryActionState.value.deletingSongId != null
        if (busy) {
            _libraryActionState.value = LibraryActionUiState(error = "Stop playback and wait for current processing to finish before deleting a multitrack.")
            return
        }

        viewModelScope.launch {
            _libraryActionState.value = LibraryActionUiState(deletingSongId = song.id)
            val wasLoaded = player.value.song?.id == song.id
            val liveContainedSong = _setlistLiveState.value.active && _selectedSetlist.value?.songs?.any { it.id == song.id } == true
            if (liveContainedSong) exitSetlistLive()
            if (wasLoaded) {
                withContext(Dispatchers.Default) { app.audio.unloadForLibraryRestore() }
                _nativeGuideState.value = NativeGuideUiState()
            }

            val error = withContext(Dispatchers.IO) { deleteSongFromStorageAndDatabase(song) }
            if (error == null) {
                if (wasLoaded) withContext(Dispatchers.IO) { app.sessionStore.clear() }
                val selectedId = _selectedSetlist.value?.setlist?.id
                if (selectedId != null) _selectedSetlist.value = withContext(Dispatchers.IO) { app.repository.getSetlistBundle(selectedId) }
                _libraryActionState.value = LibraryActionUiState()
            } else {
                _libraryActionState.value = LibraryActionUiState(error = error)
            }
        }
    }

    fun dismissLibraryActionState() {
        if (_libraryActionState.value.deletingSongId == null) _libraryActionState.value = LibraryActionUiState()
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
        val status = app.guidePacks.status()
        val referenceAvailable = bundle.tracks.any {
            TrackType.fromStorage(it.type) == TrackType.GUIDE &&
                it.name != NativeGuideRenderer.TRACK_NAME && File(it.filePath).isFile
        }
        val canReanalyze = referenceAvailable && status.installed
        val nativeTrack = bundle.tracks.firstOrNull { it.name == NativeGuideRenderer.TRACK_NAME }
        val eventFile = nativeGuideEventFile(songId)
        val analysis = NativeGuideEventStore.readAnalysis(eventFile)
        if (nativeTrack == null || !File(nativeTrack.filePath).isFile || analysis == null) {
            return NativeGuideUiState(songId = songId, canReanalyze = canReanalyze, languages = status.languages)
        }
        return NativeGuideUiState(
            songId = songId,
            available = true,
            canReanalyze = canReanalyze,
            currentLanguage = NativeGuideEventStore.readOutputLanguage(eventFile),
            detectedLanguage = analysis.dominantLanguage,
            languages = status.languages,
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
                    _nativeGuideState.value = _nativeGuideState.value.copy(rendering = true, renderPercent = (progress * 100f).roundToInt().coerceIn(0, 100))
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
        app.repository.updateTrack(nativeTrack.copy(channels = metadata.channels, sampleRate = metadata.sampleRate, bitDepth = metadata.bitDepth, durationMs = metadata.durationMs))
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

    private fun nativeGuideEventFile(songId: String): File = File(app.filesDir, "library/$songId/native-guide-events.json")

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
        if (!live.active || player.value.crossfadeInProgress) return
        val bundle = _selectedSetlist.value ?: return
        val nextIndex = SetlistLiveNavigation.nextIndex(live.currentIndex, bundle.songs.size) ?: return
        val target = bundle.songs.getOrNull(nextIndex) ?: return

        if (live.nextReady && player.value.preloadedSongId == target.id) {
            promoteSetlistLiveIndex(bundle, nextIndex)
        } else {
            loadSetlistLiveIndex(nextIndex)
        }
    }

    fun setlistLivePrevious() {
        val live = _setlistLiveState.value
        if (!live.active || player.value.crossfadeInProgress) return
        val bundle = _selectedSetlist.value ?: return
        val previous = SetlistLiveNavigation.previousIndex(live.currentIndex, bundle.songs.size) ?: return
        loadSetlistLiveIndex(previous)
    }

    fun exitSetlistLive() {
        setlistPreloadSerial.incrementAndGet()
        app.audio.clearPreloadedSong()
        _setlistLiveState.value = SetlistLiveUiState()
    }

    private fun promoteSetlistLiveIndex(bundle: SetlistBundle, index: Int) {
        val target = bundle.songs.getOrNull(index) ?: return
        val serial = setlistPreloadSerial.incrementAndGet()
        _setlistLiveState.value = buildSetlistLiveState(bundle, index).copy(preloadingNext = false, nextReady = false)
        app.audio.promotePreloadedSong(SETLIST_CROSSFADE_MS)
        viewModelScope.launch {
            val deadline = System.currentTimeMillis() + SETLIST_PROMOTE_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline && player.value.song?.id != target.id && !player.value.errorMessage.orEmpty().contains("promot", ignoreCase = true)) {
                delay(40)
            }
            if (serial != setlistPreloadSerial.get()) return@launch
            if (player.value.song?.id == target.id) {
                refreshNativeGuideState(target.id)
                scheduleNextSongPreload(bundle, index)
            } else {
                _setlistLiveState.value = _setlistLiveState.value.copy(error = "Crossfade handoff failed. Loading the song normally.")
                loadSetlistLiveIndex(index)
            }
        }
    }

    private fun loadSetlistLiveIndex(index: Int) {
        val bundle = _selectedSetlist.value ?: return
        val target = bundle.songs.getOrNull(index) ?: return
        setlistPreloadSerial.incrementAndGet()
        app.audio.clearPreloadedSong()
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
        val currentSong = bundle.songs.getOrNull(currentIndex) ?: return
        val next = bundle.songs.getOrNull(currentIndex + 1)
        val serial = setlistPreloadSerial.incrementAndGet()
        if (next == null) {
            app.audio.clearPreloadedSong()
            _setlistLiveState.value = _setlistLiveState.value.copy(preloadingNext = false, nextReady = false)
            return
        }
        _setlistLiveState.value = _setlistLiveState.value.copy(preloadingNext = true, nextReady = false)
        viewModelScope.launch {
            val loadDeadline = System.currentTimeMillis() + SETLIST_CURRENT_LOAD_TIMEOUT_MS
            while (System.currentTimeMillis() < loadDeadline && (player.value.song?.id != currentSong.id || player.value.engineState == EngineState.LOADING)) {
                if (serial != setlistPreloadSerial.get()) return@launch
                delay(60)
            }
            if (serial != setlistPreloadSerial.get()) return@launch
            if (player.value.song?.id != currentSong.id) return@launch
            delay(SETLIST_PRELOAD_DELAY_MS)
            if (serial != setlistPreloadSerial.get()) return@launch

            app.audio.preloadSong(next.id)
            val deadline = System.currentTimeMillis() + SETLIST_PRELOAD_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline && player.value.preloadedSongId != next.id) {
                if (serial != setlistPreloadSerial.get()) return@launch
                if (player.value.errorMessage?.contains("preload", ignoreCase = true) == true) break
                delay(60)
            }
            if (serial != setlistPreloadSerial.get()) return@launch
            val ready = player.value.preloadedSongId == next.id
            val current = _setlistLiveState.value
            if (!current.active || current.currentIndex != currentIndex) return@launch
            _setlistLiveState.value = current.copy(
                preloadingNext = false,
                nextReady = ready,
                error = if (ready) null else "The next song could not be fully preloaded. It can still be loaded normally.",
            )
        }
    }

    private suspend fun restorePreviousSession() {
        val snapshot = withContext(Dispatchers.IO) { app.sessionStore.read() } ?: return
        val song = withContext(Dispatchers.IO) { app.repository.getSong(snapshot.songId) }
        if (song == null) {
            withContext(Dispatchers.IO) { app.sessionStore.clear() }
            return
        }

        app.audio.setClickEnabled(snapshot.clickEnabled)
        app.audio.setGuideEnabled(snapshot.guideEnabled)
        app.audio.setClickSubdivision(snapshot.clickSubdivision)
        app.audio.setClickRoute(snapshot.clickRoute)
        app.audio.setCountInBars(snapshot.countInBars)
        app.audio.setMasterVolume(snapshot.masterVolume)

        var recoveredSetlistName: String? = null
        var recoveredBundle: SetlistBundle? = null
        var recoveredIndex = -1
        if (snapshot.setlistActive && snapshot.setlistId != null) {
            val bundle = withContext(Dispatchers.IO) { app.repository.getSetlistBundle(snapshot.setlistId) }
            if (bundle != null) {
                _selectedSetlist.value = bundle
                val currentIndex = bundle.songs.indexOfFirst { it.id == song.id }
                if (currentIndex >= 0) {
                    _setlistLiveState.value = buildSetlistLiveState(bundle, currentIndex)
                    recoveredSetlistName = bundle.setlist.name
                    recoveredBundle = bundle
                    recoveredIndex = currentIndex
                }
            }
        }

        withContext(Dispatchers.IO) { recoverNativeGuideSections(song) }
        refreshNativeGuideState(song.id)
        app.audio.loadSong(song.id, snapshot.positionMs)
        if (recoveredBundle != null && recoveredIndex >= 0) scheduleNextSongPreload(recoveredBundle, recoveredIndex)
        _sessionRecoveryState.value = SessionRecoveryUiState(
            recovered = true,
            songTitle = song.title,
            setlistName = recoveredSetlistName,
        )
    }

    private suspend fun persistCurrentSession() {
        val p = player.value
        val song = p.song ?: return
        if (p.engineState == EngineState.LOADING || p.engineState == EngineState.ERROR || p.crossfadeInProgress) return
        val live = _setlistLiveState.value
        val snapshot = PerformanceSessionStore.Snapshot(
            songId = song.id,
            positionMs = p.positionMs.coerceIn(0L, p.durationMs.coerceAtLeast(0L)),
            clickEnabled = p.clickEnabled,
            guideEnabled = p.guideEnabled,
            clickSubdivision = p.clickSubdivision,
            clickRoute = p.clickRoute,
            countInBars = p.countInBars,
            masterVolume = p.masterVolume,
            setlistActive = live.active,
            setlistId = live.setlistId,
            setlistIndex = live.currentIndex,
        )
        withContext(Dispatchers.IO) { app.sessionStore.write(snapshot) }
    }

    fun dismissSessionRecovery() {
        _sessionRecoveryState.value = SessionRecoveryUiState()
    }

    fun setLiveMode(enabled: Boolean) = viewModelScope.launch { app.settings.setLiveMode(enabled) }
    fun setPerformanceLock(enabled: Boolean) = viewModelScope.launch { app.settings.setPerformanceLock(enabled) }

    private data class MetadataSaveResult(val song: SongEntity, val generatedSections: Boolean)

    companion object {
        private val AUTO_SECTION_COLORS = longArrayOf(
            0xFF5B8CFF, 0xFF2FBF9F, 0xFF9C6CFF, 0xFFF39C55, 0xFFE85D75, 0xFF4FB6E9,
        )
        private const val SESSION_SNAPSHOT_INTERVAL_MS = 1_000L
        private const val SETLIST_PRELOAD_DELAY_MS = 300L
        private const val SETLIST_PRELOAD_TIMEOUT_MS = 8_000L
        private const val SETLIST_CURRENT_LOAD_TIMEOUT_MS = 8_000L
        private const val SETLIST_PROMOTE_TIMEOUT_MS = 4_000L
        private const val SETLIST_CROSSFADE_MS = 700
    }
}
