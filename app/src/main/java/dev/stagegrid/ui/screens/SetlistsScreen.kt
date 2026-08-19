package dev.stagegrid.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import dev.stagegrid.model.SetlistBundle
import dev.stagegrid.model.SetlistEntity
import dev.stagegrid.model.SongEntity

@Composable
fun SetlistsScreen(
    setlists: List<SetlistEntity>,
    songs: List<SongEntity>,
    selected: SetlistBundle?,
    onCreate: (String) -> Unit,
    onSelect: (String) -> Unit,
    onAddSong: (String) -> Unit,
    onRemoveSong: (String) -> Unit,
    onLoadSong: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var newName by remember { mutableStateOf("") }
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.setlists), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text(stringResource(R.string.setlist_name)) }, modifier = Modifier.weight(1f), singleLine = true)
            Button(onClick = { onCreate(newName); newName = "" }) { Text(stringResource(R.string.create)) }
        }
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            items(setlists, key = { it.id }) { setlist ->
                OutlinedButton(onClick = { onSelect(setlist.id) }) { Text(setlist.name) }
            }
        }
        Spacer(Modifier.height(12.dp))
        val bundle = selected
        if (bundle == null) {
            Text(stringResource(R.string.select_setlist), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Text(bundle.setlist.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.add_song), style = MaterialTheme.typography.labelLarge)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            songs.filter { candidate -> bundle.songs.none { it.id == candidate.id } }.take(4).forEach { song ->
                OutlinedButton(onClick = { onAddSong(song.id) }) { Text(song.title, maxLines = 1) }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (bundle.songs.isEmpty()) {
            Text(stringResource(R.string.empty_setlist), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(bundle.songs, key = { it.id }) { song ->
                    Card(Modifier.fillMaxWidth().clickable { onLoadSong(song.id) }) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(song.title, fontWeight = FontWeight.SemiBold)
                                Text(song.musicalKey.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            OutlinedButton(onClick = { onRemoveSong(song.id) }) { Text(stringResource(R.string.remove)) }
                        }
                    }
                }
            }
        }
    }
}
