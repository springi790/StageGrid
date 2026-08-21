package dev.stagegrid.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.stagegrid.audio.NativeAudioEngine
import dev.stagegrid.ui.StageGridDspUiState
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun DspControlPanel(
    dsp: StageGridDspUiState,
    originalBpm: Double?,
    onTempo: (Float) -> Unit,
    onPitch: (Float) -> Unit,
    onReset: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var tempoDraft by remember(dsp.tempoRatio) { mutableFloatStateOf(dsp.tempoRatio) }
    var pitchDraft by remember(dsp.pitchSemitones) { mutableFloatStateOf(dsp.pitchSemitones) }
    val controlsEnabled = enabled && !dsp.applying
    val effectiveBpm = originalBpm?.let { it * dsp.tempoRatio }

    Card(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Tempo & Pitch", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Signalsmith DSP · 75–150% · ±12 st",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilterChip(
                    selected = dsp.active,
                    onClick = {},
                    label = { Text(if (dsp.applying) "APLICANDO…" else if (dsp.active) "DSP ON" else "BYPASS") },
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("TEMPO", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${(tempoDraft * 100f).roundToInt()}%", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text("PITCH", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatSemitones(pitchDraft), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text("BPM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        effectiveBpm?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Text("Tempo", fontWeight = FontWeight.SemiBold)
            Slider(
                value = tempoDraft.coerceIn(NativeAudioEngine.MIN_TEMPO_RATIO, NativeAudioEngine.MAX_TEMPO_RATIO),
                onValueChange = { tempoDraft = it },
                onValueChangeFinished = { onTempo(tempoDraft) },
                enabled = controlsEnabled,
                valueRange = NativeAudioEngine.MIN_TEMPO_RATIO..NativeAudioEngine.MAX_TEMPO_RATIO,
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DspStepButton("−5%", controlsEnabled) { onTempo((dsp.tempoRatio - 0.05f).coerceAtLeast(NativeAudioEngine.MIN_TEMPO_RATIO)) }
                DspStepButton("−1%", controlsEnabled) { onTempo((dsp.tempoRatio - 0.01f).coerceAtLeast(NativeAudioEngine.MIN_TEMPO_RATIO)) }
                DspStepButton("100%", controlsEnabled) { onTempo(1f) }
                DspStepButton("+1%", controlsEnabled) { onTempo((dsp.tempoRatio + 0.01f).coerceAtMost(NativeAudioEngine.MAX_TEMPO_RATIO)) }
                DspStepButton("+5%", controlsEnabled) { onTempo((dsp.tempoRatio + 0.05f).coerceAtMost(NativeAudioEngine.MAX_TEMPO_RATIO)) }
            }

            Text("Pitch", fontWeight = FontWeight.SemiBold)
            Slider(
                value = pitchDraft.coerceIn(NativeAudioEngine.MIN_PITCH_SEMITONES, NativeAudioEngine.MAX_PITCH_SEMITONES),
                onValueChange = { pitchDraft = it },
                onValueChangeFinished = { onPitch(pitchDraft) },
                enabled = controlsEnabled,
                valueRange = NativeAudioEngine.MIN_PITCH_SEMITONES..NativeAudioEngine.MAX_PITCH_SEMITONES,
                steps = 23,
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DspStepButton("−2 st", controlsEnabled) { onPitch((dsp.pitchSemitones - 2f).coerceAtLeast(NativeAudioEngine.MIN_PITCH_SEMITONES)) }
                DspStepButton("−1 st", controlsEnabled) { onPitch((dsp.pitchSemitones - 1f).coerceAtLeast(NativeAudioEngine.MIN_PITCH_SEMITONES)) }
                DspStepButton("0 st", controlsEnabled) { onPitch(0f) }
                DspStepButton("+1 st", controlsEnabled) { onPitch((dsp.pitchSemitones + 1f).coerceAtMost(NativeAudioEngine.MAX_PITCH_SEMITONES)) }
                DspStepButton("+2 st", controlsEnabled) { onPitch((dsp.pitchSemitones + 2f).coerceAtMost(NativeAudioEngine.MAX_PITCH_SEMITONES)) }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (dsp.active) "Latencia DSP: ${dsp.latencyMs} ms" else "DSP en bypass",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (dsp.active) {
                    Text(
                        "DSP CPU: ${String.format(Locale.ROOT, "%.0f", dsp.dspCpuLoad * 100f)}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            dsp.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            OutlinedButton(
                onClick = onReset,
                enabled = controlsEnabled && (dsp.active || dsp.tempoRatio != 1f || dsp.pitchSemitones != 0f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Restablecer · 100% / 0 st")
            }
        }
    }
}

@Composable
private fun DspStepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled) { Text(label) }
}

private fun formatSemitones(value: Float): String {
    val rounded = value.roundToInt()
    return if (kotlin.math.abs(value - rounded) < 0.01f) {
        if (rounded > 0) "+$rounded st" else "$rounded st"
    } else {
        String.format(Locale.ROOT, "%+.1f st", value)
    }
}
