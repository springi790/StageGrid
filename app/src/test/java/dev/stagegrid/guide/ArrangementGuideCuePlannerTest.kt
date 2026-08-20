package dev.stagegrid.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArrangementGuideCuePlannerTest {
    @Test
    fun earlySelectionAnnouncesOneBarBeforeBoundary() {
        val plan = ArrangementGuideCuePlanner.plan(
            currentPositionMs = 10_000,
            sectionBoundaryMs = 20_000,
            barDurationMs = 2_000.0,
        )

        assertEquals(18_000L, plan?.cueAtMs)
        assertEquals(20_000L, plan?.sectionBoundaryMs)
    }

    @Test
    fun selectionInsideFinalBarMovesCueJustAfterTap() {
        val plan = ArrangementGuideCuePlanner.plan(
            currentPositionMs = 18_500,
            sectionBoundaryMs = 20_000,
            barDurationMs = 2_000.0,
        )

        assertEquals(18_590L, plan?.cueAtMs)
    }

    @Test
    fun selectionTooCloseToBoundarySkipsSpokenCue() {
        val plan = ArrangementGuideCuePlanner.plan(
            currentPositionMs = 19_700,
            sectionBoundaryMs = 20_000,
            barDurationMs = 2_000.0,
        )

        assertNull(plan)
    }

    @Test
    fun invalidGridCannotPlanCue() {
        assertNull(
            ArrangementGuideCuePlanner.plan(
                currentPositionMs = 1_000,
                sectionBoundaryMs = 5_000,
                barDurationMs = Double.NaN,
            ),
        )
    }
}
