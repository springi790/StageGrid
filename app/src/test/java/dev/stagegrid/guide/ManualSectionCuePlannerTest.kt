package dev.stagegrid.guide

import dev.stagegrid.guide.GuidePackManager.CueKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ManualSectionCuePlannerTest {
    @Test
    fun fourFourCountPlacesSectionOnOneAndNumbersOnTwoThreeFour() {
        val plan = ManualSectionCuePlanner.plan(
            sectionKey = "intro",
            sectionBoundaryMs = 8_000L,
            barDurationMs = 2_000.0,
            beatsPerBar = 4,
            includeCount = true,
        )

        assertNotNull(plan)
        val events = requireNotNull(plan).events
        assertEquals(listOf("intro", "2", "3", "4"), events.map { it.key })
        assertEquals(listOf(CueKind.SECTION, CueKind.COUNT, CueKind.COUNT, CueKind.COUNT), events.map { it.kind })
        assertEquals(listOf(6_000L, 6_500L, 7_000L, 7_500L), events.map { it.atMs })
    }

    @Test
    fun cueWithoutCountOnlyAnnouncesSectionOnBeatOne() {
        val plan = requireNotNull(
            ManualSectionCuePlanner.plan(
                sectionKey = "chorus",
                sectionBoundaryMs = 12_000L,
                barDurationMs = 2_400.0,
                beatsPerBar = 4,
                includeCount = false,
            ),
        )
        assertEquals(1, plan.events.size)
        assertEquals("chorus", plan.events.single().key)
        assertEquals(9_600L, plan.events.single().atMs)
    }
}
