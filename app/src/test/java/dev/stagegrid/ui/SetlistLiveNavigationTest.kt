package dev.stagegrid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetlistLiveNavigationTest {
    private val ids = listOf("a", "b", "c")

    @Test
    fun initialIndex_prefersLoadedSongWhenItBelongsToSetlist() {
        assertEquals(1, SetlistLiveNavigation.initialIndex(ids, "b"))
    }

    @Test
    fun initialIndex_fallsBackToFirstSong() {
        assertEquals(0, SetlistLiveNavigation.initialIndex(ids, "outside"))
        assertEquals(-1, SetlistLiveNavigation.initialIndex(emptyList(), null))
    }

    @Test
    fun nextAndPreviousRespectEdges() {
        assertNull(SetlistLiveNavigation.previousIndex(0, ids.size))
        assertEquals(0, SetlistLiveNavigation.previousIndex(1, ids.size))
        assertEquals(2, SetlistLiveNavigation.nextIndex(1, ids.size))
        assertNull(SetlistLiveNavigation.nextIndex(2, ids.size))
    }
}
