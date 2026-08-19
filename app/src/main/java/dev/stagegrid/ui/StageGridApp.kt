package dev.stagegrid.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stagegrid.R
import dev.stagegrid.model.SongEntity
import dev.stagegrid.ui.screens.CloudBrowserDialog
import dev.stagegrid.ui.screens.LibraryScreen
import dev.stagegrid.ui.screens.MixerScreen
import dev.stagegrid.ui.screens.PlayerScreen
import dev.stagegrid.ui.screens.SetlistsScreen
import dev.stagegrid.ui.screens.SettingsScreen

private enum class MainScreen(val labelRes: Int) {
    LIBRARY(R.string.library),
    SETLISTS(R.string.setlists),
    PLAYER(R.string.player),
    MIXER(R.string.mixer),
    SETTINGS(R.string.settings),
}

@Composable
fun StageGridApp(viewModel: StageGridViewModel) {
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val setlists by viewModel.setlists.collectAsStateWithLifecycle()
    val player by viewModel.player.collectAsStateWithLifecycle()
    val outputs by viewModel.outputs.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val selectedSetlist by viewModel.selectedSetlist.collectAsStateWithLifecycle()

    var screen by rememberSaveable { mutableStateOf(MainScreen.LIBRARY) }
    var editingSong by remember { mutableStateOf<SongEntity?>(null) }
    var cloudBrowserOpen by rememberSaveable { mutableStateOf(false) }

    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let(viewModel::importZip)
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let(viewModel::importFolder)
    }
    val filesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.importFiles(uris)
    }

    val availableScreens = if (settings.performanceLock) {
        listOf(MainScreen.PLAYER, MainScreen.MIXER, MainScreen.SETTINGS)
    } else {
        MainScreen.entries.toList()
    }
    LaunchedEffect(settings.performanceLock) {
        if (screen !in availableScreens) screen = MainScreen.PLAYER
    }

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
                modifier = contentModifier,
            )
            MainScreen.PLAYER -> PlayerScreen(
                state = player,
                onPlayPause = viewModel::playPause,
                onStop = viewModel::stop,
                onStopAll = viewModel::stopAll,
                onSeek = viewModel::seekTo,
                onLoop = viewModel::toggleLoop,
                onExitLoop = viewModel::exitLoop,
                onSection = viewModel::selectSection,
                onMaster = viewModel::setMaster,
                onClick = viewModel::setClick,
                onGuide = viewModel::setGuide,
                onClickSubdivision = viewModel::setClickSubdivision,
                onClickRoute = viewModel::setClickRoute,
                modifier = contentModifier,
            )
            MainScreen.MIXER -> MixerScreen(
                state = player,
                onVolume = viewModel::setTrackVolume,
                onMute = viewModel::setTrackMute,
                onSolo = viewModel::setTrackSolo,
                onPan = viewModel::setTrackPan,
                onOutputRoute = viewModel::setTrackOutputRoute,
                modifier = contentModifier,
            )
            MainScreen.SETTINGS -> SettingsScreen(
                settings = settings,
                outputs = outputs,
                selectedOutputId = player.selectedOutputDeviceId,
                diagnosticsProvider = viewModel::diagnostics,
                onLiveMode = viewModel::setLiveMode,
                onPerformanceLock = viewModel::setPerformanceLock,
                onOutput = viewModel::setOutputDevice,
                modifier = contentModifier,
            )
        }
    }

    if (cloudBrowserOpen) {
        CloudBrowserDialog(
            onDismiss = { cloudBrowserOpen = false },
            onImportZip = viewModel::importZip,
            onImportFiles = viewModel::importFiles,
        )
    }

    if (importState.running) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(stringResource(R.string.importing)) },
            text = { CircularProgressIndicator() },
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
                    Text(
                        stringResource(
                            R.string.import_summary,
                            result.tracksDetected,
                            if (result.clickDetected) stringResource(R.string.yes) else stringResource(R.string.no),
                            if (result.guideDetected) stringResource(R.string.yes) else stringResource(R.string.no),
                        ),
                    )
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
        AlertDialog(
            onDismissRequest = viewModel::dismissImportState,
            title = { Text(stringResource(R.string.import_failed)) },
            text = { Text(error, color = MaterialTheme.colorScheme.error) },
            confirmButton = { TextButton(onClick = viewModel::dismissImportState) { Text(stringResource(R.string.close)) } },
        )
    }
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

