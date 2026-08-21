package dev.stagegrid.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.LinearProgressIndicator
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
import dev.stagegrid.settings.NativeGuideFeatureGate
import dev.stagegrid.ui.StageGridViewModel
import dev.stagegrid.ui.components.SongWaveformOverview

@Composable
fun PlayerScreen(
    state: PlayerState,
    nativeGuide: StageGridViewModel.NativeGuideUiState,
    setlistLive: StageGridViewModel.SetlistLiveUiState,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onStopAll: () -> Unit,
    onSeek: (Long) -> Unit,
    onLoop: () -> Unit,
    onExitLoop: () -> Unit,
    onSection: (SectionEntity) -> Unit,
    onEditSections: () -> Unit,
    onPlaySectionWithCountIn: () -> Unit,
    onCountInBars: (Int) -> Unit,
    onMaster: (Float) -> Unit,
    onClick: (Boolean) -> Unit,
    onGuide: (Boolean) -> Unit,
    onNativeGuideLanguage: (String) -> Unit,
    onReanalyzeNativeGuide: () -> Unit,
    onClickSubdivision: (ClickSubdivision) -> Unit,
    onClickRoute: (StereoRoute) -> Unit,
    onSetlistPrevious: () -> Unit,
    onSetlistNext: () -> Unit,
    onExitSetlistLive: () -> Unit,
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
    var showAdvanced by remember { mutableStateOf(false) }
    val verticalScroll = rememberScrollState()

    LaunchedEffect(state.positionMs, state.durationMs, dragging) {
        if (!dragging) seekFraction = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
    }

    Column(
        modifier.fillMaxSize().verticalScroll(verticalScroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(song.artist.ifBlank { stringResource(R.string.unknown_artist) }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val bpmLabel = song.bpm?.let { stringResource(R.string.simple_bpm_value, "%.0f".format(it)) }
                val positionLabel = musicalPosition?.let { stringResource(R.string.simple_bar_beat_value, it.bar, it.beat) }
                val simpleMeta = listOfNotNull(bpmLabel, positionLabel).joinToString("  ·  ")
                if (simpleMeta.isNotBlank()) Text(simpleMeta, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            if (state.engineState == EngineState.PLAYING) {
                Text(stringResource(R.string.live), color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
            }
        }

        if (setlistLive.active) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.setlist_live_title), fontWeight = FontWeight.Bold)
                    Text(setlistLive.setlistName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (setlistLive.currentIndex >= 0 && setlistLive.totalSongs > 0) {
                        Text(stringResource(R.string.setlist_live_song_position, setlistLive.currentIndex + 1, setlistLive.totalSongs), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.setlist_live_current), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(setlistLive.currentSongTitle ?: song.title, fontWeight = FontWeight.SemiBold)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.setlist_live_next_song), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(setlistLive.nextSongTitle ?: "—", fontWeight = FontWeight.SemiBold)
                        }
                    }
                    when {
                        setlistLive.preloadingNext -> Text(stringResource(R.string.setlist_live_next_preparing), color = MaterialTheme.colorScheme.primary)
                        setlistLive.nextReady -> Text(stringResource(R.string.setlist_live_next_ready), color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold)
                        !setlistLive.hasNext -> Text(stringResource(R.string.setlist_live_no_next), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    setlistLive.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = onSetlistPrevious,
                            enabled = setlistLive.hasPrevious && state.engineState != EngineState.LOADING && !state.isCountingIn,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.setlist_live_previous)) }
                        Button(
                            onClick = onSetlistNext,
                            enabled = setlistLive.hasNext && state.engineState != EngineState.LOADING && !state.isCountingIn,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.setlist_live_next), fontWeight = FontWeight.Bold) }
                    }
                    OutlinedButton(onClick = onExitSetlistLive, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setlist_live_exit)) }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.isCountingIn) {
                    val targetName = state.countInTargetSection?.name ?: state.currentSection?.name ?: stringResource(R.string.section)
                    val remainingSeconds = ((state.countInRemainingMs + 999L) / 1000L).coerceAtLeast(1L)
                    Text(
                        stringResource(R.string.count_in_active, targetName, remainingSeconds),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(stringResource(R.string.count_in_click_only), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(stringResource(R.string.now_section), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(state.currentSection?.name ?: stringResource(R.string.none), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                        Column {
                            Text(stringResource(R.string.next_section_simple), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(state.nextSection?.name ?: stringResource(R.string.none), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                state.queuedSectionId?.let { queuedId ->
                    if (!state.isCountingIn) {
                        state.sections.firstOrNull { it.id == queuedId }?.let {
                            Text(stringResource(R.string.queued_section_boundary, it.name), color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                SongWaveformOverview(
                    songId = song.id,
                    positionMs = if (dragging) (seekFraction * state.durationMs).toLong() else state.positionMs,
                    durationMs = state.durationMs,
                    sections = state.sections,
                    onSeek = if (state.isCountingIn) null else { target ->
                        dragging = false
                        onSeek(target)
                    },
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatPlayerDuration(if (dragging) (seekFraction * state.durationMs).toLong() else state.positionMs))
                    Text(formatPlayerDuration(state.durationMs))
                }
                Slider(
                    value = seekFraction.coerceIn(0f, 1f),
                    onValueChange = { dragging = true; seekFraction = it },
                    onValueChangeFinished = { dragging = false; onSeek((seekFraction * state.durationMs).toLong()) },
                    enabled = !state.isCountingIn,
                )
            }
        }

        if (state.sections.isNotEmpty()) {
            Text(stringResource(R.string.jump_to_section), fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.sections.forEach { section ->
                    val selected = state.currentSection?.id == section.id
                    val queued = state.queuedSectionId == section.id
                    FilterChip(
                        selected = selected || queued,
                        onClick = { onSection(section) },
                        enabled = !state.isCountingIn,
                        label = { Text(if (queued) stringResource(R.string.queued_section_label, section.name) else section.name) },
                    )
                }
            }
        }

        OutlinedButton(
            onClick = onEditSections,
            enabled = !state.isPlaying && grid != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.edit_song_sections), fontWeight = FontWeight.SemiBold) }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onPlayPause, modifier = Modifier.weight(1.6f)) {
                Text(if (state.isPlaying) stringResource(R.string.pause) else stringResource(R.string.play), fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.stop)) }
            OutlinedButton(
                onClick = if (state.loopSectionId == null) onLoop else onExitLoop,
                modifier = Modifier.weight(1f),
                enabled = !state.isCountingIn,
            ) { Text(if (state.loopSectionId == null) stringResource(R.string.loop) else stringResource(R.string.exit_loop)) }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.performance_tools), fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(
                        selected = state.clickEnabled,
                        onClick = { onClick(!state.clickEnabled) },
                        label = { Text(stringResource(R.string.click_simple)) },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = state.guideEnabled,
                        onClick = { onGuide(!state.guideEnabled) },
                        label = { Text(stringResource(R.string.guide_simple)) },
                        modifier = Modifier.weight(1f),
                    )
                }

                Text(stringResource(R.string.click_rhythm), fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ClickSubdivision.entries.forEach { subdivision ->
                        FilterChip(
                            selected = state.clickSubdivision == subdivision,
                            onClick = { onClickSubdivision(subdivision) },
                            label = { Text(subdivisionLabel(subdivision)) },
                        )
                    }
                }

                Text(stringResource(R.string.section_count_in), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.section_count_in_help), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = state.countInBars == 0, onClick = { onCountInBars(0) }, label = { Text(stringResource(R.string.count_in_off)) })
                    FilterChip(selected = state.countInBars == 1, onClick = { onCountInBars(1) }, label = { Text(stringResource(R.string.one_bar)) })
                    FilterChip(selected = state.countInBars == 2, onClick = { onCountInBars(2) }, label = { Text(stringResource(R.string.two_bars)) })
                }
                if (state.countInBars > 0) {
                    OutlinedButton(
                        onClick = onPlaySectionWithCountIn,
                        enabled = !state.isPlaying && state.currentSection != null && grid != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.play_section_with_count_in)) }
                }
            }
        }

        if (NativeGuideFeatureGate.enabled && (nativeGuide.available || nativeGuide.canReanalyze) && nativeGuide.songId == song.id) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${stringResource(R.string.native_guide_song_title)} · ${stringResource(R.string.labs_badge)}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                    if (nativeGuide.available) {
                        Text(stringResource(R.string.native_guide_events_count, nativeGuide.eventCount), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(stringResource(R.string.native_guide_reanalysis_available), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    when {
                        nativeGuide.reanalyzing -> {
                            Text(
                                stringResource(R.string.native_guide_reanalyzing, nativeGuide.reanalyzePercent),
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            LinearProgressIndicator(
                                progress = { nativeGuide.reanalyzePercent.coerceIn(0, 100) / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        nativeGuide.rendering -> {
                            Text(
                                stringResource(R.string.native_guide_regenerating, nativeGuide.renderPercent),
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            LinearProgressIndicator(
                                progress = { nativeGuide.renderPercent.coerceIn(0, 100) / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        else -> {
                            if (nativeGuide.available) {
                                Text(stringResource(R.string.native_guide_song_language), fontWeight = FontWeight.SemiBold)
                                Text(stringResource(R.string.native_guide_language_help), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    nativeGuide.languages.forEach { language ->
                                        FilterChip(
                                            selected = nativeGuide.currentLanguage == language,
                                            onClick = { onNativeGuideLanguage(language) },
                                            enabled = !state.isPlaying && !state.isCountingIn,
                                            label = { Text(guideLanguageLabel(language)) },
                                        )
                                    }
                                }
                            }
                            if (nativeGuide.canReanalyze) {
                                OutlinedButton(
                                    onClick = onReanalyzeNativeGuide,
                                    enabled = !state.isPlaying && !state.isCountingIn,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.native_guide_reanalyze_button), fontWeight = FontWeight.SemiBold) }
                                Text(stringResource(R.string.native_guide_reanalyze_help), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (state.isPlaying || state.isCountingIn) {
                                Text(stringResource(R.string.native_guide_change_stop_first), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else if (nativeGuide.available && nativeGuide.languages.size <= 1) {
                                Text(stringResource(R.string.native_guide_pack_missing_languages), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    nativeGuide.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        Text(stringResource(R.string.master_value, (state.masterVolume * 100).toInt()))
        Slider(value = state.masterVolume.coerceIn(0f, 1f), onValueChange = onMaster)

        OutlinedButton(onClick = { showAdvanced = !showAdvanced }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showAdvanced) stringResource(R.string.hide_advanced_options) else stringResource(R.string.show_advanced_options))
        }

        if (showAdvanced) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.advanced_options), fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.grid_status, song.bpm?.let { "%.2f".format(it) } ?: "—", song.gridOffsetMs),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(stringResource(R.string.click_output), fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StereoRoute.entries.forEach { route ->
                            FilterChip(selected = state.clickRoute == route, onClick = { onClickRoute(route) }, label = { Text(routeLabel(route)) })
                        }
                    }
                }
            }
        }

        Button(onClick = onStopAll, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.stop_all), fontWeight = FontWeight.ExtraBold) }
        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun subdivisionLabel(subdivision: ClickSubdivision): String = when (subdivision) {
    ClickSubdivision.QUARTER -> stringResource(R.string.click_quarters)
    ClickSubdivision.EIGHTH -> stringResource(R.string.click_eighths)
    ClickSubdivision.TRIPLET -> stringResource(R.string.click_triplets)
    ClickSubdivision.SIXTEENTH -> stringResource(R.string.click_sixteenths)
}

@Composable
private fun routeLabel(route: StereoRoute): String = when (route) {
    StereoRoute.LEFT -> stringResource(R.string.route_left)
    StereoRoute.BOTH -> stringResource(R.string.route_both)
    StereoRoute.RIGHT -> stringResource(R.string.route_right)
}

@Composable
private fun guideLanguageLabel(language: String): String = when (language) {
    "es" -> stringResource(R.string.guide_language_es)
    "en" -> stringResource(R.string.guide_language_en)
    "fr" -> stringResource(R.string.guide_language_fr)
    "pt" -> stringResource(R.string.guide_language_pt)
    else -> language.uppercase()
}

private fun formatPlayerDuration(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0) / 1000).toInt()
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
