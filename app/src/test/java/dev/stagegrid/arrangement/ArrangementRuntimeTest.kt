package dev.stagegrid.arrangement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrangementRuntimeTest {
    private val graph = ArrangementGraph(
        songId = "song",
        nodes = listOf(
            ArrangementNode(id = "a", sectionId = "s1", label = "Verse", order = 0, repeatCount = 2),
            ArrangementNode(id = "b", sectionId = "s2", label = "Chorus", order = 1, repeatCount = -1),
            ArrangementNode(id = "c", sectionId = "s3", label = "Bridge", order = 2),
        ),
    )

    @Test fun finiteRepeatStaysThenAdvances() {
        val repeat = ArrangementRuntime.advance(graph, "a", 1, exitRequested = false)
        assertEquals("a", repeat.destination?.id)
        assertEquals(2, repeat.nextIteration)
        val advance = ArrangementRuntime.advance(graph, "a", 2, exitRequested = false)
        assertEquals("b", advance.destination?.id)
        assertEquals(1, advance.nextIteration)
    }

    @Test fun infiniteRepeatHonorsExitAtBoundary() {
        val loop = ArrangementRuntime.advance(graph, "b", 7, exitRequested = false)
        assertEquals("b", loop.destination?.id)
        assertFalse(loop.finished)
        val exit = ArrangementRuntime.advance(graph, "b", 7, exitRequested = true)
        assertEquals("c", exit.destination?.id)
        assertEquals(1, exit.nextIteration)
    }

    @Test fun lastNodeFinishes() {
        val result = ArrangementRuntime.advance(graph, "c", 1, exitRequested = false)
        assertTrue(result.finished)
        assertEquals(null, result.destination)
    }

    @Test fun liveReorderKeepsStableNodeIds() {
        val moved = ArrangementRuntime.move(graph, "c", -2)
        assertEquals(listOf("c", "a", "b"), moved.nodes.map { it.id })
    }

    @Test fun stoppedArrangementStartsFromFirstEditedNode() {
        val edited = ArrangementRuntime.move(graph, "c", -2)
        val start = ArrangementRuntime.startingNode(
            graph = edited,
            currentSectionId = "s1",
            isPlaying = false,
        )
        assertEquals("c", start?.id)
    }

    @Test fun enablingWhileAlreadyPlayingKeepsCurrentMusicalSection() {
        val edited = ArrangementRuntime.move(graph, "c", -2)
        val start = ArrangementRuntime.startingNode(
            graph = edited,
            currentSectionId = "s1",
            isPlaying = true,
        )
        assertEquals("a", start?.id)
    }
}
