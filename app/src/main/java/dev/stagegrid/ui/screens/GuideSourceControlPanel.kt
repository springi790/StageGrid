package dev.stagegrid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.stagegrid.R
import dev.stagegrid.audio.GuideSource
import dev.stagegrid.ui.StageGridViewModel
import dev.stagegrid.ui.cueGuideState
import dev.stagegrid.ui.setGuideSource
import dev.stagegrid.ui.components.StageGridPanel
import dev.stagegrid.ui.theme.StageGridColors

/** Compact two-position Guide source selector shown in Mixer. */
@Composable
fun GuideSourceControlHost(
    modifier: Modifier = Modifier,
    stageGridViewModel: StageGridViewModel = viewModel(),
) {
    val cueState by stageGridViewModel.cueGuideState.collectAsStateWithLifecycle()
    val player by stageGridViewModel.player.collectAsStateWithLifecycle()
    val guidePack by stageGridViewModel.guidePackState.collectAsStateWithLifecycle()
    val target = if (cueState.source == GuideSource.CUE) 1f else 0f
    var sliderValue by remember(target) { mutableFloatStateOf(target) }
    val cueAvailable = guidePack.status.installed
    val enabled = player.song != null && (cueAvailable || cueState.source == GuideSource.CUE)

    StageGridPanel(modifier = modifier, accent = StageGridColors.Violet) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.guide_source),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    stringResource(R.string.guide_source_original),
                    fontWeight = if (cueState.source == GuideSource.ORIGINAL) FontWeight.Bold else FontWeight.Normal,
                )
                Text(
                    stringResource(R.string.guide_source_cue),
                    fontWeight = if (cueState.source == GuideSource.CUE) FontWeight.Bold else FontWeight.Normal,
                )
            }
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = {
                    val selected = if (sliderValue >= 0.5f) GuideSource.CUE else GuideSource.ORIGINAL
                    sliderValue = if (selected == GuideSource.CUE) 1f else 0f
                    stageGridViewModel.setGuideSource(selected)
                },
                valueRange = 0f..1f,
                steps = 0,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                if (cueAvailable) stringResource(R.string.guide_source_cue_help)
                else stringResource(R.string.guide_source_cue_unavailable),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            cueState.error?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
