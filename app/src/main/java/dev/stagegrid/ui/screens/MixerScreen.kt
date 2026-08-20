package dev.stagegrid.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import dev.stagegrid.audio.PlayerState
import dev.stagegrid.model.OutputBus
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
    onOutputBus: (Int, OutputBus) -> Unit,
    onClickRoute: (StereoRoute) -> Unit,
    onClickBus: (OutputBus) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAdvancedRouting by remember { mutableStateOf(false) }
    val availableBuses = OutputBus.availableForChannels(state.outputChannelCount)

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.mixer), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.mixer_simple_explanation), color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (state.song == null) {
            Text(stringResource(R.string.no_song_loaded), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 12.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.quick_output_setup), fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.quick_output_explanation), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            stringResource(R.string.multichannel_routing_summary, state.outputChannelCount, state.requestedOutputChannels),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (state.outputFallback) {
                            Text(stringResource(R.string.multichannel_fallback_warning), color = MaterialTheme.colorScheme.tertiary)
                        }

                        OutlinedButton(
                            onClick = {
                                state.tracks.forEachIndexed { index, track ->
                                    if (TrackType.fromStorage(track.type) != TrackType.CLICK) {
                                        onOutputBus(index, OutputBus.OUT_1_2)
                                        onOutputRoute(index, StereoRoute.BOTH)
                                    }
                                }
                                onClickBus(OutputBus.OUT_1_2)
                                onClickRoute(StereoRoute.BOTH)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.preset_all_stereo))
                        }

                        OutlinedButton(
                            onClick = {
                                state.tracks.forEachIndexed { index, track ->
                                    when (TrackType.fromStorage(track.type)) {
                                        TrackType.CLICK -> Unit
                                        TrackType.GUIDE -> {
                                            onOutputBus(index, OutputBus.OUT_1_2)
                                            onOutputRoute(index, StereoRoute.LEFT)
                                        }
                                        else -> {
                                            onOutputBus(index, OutputBus.OUT_1_2)
                                            onOutputRoute(index, StereoRoute.RIGHT)
                                        }
                                    }
                                }
                                onClickBus(OutputBus.OUT_1_2)
                                onClickRoute(StereoRoute.LEFT)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.preset_stage_split))
                        }

                        Button(
                            enabled = state.outputChannelCount >= 4,
                            onClick = {
                                state.tracks.forEachIndexed { index, track ->
                                    when (TrackType.fromStorage(track.type)) {
                                        TrackType.CLICK -> Unit
                                        TrackType.GUIDE -> {
                                            onOutputBus(index, OutputBus.OUT_3_4)
                                            onOutputRoute(index, StereoRoute.RIGHT)
                                        }
                                        else -> {
                                            onOutputBus(index, OutputBus.OUT_1_2)
                                            onOutputRoute(index, StereoRoute.BOTH)
                                        }
                                    }
                                }
                                onClickBus(OutputBus.OUT_3_4)
                                onClickRoute(StereoRoute.LEFT)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.preset_four_out)) }

                        OutlinedButton(
                            enabled = state.outputChannelCount >= 6,
                            onClick = {
                                state.tracks.forEachIndexed { index, track ->
                                    when (TrackType.fromStorage(track.type)) {
                                        TrackType.CLICK -> Unit
                                        TrackType.GUIDE -> {
                                            onOutputBus(index, OutputBus.OUT_5_6)
                                            onOutputRoute(index, StereoRoute.RIGHT)
                                        }
                                        TrackType.VOCALS -> {
                                            onOutputBus(index, OutputBus.OUT_3_4)
                                            onOutputRoute(index, StereoRoute.BOTH)
                                        }
                                        else -> {
                                            onOutputBus(index, OutputBus.OUT_1_2)
                                            onOutputRoute(index, StereoRoute.BOTH)
                                        }
                                    }
                                }
                                onClickBus(OutputBus.OUT_5_6)
                                onClickRoute(StereoRoute.LEFT)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.preset_six_out)) }

                        OutlinedButton(
                            enabled = state.outputChannelCount >= 8,
                            onClick = {
                                state.tracks.forEachIndexed { index, track ->
                                    when (TrackType.fromStorage(track.type)) {
                                        TrackType.CLICK -> Unit
                                        TrackType.GUIDE -> {
                                            onOutputBus(index, OutputBus.OUT_7_8)
                                            onOutputRoute(index, StereoRoute.RIGHT)
                                        }
                                        TrackType.DRUMS, TrackType.BASS -> {
                                            onOutputBus(index, OutputBus.OUT_1_2)
                                            onOutputRoute(index, StereoRoute.BOTH)
                                        }
                                        TrackType.GUITAR, TrackType.KEYS, TrackType.SYNTH, TrackType.PAD -> {
                                            onOutputBus(index, OutputBus.OUT_3_4)
                                            onOutputRoute(index, StereoRoute.BOTH)
                                        }
                                        TrackType.VOCALS, TrackType.STRINGS, TrackType.PERCUSSION, TrackType.OTHER -> {
                                            onOutputBus(index, OutputBus.OUT_5_6)
                                            onOutputRoute(index, StereoRoute.BOTH)
                                        }
                                    }
                                }
                                onClickBus(OutputBus.OUT_7_8)
                                onClickRoute(StereoRoute.LEFT)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.preset_eight_out)) }

                        OutlinedButton(onClick = { showAdvancedRouting = !showAdvancedRouting }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                if (showAdvancedRouting) stringResource(R.string.hide_manual_routing)
                                else stringResource(R.string.show_manual_routing),
                            )
                        }
                    }
                }
            }

            if (showAdvancedRouting) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.generated_click_routing), fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.generated_click_routing_help), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.output_bus), fontWeight = FontWeight.SemiBold)
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                availableBuses.forEach { bus ->
                                    FilterChip(
                                        selected = state.clickBus == bus,
                                        onClick = { onClickBus(bus) },
                                        label = { Text(busLabel(bus)) },
                                    )
                                }
                            }
                            Text(stringResource(R.string.where_should_this_track_sound), fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StereoRoute.entries.forEach { option ->
                                    FilterChip(
                                        selected = state.clickRoute == option,
                                        onClick = { onClickRoute(option) },
                                        label = { Text(routeFriendlyLabel(option)) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            itemsIndexed(state.tracks, key = { _, track -> track.id }) { index, track ->
                val type = TrackType.fromStorage(track.type)
                val isImportedClickReference = type == TrackType.CLICK
                val route = StereoRoute.fromStorage(track.outputRoute)
                val bus = OutputBus.fromStorage(track.outputBus)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(track.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (isImportedClickReference) stringResource(R.string.click_reference_track) else friendlyTrackType(type),
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
                            Text(stringResource(R.string.volume_percent, (track.volume * 100).toInt()))
                            Slider(value = track.volume.coerceIn(0f, 1.25f), onValueChange = { onVolume(index, it) }, valueRange = 0f..1.25f)

                            if (showAdvancedRouting) {
                                Text(stringResource(R.string.output_bus), fontWeight = FontWeight.SemiBold)
                                Row(
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    availableBuses.forEach { option ->
                                        FilterChip(
                                            selected = bus == option,
                                            onClick = { onOutputBus(index, option) },
                                            label = { Text(busLabel(option)) },
                                        )
                                    }
                                }

                                Text(stringResource(R.string.where_should_this_track_sound), fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    StereoRoute.entries.forEach { option ->
                                        FilterChip(
                                            selected = route == option,
                                            onClick = { onOutputRoute(index, option) },
                                            label = { Text(routeFriendlyLabel(option)) },
                                        )
                                    }
                                }

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
}

@Composable
private fun busLabel(bus: OutputBus): String = stringResource(
    R.string.output_bus_pair,
    bus.firstPhysicalChannel,
    bus.lastPhysicalChannel,
)

@Composable
private fun routeFriendlyLabel(route: StereoRoute): String = when (route) {
    StereoRoute.LEFT -> stringResource(R.string.output_left_simple)
    StereoRoute.BOTH -> stringResource(R.string.output_both_simple)
    StereoRoute.RIGHT -> stringResource(R.string.output_right_simple)
}

@Composable
private fun friendlyTrackType(type: TrackType): String = when (type) {
    TrackType.DRUMS -> stringResource(R.string.track_type_drums)
    TrackType.BASS -> stringResource(R.string.track_type_bass)
    TrackType.GUITAR -> stringResource(R.string.track_type_guitar)
    TrackType.KEYS -> stringResource(R.string.track_type_keys)
    TrackType.SYNTH -> stringResource(R.string.track_type_synth)
    TrackType.STRINGS -> stringResource(R.string.track_type_strings)
    TrackType.VOCALS -> stringResource(R.string.track_type_vocals)
    TrackType.PERCUSSION -> stringResource(R.string.track_type_percussion)
    TrackType.GUIDE -> stringResource(R.string.track_type_guide)
    TrackType.PAD -> stringResource(R.string.track_type_pad)
    TrackType.CLICK -> stringResource(R.string.click_reference_track)
    TrackType.OTHER -> stringResource(R.string.track_type_other)
}
