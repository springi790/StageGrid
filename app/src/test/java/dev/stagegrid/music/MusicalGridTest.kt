package dev.stagegrid.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicalGridTest {
    @Test
    fun convertsBarsAndBeatsWithOffset() {
        val grid = MusicalGrid(120.0, "4/4", 100L)

        assertEquals(MusicalPosition(1, 1), grid.positionAt(100L))
        assertEquals(MusicalPosition(1, 2), grid.positionAt(600L))
        assertEquals(MusicalPosition(2, 1), grid.positionAt(2_100L))
        assertEquals(2_100L, grid.msAt(MusicalPosition(2, 1)))
    }

    @Test
    fun snapsToNearestBeatAndBar() {
        val grid = MusicalGrid(120.0, "4/4", 100L)

        assertEquals(600L, grid.snap(740L, GridSnap.BEAT))
        assertEquals(2_100L, grid.snap(1_840L, GridSnap.BAR))
    }

    @Test
    fun nextSectionJumpCanUseEndOfCurrentBar() {
        val grid = MusicalGrid(120.0, "4/4", 100L)

        assertEquals(2_100L, grid.endOfBarContaining(740L))
        assertEquals(4_100L, grid.endOfBarContaining(2_100L))
    }

    @Test
    fun numeratorControlsBeatsPerBar() {
        val grid = MusicalGrid(60.0, "6/8", 0L)

        assertEquals(MusicalPosition(1, 6), grid.positionAt(5_000L))
        assertEquals(MusicalPosition(2, 1), grid.positionAt(6_000L))
    }

    @Test
    fun factoryRejectsMissingOrOutOfRangeBpm() {
        assertNull(MusicalGrid.from(null, "4/4", 0L))
        assertNull(MusicalGrid.from(10.0, "4/4", 0L))
    }
}
