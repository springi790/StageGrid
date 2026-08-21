package dev.stagegrid.ui

import androidx.lifecycle.viewModelScope
import dev.stagegrid.StageGridApplication
import dev.stagegrid.audio.GuideSource
import dev.stagegrid.debug.StageGridDebugLog
import dev.stagegrid.guide.GuideCueLoader
import dev.stagegrid.guide.ManualSectionCuePlanner
import dev.stagegrid.model.OutputBus
import dev.stagegrid.model.SectionEntity
import dev.stagegrid.model.StereoRoute
import dev.stagegrid.model.TrackType
import dev.stagegrid.music.MusicalGrid
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runtime state for the manual-section Guide source. */
data class StageGridCueUiState(
    val source: GuideSource = GuideSource.ORIGINAL,
    val preparing: Boolean = false,
    val scheduledSectionId: String? = null,
    val error: String? = null,
)

/**
 * Manual-section Cue playback is deliberately independent of Native Guide analysis.
 *
 * Source ORIGINAL restores the imported Guide track's persisted mute state. Source CUE keeps all
 * imported Guide tracks muted at the native mixer and schedules short Guide Pack samples against
 * the authoritative musical grid. The Room TrackEntity remains unchanged, so switching back to
 * ORIGINAL restores exactly what the user had configured in Mixer.
 */
private class StageGridCueController(private val viewModel: StageGridViewModel) {
    private val app: StageGridApplication = viewModel.getApplication()
    private val _state = MutableStateFlow(StageGridCueUiState())
    val state: StateFlow<StageGridCueUiState> = _state.asStateFlow()

    private val renderSerial = AtomicLong(0L)
    private var renderJob: Job? = null
    private var appliedMuteFingerprint: String? = null
    private var scheduledToken: String? = null
    private var loadedSongId: String? = null

    init {
        viewModel.viewModelScope.launch {
            viewModel.player.collect { player ->
                if (loadedSongId != player.song?.id) {
                    loadedSongId = player.song?.id
                    invalidateSchedule("song_change")
                    appliedMuteFingerprint = null
                }
                applySourceMutes(player)
                maybeSchedule(player)
            }
        }
    }

    fun setSource(source: GuideSource) {
        if (_state.value.source == source) return
        if (source == GuideSource.CUE && !app.guidePacks.status().installed) {
            StageGridDebugLog.warning("CUE", "SOURCE_CUE rejected: no Guide Pack installed")
            _state.value = _state.value.copy(error = "Install Guide samples before selecting Cue.")
            return
        }
        StageGridDebugLog.action("CUE", "SOURCE ${_state.value.source.name} -> ${source.name}")
        invalidateSchedule("source_change")
        _state.value = _state.value.copy(source = source, preparing = false, error = null)
        appliedMuteFingerprint = null
        val player = viewModel.player.value
        applySourceMutes(player)
        maybeSchedule(player)
    }

    private fun applySourceMutes(player: dev.stagegrid.audio.PlayerState) {
        if (player.song == null) {
            appliedMuteFingerprint = null
            return
        }
        val source = _state.value.source
        val guideEntries = player.tracks.mapIndexedNotNull { index, track ->
            if (TrackType.fromStorage(track.type) == TrackType.GUIDE) index to track else null
        }
        val fingerprint = buildString {
            append(player.song.id).append('|').append(source.name)
            guideEntries.forEach { (index, track) ->
                append('|').append(index).append(':').append(track.id).append(':').append(track.muted)
            }
        }
        if (fingerprint == appliedMuteFingerprint) return

        guideEntries.forEach { (index, track) ->
            val nativeMuted = source == GuideSource.CUE || track.muted
            app.nativeAudio.setTrackMute(index, nativeMuted)
        }
        appliedMuteFingerprint = fingerprint
        StageGridDebugLog.state(
            "CUE",
            "SOURCE_APPLIED song=${player.song.id} source=${source.name} guideTracks=${guideEntries.size}",
        )
    }

    private fun maybeSchedule(player: dev.stagegrid.audio.PlayerState) {
        if (_state.value.source != GuideSource.CUE || !player.guideEnabled || !player.isPlaying) {
            if ((!player.isPlaying || !player.guideEnabled) && hasScheduledWork()) invalidateSchedule("inactive")
            return
        }
        val song = player.song ?: return
        val grid = MusicalGrid.from(song.bpm, song.timeSignature, song.gridOffsetMs) ?: return
        val targetAndBoundary = queuedTarget(player) ?: chronologicalTarget(player) ?: return
        val (target, boundaryMs) = targetAndBoundary
        if (boundaryMs <= player.positionMs + CUE_PUBLISH_LEAD_MS) return

        val token = "${song.id}:${target.id}:$boundaryMs:${target.name}:${target.cueCountEnabled}:${grid.barDurationMs}"
        if (scheduledToken == token) return
        scheduledToken = token
        val serial = renderSerial.incrementAndGet()
        renderJob?.cancel()
        renderJob = viewModel.viewModelScope.launch {
            renderManualCue(serial, player, target, boundaryMs, grid)
        }
    }

    private fun queuedTarget(player: dev.stagegrid.audio.PlayerState): Pair<SectionEntity, Long>? {
        val id = player.queuedSectionId ?: return null
        val boundary = player.queuedJumpAtMs ?: return null
        val section = player.sections.firstOrNull { it.id == id } ?: return null
        return section to boundary
    }

    private fun chronologicalTarget(player: dev.stagegrid.audio.PlayerState): Pair<SectionEntity, Long>? {
        val target = player.sections
            .sortedWith(compareBy<SectionEntity> { it.startMs }.thenBy { it.sortOrder })
            .firstOrNull { it.startMs > player.positionMs + SECTION_BOUNDARY_EPSILON_MS }
            ?: return null
        return target to target.startMs
    }

    private suspend fun renderManualCue(
        serial: Long,
        snapshot: dev.stagegrid.audio.PlayerState,
        target: SectionEntity,
        boundaryMs: Long,
        grid: MusicalGrid,
    ) {
        val sectionKey = app.guidePacks.canonicalCueKey(target.name)
        val plan = ManualSectionCuePlanner.plan(
            sectionKey = sectionKey,
            sectionBoundaryMs = boundaryMs,
            barDurationMs = grid.barDurationMs,
            beatsPerBar = grid.signature.beatsPerBar,
            includeCount = target.cueCountEnabled,
        ) ?: return

        _state.value = _state.value.copy(preparing = true, scheduledSectionId = target.id, error = null)
        StageGridDebugLog.state(
            "CUE",
            "PREPARE section=${target.id} name=${target.name} key=$sectionKey count=${target.cueCountEnabled} boundaryMs=$boundaryMs",
        )

        val result = withContext(Dispatchers.IO) {
            runCatching {
                val language = app.guidePacks.resolveCueLanguage() ?: error("No Guide Pack language is available.")
                val sampleRate = app.nativeAudio.sampleRate().coerceAtLeast(1)
                val now = app.nativeAudio.positionMs().coerceAtLeast(0L)
                val earliest = now + CUE_PUBLISH_LEAD_MS
                val pendingEvents = plan.events.filter { it.atMs >= earliest }
                if (pendingEvents.isEmpty()) return@runCatching null
                val cueAtMs = pendingEvents.minOf { it.atMs }
                val maximumFrames = sampleRate * MAX_CUE_SECONDS
                val availableFrames = ((boundaryMs - cueAtMs).coerceAtLeast(1L) * sampleRate / 1000L)
                    .coerceAtMost(maximumFrames.toLong())
                    .toInt()
                if (availableFrames <= 0) return@runCatching null

                val mix = FloatArray(availableFrames)
                var rendered = 0
                pendingEvents.forEach { event ->
                    if (serial != renderSerial.get()) return@runCatching null
                    val sample = app.guidePacks.findCueSample(language, event.key, event.kind)
                    if (sample == null) {
                        StageGridDebugLog.state(
                            "CUE",
                            "SAMPLE_MISSING language=$language key=${event.key} kind=${event.kind.name}",
                        )
                        return@forEach
                    }
                    val pcm = GuideCueLoader.loadMonoResampled(sample.file, sampleRate)
                    val offsetFrames = ((event.atMs - cueAtMs) * sampleRate / 1000L).toInt()
                    if (offsetFrames !in 0 until availableFrames) return@forEach
                    val copyFrames = minOf(pcm.size, availableFrames - offsetFrames)
                    for (i in 0 until copyFrames) {
                        val index = offsetFrames + i
                        mix[index] = (mix[index] + pcm[i]).coerceIn(-1f, 1f)
                    }
                    rendered++
                    StageGridDebugLog.state(
                        "CUE",
                        "EVENT section=${target.id} beatAtMs=${event.atMs} key=${event.key} kind=${event.kind.name}",
                    )
                }
                if (rendered == 0) return@runCatching null

                val guideTrack = snapshot.tracks.firstOrNull { TrackType.fromStorage(it.type) == TrackType.GUIDE }
                val anySolo = snapshot.tracks.any { it.solo }
                if (guideTrack?.muted == true || (guideTrack != null && anySolo && !guideTrack.solo)) {
                    StageGridDebugLog.state("CUE", "SKIP_MUTED section=${target.id}")
                    return@runCatching null
                }

                val route = guideTrack?.let { StereoRoute.fromStorage(it.outputRoute) } ?: StereoRoute.BOTH
                val bus = guideTrack?.let { OutputBus.fromStorage(it.outputBus) } ?: OutputBus.OUT_1_2
                val volume = guideTrack?.volume ?: 1f
                ManualRender(mix, cueAtMs, boundaryMs, route, bus, volume, language, rendered)
            }
        }

        if (serial != renderSerial.get()) return
        result.fold(
            onSuccess = { render ->
                if (render == null) {
                    _state.value = _state.value.copy(preparing = false)
                    return@fold
                }
                val latest = viewModel.player.value
                if (_state.value.source != GuideSource.CUE || !latest.guideEnabled || latest.song?.id != snapshot.song?.id) {
                    return@fold
                }
                val scheduled = app.nativeAudio.scheduleGuideCue(
                    monoSamples = render.samples,
                    atMs = render.atMs,
                    suppressUntilMs = render.untilMs,
                    route = render.route,
                    bus = render.bus,
                    volume = render.volume,
                )
                StageGridDebugLog.state(
                    "CUE",
                    "SCHEDULED ok=$scheduled section=${target.id} atMs=${render.atMs} boundaryMs=${render.untilMs} language=${render.language} events=${render.events}",
                )
                _state.value = _state.value.copy(
                    preparing = false,
                    scheduledSectionId = if (scheduled) target.id else null,
                    error = if (scheduled) null else "Could not schedule the section cue.",
                )
            },
            onFailure = { error ->
                StageGridDebugLog.error("CUE", "PREPARE_FAILED section=${target.id} message=${error.message}", error)
                _state.value = _state.value.copy(
                    preparing = false,
                    scheduledSectionId = null,
                    error = error.message ?: "Could not prepare the section cue.",
                )
            },
        )
    }

    private fun hasScheduledWork(): Boolean =
        scheduledToken != null || renderJob?.isActive == true || _state.value.scheduledSectionId != null

    private fun invalidateSchedule(reason: String) {
        if (!hasScheduledWork()) return
        scheduledToken = null
        renderSerial.incrementAndGet()
        renderJob?.cancel()
        renderJob = null
        app.nativeAudio.clearGuideCue()
        StageGridDebugLog.state("CUE", "INVALIDATE reason=$reason")
        _state.value = _state.value.copy(preparing = false, scheduledSectionId = null)
    }

    private data class ManualRender(
        val samples: FloatArray,
        val atMs: Long,
        val untilMs: Long,
        val route: StereoRoute,
        val bus: OutputBus,
        val volume: Float,
        val language: String,
        val events: Int,
    )

    private companion object {
        const val CUE_PUBLISH_LEAD_MS = 90L
        const val SECTION_BOUNDARY_EPSILON_MS = 20L
        const val MAX_CUE_SECONDS = 8
    }
}

private object StageGridCueRegistry {
    private val controllers = IdentityHashMap<StageGridViewModel, StageGridCueController>()

    @Synchronized
    fun controller(viewModel: StageGridViewModel): StageGridCueController =
        controllers.getOrPut(viewModel) { StageGridCueController(viewModel) }
}

val StageGridViewModel.cueGuideState: StateFlow<StageGridCueUiState>
    get() = StageGridCueRegistry.controller(this).state

fun StageGridViewModel.setGuideSource(source: GuideSource) =
    StageGridCueRegistry.controller(this).setSource(source)
