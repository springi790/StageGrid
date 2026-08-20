package dev.stagegrid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.stagegrid.R
import dev.stagegrid.model.SectionEntity
import dev.stagegrid.model.SongEntity
import dev.stagegrid.music.GridSnap
import dev.stagegrid.music.MusicalGrid
import dev.stagegrid.music.MusicalPosition
import dev.stagegrid.ui.components.SongWaveformOverview
import java.util.UUID
import kotlin.math.roundToLong

@Composable
fun SectionEditorDialog(
    song: SongEntity,
    sections: List<SectionEntity>,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    onSave: (SectionEntity) -> Unit,
    onDelete: (SectionEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val grid = remember(song.bpm, song.timeSignature, song.gridOffsetMs) {
        MusicalGrid.from(song.bpm, song.timeSignature, song.gridOffsetMs)
    }
    var selectedId by remember { mutableStateOf(sections.firstOrNull()?.id) }
    var snap by remember { mutableStateOf(GridSnap.BAR) }
    var showPrecise by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var startBar by remember { mutableStateOf("1") }
    var startBeat by remember { mutableStateOf("1") }
    var endBar by remember { mutableStateOf("1") }
    var endBeat by remember { mutableStateOf("1") }
    var validationError by remember { mutableStateOf(false) }

    LaunchedEffect(sections) {
        if (selectedId != null && sections.none { it.id == selectedId }) {
            selectedId = sections.firstOrNull()?.id
        } else if (selectedId == null) {
            selectedId = sections.firstOrNull()?.id
        }
    }

    val selected = sections.firstOrNull { it.id == selectedId }

    LaunchedEffect(selected?.id, grid) {
        if (selected != null && grid != null) {
            val start = grid.positionAt(selected.startMs)
            val end = grid.positionAt(selected.endMs)
            name = selected.name
            startBar = start.bar.toString()
            startBeat = start.beat.toString()
            endBar = end.bar.toString()
            endBeat = end.beat.toString()
            validationError = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.section_editor_simple_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (grid == null) {
                    Text(stringResource(R.string.section_editor_requires_bpm), color = MaterialTheme.colorScheme.error)
                } else {
                    val safeDuration = durationMs.coerceAtLeast(0L)
                    val playhead = grid.positionAt(positionMs.coerceAtLeast(0L))

                    Text(stringResource(R.string.section_editor_simple_help), color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(stringResource(R.string.current_song_position), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                stringResource(R.string.simple_bar_beat_value, playhead.bar, playhead.beat),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    SongWaveformOverview(
                        songId = song.id,
                        positionMs = positionMs,
                        durationMs = safeDuration,
                        sections = sections,
                    )

                    if (isPlaying) {
                        Text(stringResource(R.string.section_editor_playback_warning), color = MaterialTheme.colorScheme.tertiary)
                    }

                    MusicalGridTimeline(
                        sections = sections,
                        grid = grid,
                        durationMs = safeDuration,
                        selectedId = selectedId,
                        onSelect = { selectedId = it },
                    )

                    Text(stringResource(R.string.adjust_changes_to), fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = snap == GridSnap.BAR,
                            onClick = { snap = GridSnap.BAR },
                            label = { Text(stringResource(R.string.whole_bar)) },
                            modifier = Modifier.weight(1f),
                        )
                        FilterChip(
                            selected = snap == GridSnap.BEAT,
                            onClick = { snap = GridSnap.BEAT },
                            label = { Text(stringResource(R.string.single_beat)) },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Button(
                        enabled = !isPlaying && safeDuration > 1L,
                        onClick = {
                            val startMs = grid.snap(positionMs.coerceIn(0L, safeDuration), snap)
                                .coerceIn(0L, safeDuration - 1L)
                            val fourBarsMs = (grid.barDurationMs * 4.0).roundToLong().coerceAtLeast(1L)
                            var endMs = (startMs + fourBarsMs).coerceAtMost(safeDuration)
                            if (endMs <= startMs) {
                                endMs = (startMs + grid.beatDurationMs.roundToLong().coerceAtLeast(1L)).coerceAtMost(safeDuration)
                            }
                            if (endMs <= startMs) endMs = safeDuration
                            val item = SectionEntity(
                                id = UUID.randomUUID().toString(),
                                songId = song.id,
                                name = defaultSectionName(sections.size),
                                startMs = startMs,
                                endMs = endMs,
                                sortOrder = (sections.maxOfOrNull { it.sortOrder } ?: -1) + 1,
                            )
                            onSave(item)
                            selectedId = item.id
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.add_section_here))
                    }

                    if (sections.isEmpty()) {
                        Text(stringResource(R.string.no_sections_editor_simple), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    selected?.let { section ->
                        Text(stringResource(R.string.selected_section), fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text(stringResource(R.string.section_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isPlaying,
                        )

                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                stringResource(R.string.section_intro),
                                stringResource(R.string.section_verse),
                                stringResource(R.string.section_chorus),
                                stringResource(R.string.section_bridge),
                                stringResource(R.string.section_outro),
                            ).forEach { preset ->
                                FilterChip(
                                    selected = name.equals(preset, ignoreCase = true),
                                    onClick = { name = preset },
                                    label = { Text(preset) },
                                    enabled = !isPlaying,
                                )
                            }
                        }

                        val startText = "$startBar.$startBeat"
                        val endText = "$endBar.$endBeat"
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.section_starts_at, startText))
                                Text(stringResource(R.string.section_ends_at, endText))
                            }
                        }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                enabled = !isPlaying,
                                onClick = {
                                    val point = grid.positionAt(grid.snap(positionMs, snap).coerceIn(0L, safeDuration))
                                    startBar = point.bar.toString()
                                    startBeat = point.beat.toString()
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.set_start_here))
                            }
                            OutlinedButton(
                                enabled = !isPlaying,
                                onClick = {
                                    val point = grid.positionAt(grid.snap(positionMs, snap).coerceIn(0L, safeDuration))
                                    endBar = point.bar.toString()
                                    endBeat = point.beat.toString()
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.set_end_here))
                            }
                        }

                        OutlinedButton(onClick = { showPrecise = !showPrecise }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                if (showPrecise) stringResource(R.string.hide_precise_editing)
                                else stringResource(R.string.show_precise_editing),
                            )
                        }

                        if (showPrecise) {
                            Text(stringResource(R.string.precise_editing_help), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            PositionFields(
                                title = stringResource(R.string.start_position),
                                bar = startBar,
                                beat = startBeat,
                                beatsPerBar = grid.signature.beatsPerBar,
                                enabled = !isPlaying,
                                onBar = { startBar = it },
                                onBeat = { startBeat = it },
                            )
                            PositionFields(
                                title = stringResource(R.string.end_position),
                                bar = endBar,
                                beat = endBeat,
                                beatsPerBar = grid.signature.beatsPerBar,
                                enabled = !isPlaying,
                                onBar = { endBar = it },
                                onBeat = { endBeat = it },
                            )
                        }

                        if (validationError) {
                            Text(stringResource(R.string.invalid_section_range), color = MaterialTheme.colorScheme.error)
                        }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !isPlaying,
                                onClick = {
                                    val startPosition = MusicalPosition(
                                        startBar.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                                        startBeat.toIntOrNull()?.coerceIn(1, grid.signature.beatsPerBar) ?: 1,
                                    )
                                    val endPosition = MusicalPosition(
                                        endBar.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                                        endBeat.toIntOrNull()?.coerceIn(1, grid.signature.beatsPerBar) ?: 1,
                                    )
                                    val newStart = grid.msAt(startPosition).coerceIn(0L, safeDuration)
                                    val newEnd = grid.msAt(endPosition).coerceIn(0L, safeDuration)
                                    validationError = newEnd <= newStart
                                    if (!validationError) {
                                        onSave(section.copy(name = name.trim().ifBlank { section.name }, startMs = newStart, endMs = newEnd))
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.save_changes))
                            }
                            OutlinedButton(
                                enabled = !isPlaying,
                                onClick = {
                                    onDelete(section)
                                    selectedId = sections.firstOrNull { it.id != section.id }?.id
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.delete))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun PositionFields(
    title: String,
    bar: String,
    beat: String,
    beatsPerBar: Int,
    enabled: Boolean,
    onBar: (String) -> Unit,
    onBeat: (String) -> Unit,
) {
    Text(title, fontWeight = FontWeight.SemiBold)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = bar,
            onValueChange = { onBar(it.filter(Char::isDigit)) },
            label = { Text(stringResource(R.string.bar)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = beat,
            onValueChange = {
                val value = it.filter(Char::isDigit).toIntOrNull()
                onBeat(if (value == null) "" else value.coerceIn(1, beatsPerBar).toString())
            },
            label = { Text(stringResource(R.string.beat)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MusicalGridTimeline(
    sections: List<SectionEntity>,
    grid: MusicalGrid,
    durationMs: Long,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    val scroll = rememberScrollState()
    val barWidth = 68.dp
    val totalBars = grid.totalBars(durationMs).coerceAtMost(256)

    Text(stringResource(R.string.song_structure), fontWeight = FontWeight.SemiBold)
    Text(stringResource(R.string.tap_section_to_edit), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
        Row {
            repeat(totalBars) { index ->
                Box(
                    modifier = Modifier.width(barWidth).height(32.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text((index + 1).toString(), style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        var cursorMs = 0L
        Row(verticalAlignment = Alignment.CenterVertically) {
            sections.sortedWith(compareBy<SectionEntity> { it.startMs }.thenBy { it.sortOrder }).forEach { section ->
                val gap = (section.startMs - cursorMs).coerceAtLeast(0L)
                if (gap > 0L) {
                    val gapBars = gap / grid.barDurationMs
                    val gapWidth = (gapBars * 68.0).coerceAtMost(272.0).toFloat().dp
                    Spacer(Modifier.width(gapWidth))
                }
                val sectionBars = (section.endMs - section.startMs).coerceAtLeast(1L) / grid.barDurationMs
                val sectionWidth = (sectionBars * 68.0).coerceIn(92.0, 408.0).toFloat().dp
                val isSelected = section.id == selectedId
                Card(
                    modifier = Modifier.width(sectionWidth).height(72.dp).clickable { onSelect(section.id) },
                ) {
                    Column(
                        Modifier
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                            )
                            .padding(8.dp)
                            .fillMaxWidth(),
                    ) {
                        Text(section.name, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(
                            stringResource(
                                R.string.simple_section_range,
                                grid.positionAt(section.startMs).bar,
                                grid.positionAt(section.endMs).bar,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                cursorMs = maxOf(cursorMs, section.endMs)
            }
        }
    }
}

private fun defaultSectionName(index: Int): String = "Section ${index + 1}"
