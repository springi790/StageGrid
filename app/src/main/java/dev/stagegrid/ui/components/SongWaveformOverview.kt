package dev.stagegrid.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stagegrid.R
import dev.stagegrid.model.SectionEntity
import dev.stagegrid.waveform.WaveformPeakCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface WaveformUiState {
    data object Loading : WaveformUiState
    data class Ready(val data: WaveformPeakCache.PeakData) : WaveformUiState
    data class Failed(val message: String?) : WaveformUiState
}

/**
 * Lightweight overview generated from app-private playback WAVs. Peak generation and disk access
 * run on Dispatchers.IO and never touch the realtime audio callback.
 */
@Composable
fun SongWaveformOverview(
    songId: String,
    positionMs: Long,
    durationMs: Long,
    sections: List<SectionEntity> = emptyList(),
    onSeek: ((Long) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val uiState by produceState<WaveformUiState>(WaveformUiState.Loading, songId) {
        value = runCatching {
            withContext(Dispatchers.IO) {
                val root = File(context.filesDir, "library/$songId")
                WaveformPeakCache.loadOrGenerate(root)
            }
        }.fold(
            onSuccess = { WaveformUiState.Ready(it) },
            onFailure = { WaveformUiState.Failed(it.message) },
        )
    }

    val surface = MaterialTheme.colorScheme.surfaceVariant
    val waveform = MaterialTheme.colorScheme.onSurfaceVariant
    val playhead = MaterialTheme.colorScheme.primary
    val boundary = MaterialTheme.colorScheme.outline
    val description = stringResource(R.string.waveform_description)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        when (val state = uiState) {
            WaveformUiState.Loading -> Text(stringResource(R.string.waveform_generating), style = MaterialTheme.typography.labelMedium)
            is WaveformUiState.Failed -> Text(
                state.message?.takeIf { it.isNotBlank() } ?: stringResource(R.string.waveform_unavailable),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            is WaveformUiState.Ready -> {
                val data = state.data
                val effectiveDuration = durationMs.takeIf { it > 0L } ?: data.durationMs.coerceAtLeast(1L)
                Canvas(
                    Modifier
                        .matchParentSize()
                        .pointerInput(songId, effectiveDuration, onSeek) {
                            if (onSeek != null) {
                                detectTapGestures { point ->
                                    val fraction = (point.x / size.width.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                                    onSeek((fraction * effectiveDuration).toLong())
                                }
                            }
                        },
                ) {
                    drawRect(surface)
                    val centerY = size.height / 2f
                    val width = size.width.coerceAtLeast(1f)
                    val buckets = data.bucketCount.coerceAtLeast(1)
                    val step = width / buckets
                    for (index in 0 until buckets) {
                        val x = (index + 0.5f) * step
                        val minY = centerY - data.min[index].coerceIn(-1f, 1f) * centerY
                        val maxY = centerY - data.max[index].coerceIn(-1f, 1f) * centerY
                        drawLine(waveform, Offset(x, minY), Offset(x, maxY), strokeWidth = step.coerceIn(1f, 3f))
                    }
                    sections.forEach { section ->
                        val x = (section.startMs.toFloat() / effectiveDuration.toFloat()).coerceIn(0f, 1f) * width
                        drawLine(boundary, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                    }
                    val playheadX = (positionMs.toFloat() / effectiveDuration.toFloat()).coerceIn(0f, 1f) * width
                    drawLine(playhead, Offset(playheadX, 0f), Offset(playheadX, size.height), strokeWidth = 3f)
                }
            }
        }
    }
}
