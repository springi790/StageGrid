package dev.stagegrid.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.stagegrid.R
import dev.stagegrid.model.SectionEntity
import dev.stagegrid.music.MusicalGrid
import dev.stagegrid.ui.theme.StageGridColors

/**
 * Sections already carry a distinct `colorArgb`, assigned at import/analysis time, but the live UI
 * never showed it. Colour is the fastest channel a performer has, so the same hue now identifies a
 * section on the waveform and on its jump button.
 */
fun sectionDisplayColor(section: SectionEntity): Color =
    Color(section.colorArgb.toInt()).copy(alpha = 1f)

fun formatStageClock(ms: Long): String {
    val total = ms.coerceAtLeast(0L) / 1000L
    return "%d:%02d".format(total / 60L, total % 60L)
}

/**
 * Transport dock.
 *
 * The live surface is a single scrolling column, so the transport used to sit below the waveform,
 * the DSP panel, the now/next block and the section rail — off-screen on a phone. A musician cannot
 * scroll to find Stop in the middle of a song, so the transport is pinned here instead and the
 * scrolling content ends above it.
 */
@Composable
fun StageTransportDock(
    isPlaying: Boolean,
    enabled: Boolean,
    positionMs: Long,
    durationMs: Long,
    countInText: String?,
    setlistActive: Boolean,
    hasPreviousSong: Boolean,
    hasNextSong: Boolean,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onStopAll: () -> Unit,
    onPreviousSong: () -> Unit,
    onNextSong: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val dockShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(dockShape)
            .background(StageGridColors.Surface)
            .border(width = 1.dp, color = StageGridColors.Outline, shape = dockShape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (countInText != null) {
            // While counting in, the dock takes over: nothing else on screen matters until the
            // stems drop, and the musician needs the number, not a row of disabled buttons.
            Text(
                countInText,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.count_in_click_only),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatStageClock(positionMs), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(formatStageClock(durationMs), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onPlayPause,
                enabled = enabled,
                modifier = Modifier.weight(1.6f).heightIn(min = 60.dp),
            ) {
                StageGlyph(if (isPlaying) StageIcon.PAUSE else StageIcon.PLAY, contentDescription = null, size = 20.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isPlaying) stringResource(R.string.workspace_pause) else stringResource(R.string.workspace_play),
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                )
            }
            OutlinedButton(
                onClick = onStop,
                enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = 60.dp),
            ) {
                StageGlyph(StageIcon.STOP, contentDescription = null, size = 18.dp)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.workspace_stop), maxLines = 1)
            }
            Button(
                onClick = onStopAll,
                modifier = Modifier.weight(1f).heightIn(min = 60.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                StageGlyph(StageIcon.PANIC, contentDescription = null, size = 18.dp)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.workspace_stop_all), fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }

        if (setlistActive) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onPreviousSong,
                    enabled = hasPreviousSong && enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                ) {
                    StageGlyph(StageIcon.PREVIOUS, contentDescription = null, size = 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.workspace_previous_song), maxLines = 1)
                }
                OutlinedButton(
                    onClick = onNextSong,
                    enabled = hasNextSong && enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                ) {
                    Text(stringResource(R.string.workspace_next_song), maxLines = 1)
                    Spacer(Modifier.width(6.dp))
                    StageGlyph(StageIcon.NEXT, contentDescription = null, size = 16.dp)
                }
            }
        }
    }
}

/**
 * Global mini transport shown above the navigation bar on every screen except the live workspace.
 * Leaving the workspace to move a fader or check a setting used to mean losing every transport
 * control until you navigated back.
 */
@Composable
fun StageMiniTransport(
    songTitle: String,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    enabled: Boolean,
    onOpenLive: () -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val openLabel = stringResource(R.string.cd_open_live)
    Row(
        modifier
            .fillMaxWidth()
            .background(StageGridColors.SurfaceRaised)
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = openLabel, onClick = onOpenLive)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Text(
                songTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${formatStageClock(positionMs)} / ${formatStageClock(durationMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onPlayPause, enabled = enabled) {
            StageGlyph(
                if (isPlaying) StageIcon.PAUSE else StageIcon.PLAY,
                contentDescription = if (isPlaying) stringResource(R.string.cd_pause) else stringResource(R.string.cd_play),
                size = 22.dp,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(onClick = onStop, enabled = enabled) {
            StageGlyph(
                StageIcon.STOP,
                contentDescription = stringResource(R.string.cd_stop),
                size = 18.dp,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Large, colour-coded section target. 64dp minimum height, because this is the control people press
 * mid-song, on a dark stage, without looking straight at the screen — a 32dp default `FilterChip`
 * is not a realistic target under those conditions.
 */
@Composable
fun StageSectionButton(
    label: String,
    accent: Color,
    isCurrent: Boolean,
    isQueued: Boolean,
    enabled: Boolean,
    supportingText: String?,
    clickLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val queuedColor = MaterialTheme.colorScheme.tertiary
    val border = when {
        isQueued -> queuedColor
        isCurrent -> accent
        else -> StageGridColors.Outline
    }
    val background = when {
        isCurrent -> accent.copy(alpha = 0.26f)
        isQueued -> queuedColor.copy(alpha = 0.18f)
        else -> StageGridColors.SurfaceRaised
    }
    val shape = RoundedCornerShape(16.dp)

    Row(
        modifier
            .heightIn(min = 64.dp)
            .widthIn(min = 116.dp)
            .clip(shape)
            .background(background)
            .border(width = if (isCurrent || isQueued) 2.dp else 1.dp, color = border, shape = shape)
            .clickable(enabled = enabled, onClickLabel = clickLabel, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(width = 6.dp, height = 34.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(accent),
        )
        Column {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            supportingText?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Visual metronome. A click-driven player should let the musician confirm the grid with their eyes
 * during setup instead of having to trust the in-ear mix.
 */
@Composable
fun StageBeatPulse(
    grid: MusicalGrid?,
    positionMs: Long,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    if (grid == null) return
    val position = grid.positionAt(positionMs)
    val accent = MaterialTheme.colorScheme.primary
    val downbeat = MaterialTheme.colorScheme.secondary
    val idle = StageGridColors.OutlineStrong
    val description = stringResource(R.string.cd_beat_pulse, position.bar, position.beat)

    Row(
        modifier.semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(grid.signature.beatsPerBar) { index ->
            val isCurrent = active && index == position.beat - 1
            val color = when {
                isCurrent && index == 0 -> downbeat
                isCurrent -> accent
                else -> idle
            }
            Box(Modifier.height(16.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(if (isCurrent) 15.dp else 10.dp)) {
                    drawCircle(color = color, radius = size.minDimension / 2f)
                }
            }
        }
    }
}
