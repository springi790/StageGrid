package dev.stagegrid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.stagegrid.R
import dev.stagegrid.audio.PlayerState
import dev.stagegrid.model.StereoRoute
import dev.stagegrid.model.TrackType

@Composable
fun MixerScreen(
    state: PlayerState,
    onVolume: (Int, Float) -> Unit,
    onMute: (Int, Boolean) -> Unit,
    onSolo: (Int, Boolean) -> Unit,
    onPan: (Int, Float) -> Unit,
    onOutputRoute: (Int, StereoRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.mixer), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (state.song == null) {
            Text(stringResource(R.string.no_song_loaded), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 12.dp)) {
            itemsIndexed(state.tracks, key = { _, track -> track.id }) { index, track ->
                val isImportedClickReference = track.type == TrackType.CLICK.name
                val route = StereoRoute.fromStorage(track.outputRoute)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text(track.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (isImportedClickReference) stringResource(R.string.click_reference_track) else track.type,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (!isImportedClickReference) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected = track.muted, onClick = { onMute(index, !track.muted) }, label = { Text(stringResource(R.string.mute)) })
                                    FilterChip(selected = track.solo, onClick = { onSolo(index, !track.solo) }, label = { Text(stringResource(R.string.solo)) })
                                }
                            }
                        }

                        if (isImportedClickReference) {
                            Text(stringResource(R.string.click_reference_explanation), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text(stringResource(R.string.output_route))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StereoRoute.entries.forEach { option ->
                                    FilterChip(
                                        selected = route == option,
                                        onClick = { onOutputRoute(index, option) },
                                        label = { Text(routeLabel(option)) },
                                    )
                                }
                            }
                            Text(stringResource(R.string.volume_percent, (track.volume * 100).toInt()))
                            Slider(value = track.volume.coerceIn(0f, 1.25f), onValueChange = { onVolume(index, it) }, valueRange = 0f..1.25f)
                            if (route == StereoRoute.BOTH) {
                                Text(stringResource(R.string.pan_value, (track.pan * 100).toInt()))
                                Slider(value = track.pan.coerceIn(-1f, 1f), onValueChange = { onPan(index, it) }, valueRange = -1f..1f)
                            } else {
                                Text(stringResource(R.string.pan_disabled_for_mono_route), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun routeLabel(route: StereoRoute): String = when (route) {
    StereoRoute.LEFT -> stringResource(R.string.route_left)
    StereoRoute.BOTH -> stringResource(R.string.route_both)
    StereoRoute.RIGHT -> stringResource(R.string.route_right)
}
