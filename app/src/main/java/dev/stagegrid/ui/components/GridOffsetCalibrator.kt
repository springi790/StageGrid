package dev.stagegrid.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.stagegrid.R
import kotlin.math.roundToLong

/**
 * Fine calibration control for the musical-grid origin.
 *
 * The importer remains authoritative for the initial estimate, while the musician can correct
 * small detector errors without typing raw milliseconds. The control deliberately works in a
 * narrow +/-500 ms window around the supplied reference; exact 1 ms and 10 ms nudges remain
 * available for final alignment.
 */
@Composable
fun GridOffsetCalibrator(
    referenceOffsetMs: Long,
    valueMs: Long,
    onValueChange: (Long) -> Unit,
    referenceIsDetected: Boolean,
    modifier: Modifier = Modifier,
) {
    val reference = referenceOffsetMs.coerceIn(0L, MAX_GRID_OFFSET_MS)
    val current = valueMs.coerceIn(0L, MAX_GRID_OFFSET_MS)
    val delta = current - reference
    val minDelta = -minOf(reference, FINE_RANGE_MS)
    val maxDelta = minOf(MAX_GRID_OFFSET_MS - reference, FINE_RANGE_MS)
    val safeDelta = delta.coerceIn(minDelta, maxDelta)

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

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(
                        if (referenceIsDetected) stringResource(R.string.grid_calibration_detected)
                        else stringResource(R.string.grid_calibration_reference),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("${reference} ms", fontWeight = FontWeight.SemiBold)
                }
                Column {
                    Text(
                        stringResource(R.string.grid_calibration_current),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("${current} ms", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Column {
                    Text(
                        stringResource(R.string.grid_calibration_delta),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (delta >= 0) "+${delta} ms" else "${delta} ms",
                        fontWeight = FontWeight.SemiBold,
                        color = if (delta == 0L) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
                    )
                }
            }

            GridOffsetRuler(
                deltaMs = safeDelta,
                minDeltaMs = minDelta,
                maxDeltaMs = maxDelta,
            )

            Slider(
                value = safeDelta.toFloat(),
                onValueChange = { raw ->
                    onValueChange((reference + raw.roundToLong()).coerceIn(0L, MAX_GRID_OFFSET_MS))
                },
                valueRange = minDelta.toFloat()..maxDelta.toFloat(),
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NudgeButton("−10", Modifier.weight(1f)) { onValueChange((current - 10L).coerceAtLeast(0L)) }
                NudgeButton("−1", Modifier.weight(1f)) { onValueChange((current - 1L).coerceAtLeast(0L)) }
                OutlinedButton(
                    onClick = { onValueChange(reference) },
                    modifier = Modifier.weight(1.7f),
                ) { Text(stringResource(R.string.grid_calibration_reset)) }
                NudgeButton("+1", Modifier.weight(1f)) { onValueChange((current + 1L).coerceAtMost(MAX_GRID_OFFSET_MS)) }
                NudgeButton("+10", Modifier.weight(1f)) { onValueChange((current + 10L).coerceAtMost(MAX_GRID_OFFSET_MS)) }
            }

            Text(
                stringResource(R.string.grid_calibration_tip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NudgeButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
}

@Composable
private fun GridOffsetRuler(deltaMs: Long, minDeltaMs: Long, maxDeltaMs: Long) {
    val line = MaterialTheme.colorScheme.outline
    val center = MaterialTheme.colorScheme.onSurfaceVariant
    val marker = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(42.dp)) {
        val y = size.height * 0.68f
        drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 2f)
        repeat(11) { index ->
            val fraction = index / 10f
            val x = size.width * fraction
            val major = index == 0 || index == 5 || index == 10
            val tickHeight = if (major) size.height * 0.34f else size.height * 0.2f
            drawLine(if (index == 5) center else line, Offset(x, y - tickHeight), Offset(x, y + 2f), strokeWidth = if (major) 2f else 1f)
        }
        val span = (maxDeltaMs - minDeltaMs).coerceAtLeast(1L).toFloat()
        val markerFraction = ((deltaMs - minDeltaMs).toFloat() / span).coerceIn(0f, 1f)
        val markerX = size.width * markerFraction
        drawLine(marker, Offset(markerX, 0f), Offset(markerX, size.height), strokeWidth = 4f)
    }
}

private const val FINE_RANGE_MS = 500L
private const val MAX_GRID_OFFSET_MS = 60_000L
