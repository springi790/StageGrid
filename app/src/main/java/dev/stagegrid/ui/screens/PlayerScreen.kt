package dev.stagegrid.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.stagegrid.R
import dev.stagegrid.audio.ClickSubdivision
import dev.stagegrid.audio.EngineState
import dev.stagegrid.audio.PlayerState
import dev.stagegrid.model.SectionEntity
import dev.stagegrid.model.StereoRoute
import dev.stagegrid.music.MusicalGrid

@Composable
fun PlayerScreen(
    state: PlayerState,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onStopAll: () -> Unit,
    onSeek: (Long) -> Unit,
    onLoop: () -> Unit,
    onExitLoop: () -> Unit,
    onSection: (SectionEntity) -> Unit,
    onEditSections: () -> Unit,
    onMaster: (Float) -> Unit,
    onClick: (Boolean) -> Unit,
    onGuide: (Boolean) -> Unit,
    onClickSubdivision: (ClickSubdivision) -> Unit,
    onClickRoute: (StereoRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    val song = state.song
    if (song == null) {
        Column(modifier.fillMaxSize().padding(24.dp)) {
            Text(stringResource(R.string.player), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.no_song_loaded), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val grid = remember(song.bpm, song.timeSignature, song.gridOffsetMs) {
        MusicalGrid.from(song.bpm, song.timeSignature, song.gridOffsetMs)
    }
    val musicalPosition = grid?.positionAt(state.positionMs)

    var dragging by remember { mutableStateOf(false) }
    var seekFraction by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.positionMs, state.durationMs, dragging) {
        if (!dragging) seekFraction = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
    }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(song.artist.ifBlank { stringResource(R.string.unknown_artist) }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(
                        R.string.grid_status,
                        song.bpm?.let { "%.2f".format(it) } ?: "—",
                        song.gridOffsetMs,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                musicalPosition?.let {
                    Text(
                        stringResource(R.string.bar_beat_value, it.bar, it.beat),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (state.engineState == EngineState.PLAYING) Text(stringResource(R.string.live), color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatPlayerDuration(if (dragging) (seekFraction * state.durationMs).toLong() else state.positionMs))
                    Text(stringResource(R.string.remaining_value, formatPlayerDuration((state.durationMs - state.positionMs).coerceAtLeast(0))))
                }
                Slider(
                    value = seekFraction.coerceIn(0f, 1f),
                    onValueChange = { dragging = true; seekFraction = it },
                    onValueChangeFinished = {
                        dragging = false
                        onSeek((seekFraction * state.durationMs).toLong())
                    },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.section_value, state.currentSection?.name ?: stringResource(R.string.none)))
                    Text(stringResource(R.string.next_section_value, state.nextSection?.name ?: stringResource(R.string.none)))
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.sections.forEach { section ->
                val selected = state.currentSection?.id == section.id
                val queued = state.queuedSectionId == section.id
                FilterChip(
                    selected = selected || queued,
                    onClick = { onSection(section) },
                    label = { Text(if (queued) stringResource(R.string.queued_section_label, section.name) else section.name) },
                )
            }
        }

        OutlinedButton(
            onClick = onEditSections,
            enabled = !state.isPlaying && grid != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.edit_sections))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onPlayPause, modifier = Modifier.weight(1.5f)) {
                Text(if (state.isPlaying) stringResource(R.string.pause) else stringResource(R.string.play))
            }
            OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.stop)) }
            OutlinedButton(onClick = if (state.loopSectionId == null) onLoop else onExitLoop, modifier = Modifier.weight(1f)) {
                Text(if (state.loopSectionId == null) stringResource(R.string.loop) else stringResource(R.string.exit_loop))
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(selected = state.clickEnabled, onClick = { onClick(!state.clickEnabled) }, label = { Text(stringResource(R.string.native_click)) }, modifier = Modifier.weight(1f))
                    FilterChip(selected = state.guideEnabled, onClick = { onGuide(!state.guideEnabled) }, label = { Text(stringResource(R.string.guide)) }, modifier = Modifier.weight(1f))
                }

                Text(stringResource(R.string.click_subdivision), fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ClickSubdivision.entries.forEach { subdivision ->
                        FilterChip(
                            selected = state.clickSubdivision == subdivision,
                            onClick = { onClickSubdivision(subdivision) },
                            label = { Text(subdivision.label) },
                        )
                    }
                }

                Text(stringResource(R.string.click_output), fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StereoRoute.entries.forEach { route ->
                        FilterChip(
                            selected = state.clickRoute == route,
                            onClick = { onClickRoute(route) },
                            label = { Text(routeLabel(route)) },
                        )
                    }
                }
            }
        }

        Text(stringResource(R.string.master_value, (state.masterVolume * 100).toInt()))
        Slider(value = state.masterVolume.coerceIn(0f, 1f), onValueChange = onMaster)
        Button(onClick = onStopAll, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.stop_all), fontWeight = FontWeight.ExtraBold) }

        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun routeLabel(route: StereoRoute): String = when (route) {
    StereoRoute.LEFT -> stringResource(R.string.route_left)
    StereoRoute.BOTH -> stringResource(R.string.route_both)
    StereoRoute.RIGHT -> stringResource(R.string.route_right)
}

private fun formatPlayerDuration(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0) / 1000).toInt()
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
