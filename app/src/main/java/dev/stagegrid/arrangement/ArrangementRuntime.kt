package dev.stagegrid.arrangement

/** Pure arrangement-path logic. It never owns the audio clock. */
object ArrangementRuntime {
    data class Advance(
        val destination: ArrangementNode?,
        val nextIteration: Int,
        val finished: Boolean,
    )

    /**
     * Chooses the node that becomes active when Arrangement is enabled.
     *
     * While stopped, the authored arrangement order is authoritative: playback must begin from the
     * first arranged node even if the transport playhead is currently sitting inside another
     * section. While already playing, keeping the current musical section is less disruptive, so
     * the matching arranged node is used when possible.
     */
    fun startingNode(
        graph: ArrangementGraph,
        currentSectionId: String?,
        isPlaying: Boolean,
    ): ArrangementNode? {
        val ordered = graph.normalized().nodes
        if (ordered.isEmpty()) return null
        if (!isPlaying) return ordered.first()
        return ordered.firstOrNull { it.sectionId == currentSectionId } ?: ordered.first()
    }

    fun advance(
        graph: ArrangementGraph,
        activeNodeId: String,
        iteration: Int,
        exitRequested: Boolean,
    ): Advance {
        val ordered = graph.normalized().nodes
        val index = ordered.indexOfFirst { it.id == activeNodeId }
        if (index < 0) return Advance(ordered.firstOrNull(), 1, ordered.isEmpty())
        val active = ordered[index]
        val safeIteration = iteration.coerceAtLeast(1)

        val shouldRepeat = when {
            active.infinite -> !exitRequested
            safeIteration < active.repeatCount -> true
            else -> false
        }
        if (shouldRepeat) return Advance(active, safeIteration + 1, false)

        val next = ordered.getOrNull(index + 1)
        return Advance(next, 1, next == null)
    }

    fun move(graph: ArrangementGraph, nodeId: String, delta: Int): ArrangementGraph {
        if (delta == 0) return graph.normalized()
        val nodes = graph.normalized().nodes.toMutableList()
        val index = nodes.indexOfFirst { it.id == nodeId }
        if (index < 0) return graph.normalized()
        val target = (index + delta).coerceIn(0, nodes.lastIndex)
        if (target == index) return graph.normalized()
        val item = nodes.removeAt(index)
        nodes.add(target, item)
        return graph.copy(
            nodes = nodes.mapIndexed { newOrder, node -> node.copy(order = newOrder) },
        ).normalized()
    }

    fun setRepeat(graph: ArrangementGraph, nodeId: String, repeatCount: Int): ArrangementGraph = graph.copy(
        nodes = graph.nodes.map { node ->
            if (node.id == nodeId) node.copy(repeatCount = if (repeatCount < 0) -1 else repeatCount.coerceIn(1, 16)) else node
        },
    ).normalized()

    fun setPreRoll(graph: ArrangementGraph, nodeId: String, bars: Int): ArrangementGraph = graph.copy(
        nodes = graph.nodes.map { node -> if (node.id == nodeId) node.copy(preRollBars = bars.coerceIn(0, 2)) else node },
    ).normalized()
}
