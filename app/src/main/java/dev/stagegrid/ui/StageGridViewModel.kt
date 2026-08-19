package dev.stagegrid.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.stagegrid.StageGridApplication
import dev.stagegrid.audio.AudioDeviceManager
import dev.stagegrid.audio.NativeAudioEngine
import dev.stagegrid.audio.ClickSubdivision
import dev.stagegrid.audio.PlayerState
import dev.stagegrid.guide.GuideCueAnalyzer
import dev.stagegrid.guide.GuidePackManager
import dev.stagegrid.guide.NativeGuideEventStore
import dev.stagegrid.importer.SongImporter
import dev.stagegrid.model.SectionEntity
import dev.stagegrid.model.SetlistBundle
import dev.stagegrid.model.SetlistEntity
import dev.stagegrid.model.SongEntity
import dev.stagegrid.model.StereoRoute
import dev.stagegrid.settings.AppSettingsRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
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
        val result: SongImporter.ImportResult? = null,
        val error: String? = null,
    )

    data class GuidePackUiState(
        val installing: Boolean = false,
        val status: GuidePackManager.Status = GuidePackManager.Status(false, 0, emptyList()),
        val error: String? = null,
    )

    private val _importState = MutableStateFlow(ImportUiState())
    val importState: StateFlow<ImportUiState> = _importState.asStateFlow()

    private val _guidePackState = MutableStateFlow(GuidePackUiState(status = app.guidePacks.status()))
    val guidePackState: StateFlow<GuidePackUiState> = _guidePackState.asStateFlow()

    private val _selectedSetlist = MutableStateFlow<SetlistBundle?>(null)
    val selectedSetlist: StateFlow<SetlistBundle?> = _selectedSetlist.asStateFlow()

    init {
        viewModelScope.launch {
            app.settings.settings.collectLatest { preferences ->
                app.audio.setClickSubdivision(preferences.clickSubdivision)
                app.audio.setClickRoute(preferences.clickRoute)
                app.audio.setCountInBars(preferences.countInBars)
            }
        }
    }

    fun importZip(uri: Uri) = runImport { app.importer.importZip(uri) }
    fun importFolder(uri: Uri) = runImport { app.importer.importFolder(uri) }
    fun importFiles(uris: List<Uri>) = runImport { app.importer.importFiles(uris) }

    private fun runImport(block: suspend () -> SongImporter.ImportResult) {
        viewModelScope.launch {
            _importState.value = ImportUiState(running = true)
            try {
                val result = withContext(Dispatchers.IO) { block() }
                _importState.value = ImportUiState(result = result)
            } catch (t: Throwable) {
                _importState.value = ImportUiState(error = t.message ?: "Import failed")
            }
        }
    }

    fun installGuidePack(uri: Uri) {
        viewModelScope.launch {
            _guidePackState.value = _guidePackState.value.copy(installing = true, error = null)
            try {
                val result = withContext(Dispatchers.IO) { app.guidePacks.installZip(uri) }
                _guidePackState.value = GuidePackUiState(status = result.status)
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
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                app.repository.getSong(songId)?.let { recoverNativeGuideSections(it) }
            }
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
                if (shouldReload) app.audio.loadSong(result.song.id)
            }
        }
    }

    /**
     * Reuses cues already saved by alpha04 once a valid BPM/grid becomes available. The audio is
     * never re-analyzed here. Existing user-authored section maps are protected by the repository's
     * placeholder-only replacement guard.
     */
    private suspend fun recoverNativeGuideSections(song: SongEntity): Boolean {
        if (song.bpm == null || song.durationMs <= 0L) return false
        val eventFile = File(app.filesDir, "library/${song.id}/native-guide-events.json")
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

    fun createSetlist(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val created = app.repository.createSetlist(name)
            loadSetlist(created.id)
        }
    }

    fun loadSetlist(id: String) {
        viewModelScope.launch {
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
        viewModelScope.launch(Dispatchers.IO) {
            app.repository.removeSongFromSetlist(id, songId)
            loadSetlist(id)
        }
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
    }
}
