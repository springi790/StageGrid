package dev.stagegrid.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stagegrid.R
import dev.stagegrid.model.SectionEntity
import dev.stagegrid.ui.theme.StageGridColors
import dev.stagegrid.waveform.WaveformPeakCache
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    val playedWave = MaterialTheme.colorScheme.primary
    val futureWave = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.46f)
    val playhead = StageGridColors.Cyan
    val boundary = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f)
    val centerLine = StageGridColors.OutlineStrong.copy(alpha = 0.55f)
    val description = stringResource(R.string.waveform_description)
    val shape = RoundedCornerShape(18.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(112.dp)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(StageGridColors.SurfaceBright, StageGridColors.SurfaceRaised, StageGridColors.Surface),
                ),
            )
            .border(1.dp, StageGridColors.OutlineStrong, shape)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        when (val state = uiState) {
            WaveformUiState.Loading -> Text(
                stringResource(R.string.waveform_generating),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
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
                    val centerY = size.height / 2f
                    val width = size.width.coerceAtLeast(1f)
                    val playheadX = (positionMs.toFloat() / effectiveDuration.toFloat()).coerceIn(0f, 1f) * width

                    drawLine(centerLine, Offset(0f, centerY), Offset(width, centerY), strokeWidth = 1f)

                    val buckets = data.bucketCount.coerceAtLeast(1)
                    val step = width / buckets
                    for (index in 0 until buckets) {
                        val x = (index + 0.5f) * step
                        val minY = centerY - data.min[index].coerceIn(-1f, 1f) * centerY * 0.82f
                        val maxY = centerY - data.max[index].coerceIn(-1f, 1f) * centerY * 0.82f
                        drawLine(
                            color = if (x <= playheadX) playedWave else futureWave,
                            start = Offset(x, minY),
                            end = Offset(x, maxY),
                            strokeWidth = step.coerceIn(1f, 3.2f),
                        )
                    }

                    sections.forEach { section ->
                        val x = (section.startMs.toFloat() / effectiveDuration.toFloat()).coerceIn(0f, 1f) * width
                        drawLine(boundary, Offset(x, 8f), Offset(x, size.height - 8f), strokeWidth = 1.2f)
                        drawCircle(boundary, radius = 3.2f, center = Offset(x, 8f))
                    }

                    drawLine(playhead, Offset(playheadX, 0f), Offset(playheadX, size.height), strokeWidth = 2.6f)
                    drawCircle(playhead, radius = 5.4f, center = Offset(playheadX, 8f))
                }
            }
        }
    }
}
