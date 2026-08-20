package dev.stagegrid.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stagegrid.R
import dev.stagegrid.backup.BackupStage
import dev.stagegrid.importer.ImportStage
import dev.stagegrid.model.SongEntity
import dev.stagegrid.ui.screens.CloudBrowserDialog
import dev.stagegrid.ui.screens.LibraryScreen
import dev.stagegrid.ui.screens.LiveWorkspaceScreen
import dev.stagegrid.ui.screens.MixerScreen
import dev.stagegrid.ui.screens.PlayerScreen
import dev.stagegrid.ui.screens.SectionEditorDialog
import dev.stagegrid.ui.screens.SetlistsScreen
import dev.stagegrid.ui.screens.SettingsScreen

private enum class MainScreen(val labelRes: Int) {
    LIBRARY(R.string.library),
    SETLISTS(R.string.setlists),
    PLAYER(R.string.live_workspace),
    MIXER(R.string.mixer),
    ADVANCED(R.string.advanced_options),
    SETTINGS(R.string.settings),
}

@Composable
fun StageGridApp(viewModel: StageGridViewModel) {
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val setlists by viewModel.setlists.collectAsStateWithLifecycle()
    val player by viewModel.player.collectAsStateWithLifecycle()
    val arrangement by viewModel.arrangementState.collectAsStateWithLifecycle()
    val outputs by viewModel.outputs.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val guidePackState by viewModel.guidePackState.collectAsStateWithLifecycle()
    val nativeGuideState by viewModel.nativeGuideState.collectAsStateWithLifecycle()
    val sessionRecoveryState by viewModel.sessionRecoveryState.collectAsStateWithLifecycle()
    val backupState by viewModel.backupState.collectAsStateWithLifecycle()
    val libraryActionState by viewModel.libraryActionState.collectAsStateWithLifecycle()
    val selectedSetlist by viewModel.selectedSetlist.collectAsStateWithLifecycle()
    val setlistLiveState by viewModel.setlistLiveState.collectAsStateWithLifecycle()

    var screen by rememberSaveable { mutableStateOf(MainScreen.LIBRARY) }
    var editingSong by remember { mutableStateOf<SongEntity?>(null) }
    var deletingSong by remember { mutableStateOf<SongEntity?>(null) }
    var cloudBrowserOpen by rememberSaveable { mutableStateOf(false) }
    var sectionEditorOpen by rememberSaveable { mutableStateOf(false) }

    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> uri?.let(viewModel::importZip) }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? -> uri?.let(viewModel::importFolder) }
    val filesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) viewModel.importFiles(uris) }
    val guidePackLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> uri?.let(viewModel::installGuidePack) }
    val backupFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? -> uri?.let(viewModel::createLibraryBackup) }
    val restoreBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> uri?.let(viewModel::restoreLibraryBackup) }

    val availableScreens = if (settings.performanceLock) {
        listOf(MainScreen.PLAYER, MainScreen.MIXER, MainScreen.SETTINGS)
    } else {
        MainScreen.entries.toList()
    }
    LaunchedEffect(settings.performanceLock) { if (screen !in availableScreens) screen = MainScreen.PLAYER }
    LaunchedEffect(settings.clickBus) { viewModel.applyPersistedClickBus(settings.clickBus) }
    LaunchedEffect(outputs) { viewModel.handleOutputDevicesChanged(outputs) }

    Scaffold(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        bottomBar = {
            NavigationBar {
                availableScreens.forEach { item ->
                    NavigationBarItem(
                        selected = screen == item,
                        onClick = { screen = item },
                        icon = { Text(item.name.take(1)) },
                        label = { Text(stringResource(item.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        val contentModifier = Modifier.fillMaxSize().padding(padding)
        when (screen) {
            MainScreen.LIBRARY -> LibraryScreen(
                songs = songs,
                onImportZip = { zipLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                onImportFolder = { folderLauncher.launch(null) },
                onImportFiles = { filesLauncher.launch(arrayOf("audio/wav", "audio/x-wav", "audio/*")) },
                onOpenCloud = { cloudBrowserOpen = true },
                onLoadSong = { id -> viewModel.loadSong(id); screen = MainScreen.PLAYER },
                onEditSong = { editingSong = it },
                onDeleteSong = { deletingSong = it },
                modifier = contentModifier,
            )
            MainScreen.SETLISTS -> SetlistsScreen(
                setlists = setlists,
                songs = songs,
                selected = selectedSetlist,
                onCreate = viewModel::createSetlist,
                onSelect = viewModel::loadSetlist,
                onAddSong = viewModel::addSongToSelectedSetlist,
                onRemoveSong = viewModel::removeSongFromSelectedSetlist,
                onLoadSong = { id -> viewModel.loadSong(id); screen = MainScreen.PLAYER },
                onStartLive = { viewModel.startSelectedSetlistLive(); screen = MainScreen.PLAYER },
                modifier = contentModifier,
            )
            MainScreen.PLAYER -> LiveWorkspaceScreen(
                state = player,
                arrangement = arrangement,
                setlistLive = setlistLiveState,
                onPlayPause = viewModel::playPause,
                onStop = viewModel::stop,
                onStopAll = viewModel::stopAll,
                onSeek = viewModel::seekTo,
                onMaster = viewModel::setMaster,
                onClick = viewModel::setClick,
                onGuide = viewModel::setGuide,
                onTrackVolume = viewModel::setTrackVolume,
                onTrackMute = viewModel::setTrackMute,
                onTrackSolo = viewModel::setTrackSolo,
                onArrangementStart = viewModel::startArrangement,
                onArrangementStop = viewModel::stopArrangement,
                onArrangementExitLoop = viewModel::exitArrangementLoop,
                onArrangementNode = viewModel::selectArrangementNode,
                onArrangementMove = viewModel::moveArrangementNode,
                onArrangementRepeat = viewModel::setArrangementRepeat,
                onArrangementPreRoll = viewModel::setArrangementPreRoll,
                onSetlistPrevious = viewModel::setlistLivePrevious,
                onSetlistNext = viewModel::setlistLiveNext,
                onExitSetlistLive = viewModel::exitSetlistLive,
                modifier = contentModifier,
            )
            MainScreen.MIXER -> MixerScreen(
                state = player,
                onVolume = viewModel::setTrackVolume,
                onMute = viewModel::setTrackMute,
                onSolo = viewModel::setTrackSolo,
                onPan = viewModel::setTrackPan,
                onOutputRoute = viewModel::setTrackOutputRoute,
                onOutputBus = { index, bus -> viewModel.setTrackOutputBus(index, bus) },
                onClickRoute = viewModel::setClickRoute,
                onClickBus = { bus -> viewModel.setClickBus(bus) },
                modifier = contentModifier,
            )
            MainScreen.ADVANCED -> PlayerScreen(
                state = player,
                nativeGuide = nativeGuideState,
                setlistLive = setlistLiveState,
                onPlayPause = viewModel::playPause,
                onStop = viewModel::stop,
                onStopAll = viewModel::stopAll,
                onSeek = viewModel::seekTo,
                onLoop = viewModel::toggleLoop,
                onExitLoop = viewModel::exitLoop,
                onSection = viewModel::selectSection,
                onEditSections = { sectionEditorOpen = true },
                onPlaySectionWithCountIn = viewModel::playCurrentSectionWithCountIn,
                onCountInBars = viewModel::setCountInBars,
                onMaster = viewModel::setMaster,
                onClick = viewModel::setClick,
                onGuide = viewModel::setGuide,
                onNativeGuideLanguage = viewModel::setSongNativeGuideLanguage,
                onReanalyzeNativeGuide = viewModel::reanalyzeCurrentNativeGuide,
                onClickSubdivision = viewModel::setClickSubdivision,
                onClickRoute = viewModel::setClickRoute,
                onSetlistPrevious = viewModel::setlistLivePrevious,
                onSetlistNext = viewModel::setlistLiveNext,
                onExitSetlistLive = viewModel::exitSetlistLive,
                modifier = contentModifier,
            )
            MainScreen.SETTINGS -> SettingsScreen(
                settings = settings,
                guidePackState = guidePackState,
                outputs = outputs,
                selectedOutputId = player.selectedOutputDeviceId,
                diagnosticsProvider = viewModel::diagnostics,
                onLiveMode = viewModel::setLiveMode,
                onPerformanceLock = viewModel::setPerformanceLock,
                onOutput = { id, channels -> viewModel.setMultichannelOutputDevice(id, channels) },
                onTestOutput = { channel -> viewModel.testOutputChannel(channel) },
                onInstallGuidePack = { guidePackLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                onNativeGuideLanguage = viewModel::setNativeGuideLanguage,
                onCreateBackup = { backupFolderLauncher.launch(null) },
                onRestoreBackup = { restoreBackupLauncher.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) },
                backupBusy = backupState.running,
                modifier = contentModifier,
            )
        }
    }

    if (cloudBrowserOpen) {
        CloudBrowserDialog(onDismiss = { cloudBrowserOpen = false }, onImportZip = viewModel::importZip, onImportFiles = viewModel::importFiles)
    }

    if (sectionEditorOpen) {
        player.song?.let { loadedSong ->
            SectionEditorDialog(
                song = loadedSong,
                sections = player.sections,
                positionMs = player.positionMs,
                durationMs = player.durationMs,
                isPlaying = player.isPlaying,
                onSave = viewModel::saveSection,
                onDelete = viewModel::deleteSection,
                onDismiss = { sectionEditorOpen = false },
            )
        } ?: run { sectionEditorOpen = false }
    }

    deletingSong?.let { song ->
        AlertDialog(
            onDismissRequest = { deletingSong = null },
            title = { Text(stringResource(R.string.delete_song_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.delete_song_message, song.title), fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.delete_song_files_note), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = { deletingSong = null; viewModel.deleteSong(song) }) {
                    Text(stringResource(R.string.delete_song_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deletingSong = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (sessionRecoveryState.recovered) {
        AlertDialog(
            onDismissRequest = viewModel::dismissSessionRecovery,
            title = { Text(stringResource(R.string.session_recovered_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.session_recovered_song, sessionRecoveryState.songTitle ?: "—"), fontWeight = FontWeight.SemiBold)
                    sessionRecoveryState.setlistName?.let { Text(stringResource(R.string.session_recovered_setlist, it)) }
                    Text(stringResource(R.string.session_recovered_safe_stop), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = viewModel::dismissSessionRecovery) { Text(stringResource(R.string.close)) } },
        )
    }

    if (importState.running) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(stringResource(R.string.importing)) },
            text = {
                Column {
                    Text(stringResource(R.string.import_progress_percent, importState.progress.percent), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    LinearProgressIndicator(progress = { importState.progress.fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                    Text(importStageLabel(importState.progress.stage), fontWeight = FontWeight.SemiBold)
                    importState.progress.detail?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            },
        )
    }

    importState.result?.let { result ->
        var title by rememberSaveable(result.songId) { mutableStateOf(result.title) }
        var artist by rememberSaveable(result.songId) { mutableStateOf(result.artist) }
        var bpm by rememberSaveable(result.songId) { mutableStateOf(result.bpm?.toString().orEmpty()) }
        var key by rememberSaveable(result.songId) { mutableStateOf(result.key.orEmpty()) }
        var timeSignature by rememberSaveable(result.songId) { mutableStateOf(result.timeSignature) }
        var gridOffsetMs by rememberSaveable(result.songId) { mutableStateOf(result.gridOffsetMs.toString()) }
        var notes by rememberSaveable(result.songId) { mutableStateOf(result.notes) }
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.import_complete)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.import_summary, result.tracksDetected, if (result.clickDetected) stringResource(R.string.yes) else stringResource(R.string.no), if (result.guideDetected) stringResource(R.string.yes) else stringResource(R.string.no)))
                    if (result.warnings.isNotEmpty()) Text(result.warnings.joinToString("\n"), color = MaterialTheme.colorScheme.tertiary)
                    OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.title)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(artist, { artist = it }, label = { Text(stringResource(R.string.artist)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(bpm, { bpm = it }, label = { Text(stringResource(R.string.bpm)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(key, { key = it }, label = { Text(stringResource(R.string.key)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(timeSignature, { timeSignature = it }, label = { Text(stringResource(R.string.time_signature)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(gridOffsetMs, { gridOffsetMs = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.grid_offset_ms)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(notes, { notes = it }, label = { Text(stringResource(R.string.notes)) }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.saveSongMetadata(result.songId, title, artist, bpm, key, timeSignature, gridOffsetMs, notes, loadAfterSave = true)
                    screen = MainScreen.PLAYER
                    viewModel.dismissImportState()
                }) { Text(stringResource(R.string.save_and_load)) }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissImportState) { Text(stringResource(R.string.save_later)) } },
        )
    }

    editingSong?.let { song ->
        SongMetadataDialog(
            song = song,
            onDismiss = { editingSong = null },
            onSave = { title, artist, bpm, key, signature, gridOffsetMs, notes ->
                viewModel.saveSongMetadata(song.id, title, artist, bpm, key, signature, gridOffsetMs, notes)
                editingSong = null
            },
        )
    }

    importState.error?.let { error ->
        AlertDialog(onDismissRequest = viewModel::dismissImportState, title = { Text(stringResource(R.string.import_failed)) }, text = { Text(error, color = MaterialTheme.colorScheme.error) }, confirmButton = { TextButton(onClick = viewModel::dismissImportState) { Text(stringResource(R.string.close)) } })
    }

    libraryActionState.error?.let { error ->
        AlertDialog(onDismissRequest = viewModel::dismissLibraryActionState, title = { Text(stringResource(R.string.delete_song_error_title)) }, text = { Text(error, color = MaterialTheme.colorScheme.error) }, confirmButton = { TextButton(onClick = viewModel::dismissLibraryActionState) { Text(stringResource(R.string.close)) } })
    }

    if (backupState.running) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(if (backupState.operation == StageGridViewModel.BackupOperation.RESTORE) stringResource(R.string.backup_restore_running_title) else stringResource(R.string.backup_create_running_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.backup_progress_percent, backupState.progress.percent), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    LinearProgressIndicator(progress = { backupState.progress.fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                    Text(backupStageLabel(backupState.progress.stage), fontWeight = FontWeight.SemiBold)
                    backupState.progress.detail?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            },
        )
    }
    backupState.backupResult?.let { result ->
        AlertDialog(onDismissRequest = viewModel::dismissBackupState, title = { Text(stringResource(R.string.backup_complete_title)) }, text = { Text(stringResource(R.string.backup_complete_summary, result.songs, result.setlists, result.fileName)) }, confirmButton = { TextButton(onClick = viewModel::dismissBackupState) { Text(stringResource(R.string.close)) } })
    }
    backupState.restoreResult?.let { result ->
        AlertDialog(onDismissRequest = viewModel::dismissBackupState, title = { Text(stringResource(R.string.backup_restore_complete_title)) }, text = { Text(stringResource(R.string.backup_restore_complete_summary, result.songs, result.setlists, result.files)) }, confirmButton = { TextButton(onClick = viewModel::dismissBackupState) { Text(stringResource(R.string.close)) } })
    }
    backupState.error?.let { error ->
        AlertDialog(onDismissRequest = viewModel::dismissBackupState, title = { Text(stringResource(R.string.backup_error_title)) }, text = { Text(error, color = MaterialTheme.colorScheme.error) }, confirmButton = { TextButton(onClick = viewModel::dismissBackupState) { Text(stringResource(R.string.close)) } })
    }
}

@Composable
private fun importStageLabel(stage: ImportStage): String = when (stage) {
    ImportStage.PREPARING -> stringResource(R.string.import_stage_preparing)
    ImportStage.COPYING -> stringResource(R.string.import_stage_copying)
    ImportStage.PROCESSING_TRACK -> stringResource(R.string.import_stage_processing_track)
    ImportStage.DECODING_MP3 -> stringResource(R.string.import_stage_decoding_mp3)
    ImportStage.ANALYZING_CLICK -> stringResource(R.string.import_stage_analyzing_click)
    ImportStage.ANALYZING_GUIDE -> stringResource(R.string.import_stage_analyzing_guide)
    ImportStage.RENDERING_GUIDE -> stringResource(R.string.import_stage_rendering_guide)
    ImportStage.BUILDING_SECTIONS -> stringResource(R.string.import_stage_building_sections)
    ImportStage.SAVING_LIBRARY -> stringResource(R.string.import_stage_saving_library)
    ImportStage.COMPLETE -> stringResource(R.string.import_stage_complete)
}

@Composable
private fun backupStageLabel(stage: BackupStage): String = when (stage) {
    BackupStage.PREPARING -> stringResource(R.string.backup_stage_preparing)
    BackupStage.HASHING -> stringResource(R.string.backup_stage_hashing)
    BackupStage.WRITING -> stringResource(R.string.backup_stage_writing)
    BackupStage.COPYING_ARCHIVE -> stringResource(R.string.backup_stage_copying_archive)
    BackupStage.VALIDATING -> stringResource(R.string.backup_stage_validating)
    BackupStage.RESTORING_FILES -> stringResource(R.string.backup_stage_restoring_files)
    BackupStage.RESTORING_LIBRARY -> stringResource(R.string.backup_stage_restoring_library)
    BackupStage.COMPLETE -> stringResource(R.string.backup_stage_complete)
}

@Composable
private fun SongMetadataDialog(
    song: SongEntity,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String, String, String) -> Unit,
) {
    var title by rememberSaveable(song.id) { mutableStateOf(song.title) }
    var artist by rememberSaveable(song.id) { mutableStateOf(song.artist) }
    var bpm by rememberSaveable(song.id) { mutableStateOf(song.bpm?.toString().orEmpty()) }
    var key by rememberSaveable(song.id) { mutableStateOf(song.musicalKey.orEmpty()) }
    var signature by rememberSaveable(song.id) { mutableStateOf(song.timeSignature) }
    var gridOffsetMs by rememberSaveable(song.id) { mutableStateOf(song.gridOffsetMs.toString()) }
    var notes by rememberSaveable(song.id) { mutableStateOf(song.notes) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_metadata)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.title)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(artist, { artist = it }, label = { Text(stringResource(R.string.artist)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(bpm, { bpm = it }, label = { Text(stringResource(R.string.bpm)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(key, { key = it }, label = { Text(stringResource(R.string.key)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(signature, { signature = it }, label = { Text(stringResource(R.string.time_signature)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(gridOffsetMs, { gridOffsetMs = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.grid_offset_ms)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(notes, { notes = it }, label = { Text(stringResource(R.string.notes)) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { Button(onClick = { onSave(title, artist, bpm, key, signature, gridOffsetMs, notes) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
