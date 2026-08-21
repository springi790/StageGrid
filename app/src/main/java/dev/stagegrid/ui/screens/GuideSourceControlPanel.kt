package dev.stagegrid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
    val cueAvailable = guidePack.status.installed
    val songLoaded = player.song != null

    StageGridPanel(modifier = modifier, accent = StageGridColors.Violet) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.guide_source),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = cueState.source == GuideSource.ORIGINAL,
                    onClick = { stageGridViewModel.setGuideSource(GuideSource.ORIGINAL) },
                    label = { Text(stringResource(R.string.guide_source_off)) },
                    enabled = songLoaded,
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = cueState.source == GuideSource.CUE,
                    onClick = { stageGridViewModel.setGuideSource(GuideSource.CUE) },
                    label = { Text(stringResource(R.string.guide_source_on)) },
                    enabled = songLoaded && cueAvailable,
                    modifier = Modifier.weight(1f),
                )
            }

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
