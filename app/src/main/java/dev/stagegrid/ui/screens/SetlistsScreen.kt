package dev.stagegrid.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.stagegrid.R
import dev.stagegrid.model.SetlistBundle
import dev.stagegrid.model.SetlistEntity
import dev.stagegrid.model.SongEntity
import dev.stagegrid.ui.components.StageGridPanel
import dev.stagegrid.ui.components.StageGridPill
import dev.stagegrid.ui.components.StageGridScreenHeader
import dev.stagegrid.ui.components.formatStageClock
import java.util.Locale

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
    onStartLive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var newName by remember { mutableStateOf("") }
    var addQuery by remember { mutableStateOf("") }

    val bundle = selected
    val candidates = remember(songs, bundle, addQuery) {
        val alreadyIn = bundle?.songs?.map { it.id }?.toSet().orEmpty()
        val normalized = addQuery.trim().lowercase(Locale.getDefault())
        songs.asSequence()
            .filter { it.id !in alreadyIn }
            .filter { song ->
                normalized.isBlank() ||
                    song.title.lowercase(Locale.getDefault()).contains(normalized) ||
                    song.artist.lowercase(Locale.getDefault()).contains(normalized)
            }
            .toList()
    }
    val totalDurationMs = bundle?.songs?.sumOf { it.durationMs } ?: 0L

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 14.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StageGridScreenHeader(
                kicker = stringResource(R.string.setlists_kicker),
                title = stringResource(R.string.setlists),
                subtitle = stringResource(R.string.setlists_subtitle),
                badge = stringResource(R.string.setlists_count, setlists.size),
            )
        }

        item {
            StageGridPanel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(stringResource(R.string.setlist_name)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    Button(
                        onClick = { onCreate(newName); newName = "" },
                        enabled = newName.isNotBlank(),
                        modifier = Modifier.heightIn(min = 56.dp),
                    ) { Text(stringResource(R.string.create)) }
                }
            }
        }

        if (setlists.isNotEmpty()) {
            item {
                // FilterChip instead of OutlinedButton: the previous row gave no indication of
                // which setlist was actually open.
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    items(setlists, key = { it.id }) { setlist ->
                        FilterChip(
                            selected = bundle?.setlist?.id == setlist.id,
                            onClick = { onSelect(setlist.id) },
                            label = { Text(setlist.name, maxLines = 1) },
                        )
                    }
                }
            }
        }

        if (bundle == null) {
            item {
                StageGridPanel {
                    Text(
                        stringResource(if (setlists.isEmpty()) R.string.setlists_empty_hint else R.string.select_setlist),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@LazyColumn
        }

        item {
            StageGridPanel(accent = MaterialTheme.colorScheme.primary) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            bundle.setlist.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        StageGridPill(
                            stringResource(R.string.setlist_summary, bundle.songs.size, formatSetlistTotal(totalDurationMs)),
                            MaterialTheme.colorScheme.primary,
                        )
                    }
                    Button(
                        onClick = onStartLive,
                        enabled = bundle.songs.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                    ) {
                        Text(stringResource(R.string.setlist_live_start), fontWeight = FontWeight.Black)
                    }
                    Text(
                        if (bundle.songs.isEmpty()) stringResource(R.string.setlist_live_empty) else stringResource(R.string.setlist_live_transport_note),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            // The add-song row used to be `songs.filter { ... }.take(4)`, so once four candidates
            // were listed the rest of the library was unreachable from this screen. Every remaining
            // song is now searchable.
            StageGridPanel {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.add_song), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = addQuery,
                        onValueChange = { addQuery = it },
                        label = { Text(stringResource(R.string.setlist_add_search)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    if (candidates.isEmpty()) {
                        Text(
                            stringResource(R.string.setlist_add_no_candidates),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            candidates.take(SETLIST_CANDIDATE_PAGE).forEach { song ->
                                OutlinedButton(
                                    onClick = { onAddSong(song.id) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                ) {
                                    Text(
                                        song.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text("+", fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            if (candidates.size > SETLIST_CANDIDATE_PAGE) {
                                Text(
                                    stringResource(R.string.setlist_add_more, candidates.size - SETLIST_CANDIDATE_PAGE),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (bundle.songs.isEmpty()) {
            item {
                Text(stringResource(R.string.empty_setlist), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            itemsIndexedSetlistSongs(bundle.songs, onLoadSong, onRemoveSong)
        }
    }
}

private const val SETLIST_CANDIDATE_PAGE = 8

/** A whole set runs past an hour, where "83:20" stops being readable. */
private fun formatSetlistTotal(ms: Long): String {
    val totalMinutes = ms.coerceAtLeast(0L) / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) "%d:%02d h".format(hours, minutes) else "%d min".format(minutes)
}

private fun LazyListScope.itemsIndexedSetlistSongs(
    songs: List<SongEntity>,
    onLoadSong: (String) -> Unit,
    onRemoveSong: (String) -> Unit,
) {
    itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
        val position = index + 1
        StageGridPanel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    position.toString(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary,
                )
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { onLoadSong(song.id) },
                ) {
                    Text(song.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            song.artist.takeIf { it.isNotBlank() },
                            song.musicalKey?.takeIf { it.isNotBlank() },
                            formatStageClock(song.durationMs),
                        ).joinToString("  ·  "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = { onRemoveSong(song.id) }) { Text(stringResource(R.string.remove)) }
            }
        }
    }
}
