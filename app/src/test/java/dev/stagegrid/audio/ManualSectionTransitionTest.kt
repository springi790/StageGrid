package dev.stagegrid.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualSectionTransitionTest {
    @Test
    fun manualChangeWaitsForCurrentSectionEnd() {
        assertEquals(
            20_000L,
            ManualSectionTransition.boundary(
                currentPositionMs = 16_000L,
                currentSectionEndMs = 20_000L,
            ),
        )
    }

    @Test
    fun internalBarLinesDoNotAffectManualSectionBoundary() {
        assertEquals(
            40_000L,
            ManualSectionTransition.boundary(
                currentPositionMs = 31_500L,
                currentSectionEndMs = 40_000L,
            ),
        )
    }

    @Test
    fun passedBoundaryRequestsImmediateFallback() {
        assertNull(
            ManualSectionTransition.boundary(
                currentPositionMs = 20_001L,
                currentSectionEndMs = 20_000L,
            ),
        )
    }
}
