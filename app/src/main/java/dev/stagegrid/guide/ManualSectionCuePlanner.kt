package dev.stagegrid.guide

import dev.stagegrid.guide.GuidePackManager.CueKind
import kotlin.math.roundToLong

/**
 * Deterministic cue plan for manually-authored sections.
 *
 * The bar immediately before the section boundary is the cue bar. Beat 1 announces the section
 * name. When [includeCount] is enabled, beats 2..N speak their beat number. This is deliberately
 * independent of Native Guide analysis: the section list + musical grid are the source of truth.
 */
object ManualSectionCuePlanner {
    data class Event(
        val key: String,
        val kind: CueKind,
        val atMs: Long,
    )

    data class Plan(
        val cueBarStartMs: Long,
        val sectionBoundaryMs: Long,
        val events: List<Event>,
    )

    fun plan(
        sectionKey: String,
        sectionBoundaryMs: Long,
        barDurationMs: Double,
        beatsPerBar: Int,
        includeCount: Boolean,
    ): Plan? {
        if (sectionKey.isBlank() || sectionBoundaryMs <= 0L) return null
        val barMs = barDurationMs.takeIf { it.isFinite() && it > 0.0 }?.roundToLong()?.coerceAtLeast(1L)
            ?: return null
        val safeBeats = beatsPerBar.coerceIn(1, MAX_SPOKEN_BEATS)
        val cueStart = (sectionBoundaryMs - barMs).coerceAtLeast(0L)
        if (cueStart >= sectionBoundaryMs) return null

        val events = mutableListOf(
            Event(sectionKey, CueKind.SECTION, cueStart),
        )
        if (includeCount && safeBeats >= 2) {
            val beatMs = barMs.toDouble() / safeBeats.toDouble()
            for (beat in 2..safeBeats) {
                val at = cueStart + ((beat - 1) * beatMs).roundToLong()
                if (at < sectionBoundaryMs) events += Event(beat.toString(), CueKind.COUNT, at)
            }
        }
        return Plan(cueStart, sectionBoundaryMs, events)
    }

    private const val MAX_SPOKEN_BEATS = 8
}
