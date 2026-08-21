package dev.stagegrid.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * StageGrid glyph set.
 *
 * The bottom navigation used to render `item.name.take(1)`, which produces `L S P M A S` — six
 * destinations, two of them showing the same letter, and nothing recognisable at a glance in stage
 * light. These glyphs are drawn straight onto a [Canvas] from normalised coordinates, so they stay
 * crisp at any size, follow the Neon Slate content colour and add no icon dependency to the build.
 */
enum class StageIcon {
    LIBRARY,
    SETLISTS,
    LIVE,
    MIXER,
    ADVANCED,
    SETTINGS,
    PLAY,
    PAUSE,
    STOP,
    PANIC,
    PREVIOUS,
    NEXT,
}

@Composable
fun StageGlyph(
    icon: StageIcon,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = LocalContentColor.current,
) {
    // Captured into a local so the assignment below cannot resolve back to the write-only
    // semantics property that shares this name.
    val description = contentDescription
    val semanticsModifier = if (description == null) {
        modifier
    } else {
        modifier.semantics { this.contentDescription = description }
    }
    Canvas(semanticsModifier.size(size)) { drawStageIcon(icon, tint) }
}

private fun DrawScope.drawStageIcon(icon: StageIcon, tint: Color) {
    val side = kotlin.math.min(size.width, size.height)
    val left = (size.width - side) / 2f
    val top = (size.height - side) / 2f
    fun x(fraction: Float) = left + side * fraction
    fun y(fraction: Float) = top + side * fraction
    fun u(fraction: Float) = side * fraction
    val ring = Stroke(width = u(0.085f), cap = StrokeCap.Round)

    when (icon) {
        // Four rounded tiles: a *collection* of songs.
        StageIcon.LIBRARY -> {
            val tile = u(0.34f)
            listOf(0.12f to 0.12f, 0.54f to 0.12f, 0.12f to 0.54f, 0.54f to 0.54f).forEach { (fx, fy) ->
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(x(fx), y(fy)),
                    size = Size(tile, tile),
                    cornerRadius = CornerRadius(u(0.09f)),
                )
            }
        }
        // Bullet + line rows: an *ordered list* of songs.
        StageIcon.SETLISTS -> {
            listOf(0.22f, 0.5f, 0.78f).forEach { fy ->
                drawCircle(color = tint, radius = u(0.075f), center = Offset(x(0.17f), y(fy)))
                drawLine(
                    color = tint,
                    start = Offset(x(0.36f), y(fy)),
                    end = Offset(x(0.86f), y(fy)),
                    strokeWidth = u(0.1f),
                    cap = StrokeCap.Round,
                )
            }
        }
        // Play triangle inside a ring: the live workspace.
        StageIcon.LIVE -> {
            drawCircle(color = tint, radius = u(0.4f), center = Offset(x(0.5f), y(0.5f)), style = ring)
            drawPath(trianglePath(x(0.4f), y(0.3f), u(0.32f), u(0.4f)), tint)
        }
        // Vertical faders: the mixer.
        StageIcon.MIXER -> {
            listOf(0.2f to 0.62f, 0.5f to 0.34f, 0.8f to 0.72f).forEach { (fx, knob) ->
                drawLine(
                    color = tint,
                    start = Offset(x(fx), y(0.12f)),
                    end = Offset(x(fx), y(0.88f)),
                    strokeWidth = u(0.075f),
                    cap = StrokeCap.Round,
                )
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(x(fx) - u(0.16f), y(knob) - u(0.08f)),
                    size = Size(u(0.32f), u(0.16f)),
                    cornerRadius = CornerRadius(u(0.08f)),
                )
            }
        }
        // Horizontal sliders: the advanced/detailed controls. Deliberately the opposite axis from
        // the mixer faders so the two never read as the same icon.
        StageIcon.ADVANCED -> {
            listOf(0.22f to 0.66f, 0.5f to 0.34f, 0.78f to 0.58f).forEach { (fy, knob) ->
                drawLine(
                    color = tint,
                    start = Offset(x(0.12f), y(fy)),
                    end = Offset(x(0.88f), y(fy)),
                    strokeWidth = u(0.075f),
                    cap = StrokeCap.Round,
                )
                drawCircle(color = tint, radius = u(0.115f), center = Offset(x(knob), y(fy)))
            }
        }
        // Gear: six full-diameter spokes make twelve teeth, and a solid hub hides their crossing.
        StageIcon.SETTINGS -> {
            repeat(6) { index ->
                rotate(degrees = index * 30f, pivot = Offset(x(0.5f), y(0.5f))) {
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset(x(0.5f) - u(0.075f), y(0.5f) - u(0.46f)),
                        size = Size(u(0.15f), u(0.92f)),
                        cornerRadius = CornerRadius(u(0.06f)),
                    )
                }
            }
            drawCircle(color = tint, radius = u(0.31f), center = Offset(x(0.5f), y(0.5f)))
        }
        StageIcon.PLAY -> drawPath(trianglePath(x(0.24f), y(0.14f), u(0.58f), u(0.72f)), tint)
        StageIcon.PAUSE -> {
            listOf(0.24f, 0.56f).forEach { fx ->
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(x(fx), y(0.16f)),
                    size = Size(u(0.2f), u(0.68f)),
                    cornerRadius = CornerRadius(u(0.06f)),
                )
            }
        }
        StageIcon.STOP -> drawRoundRect(
            color = tint,
            topLeft = Offset(x(0.22f), y(0.22f)),
            size = Size(u(0.56f), u(0.56f)),
            cornerRadius = CornerRadius(u(0.1f)),
        )
        // Square inside a ring: "stop everything", visibly heavier than a plain stop.
        StageIcon.PANIC -> {
            drawCircle(color = tint, radius = u(0.42f), center = Offset(x(0.5f), y(0.5f)), style = ring)
            drawRoundRect(
                color = tint,
                topLeft = Offset(x(0.34f), y(0.34f)),
                size = Size(u(0.32f), u(0.32f)),
                cornerRadius = CornerRadius(u(0.07f)),
            )
        }
        StageIcon.PREVIOUS -> {
            drawPath(mirroredTrianglePath(x(0.3f), y(0.18f), u(0.44f), u(0.64f)), tint)
            drawRoundRect(
                color = tint,
                topLeft = Offset(x(0.18f), y(0.18f)),
                size = Size(u(0.11f), u(0.64f)),
                cornerRadius = CornerRadius(u(0.05f)),
            )
        }
        StageIcon.NEXT -> {
            drawPath(trianglePath(x(0.26f), y(0.18f), u(0.44f), u(0.64f)), tint)
            drawRoundRect(
                color = tint,
                topLeft = Offset(x(0.71f), y(0.18f)),
                size = Size(u(0.11f), u(0.64f)),
                cornerRadius = CornerRadius(u(0.05f)),
            )
        }
    }
}

/** Right-pointing triangle anchored at [left]/[top]. */
private fun trianglePath(left: Float, top: Float, width: Float, height: Float): Path = Path().apply {
    moveTo(left, top)
    lineTo(left + width, top + height / 2f)
    lineTo(left, top + height)
    close()
}

/** Left-pointing triangle anchored at [left]/[top]. */
private fun mirroredTrianglePath(left: Float, top: Float, width: Float, height: Float): Path = Path().apply {
    moveTo(left + width, top)
    lineTo(left, top + height / 2f)
    lineTo(left + width, top + height)
    close()
}
