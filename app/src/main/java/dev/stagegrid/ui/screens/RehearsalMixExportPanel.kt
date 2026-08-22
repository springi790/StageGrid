package dev.stagegrid.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.stagegrid.R
import dev.stagegrid.StageGridApplication
import dev.stagegrid.audio.EngineState
import dev.stagegrid.audio.GuideSource
import dev.stagegrid.audio.PlayerState
import dev.stagegrid.audio.RehearsalMixExportState
import dev.stagegrid.audio.RehearsalMixExporter
import dev.stagegrid.ui.StageGridViewModel
import dev.stagegrid.ui.components.StageGridMetric
import dev.stagegrid.ui.components.StageGridPanel
import dev.stagegrid.ui.components.StageGridPill
import dev.stagegrid.ui.cueGuideState
import dev.stagegrid.ui.theme.StageGridColors
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RehearsalMixExportPanel(
    state: PlayerState,
    modifier: Modifier = Modifier,
    stageGridViewModel: StageGridViewModel = viewModel(),
) {
    val context = LocalContext.current
    val app = context.applicationContext as StageGridApplication
    val scope = rememberCoroutineScope()
    val cueState by stageGridViewModel.cueGuideState.collectAsStateWithLifecycle()
    var exportState by remember(state.song?.id) { mutableStateOf(RehearsalMixExportState()) }

    val transportBusy = state.isPlaying ||
        state.isCountingIn ||
        state.crossfadeInProgress ||
        state.engineState in setOf(EngineState.LOADING, EngineState.SEEKING, EngineState.STOPPING)
    val canExport = state.song != null && state.tracks.isNotEmpty() && !transportBusy && !exportState.running
    val effectiveBpm = state.effectiveBpm?.let { bpm ->
        if (bpm % 1.0 == 0.0) bpm.toInt().toString() else String.format(Locale.ROOT, "%.1f", bpm)
    } ?: "—"
    val pitchLabel = when {
        state.pitchSemitones > 0.005f -> "+${formatPitch(state.pitchSemitones)} st"
        state.pitchSemitones < -0.005f -> "${formatPitch(state.pitchSemitones)} st"
        else -> "0 st"
    }

    StageGridPanel(modifier = modifier, accent = StageGridColors.Cyan) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.rehearsal_mix_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        stringResource(R.string.rehearsal_mix_desc),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StageGridPill("WAV", StageGridColors.Cyan)
            }

            Text(
                stringResource(R.string.rehearsal_mix_format),
                style = MaterialTheme.typography.labelMedium,
                color = StageGridColors.Cyan,
                fontWeight = FontWeight.Bold,
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                StageGridMetric("BPM", effectiveBpm, Modifier.weight(1f), StageGridColors.Cyan)
                StageGridMetric(
                    "TEMPO",
                    "${(state.tempoRatio * 100f).roundToInt()}%",
                    Modifier.weight(1f),
                    MaterialTheme.colorScheme.primary,
                )
                StageGridMetric("PITCH", pitchLabel, Modifier.weight(1f), MaterialTheme.colorScheme.tertiary)
            }

            Text(
                stringResource(R.string.rehearsal_mix_snapshot_help),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.rehearsal_mix_linear_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (cueState.source == GuideSource.CUE) {
                Text(
                    stringResource(R.string.rehearsal_mix_cue_auto_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = StageGridColors.Amber,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (transportBusy) {
                Text(
                    stringResource(R.string.rehearsal_mix_stop_first),
                    color = StageGridColors.Amber,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (exportState.running) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.rehearsal_mix_rendering),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                Button(
                    enabled = canExport,
                    onClick = {
                        // Freeze every mix/DSP value now. Later UI edits cannot alter this render.
                        // Cue Auto mutes imported Guide tracks in the live engine without changing
                        // Room's TrackEntity, so explicitly keep the original Guide out here too.
                        val snapshot = state.copy(
                            tracks = state.tracks.toList(),
                            sections = state.sections.toList(),
                            guideEnabled = state.guideEnabled && cueState.source == GuideSource.ORIGINAL,
                            guideSource = cueState.source,
                        )
                        exportState = RehearsalMixExportState(running = true)
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                app.rehearsalMixExporter.export(snapshot)
                            }
                            exportState = result.fold(
                                onSuccess = { file ->
                                    RehearsalMixExportState(
                                        running = false,
                                        filePath = file.absolutePath,
                                        fileName = file.name,
                                        sizeBytes = file.length(),
                                    )
                                },
                                onFailure = { error ->
                                    RehearsalMixExportState(
                                        running = false,
                                        error = error.message ?: context.getString(R.string.rehearsal_mix_error_title),
                                    )
                                },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.rehearsal_mix_export), fontWeight = FontWeight.Bold)
                }
            }

            exportState.error?.let { error ->
                Text(
                    stringResource(R.string.rehearsal_mix_error_title),
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Black,
                )
                Text(error, color = MaterialTheme.colorScheme.error)
            }

            val exportedPath = exportState.filePath
            if (!exportState.running && exportedPath != null) {
                val exportedFile = remember(exportedPath, exportState.sizeBytes) { File(exportedPath) }
                Text(
                    stringResource(R.string.rehearsal_mix_ready),
                    color = StageGridColors.Mint,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    stringResource(
                        R.string.rehearsal_mix_file_info,
                        exportState.fileName ?: exportedFile.name,
                        formatFileSize(exportState.sizeBytes),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        if (!exportedFile.isFile) {
                            exportState = exportState.copy(
                                filePath = null,
                                fileName = null,
                                sizeBytes = 0L,
                                error = context.getString(R.string.rehearsal_mix_missing_file),
                            )
                        } else {
                            runCatching {
                                val share = RehearsalMixExporter.shareIntent(context, exportedFile)
                                context.startActivity(
                                    Intent.createChooser(
                                        share,
                                        context.getString(R.string.rehearsal_mix_share_chooser),
                                    ),
                                )
                            }.onFailure { throwable ->
                                exportState = exportState.copy(
                                    error = throwable.message ?: context.getString(R.string.rehearsal_mix_error_title),
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.rehearsal_mix_share), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun formatPitch(value: Float): String = if (kotlin.math.abs(value - value.roundToInt()) < 0.01f) {
    value.roundToInt().toString()
} else {
    String.format(Locale.ROOT, "%.1f", value)
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
