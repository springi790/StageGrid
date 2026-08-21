package dev.stagegrid.ui.components

import androidx.compose.animation.core.CubicBezierEasing

/**
 * Shared motion language for StageGrid.
 *
 * Live audio surfaces intentionally stay fast: no continuous animation is attached to the waveform,
 * playhead or meters. These tokens are for discrete UI state changes only.
 */
object StageMotion {
    const val InstantMs = 90
    const val QuickMs = 130
    const val ShortMs = 180
    const val MediumMs = 260
    const val LongMs = 360

    val Standard = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val Emphasized = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
}
