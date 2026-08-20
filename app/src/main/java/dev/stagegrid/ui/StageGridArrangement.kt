package dev.stagegrid.ui

import dev.stagegrid.StageGridApplication
import dev.stagegrid.arrangement.ArrangementGraph
import dev.stagegrid.arrangement.ArrangementGraphStore
import dev.stagegrid.arrangement.ArrangementRuntime
import dev.stagegrid.arrangement.ArrangementRuntimeState
import dev.stagegrid.audio.EngineState
import dev.stagegrid.audio.PlayerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.IdentityHashMap

private class ArrangementUiController(private val viewModel: StageGridViewModel) {
    private val app: StageGridApplication = viewModel.getApplication()
    private val store = ArrangementGraphStore(app.filesDir)
    private val _state = MutableStateFlow(ArrangementRuntimeState())
    val state: StateFlow<ArrangementRuntimeState> = _state.asStateFlow()

    private var loadedSongId: String? = null
    private var previousPositionMs: Long = 0L
    private var previousSectionId: String? = null
    private var previousPlaying = false
    private var previousCountingIn = false

    init {
        viewModel.viewModelScope.launch {
            viewModel.player.collectLatest { player -> onPlayerState(player) }
        }
    }

    private suspend fun onPlayerState(player: PlayerState) {
        val song = player.song
        if (song == null) {
            loadedSongId = null
            previousPlaying = false
            previousCountingIn = false
            _state.value = ArrangementRuntimeState()
            return
        }
        if (loadedSongId != song.id) {
            loadedSongId = song.id
            val graph = withContext(Dispatchers.IO) {
                store.loadOrCreate(song.id, ArrangementGraph.fromSections(song.id, player.sections))
            }
            _state.value = ArrangementRuntimeState(graph = reconcile(graph, player))
            previousPositionMs = player.positionMs
            previousSectionId = player.currentSection?.id
            previousPlaying = player.isPlaying
            previousCountingIn = player.isCountingIn
            return
        }

        val runtime = _state.value
        if (!runtime.active || runtime.graph == null) {
            previousPositionMs = player.positionMs
            previousSectionId = player.currentSection?.id
            previousPlaying = player.isPlaying
            previousCountingIn = player.isCountingIn
            return
        }

        // Starting Arrangement while stopped must never seek to the next node on its own.
        // Arm the active node only when normal playback actually begins, or when a pre-roll/count-in
        // finishes and the song enters its audible section.
        val playbackBecameReady = player.isPlaying && !player.isCountingIn && (!previousPlaying || previousCountingIn)
        if (playbackBecameReady && runtime.queuedNodeId == null) {
            configureActiveNode(player, runtime)
        }

        val currentSection = player.currentSection
        val latestRuntime = _state.value
        val queued = latestRuntime.queuedNode
        if (queued != null && currentSection?.id == queued.sectionId && previousSectionId != currentSection.id) {
            _state.value = latestRuntime.copy(
                activeNodeId = queued.id,
                queuedNodeId = null,
                iteration = 1,
                exitRequested = false,
            )
            configureActiveNode(player, _state.value)
        } else {
            val activeRuntime = _state.value
            val active = activeRuntime.activeNode
            val activeSection = activeRuntime.graph?.sectionFor(active, player.sections)
            val loopWrapped = activeSection != null &&
                previousSectionId == activeSection.id && currentSection?.id == activeSection.id &&
                previousPositionMs > activeSection.startMs + (activeSection.endMs - activeSection.startMs) / 2 &&
                player.positionMs < activeSection.startMs + (activeSection.endMs - activeSection.startMs) / 3

            if (loopWrapped && active != null) {
                val nextIteration = activeRuntime.iteration + 1
                if (!active.infinite && nextIteration >= active.repeatCount) {
                    _state.value = activeRuntime.copy(iteration = nextIteration)
                    app.audio.exitLoop()
                    queueNext(player, _state.value.copy(iteration = active.repeatCount))
                } else {
                    _state.value = activeRuntime.copy(iteration = nextIteration)
                }
            }

            val latest = _state.value
            if (latest.exitRequested && latest.activeNode?.infinite == true && player.loopSectionId != null) {
                app.audio.exitLoop()
                queueNext(player, latest)
            }
        }
        previousPositionMs = player.positionMs
        previousSectionId = currentSection?.id
        previousPlaying = player.isPlaying
        previousCountingIn = player.isCountingIn
    }

    private fun reconcile(graph: ArrangementGraph, player: PlayerState): ArrangementGraph {
        val validSections = player.sections.map { it.id }.toSet()
        val kept = graph.nodes.filter { it.sectionId in validSections }
        val missing = player.sections.filter { section -> kept.none { it.sectionId == section.id } }
        return graph.copy(
            nodes = kept + ArrangementGraph.fromSections(graph.songId, missing).nodes.mapIndexed { index, node ->
                node.copy(order = kept.size + index)
            },
        ).normalized()
    }

    fun start() {
        val player = viewModel.player.value
        val graph = _state.value.graph ?: return
        if (graph.nodes.isEmpty()) return
        val currentSectionId = player.currentSection?.id
        val active = graph.nodes.firstOrNull { it.sectionId == currentSectionId } ?: graph.nodes.first()
        _state.value = _state.value.copy(
            active = true,
            activeNodeId = active.id,
            queuedNodeId = null,
            iteration = 1,
            exitRequested = false,
            error = null,
        )
        val section = graph.sectionFor(active, player.sections) ?: return
        if (!player.isPlaying) {
            // When stopped, arming Arrangement is state-only. A configured pre-roll is the only
            // operation allowed to initiate transport, and it targets the current active node.
            if (active.preRollBars > 0) launchStoppedNodeWithPreRoll(active.id, section.id, active.preRollBars)
            return
        }
        if (player.currentSection?.id != active.sectionId) app.audio.queueOrJumpSection(section)
        configureActiveNode(player, _state.value)
    }

    fun stop() {
        app.audio.exitLoop()
        _state.value = _state.value.copy(active = false, queuedNodeId = null, exitRequested = false, iteration = 1)
    }

    fun requestExit() {
        val current = _state.value
        if (!current.active || current.activeNode?.infinite != true) return
        _state.value = current.copy(exitRequested = true)
    }

    fun selectNode(nodeId: String) {
        val player = viewModel.player.value
        val graph = _state.value.graph ?: return
        val node = graph.nodes.firstOrNull { it.id == nodeId } ?: return
        val section = graph.sectionFor(node, player.sections) ?: return
        app.audio.exitLoop()
        app.audio.queueOrJumpSection(section)
        _state.value = _state.value.copy(
            active = true,
            queuedNodeId = node.id,
            exitRequested = false,
            error = null,
        )
        if (!player.isPlaying) {
            _state.value = _state.value.copy(activeNodeId = node.id, queuedNodeId = null, iteration = 1)
            if (node.preRollBars > 0) launchStoppedNodeWithPreRoll(node.id, section.id, node.preRollBars)
        }
    }

    private fun launchStoppedNodeWithPreRoll(nodeId: String, sectionId: String, bars: Int) {
        viewModel.viewModelScope.launch {
            val deadline = System.currentTimeMillis() + PRE_ROLL_SEEK_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val player = viewModel.player.value
                if (player.currentSection?.id == sectionId && player.engineState !in setOf(EngineState.LOADING, EngineState.SEEKING)) break
                delay(30)
            }
            val latest = viewModel.player.value
            if (_state.value.activeNodeId != nodeId || latest.currentSection?.id != sectionId) return@launch
            app.audio.setCountInBars(bars)
            app.audio.playCurrentSectionWithCountIn()
        }
    }

    fun move(nodeId: String, delta: Int) = mutate { ArrangementRuntime.move(it, nodeId, delta) }
    fun repeat(nodeId: String, count: Int) = mutate { ArrangementRuntime.setRepeat(it, nodeId, count) }
    fun preRoll(nodeId: String, bars: Int) = mutate { ArrangementRuntime.setPreRoll(it, nodeId, bars) }

    private fun mutate(transform: (ArrangementGraph) -> ArrangementGraph) {
        val graph = _state.value.graph ?: return
        val updated = transform(graph)
        _state.value = _state.value.copy(graph = updated)
        viewModel.viewModelScope.launch(Dispatchers.IO) { store.save(updated) }
    }

    private fun configureActiveNode(player: PlayerState, runtime: ArrangementRuntimeState) {
        val graph = runtime.graph ?: return
        val active = runtime.activeNode ?: return
        val section = graph.sectionFor(active, player.sections) ?: return
        if (active.repeatCount != 1) {
            if (player.currentSection?.id == section.id && player.loopSectionId != section.id) app.audio.toggleCurrentSectionLoop()
        } else {
            queueNext(player, runtime)
        }
    }

    private fun queueNext(player: PlayerState, runtime: ArrangementRuntimeState) {
        val graph = runtime.graph ?: return
        val active = runtime.activeNode ?: return
        if (runtime.queuedNodeId != null) return
        val next = ArrangementRuntime.advance(
            graph = graph,
            activeNodeId = active.id,
            iteration = runtime.iteration,
            exitRequested = runtime.exitRequested,
        )
        if (next.finished || next.destination == null) return
        val destination = graph.sectionFor(next.destination, player.sections) ?: return
        if (destination.id == player.currentSection?.id) return
        app.audio.queueOrJumpSection(destination)
        _state.value = _state.value.copy(
            queuedNodeId = next.destination.id,
            iteration = next.nextIteration,
            exitRequested = false,
        )
    }

    private companion object {
        const val PRE_ROLL_SEEK_TIMEOUT_MS = 2_000L
    }
}

private object ArrangementUiRegistry {
    private val controllers = IdentityHashMap<StageGridViewModel, ArrangementUiController>()
    @Synchronized fun controller(viewModel: StageGridViewModel): ArrangementUiController =
        controllers.getOrPut(viewModel) { ArrangementUiController(viewModel) }
}

val StageGridViewModel.arrangementState: StateFlow<ArrangementRuntimeState>
    get() = ArrangementUiRegistry.controller(this).state

fun StageGridViewModel.startArrangement() = ArrangementUiRegistry.controller(this).start()
fun StageGridViewModel.stopArrangement() = ArrangementUiRegistry.controller(this).stop()
fun StageGridViewModel.exitArrangementLoop() = ArrangementUiRegistry.controller(this).requestExit()
fun StageGridViewModel.selectArrangementNode(nodeId: String) = ArrangementUiRegistry.controller(this).selectNode(nodeId)
fun StageGridViewModel.moveArrangementNode(nodeId: String, delta: Int) = ArrangementUiRegistry.controller(this).move(nodeId, delta)
fun StageGridViewModel.setArrangementRepeat(nodeId: String, count: Int) = ArrangementUiRegistry.controller(this).repeat(nodeId, count)
fun StageGridViewModel.setArrangementPreRoll(nodeId: String, bars: Int) = ArrangementUiRegistry.controller(this).preRoll(nodeId, bars)
