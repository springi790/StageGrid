package dev.stagegrid.ui.startup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.stagegrid.R
import dev.stagegrid.audio.ClickSubdivision
import dev.stagegrid.ui.components.StageGridPanel
import dev.stagegrid.ui.components.StageMotion
import dev.stagegrid.ui.theme.StageGridColors

private const val QUICK_SETUP_STEPS = 4

data class QuickSetupChoices(
    val liveMode: Boolean,
    val performanceLock: Boolean,
    val clickSubdivision: ClickSubdivision,
    val countInBars: Int,
    val guideLanguage: String,
)

@Composable
fun StageGridSplashScreen(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "stagegrid-splash")
    val logoScale by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(720, easing = StageMotion.Standard),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "stagegrid-logo-pulse",
    )
    val glowAlpha by transition.animateFloat(
        initialValue = 0.16f,
        targetValue = 0.34f,
        animationSpec = infiniteRepeatable(
            animation = tween(720, easing = StageMotion.Standard),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "stagegrid-logo-glow",
    )

    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        StageGridColors.Canvas,
                        Color(0xFF06080C),
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(28.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier.size(118.dp).scale(logoScale),
                    shape = RoundedCornerShape(32.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = glowAlpha),
                    shadowElevation = 18.dp,
                ) {}
                Surface(
                    modifier = Modifier.size(94.dp).scale(logoScale),
                    shape = RoundedCornerShape(26.dp),
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher_foreground),
                            contentDescription = null,
                            modifier = Modifier.size(86.dp),
                        )
                    }
                }
            }
            Text(
                text = "STAGEGRID",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black,
                letterSpacing = MaterialTheme.typography.headlineLarge.letterSpacing,
            )
            Text(
                text = stringResource(R.string.startup_tagline),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(0.48f),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            )
            Text(
                stringResource(R.string.startup_loading),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun QuickSetupScreen(
    initialLiveMode: Boolean,
    initialPerformanceLock: Boolean,
    initialClickSubdivision: ClickSubdivision,
    initialCountInBars: Int,
    initialGuideLanguage: String,
    onSkip: () -> Unit,
    onFinish: (QuickSetupChoices) -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var liveMode by rememberSaveable { mutableStateOf(initialLiveMode) }
    var performanceLock by rememberSaveable { mutableStateOf(initialPerformanceLock) }
    var clickSubdivisionName by rememberSaveable { mutableStateOf(initialClickSubdivision.name) }
    var countInBars by rememberSaveable { mutableIntStateOf(initialCountInBars.coerceIn(0, 2)) }
    var guideLanguage by rememberSaveable {
        mutableStateOf(initialGuideLanguage.takeIf { it in setOf("auto", "es", "en", "fr", "pt") } ?: "auto")
    }

    val clickSubdivision = remember(clickSubdivisionName) {
        ClickSubdivision.entries.firstOrNull { it.name == clickSubdivisionName } ?: ClickSubdivision.QUARTER
    }
    val progress = (step + 1f) / QUICK_SETUP_STEPS.toFloat()

    Column(
        modifier
            .fillMaxSize()
            .background(StageGridColors.Canvas)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.quick_setup_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    stringResource(R.string.quick_setup_subtitle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            TextButton(onClick = onSkip) { Text(stringResource(R.string.quick_setup_skip)) }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.quick_setup_step, step + 1, QUICK_SETUP_STEPS),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val forward = targetState > initialState
                    val enter = fadeIn(tween(StageMotion.ShortMs)) + slideInHorizontally(
                        animationSpec = tween(StageMotion.MediumMs, easing = StageMotion.Standard),
                        initialOffsetX = { if (forward) it / 5 else -it / 5 },
                    )
                    val exit = fadeOut(tween(StageMotion.QuickMs)) + slideOutHorizontally(
                        animationSpec = tween(StageMotion.ShortMs, easing = StageMotion.Standard),
                        targetOffsetX = { if (forward) -it / 6 else it / 6 },
                    )
                    enter togetherWith exit
                },
                label = "quick-setup-step",
            ) { currentStep ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (currentStep) {
                        0 -> StageSetupStep(
                            title = stringResource(R.string.quick_setup_stage_title),
                            description = stringResource(R.string.quick_setup_stage_desc),
                        ) {
                            SetupSwitchRow(
                                title = stringResource(R.string.quick_setup_live_mode),
                                description = stringResource(R.string.quick_setup_live_mode_desc),
                                checked = liveMode,
                                onCheckedChange = { liveMode = it },
                            )
                            SetupSwitchRow(
                                title = stringResource(R.string.quick_setup_performance_lock),
                                description = stringResource(R.string.quick_setup_performance_lock_desc),
                                checked = performanceLock,
                                onCheckedChange = { performanceLock = it },
                            )
                        }

                        1 -> StageSetupStep(
                            title = stringResource(R.string.quick_setup_click_title),
                            description = stringResource(R.string.quick_setup_click_desc),
                        ) {
                            Text(stringResource(R.string.quick_setup_subdivision), fontWeight = FontWeight.Bold)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                ClickSubdivision.entries.forEach { subdivision ->
                                    FilterChip(
                                        selected = clickSubdivision == subdivision,
                                        onClick = { clickSubdivisionName = subdivision.name },
                                        label = { Text(subdivision.label) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.quick_setup_count_in), fontWeight = FontWeight.Bold)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                listOf(
                                    0 to stringResource(R.string.quick_setup_no_count_in),
                                    1 to stringResource(R.string.quick_setup_one_bar),
                                    2 to stringResource(R.string.quick_setup_two_bars),
                                ).forEach { (bars, label) ->
                                    FilterChip(
                                        selected = countInBars == bars,
                                        onClick = { countInBars = bars },
                                        label = { Text(label) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }

                        2 -> StageSetupStep(
                            title = stringResource(R.string.quick_setup_guide_title),
                            description = stringResource(R.string.quick_setup_guide_desc),
                        ) {
                            val languages = listOf(
                                "auto" to stringResource(R.string.quick_setup_guide_auto),
                                "es" to "Español",
                                "en" to "English",
                                "fr" to "Français",
                                "pt" to "Português",
                            )
                            languages.forEach { (code, label) ->
                                FilterChip(
                                    selected = guideLanguage == code,
                                    onClick = { guideLanguage = code },
                                    label = { Text(label) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }

                        else -> StageSetupStep(
                            title = stringResource(R.string.quick_setup_summary_title),
                            description = stringResource(R.string.quick_setup_summary_desc),
                        ) {
                            val enabledLabel = stringResource(R.string.quick_setup_on)
                            val disabledLabel = stringResource(R.string.quick_setup_off)
                            SummaryLine(stringResource(R.string.quick_setup_summary_live, if (liveMode) enabledLabel else disabledLabel))
                            SummaryLine(stringResource(R.string.quick_setup_summary_lock, if (performanceLock) enabledLabel else disabledLabel))
                            SummaryLine(stringResource(R.string.quick_setup_summary_click, clickSubdivision.label))
                            SummaryLine(
                                stringResource(
                                    R.string.quick_setup_summary_count,
                                    when (countInBars) {
                                        1 -> stringResource(R.string.quick_setup_one_bar)
                                        2 -> stringResource(R.string.quick_setup_two_bars)
                                        else -> stringResource(R.string.quick_setup_no_count_in)
                                    },
                                ),
                            )
                            SummaryLine(
                                stringResource(
                                    R.string.quick_setup_summary_guide,
                                    when (guideLanguage) {
                                        "es" -> "Español"
                                        "en" -> "English"
                                        "fr" -> "Français"
                                        "pt" -> "Português"
                                        else -> stringResource(R.string.quick_setup_guide_auto)
                                    },
                                ),
                            )
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (step > 0) {
                OutlinedButton(onClick = { step-- }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.quick_setup_back))
                }
            }
            Button(
                onClick = {
                    if (step < QUICK_SETUP_STEPS - 1) {
                        step++
                    } else {
                        onFinish(
                            QuickSetupChoices(
                                liveMode = liveMode,
                                performanceLock = performanceLock,
                                clickSubdivision = clickSubdivision,
                                countInBars = countInBars,
                                guideLanguage = guideLanguage,
                            ),
                        )
                    }
                },
                modifier = Modifier.weight(if (step > 0) 1f else 2f),
            ) {
                Text(
                    if (step == QUICK_SETUP_STEPS - 1) stringResource(R.string.quick_setup_finish)
                    else stringResource(R.string.quick_setup_next),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun StageSetupStep(
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    StageGridPanel(accent = MaterialTheme.colorScheme.primary) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun SetupSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SummaryLine(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
    ) {
        Text(text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), fontWeight = FontWeight.SemiBold)
    }
}
