package dev.stagegrid.arrangement

import dev.stagegrid.model.SectionEntity
import java.util.UUID

data class ArrangementNode(
    val id: String = UUID.randomUUID().toString(),
    val sectionId: String,
    val label: String,
    val order: Int,
    /** -1 means repeat until Exit is requested; otherwise 1..16 plays. */
    val repeatCount: Int = 1,
    val preRollBars: Int = 0,
    val guideEnabled: Boolean = true,
) {
    val infinite: Boolean get() = repeatCount < 0
}

data class ArrangementGraph(
    val songId: String,
    val nodes: List<ArrangementNode>,
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
) {
    fun normalized(): ArrangementGraph = copy(
        nodes = nodes
            .sortedWith(compareBy<ArrangementNode> { it.order }.thenBy { it.label })
            .mapIndexed { index, node ->
                node.copy(
                    order = index,
                    repeatCount = if (node.repeatCount < 0) -1 else node.repeatCount.coerceIn(1, 16),
                    preRollBars = node.preRollBars.coerceIn(0, 2),
                )
            },
    )

    fun sectionFor(node: ArrangementNode?, sections: List<SectionEntity>): SectionEntity? =
        node?.let { value -> sections.firstOrNull { it.id == value.sectionId } }

    companion object {
        fun fromSections(songId: String, sections: List<SectionEntity>): ArrangementGraph = ArrangementGraph(
            songId = songId,
            nodes = sections.sortedBy { it.sortOrder }.mapIndexed { index, section ->
                ArrangementNode(
                    sectionId = section.id,
                    label = section.name,
                    order = index,
                )
            },
        )
    }
}

data class ArrangementRuntimeState(
    val graph: ArrangementGraph? = null,
    val active: Boolean = false,
    val activeNodeId: String? = null,
    val queuedNodeId: String? = null,
    val iteration: Int = 1,
    val exitRequested: Boolean = false,
    val editing: Boolean = false,
    val error: String? = null,
) {
    val activeNode: ArrangementNode?
        get() = graph?.nodes?.firstOrNull { it.id == activeNodeId }
    val queuedNode: ArrangementNode?
        get() = graph?.nodes?.firstOrNull { it.id == queuedNodeId }
}
