package dev.stagegrid.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.stagegrid.R
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Fine calibration control for the musical-grid origin.
 *
 * Grid offset remains persisted in milliseconds. The UI can expose that same value either as raw
 * milliseconds or as musical beats derived from the current BPM, so changing units never changes
 * the underlying project format or audio-engine contract.
 */
@Composable
fun GridOffsetCalibrator(
    referenceOffsetMs: Long,
    valueMs: Long,
    onValueChange: (Long) -> Unit,
    referenceIsDetected: Boolean,
    bpm: Double? = null,
    modifier: Modifier = Modifier,
) {
    val reference = referenceOffsetMs.coerceIn(0L, MAX_GRID_OFFSET_MS)
    val current = valueMs.coerceIn(0L, MAX_GRID_OFFSET_MS)
    val validBpm = bpm?.takeIf { it.isFinite() && it in 20.0..400.0 }
    val beatDurationMs = validBpm?.let { 60_000.0 / it }
    var unit by rememberSaveable { mutableStateOf(CalibrationUnit.MILLISECONDS) }
    val beatMode = unit == CalibrationUnit.BEATS && beatDurationMs != null

    val fineRangeMs = if (beatMode) {
        beatDurationMs!!.roundToLong().coerceAtLeast(1L)
    } else {
        FINE_RANGE_MS
    }
    val minDelta = -minOf(reference, fineRangeMs)
    val maxDelta = minOf(MAX_GRID_OFFSET_MS - reference, fineRangeMs)
    val delta = current - reference
    val sliderDelta = delta.coerceIn(minDelta, maxDelta)
    val minimumValue = reference + minDelta
    val maximumValue = reference + maxDelta

    Card(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.grid_calibration_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.grid_calibration_help),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !beatMode,
                    onClick = { unit = CalibrationUnit.MILLISECONDS },
                    label = { Text(stringResource(R.string.grid_calibration_unit_ms)) },
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = beatMode,
                    onClick = { if (beatDurationMs != null) unit = CalibrationUnit.BEATS },
                    enabled = beatDurationMs != null,
                    label = { Text(stringResource(R.string.grid_calibration_unit_beats)) },
                    modifier = Modifier.weight(1f),
                )
            }
            if (beatDurationMs == null) {
                Text(
                    stringResource(R.string.grid_calibration_beats_requires_bpm),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(
                        if (referenceIsDetected) stringResource(R.string.grid_calibration_detected)
                        else stringResource(R.string.grid_calibration_reference),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        calibrationValueLabel(reference, beatDurationMs, beatMode),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Column {
                    Text(
                        stringResource(R.string.grid_calibration_current),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        calibrationValueLabel(current, beatDurationMs, beatMode),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Column {
                    Text(
                        stringResource(R.string.grid_calibration_delta),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        calibrationDeltaLabel(delta, beatDurationMs, beatMode),
                        fontWeight = FontWeight.SemiBold,
                        color = if (delta == 0L) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
                    )
                }
            }

            GridOffsetRuler(
                deltaMs = sliderDelta,
                minDeltaMs = minDelta,
                maxDeltaMs = maxDelta,
            )

            Slider(
                value = sliderDelta.toFloat(),
                onValueChange = { raw ->
                    onValueChange((reference + raw.roundToLong()).coerceIn(minimumValue, maximumValue))
                },
                valueRange = minDelta.toFloat()..maxDelta.toFloat(),
            )

            if (beatMode) {
                val beatMs = beatDurationMs!!
                val beatStep = beatMs.roundToLong().coerceAtLeast(1L)
                val quarterBeatStep = (beatMs / 4.0).roundToLong().coerceAtLeast(1L)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NudgeButton("−1B", Modifier.weight(1f)) {
                        onValueChange((current - beatStep).coerceIn(minimumValue, maximumValue))
                    }
                    NudgeButton("−¼B", Modifier.weight(1f)) {
                        onValueChange((current - quarterBeatStep).coerceIn(minimumValue, maximumValue))
                    }
                    OutlinedButton(
                        onClick = { onValueChange(reference) },
                        modifier = Modifier.weight(1.7f),
                    ) { Text(stringResource(R.string.grid_calibration_reset)) }
                    NudgeButton("+¼B", Modifier.weight(1f)) {
                        onValueChange((current + quarterBeatStep).coerceIn(minimumValue, maximumValue))
                    }
                    NudgeButton("+1B", Modifier.weight(1f)) {
                        onValueChange((current + beatStep).coerceIn(minimumValue, maximumValue))
                    }
                }
                Text(
                    stringResource(R.string.grid_calibration_beat_tip, validBpm ?: 0.0, beatMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NudgeButton("−10", Modifier.weight(1f)) {
                        onValueChange((current - 10L).coerceIn(minimumValue, maximumValue))
                    }
                    NudgeButton("−1", Modifier.weight(1f)) {
                        onValueChange((current - 1L).coerceIn(minimumValue, maximumValue))
                    }
                    OutlinedButton(
                        onClick = { onValueChange(reference) },
                        modifier = Modifier.weight(1.7f),
                    ) { Text(stringResource(R.string.grid_calibration_reset)) }
                    NudgeButton("+1", Modifier.weight(1f)) {
                        onValueChange((current + 1L).coerceIn(minimumValue, maximumValue))
                    }
                    NudgeButton("+10", Modifier.weight(1f)) {
                        onValueChange((current + 10L).coerceIn(minimumValue, maximumValue))
                    }
                }
                Text(
                    stringResource(R.string.grid_calibration_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NudgeButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
}

@Composable
private fun GridOffsetRuler(deltaMs: Long, minDeltaMs: Long, maxDeltaMs: Long) {
    // Keep the ruler deliberately high-contrast. This control is used for fine alignment in dark
    // stage environments, so the grid itself should remain visible independently of theme outlines.
    val line = Color.White.copy(alpha = 0.62f)
    val center = Color.White.copy(alpha = 0.96f)
    val marker = Color.White
    Canvas(Modifier.fillMaxWidth().height(42.dp)) {
        val y = size.height * 0.68f
        drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 3f)
        repeat(11) { index ->
            val fraction = index / 10f
            val x = size.width * fraction
            val major = index == 0 || index == 5 || index == 10
            val tickHeight = if (major) size.height * 0.34f else size.height * 0.2f
            drawLine(
                if (index == 5) center else line,
                Offset(x, y - tickHeight),
                Offset(x, y + 2f),
                strokeWidth = if (major) 2.5f else 1.5f,
            )
        }
        val span = (maxDeltaMs - minDeltaMs).coerceAtLeast(1L).toFloat()
        val markerFraction = ((deltaMs - minDeltaMs).toFloat() / span).coerceIn(0f, 1f)
        val markerX = size.width * markerFraction
        drawLine(marker, Offset(markerX, 0f), Offset(markerX, size.height), strokeWidth = 5f)
    }
}

private fun calibrationValueLabel(valueMs: Long, beatDurationMs: Double?, beatMode: Boolean): String {
    if (!beatMode || beatDurationMs == null) return "$valueMs ms"
    return String.format(Locale.ROOT, "%.3f B", valueMs / beatDurationMs)
}

private fun calibrationDeltaLabel(deltaMs: Long, beatDurationMs: Double?, beatMode: Boolean): String {
    if (!beatMode || beatDurationMs == null) return if (deltaMs >= 0) "+$deltaMs ms" else "$deltaMs ms"
    val beats = deltaMs / beatDurationMs
    return String.format(Locale.ROOT, "%+.3f B", beats)
}

private enum class CalibrationUnit { MILLISECONDS, BEATS }

private const val FINE_RANGE_MS = 500L
private const val MAX_GRID_OFFSET_MS = 60_000L
