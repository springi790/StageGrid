package dev.stagegrid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.stagegrid.R
import dev.stagegrid.model.SongEntity
import java.util.Locale

@Composable
fun LibraryScreen(
    songs: List<SongEntity>,
    onImportZip: () -> Unit,
    onImportFolder: () -> Unit,
    onImportFiles: () -> Unit,
    onOpenCloud: () -> Unit,
    onLoadSong: (String) -> Unit,
    onEditSong: (SongEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val normalized = query.trim().lowercase(Locale.getDefault())
    val visible = remember(songs, normalized) {
        if (normalized.isBlank()) songs else songs.filter {
            it.title.lowercase(Locale.getDefault()).contains(normalized) ||
                it.artist.lowercase(Locale.getDefault()).contains(normalized) ||
                it.musicalKey.orEmpty().lowercase(Locale.getDefault()).contains(normalized)
        }
    }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.library), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.local_multitrack_player), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onImportZip, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.import_zip)) }
            OutlinedButton(onClick = onImportFolder, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.import_folder)) }
            OutlinedButton(onClick = onImportFiles, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.import_files)) }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenCloud, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.cloud_folder_button))
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.search_library)) },
        )
        Spacer(Modifier.height(12.dp))
        if (visible.isEmpty()) {
            Text(stringResource(R.string.no_songs), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(visible, key = { it.id }) { song ->
                    SongCard(song = song, onLoadSong = onLoadSong, onEditSong = onEditSong)
                }
            }
        }
    }
}

@Composable
private fun SongCard(song: SongEntity, onLoadSong: (String) -> Unit, onEditSong: (SongEntity) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(song.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (song.artist.isNotBlank()) Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(song.bpm?.let { stringResource(R.string.bpm_value, it) } ?: stringResource(R.string.bpm_unknown))
                Text(song.musicalKey?.let { stringResource(R.string.key_value, it) } ?: stringResource(R.string.key_unknown))
                Text(stringResource(R.string.duration_value, formatDuration(song.durationMs)))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onLoadSong(song.id) }) { Text(stringResource(R.string.load)) }
                OutlinedButton(onClick = { onEditSong(song) }) { Text(stringResource(R.string.edit)) }
            }
        }
    }
}

internal fun formatDuration(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0) / 1000)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
