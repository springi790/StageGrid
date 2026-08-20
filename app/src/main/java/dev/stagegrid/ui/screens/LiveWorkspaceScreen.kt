package dev.stagegrid.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.stagegrid.R
import dev.stagegrid.arrangement.ArrangementNode
import dev.stagegrid.arrangement.ArrangementRuntimeState
import dev.stagegrid.audio.PlayerState
import dev.stagegrid.model.TrackType
import dev.stagegrid.ui.StageGridViewModel
import dev.stagegrid.ui.components.SongWaveformOverview
import kotlin.math.roundToInt

private enum class WorkspaceSheet { QUICK_MIX, ARRANGEMENT, SETLIST }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveWorkspaceScreen(
    state: PlayerState,
    arrangement: ArrangementRuntimeState,
    setlistLive: StageGridViewModel.SetlistLiveUiState,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onStopAll: () -> Unit,
    onSeek: (Long) -> Unit,
    onMaster: (Float) -> Unit,
    onClick: (Boolean) -> Unit,
    onGuide: (Boolean) -> Unit,
    onTrackVolume: (Int, Float) -> Unit,
    onTrackMute: (Int, Boolean) -> Unit,
    onTrackSolo: (Int, Boolean) -> Unit,
    onArrangementStart: () -> Unit,
    onArrangementStop: () -> Unit,
    onArrangementExitLoop: () -> Unit,
    onArrangementNode: (String) -> Unit,
    onArrangementMove: (String, Int) -> Unit,
    onArrangementRepeat: (String, Int) -> Unit,
    onArrangementPreRoll: (String, Int) -> Unit,
    onSetlistPrevious: () -> Unit,
    onSetlistNext: () -> Unit,
    onExitSetlistLive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var sheet by remember { mutableStateOf<WorkspaceSheet?>(null) }

    if (state.song == null) {
        Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.live_workspace), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.workspace_no_song), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val tablet = maxWidth >= 720.dp
        if (tablet) {
            Row(
                Modifier.fillMaxSize().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PerformanceSurface(
                    state = state,
                    arrangement = arrangement,
                    setlistLive = setlistLive,
                    compact = false,
                    onPlayPause = onPlayPause,
                    onStop = onStop,
                    onStopAll = onStopAll,
                    onSeek = onSeek,
                    onMaster = onMaster,
                    onClick = onClick,
                    onGuide = onGuide,
                    onArrangementStart = onArrangementStart,
                    onArrangementStop = onArrangementStop,
                    onArrangementExitLoop = onArrangementExitLoop,
                    onArrangementNode = onArrangementNode,
                    onOpenQuickMix = { sheet = WorkspaceSheet.QUICK_MIX },
                    onOpenArrangement = { sheet = WorkspaceSheet.ARRANGEMENT },
                    onOpenSetlist = { sheet = WorkspaceSheet.SETLIST },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                Column(
                    Modifier.width(340.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    QuickMixPanel(
                        state = state,
                        onTrackVolume = onTrackVolume,
                        onTrackMute = onTrackMute,
                        onTrackSolo = onTrackSolo,
                        modifier = Modifier.weight(1f),
                    )
                    SetlistPanel(
                        state = setlistLive,
                        onPrevious = onSetlistPrevious,
                        onNext = onSetlistNext,
                        onExit = onExitSetlistLive,
                    )
                }
            }
        } else {
            PerformanceSurface(
                state = state,
                arrangement = arrangement,
                setlistLive = setlistLive,
                compact = true,
                onPlayPause = onPlayPause,
                onStop = onStop,
                onStopAll = onStopAll,
                onSeek = onSeek,
                onMaster = onMaster,
                onClick = onClick,
                onGuide = onGuide,
                onArrangementStart = onArrangementStart,
                onArrangementStop = onArrangementStop,
                onArrangementExitLoop = onArrangementExitLoop,
                onArrangementNode = onArrangementNode,
                onOpenQuickMix = { sheet = WorkspaceSheet.QUICK_MIX },
                onOpenArrangement = { sheet = WorkspaceSheet.ARRANGEMENT },
                onOpenSetlist = { sheet = WorkspaceSheet.SETLIST },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    sheet?.let { openSheet ->
        ModalBottomSheet(onDismissRequest = { sheet = null }) {
            when (openSheet) {
                WorkspaceSheet.QUICK_MIX -> QuickMixPanel(
                    state = state,
                    onTrackVolume = onTrackVolume,
                    onTrackMute = onTrackMute,
                    onTrackSolo = onTrackSolo,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(horizontal = 12.dp),
                )
                WorkspaceSheet.ARRANGEMENT -> ArrangementEditor(
                    arrangement = arrangement,
                    onMove = onArrangementMove,
                    onRepeat = onArrangementRepeat,
                    onPreRoll = onArrangementPreRoll,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(horizontal = 12.dp),
                )
                WorkspaceSheet.SETLIST -> SetlistPanel(
                    state = setlistLive,
                    onPrevious = onSetlistPrevious,
                    onNext = onSetlistNext,
                    onExit = onExitSetlistLive,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun PerformanceSurface(
    state: PlayerState,
    arrangement: ArrangementRuntimeState,
    setlistLive: StageGridViewModel.SetlistLiveUiState,
    compact: Boolean,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onStopAll: () -> Unit,
    onSeek: (Long) -> Unit,
    onMaster: (Float) -> Unit,
    onClick: (Boolean) -> Unit,
    onGuide: (Boolean) -> Unit,
    onArrangementStart: () -> Unit,
    onArrangementStop: () -> Unit,
    onArrangementExitLoop: () -> Unit,
    onArrangementNode: (String) -> Unit,
    onOpenQuickMix: () -> Unit,
    onOpenArrangement: () -> Unit,
    onOpenSetlist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val song = state.song ?: return
    val nodes = arrangement.graph?.nodes.orEmpty()
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(if (compact) 12.dp else 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (song.artist.isNotBlank()) {
                    Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text("${formatWorkspaceTime(state.positionMs)} / ${formatWorkspaceTime(state.durationMs)}", fontWeight = FontWeight.SemiBold)
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusChip(state.engineState.name, state.isPlaying)
            StatusChip(stringResource(R.string.workspace_android_channels, state.outputChannelCount), state.outputChannelCount > 2)
            if (arrangement.active) StatusChip(stringResource(R.string.workspace_arrangement), true)
            arrangement.queuedNode?.let { StatusChip("${stringResource(R.string.workspace_queued)} → ${it.label}", true) }
            if (state.loopSectionId != null) StatusChip("LOOP", true)
            if (state.crossfadeInProgress) StatusChip(stringResource(R.string.workspace_crossfade), true)
            if (setlistLive.nextReady && state.preloadedSongId != null) StatusChip(stringResource(R.string.workspace_preloaded), true)
        }

        Card(Modifier.fillMaxWidth()) {
            SongWaveformOverview(
                songId = song.id,
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                sections = state.sections,
                onSeek = if (state.crossfadeInProgress || state.isCountingIn) null else onSeek,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.workspace_now), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(arrangement.activeNode?.label ?: state.currentSection?.name ?: "—", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (arrangement.active && arrangement.iteration > 1) {
                    Text(stringResource(R.string.workspace_iteration, arrangement.iteration), style = MaterialTheme.typography.labelMedium)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.workspace_next), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(arrangement.queuedNode?.label ?: state.nextSection?.name ?: "—", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }

        Text(stringResource(R.string.workspace_sections), fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (nodes.isNotEmpty()) {
                items(nodes, key = { it.id }) { node ->
                    val active = arrangement.activeNodeId == node.id
                    val queued = arrangement.queuedNodeId == node.id
                    FilterChip(
                        selected = active || queued,
                        onClick = { onArrangementNode(node.id) },
                        enabled = !state.crossfadeInProgress && !state.isCountingIn,
                        label = {
                            Text(
                                when {
                                    node.repeatCount < 0 -> "${node.label} · ${stringResource(R.string.arrangement_until_continue)}"
                                    node.repeatCount > 1 -> "${node.label} · ${stringResource(R.string.arrangement_times_short, node.repeatCount)}"
                                    else -> node.label
                                },
                            )
                        },
                    )
                }
            } else {
                items(state.sections, key = { it.id }) { section ->
                    StatusChip(section.name, state.currentSection?.id == section.id || state.queuedSectionId == section.id)
                }
            }
        }
        Text(stringResource(R.string.workspace_live_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.workspace_transport), fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onPlayPause,
                        enabled = !state.crossfadeInProgress,
                        modifier = Modifier.weight(1.4f),
                    ) {
                        Text(if (state.isPlaying) stringResource(R.string.workspace_pause) else stringResource(R.string.workspace_play), fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(onClick = onStop, enabled = !state.crossfadeInProgress, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.workspace_stop))
                    }
                    Button(
                        onClick = onStopAll,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text(stringResource(R.string.workspace_stop_all))
                    }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = state.clickEnabled, onClick = { onClick(!state.clickEnabled) }, label = { Text(stringResource(R.string.workspace_click)) })
                    FilterChip(selected = state.guideEnabled, onClick = { onGuide(!state.guideEnabled) }, label = { Text(stringResource(R.string.workspace_guide)) })
                    if (!arrangement.active) {
                        OutlinedButton(onClick = onArrangementStart) { Text(stringResource(R.string.workspace_arrangement_start)) }
                    } else {
                        OutlinedButton(onClick = onArrangementStop) { Text(stringResource(R.string.workspace_arrangement_stop)) }
                        if (arrangement.activeNode?.infinite == true) {
                            Button(onClick = onArrangementExitLoop) { Text(stringResource(R.string.workspace_exit_loop)) }
                        }
                    }
                }
                Text(stringResource(R.string.workspace_master, (state.masterVolume * 100).roundToInt()))
                Slider(value = state.masterVolume.coerceIn(0f, 1.25f), onValueChange = onMaster, valueRange = 0f..1.25f)
            }
        }

        if (compact) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenQuickMix, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.workspace_quick_mix)) }
                OutlinedButton(onClick = onOpenArrangement, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.workspace_edit_arrangement)) }
                OutlinedButton(onClick = onOpenSetlist, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.workspace_setlist)) }
            }
        } else {
            OutlinedButton(onClick = onOpenArrangement, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.workspace_edit_arrangement))
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, selected: Boolean) {
    FilterChip(selected = selected, onClick = {}, label = { Text(label) })
}

@Composable
private fun QuickMixPanel(
    state: PlayerState,
    onTrackVolume: (Int, Float) -> Unit,
    onTrackMute: (Int, Boolean) -> Unit,
    onTrackSolo: (Int, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visibleTracks = state.tracks.mapIndexed { index, track -> index to track }
        .filter { (_, track) -> TrackType.fromStorage(track.type) != TrackType.CLICK }
    Card(modifier) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.workspace_quick_mix), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visibleTracks, key = { it.second.id }) { (index, track) ->
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(track.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            FilterChip(selected = track.muted, onClick = { onTrackMute(index, !track.muted) }, label = { Text(stringResource(R.string.workspace_mute)) })
                            FilterChip(selected = track.solo, onClick = { onTrackSolo(index, !track.solo) }, label = { Text(stringResource(R.string.workspace_solo)) })
                        }
                        Slider(value = track.volume.coerceIn(0f, 1.25f), onValueChange = { onTrackVolume(index, it) }, valueRange = 0f..1.25f)
                    }
                }
            }
        }
    }
}

@Composable
private fun ArrangementEditor(
    arrangement: ArrangementRuntimeState,
    onMove: (String, Int) -> Unit,
    onRepeat: (String, Int) -> Unit,
    onPreRoll: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val nodes = arrangement.graph?.nodes.orEmpty()
    Card(modifier) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.arrangement_simple_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.arrangement_simple_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (nodes.isEmpty()) {
                Text(
                    stringResource(R.string.arrangement_no_sections),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(nodes, key = { _, node -> node.id }) { index, node ->
                        ArrangementNodeEditor(
                            position = index + 1,
                            node = node,
                            canMoveBefore = index > 0,
                            canMoveAfter = index < nodes.lastIndex,
                            onMove = onMove,
                            onRepeat = onRepeat,
                            onPreRoll = onPreRoll,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArrangementNodeEditor(
    position: Int,
    node: ArrangementNode,
    canMoveBefore: Boolean,
    canMoveAfter: Boolean,
    onMove: (String, Int) -> Unit,
    onRepeat: (String, Int) -> Unit,
    onPreRoll: (String, Int) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "$position.",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f)) {
                    Text(node.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            node.repeatCount < 0 -> stringResource(R.string.arrangement_summary_until_continue)
                            node.repeatCount == 1 -> stringResource(R.string.arrangement_summary_once)
                            else -> stringResource(R.string.arrangement_summary_times, node.repeatCount)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(stringResource(R.string.arrangement_order_label), fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onMove(node.id, -1) },
                    enabled = canMoveBefore,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.arrangement_move_up)) }
                OutlinedButton(
                    onClick = { onMove(node.id, 1) },
                    enabled = canMoveAfter,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.arrangement_move_down)) }
            }

            Text(stringResource(R.string.arrangement_repeat_label), fontWeight = FontWeight.SemiBold)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(1, 2, 4, -1).forEach { repeat ->
                    FilterChip(
                        selected = node.repeatCount == repeat,
                        onClick = { onRepeat(node.id, repeat) },
                        label = {
                            Text(
                                when (repeat) {
                                    1 -> stringResource(R.string.arrangement_once)
                                    2 -> stringResource(R.string.arrangement_twice)
                                    4 -> stringResource(R.string.arrangement_four_times)
                                    else -> stringResource(R.string.arrangement_until_continue)
                                },
                            )
                        },
                    )
                }
            }
            if (node.repeatCount < 0) {
                Text(
                    stringResource(R.string.arrangement_infinite_help),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(stringResource(R.string.arrangement_entry_label), fontWeight = FontWeight.SemiBold)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                (0..2).forEach { bars ->
                    FilterChip(
                        selected = node.preRollBars == bars,
                        onClick = { onPreRoll(node.id, bars) },
                        label = {
                            Text(
                                when (bars) {
                                    0 -> stringResource(R.string.arrangement_no_entry)
                                    1 -> stringResource(R.string.arrangement_one_bar_entry)
                                    else -> stringResource(R.string.arrangement_two_bar_entry)
                                },
                            )
                        },
                    )
                }
            }
            if (node.preRollBars > 0) {
                Text(
                    stringResource(R.string.arrangement_entry_help),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SetlistPanel(
    state: StageGridViewModel.SetlistLiveUiState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.workspace_setlist), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (!state.active) {
                Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(state.setlistName, fontWeight = FontWeight.SemiBold)
                Text("${state.currentIndex + 1} / ${state.totalSongs} · ${state.currentSongTitle.orEmpty()}")
                state.nextSongTitle?.let {
                    Text("${stringResource(R.string.workspace_next)}: $it", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when {
                    state.preloadingNext -> Text("${stringResource(R.string.workspace_preloaded)}…", color = MaterialTheme.colorScheme.primary)
                    state.nextReady -> Text(stringResource(R.string.workspace_preloaded), color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold)
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPrevious, enabled = state.hasPrevious, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.workspace_previous_song))
                    }
                    Button(onClick = onNext, enabled = state.hasNext, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.workspace_next_song))
                    }
                }
                TextButton(onClick = onExit) { Text(stringResource(R.string.close)) }
            }
        }
    }
}

private fun formatWorkspaceTime(ms: Long): String {
    val total = ms.coerceAtLeast(0L) / 1000L
    return "%d:%02d".format(total / 60L, total % 60L)
}
