package dev.stagegrid.guide

import kotlin.math.roundToLong

data class ArrangementGuideCuePlan(
    val cueAtMs: Long,
    val sectionBoundaryMs: Long,
)

/**
 * Plans a spoken target-section cue without changing the section-boundary transition itself.
 * Early choices are announced one musical bar before the current section ends; late choices are
 * announced shortly after the tap only when there is still enough useful time before the boundary.
 */
object ArrangementGuideCuePlanner {
    fun plan(
        currentPositionMs: Long,
        sectionBoundaryMs: Long,
        barDurationMs: Double,
        publishLeadMs: Long = 90L,
        minimumUsefulWindowMs: Long = 350L,
    ): ArrangementGuideCuePlan? {
        if (sectionBoundaryMs <= currentPositionMs) return null
        val bar = barDurationMs.takeIf { it.isFinite() && it > 0.0 }?.roundToLong() ?: return null
        val earliest = currentPositionMs + publishLeadMs.coerceAtLeast(0L)
        if (sectionBoundaryMs - earliest < minimumUsefulWindowMs.coerceAtLeast(1L)) return null
        val ideal = sectionBoundaryMs - bar
        return ArrangementGuideCuePlan(
            cueAtMs = maxOf(ideal, earliest),
            sectionBoundaryMs = sectionBoundaryMs,
        )
    }
}
