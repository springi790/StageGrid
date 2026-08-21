package dev.stagegrid.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.stagegrid.ui.theme.StageGridColors

@Composable
fun StageGridScreenHeader(
    kicker: String,
    title: String,
    subtitle: String? = null,
    badge: String? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = tween(StageMotion.MediumMs, easing = StageMotion.Standard),
            )
            .background(
                brush = Brush.horizontalGradient(
                    listOf(StageGridColors.SurfaceRaised, StageGridColors.Surface, StageGridColors.Canvas),
                ),
                shape = RoundedCornerShape(24.dp),
            )
            .border(1.dp, StageGridColors.Outline, RoundedCornerShape(24.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    kicker.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                badge?.let { StageGridPill(it, accent = MaterialTheme.colorScheme.tertiary) }
            }
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun StageGridPanel(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable () -> Unit,
) {
    val targetBorder = accent?.copy(alpha = 0.45f) ?: StageGridColors.Outline
    val borderColor by animateColorAsState(
        targetValue = targetBorder,
        animationSpec = tween(StageMotion.ShortMs, easing = StageMotion.Standard),
        label = "stage-panel-border",
    )
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = tween(StageMotion.MediumMs, easing = StageMotion.Standard),
            )
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(20.dp),
            ),
        color = StageGridColors.SurfaceRaised,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(Modifier.padding(14.dp)) { content() }
    }
}

@Composable
fun StageGridPill(
    text: String,
    accent: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
) {
    val animatedAccent by animateColorAsState(
        targetValue = accent,
        animationSpec = tween(StageMotion.ShortMs, easing = StageMotion.Standard),
        label = "stage-pill-accent",
    )
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(100.dp),
        color = animatedAccent.copy(alpha = 0.13f),
        contentColor = animatedAccent,
        tonalElevation = 0.dp,
    ) {
        Text(
            text.uppercase(),
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Black,
        )
    }
}

@Composable
fun StageGridMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    val animatedAccent by animateColorAsState(
        targetValue = accent,
        animationSpec = tween(StageMotion.ShortMs, easing = StageMotion.Standard),
        label = "stage-metric-accent",
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = animatedAccent)
    }
}

/** A reusable, action-oriented empty state instead of a dead-end message. */
@Composable
fun StageGridEmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    StageGridPanel(modifier = modifier, accent = MaterialTheme.colorScheme.primary) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text("STAGEGRID", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
            }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
                    Text(actionLabel, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
