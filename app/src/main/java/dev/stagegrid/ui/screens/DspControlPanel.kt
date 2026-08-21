package dev.stagegrid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
                CompactBpmField(
                    baseBpm = baseBpm,
                    currentBpm = currentBpm,
                    enabled = controlsEnabled && baseBpm != null,
                    onBpm = onBpm,
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
private fun CompactBpmField(
    baseBpm: Double?,
    currentBpm: Double?,
    enabled: Boolean,
    onBpm: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    var bpmText by remember(baseBpm, currentBpm) {
        mutableStateOf(currentBpm?.let(::formatBpm).orEmpty())
    }
    var wasFocused by remember { mutableStateOf(false) }

    fun commitBpm() {
        val base = baseBpm ?: return
        val fallback = currentBpm ?: base
        val parsed = bpmText.trim().replace(',', '.').toDoubleOrNull()
        if (parsed == null || !parsed.isFinite() || parsed <= 0.0) {
            bpmText = formatBpm(fallback)
            return
        }
        val min = base * NativeAudioEngine.MIN_TEMPO_RATIO
        val max = base * NativeAudioEngine.MAX_TEMPO_RATIO
        val applied = parsed.coerceIn(min, max)
        bpmText = formatBpm(applied)
        if (abs(applied - fallback) >= 0.01) onBpm(applied)
    }

    OutlinedTextField(
        value = bpmText,
        onValueChange = { value ->
            bpmText = value.filter { it.isDigit() || it == '.' || it == ',' }.take(7)
        },
        enabled = enabled,
        singleLine = true,
        label = { Text("BPM") },
        suffix = { Text("BPM") },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                commitBpm()
                focusManager.clearFocus()
            },
        ),
        modifier = modifier.onFocusChanged { state ->
            if (wasFocused && !state.isFocused) commitBpm()
            wasFocused = state.isFocused
        },
    )
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

private fun formatBpm(value: Double): String {
    val rounded = round(value)
    return if (abs(value - rounded) < 0.05) {
        rounded.toInt().toString()
    } else {
        String.format(Locale.ROOT, "%.1f", value)
    }
}
