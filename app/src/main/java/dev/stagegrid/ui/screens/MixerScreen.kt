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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.stagegrid.R
import dev.stagegrid.audio.PlayerState
import dev.stagegrid.model.OutputBus
import dev.stagegrid.model.StereoRoute
import dev.stagegrid.model.TrackType
import dev.stagegrid.ui.components.StageGridMetric
import dev.stagegrid.ui.components.StageGridPanel
import dev.stagegrid.ui.components.StageGridPill
import dev.stagegrid.ui.components.StageGridScreenHeader
import dev.stagegrid.ui.theme.StageGridColors

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
    val playableTracks = state.tracks.count { TrackType.fromStorage(it.type) != TrackType.CLICK }
    val soloedTrackCount = state.tracks.count { it.solo }

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 14.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StageGridScreenHeader(
                kicker = stringResource(R.string.mixer_kicker),
                title = stringResource(R.string.mixer),
                subtitle = state.song?.let { "${it.title} · ${stringResource(R.string.mixer_simple_explanation)}" }
                    ?: stringResource(R.string.no_song_loaded),
                badge = stringResource(R.string.mixer_channel_count, playableTracks),
            )
        }

        if (state.song == null) {
            item {
                StageGridPanel {
                    Text(stringResource(R.string.no_song_loaded), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            return@LazyColumn
        }

        // Solo silences every other stem. Finding out mid-song that a rehearsal solo was left
        // armed is a classic "why is there no sound" moment, so it gets an unmissable banner and a
        // one-tap way out instead of being visible only as a chip state further down the list.
        if (soloedTrackCount > 0) {
            item {
                StageGridPanel(accent = StageGridColors.Amber) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                stringResource(R.string.mixer_solo_active_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Black,
                                color = StageGridColors.Amber,
                            )
                            StageGridPill(stringResource(R.string.solo), StageGridColors.Amber)
                        }
                        Text(
                            stringResource(R.string.mixer_solo_active_desc, soloedTrackCount),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = {
                                state.tracks.forEachIndexed { index, track -> if (track.solo) onSolo(index, false) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.mixer_clear_solo), fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }

        item {
            StageGridPanel(accent = MaterialTheme.colorScheme.primary) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.mixer_quick_routing), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                            Text(stringResource(R.string.quick_output_explanation), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StageGridPill("${state.outputChannelCount} OUT", if (state.outputFallback) StageGridColors.Amber else StageGridColors.Mint)
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        StageGridMetric("ACTIVE", state.outputChannelCount.toString(), Modifier.weight(1f))
                        StageGridMetric("REQUEST", state.requestedOutputChannels.toString(), Modifier.weight(1f), MaterialTheme.colorScheme.tertiary)
                        StageGridMetric("TRACKS", playableTracks.toString(), Modifier.weight(1f), MaterialTheme.colorScheme.secondary)
                    }

                    if (state.outputFallback) {
                        Text(stringResource(R.string.multichannel_fallback_warning), color = StageGridColors.Amber, fontWeight = FontWeight.SemiBold)
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
                    ) { Text(stringResource(R.string.preset_all_stereo)) }

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
                    ) { Text(stringResource(R.string.preset_stage_split)) }

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
                    ) { Text(stringResource(R.string.preset_four_out), fontWeight = FontWeight.Bold) }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            modifier = Modifier.weight(1f),
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
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.preset_eight_out)) }
                    }

                    OutlinedButton(onClick = { showAdvancedRouting = !showAdvancedRouting }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (showAdvancedRouting) stringResource(R.string.hide_manual_routing) else stringResource(R.string.show_manual_routing))
                    }
                }
            }
        }

        item {
            GuideSourceControlHost(modifier = Modifier.fillMaxWidth())
        }

        if (showAdvancedRouting) {
            item {
                StageGridPanel(accent = MaterialTheme.colorScheme.tertiary) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.generated_click_routing), fontWeight = FontWeight.Bold)
                            StageGridPill("CLICK", MaterialTheme.colorScheme.tertiary)
                        }
                        Text(stringResource(R.string.generated_click_routing_help), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.output_bus), fontWeight = FontWeight.SemiBold)
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            availableBuses.forEach { bus ->
                                FilterChip(selected = state.clickBus == bus, onClick = { onClickBus(bus) }, label = { Text(busLabel(bus)) })
                            }
                        }
                        Text(stringResource(R.string.where_should_this_track_sound), fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StereoRoute.entries.forEach { option ->
                                FilterChip(selected = state.clickRoute == option, onClick = { onClickRoute(option) }, label = { Text(routeFriendlyLabel(option)) })
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
            val accent = trackAccent(type)

            StageGridPanel(accent = if (track.solo) StageGridColors.Mint else if (track.muted) StageGridColors.Danger else accent) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                track.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Black,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                stringResource(R.string.mixer_channel_strip),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        StageGridPill(
                            if (isImportedClickReference) stringResource(R.string.mixer_reference_badge) else friendlyTrackType(type),
                            accent,
                        )
                    }

                    if (isImportedClickReference) {
                        Text(stringResource(R.string.click_reference_explanation), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = track.muted,
                                onClick = { onMute(index, !track.muted) },
                                label = { Text(stringResource(R.string.mute), fontWeight = FontWeight.Black) },
                                modifier = Modifier.weight(1f),
                            )
                            FilterChip(
                                selected = track.solo,
                                onClick = { onSolo(index, !track.solo) },
                                label = { Text(stringResource(R.string.solo), fontWeight = FontWeight.Black) },
                                modifier = Modifier.weight(1f),
                            )
                            StageGridMetric("VOL", "${(track.volume * 100).toInt()}%", Modifier.weight(1f), accent)
                        }

                        Slider(value = track.volume.coerceIn(0f, 1.25f), onValueChange = { onVolume(index, it) }, valueRange = 0f..1.25f)

                        if (showAdvancedRouting) {
                            Text(stringResource(R.string.output_bus), fontWeight = FontWeight.SemiBold)
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                availableBuses.forEach { option ->
                                    FilterChip(selected = bus == option, onClick = { onOutputBus(index, option) }, label = { Text(busLabel(option)) })
                                }
                            }

                            Text(stringResource(R.string.where_should_this_track_sound), fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StereoRoute.entries.forEach { option ->
                                    FilterChip(selected = route == option, onClick = { onOutputRoute(index, option) }, label = { Text(routeFriendlyLabel(option)) })
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

@Composable
private fun trackAccent(type: TrackType) = when (type) {
    TrackType.DRUMS, TrackType.PERCUSSION -> StageGridColors.Amber
    TrackType.BASS -> StageGridColors.Mint
    TrackType.GUITAR -> MaterialTheme.colorScheme.tertiary
    TrackType.KEYS, TrackType.SYNTH, TrackType.PAD -> MaterialTheme.colorScheme.primary
    TrackType.VOCALS -> StageGridColors.Violet
    TrackType.STRINGS -> StageGridColors.Cyan
    TrackType.GUIDE -> StageGridColors.Violet
    TrackType.CLICK -> StageGridColors.Amber
    TrackType.OTHER -> MaterialTheme.colorScheme.onSurfaceVariant
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
