package dev.stagegrid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.stagegrid.audio.NativeAudioEngine
import dev.stagegrid.music.MusicalKey
import dev.stagegrid.ui.StageGridDspUiState
import dev.stagegrid.ui.StageGridViewModel
import dev.stagegrid.ui.dspState
import dev.stagegrid.ui.resetDsp
import dev.stagegrid.ui.setTargetBpm
import dev.stagegrid.ui.setTargetMusicalKey
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

@Composable
fun StageGridDspControlHost(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    stageGridViewModel: StageGridViewModel = viewModel(),
) {
    val dsp by stageGridViewModel.dspState.collectAsStateWithLifecycle()
    val player by stageGridViewModel.player.collectAsStateWithLifecycle()
    DspControlPanel(
        dsp = dsp,
        originalBpm = player.song?.bpm,
        originalKey = player.song?.musicalKey,
        onBpm = stageGridViewModel::setTargetBpm,
        onKey = stageGridViewModel::setTargetMusicalKey,
        onReset = stageGridViewModel::resetDsp,
        enabled = enabled && player.song != null,
        modifier = modifier,
    )
}

@Composable
fun DspControlPanel(
    dsp: StageGridDspUiState,
    originalBpm: Double?,
    originalKey: String?,
    onBpm: (Double) -> Unit,
    onKey: (String) -> Unit,
    onReset: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val baseBpm = originalBpm?.takeIf { it > 0.0 }
    val currentBpm = baseBpm?.times(dsp.tempoRatio)
    val bpmOptions = remember(baseBpm) {
        baseBpm?.let(::buildBpmOptions).orEmpty()
    }
    val keyOptions = remember(originalKey) { MusicalKey.optionsFor(originalKey) }
    val currentKey = MusicalKey.transpose(originalKey, dsp.pitchSemitones)
    val controlsEnabled = enabled && !dsp.applying

    Card(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CompactDropdown(
                    label = "BPM",
                    value = currentBpm?.let { "${formatBpm(it)} BPM" } ?: "— BPM",
                    options = bpmOptions.map { it to "${formatBpm(it)} BPM" },
                    enabled = controlsEnabled && baseBpm != null,
                    onSelected = onBpm,
                    modifier = Modifier.weight(1f),
                )
                CompactDropdown(
                    label = "Tonalidad",
                    value = currentKey ?: "—",
                    options = keyOptions.map { it to it },
                    enabled = controlsEnabled && keyOptions.isNotEmpty(),
                    onSelected = onKey,
                    modifier = Modifier.weight(1f),
                )
            }

            if (dsp.active || dsp.applying || dsp.error != null) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        when {
                            dsp.applying -> "Aplicando…"
                            dsp.active -> "DSP · ${dsp.latencyMs} ms"
                            else -> "DSP"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (dsp.active && !dsp.applying) {
                        TextButton(onClick = onReset) {
                            Text("Original")
                        }
                    }
                }
            }
            dsp.error?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun <T> CompactDropdown(
    label: String,
    value: String,
    options: List<Pair<T, String>>,
    enabled: Boolean,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(value, maxLines = 1)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { (option, optionLabel) ->
                    DropdownMenuItem(
                        text = { Text(optionLabel) },
                        onClick = {
                            expanded = false
                            onSelected(option)
                        },
                    )
                }
            }
        }
    }
}

private fun buildBpmOptions(baseBpm: Double): List<Double> {
    val min = ceil(baseBpm * NativeAudioEngine.MIN_TEMPO_RATIO).toInt().coerceAtLeast(1)
    val max = floor(baseBpm * NativeAudioEngine.MAX_TEMPO_RATIO).toInt().coerceAtLeast(min)
    val values = (min..max).map(Int::toDouble).toMutableList()
    if (values.none { abs(it - baseBpm) < 0.01 }) values += baseBpm
    return values.distinctBy { round(it * 10.0).toInt() }.sorted()
}

private fun formatBpm(value: Double): String {
    val rounded = round(value)
    return if (abs(value - rounded) < 0.05) {
        rounded.toInt().toString()
    } else {
        String.format(Locale.ROOT, "%.1f", value)
    }
}
