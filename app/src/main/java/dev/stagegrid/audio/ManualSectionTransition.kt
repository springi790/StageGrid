package dev.stagegrid.audio

/**
 * Manual section changes are musical section changes, not generic grid jumps.
 *
 * A request made while a section is playing must let that section finish and
 * enter the requested section exactly at the current section's end marker.
 * The Musical Grid remains useful for authoring/snap/count-in, but it must not
 * move a manual section exit to an earlier bar line inside the current section.
 */
internal object ManualSectionTransition {
    fun boundary(currentPositionMs: Long, currentSectionEndMs: Long): Long? =
        currentSectionEndMs.takeIf { it > currentPositionMs }
}
