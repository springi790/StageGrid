package dev.stagegrid.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.stagegrid.R
import dev.stagegrid.model.SongEntity
import dev.stagegrid.ui.components.StageGridEmptyState
import dev.stagegrid.ui.components.StageGridMetric
import dev.stagegrid.ui.components.StageGridPanel
import dev.stagegrid.ui.components.StageGridPill
import dev.stagegrid.ui.components.StageGridScreenHeader
import dev.stagegrid.ui.theme.StageGridColors
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
    onDeleteSong: (SongEntity) -> Unit,
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

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StageGridScreenHeader(
                kicker = stringResource(R.string.brand_library_kicker),
                title = stringResource(R.string.library),
                subtitle = stringResource(R.string.local_multitrack_player),
                badge = stringResource(R.string.library_song_count, songs.size),
            )
        }

        item {
            StageGridPanel(accent = MaterialTheme.colorScheme.primary) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.library_import_primary), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.library_sources), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StageGridPill("WAV · ZIP", MaterialTheme.colorScheme.primary)
                    }
                    Button(onClick = onImportZip, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.import_zip), fontWeight = FontWeight.Bold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = onImportFolder, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.import_folder))
                        }
                        OutlinedButton(onClick = onImportFiles, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.import_files))
                        }
                    }
                    TextButton(onClick = onOpenCloud, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cloud_folder_button))
                    }
                }
            }
        }

        item {
            val clearSearchDescription = stringResource(R.string.library_clear_search)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.search_library)) },
                leadingIcon = { Text("⌕", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary) },
                trailingIcon = if (query.isBlank()) null else {
                    {
                        IconButton(
                            onClick = { query = "" },
                            modifier = Modifier.semantics { contentDescription = clearSearchDescription },
                        ) {
                            Text("×", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                },
            )
        }

        if (visible.isEmpty()) {
            item {
                if (songs.isEmpty()) {
                    StageGridEmptyState(
                        title = stringResource(R.string.library_empty_title),
                        description = stringResource(R.string.library_import_hint),
                        actionLabel = stringResource(R.string.import_zip),
                        onAction = onImportZip,
                    )
                } else {
                    StageGridEmptyState(
                        title = stringResource(R.string.library_no_matches_title),
                        description = stringResource(R.string.library_no_matches_desc),
                        actionLabel = stringResource(R.string.library_clear_search),
                        onAction = { query = "" },
                    )
                }
            }
        } else {
            items(visible, key = { it.id }) { song ->
                SongCard(
                    song = song,
                    onLoadSong = onLoadSong,
                    onEditSong = onEditSong,
                    onDeleteSong = onDeleteSong,
                )
            }
        }
    }
}

@Composable
private fun SongCard(
    song: SongEntity,
    onLoadSong: (String) -> Unit,
    onEditSong: (SongEntity) -> Unit,
    onDeleteSong: (SongEntity) -> Unit,
) {
    val artwork = remember(song.artworkPath) {
        song.artworkPath?.let { path ->
            runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        }
    }

    StageGridPanel {
        Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                artwork?.let { image ->
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        modifier = Modifier.size(68.dp).clip(RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Crop,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        song.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        song.artist.ifBlank { "—" },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StageGridPill(stringResource(R.string.brand_ready), StageGridColors.Mint)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                StageGridMetric(
                    label = "BPM",
                    value = song.bpm?.let { if (it % 1.0 == 0.0) it.toInt().toString() else String.format(Locale.ROOT, "%.1f", it) } ?: "—",
                    modifier = Modifier.weight(1f),
                )
                StageGridMetric(
                    label = stringResource(R.string.key),
                    value = song.musicalKey ?: "—",
                    modifier = Modifier.weight(1f),
                    accent = MaterialTheme.colorScheme.tertiary,
                )
                StageGridMetric(
                    label = "TIME",
                    value = formatDuration(song.durationMs),
                    modifier = Modifier.weight(1f),
                    accent = MaterialTheme.colorScheme.secondary,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { onLoadSong(song.id) }, modifier = Modifier.weight(1.25f)) {
                    Text(stringResource(R.string.load), fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = { onEditSong(song) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.edit))
                }
                TextButton(
                    onClick = { onDeleteSong(song) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.delete_song))
                }
            }
        }
    }
}

internal fun formatDuration(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0) / 1000)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
