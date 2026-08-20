package dev.stagegrid.arrangement

/** Pure arrangement-path logic. It never owns the audio clock. */
object ArrangementRuntime {
    data class Advance(
        val destination: ArrangementNode?,
        val nextIteration: Int,
        val finished: Boolean,
    )

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
        return graph.copy(nodes = nodes).normalized()
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
