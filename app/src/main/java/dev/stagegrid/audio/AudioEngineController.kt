package dev.stagegrid.audio

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.core.content.ContextCompat
import dev.stagegrid.MainActivity
import dev.stagegrid.data.LibraryRepository
import dev.stagegrid.guide.ArrangementGuideCuePlanner
import dev.stagegrid.guide.ArrangementGuideSequencePlanner
import dev.stagegrid.guide.GuideCueLoader
import dev.stagegrid.guide.GuidePackManager
import dev.stagegrid.guide.NativeGuideEventStore
import dev.stagegrid.guide.NativeGuideRenderer
import dev.stagegrid.model.OutputBus
import dev.stagegrid.model.SectionEntity
import dev.stagegrid.model.SongBundle
import dev.stagegrid.model.StereoRoute
import dev.stagegrid.model.TrackEntity
import dev.stagegrid.model.TrackType
import dev.stagegrid.music.MusicalGrid
import dev.stagegrid.service.PlaybackService
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AudioEngineController(
    private val context: Context,
    private val repository: LibraryRepository,
    private val native: NativeAudioEngine,
    private val guidePacks: GuidePackManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private val guideCueRequestSerial = AtomicLong(0L)
    private var preferredOutputDeviceId: Int? = null
    private var preferredOutputChannels: Int = 2
    private var outputSwitchInProgress = false
    private var preloadedBundle: SongBundle? = null

    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setOnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
                AudioManager.AUDIOFOCUS_GAIN -> Unit
            }
        }
        .setWillPauseWhenDucked(false)
        .build()

    init {
        scope.launch {
            while (true) {
                delay(80)
                val current = _state.value
                if (current.song != null && current.engineState !in setOf(EngineState.LOADING, EngineState.ERROR)) {
                    val rawPosition = native.positionMs()
                    val position = rawPosition.coerceAtLeast(0L)
                    val actuallyPlaying = native.isPlaying()
                    val countInRemaining = native.countInRemainingMs()
                    val crossedQueued = current.queuedSectionId?.let { queuedId ->
                        val queued = current.sections.firstOrNull { it.id == queuedId }
                        queued != null && position >= queued.startMs && position < queued.endMs
                    } ?: false
                    if (crossedQueued) guideCueRequestSerial.incrementAndGet()
                    _state.update { previous ->
                        previous.copy(
                            positionMs = position,
                            countInRemainingMs = countInRemaining,
                            countInTargetSectionId = if (countInRemaining > 0L) previous.countInTargetSectionId else null,
                            engineState = when {
                                actuallyPlaying -> EngineState.PLAYING
                                previous.engineState == EngineState.PLAYING && position >= previous.durationMs - 5 -> EngineState.READY
                                previous.engineState == EngineState.PLAYING -> EngineState.PAUSED
                                else -> previous.engineState
                            },
                            queuedSectionId = if (crossedQueued) null else previous.queuedSectionId,
                            queuedJumpAtMs = if (crossedQueued) null else previous.queuedJumpAtMs,
                            loopSectionId = if (crossedQueued) null else previous.loopSectionId,
                        )
                    }
                }
            }
        }
    }

    /** Loads a song stopped. initialPositionMs is used only for safe session recovery. */
    fun loadSong(songId: String, initialPositionMs: Long = 0L) {
        cancelArrangementGuideCue()
        native.clearPreloaded()
        preloadedBundle = null
        scope.launch {
            _state.update {
                it.copy(
                    engineState = EngineState.LOADING,
                    preloadedSongId = null,
                    preloadedSongTitle = null,
                    crossfadeInProgress = false,
                    errorMessage = null,
                )
            }
            val bundle = withContext(Dispatchers.IO) { repository.getSongBundle(songId) }
            if (bundle == null) {
                _state.update { it.copy(engineState = EngineState.ERROR, errorMessage = "Song not found") }
                return@launch
            }
            val beatsPerBar = bundle.song.timeSignature.substringBefore('/').toIntOrNull()?.coerceIn(1, 32) ?: 4
            val ok = withContext(Dispatchers.Default) {
                native.loadSong(bundle.tracks, bundle.song.bpm, beatsPerBar, bundle.song.gridOffsetMs)
            }
            if (!ok) {
                _state.update { it.copy(engineState = EngineState.ERROR, errorMessage = native.diagnostics().lastError) }
                return@launch
            }
            bundle.tracks.forEachIndexed { index, track ->
                native.setTrackVolume(index, track.volume)
                native.setTrackMute(index, track.muted)
                native.setTrackSolo(index, track.solo)
                native.setTrackPan(index, track.pan)
                native.setTrackOutputRoute(index, StereoRoute.fromStorage(track.outputRoute))
                native.setTrackOutputBus(index, OutputBus.fromStorage(track.outputBus))
            }
            val previous = _state.value
            native.setClickSubdivision(previous.clickSubdivision.subdivisionsPerBeat)
            native.setClickRoute(previous.clickRoute)
            native.setClickOutputBus(previous.clickBus)
            native.setClickEnabled(previous.clickEnabled)
            native.setGuideEnabled(previous.guideEnabled)
            native.setMasterVolume(previous.masterVolume)
            val duration = native.durationMs().takeIf { it > 0 } ?: bundle.song.durationMs
            val recoveredPosition = initialPositionMs.coerceIn(0L, duration.coerceAtLeast(0L))
            if (recoveredPosition > 0L) {
                withContext(Dispatchers.Default) { native.seekToMs(recoveredPosition) }
            }
            val diagnostics = native.diagnostics()
            _state.value = PlayerState(
                engineState = EngineState.READY,
                song = bundle.song,
                tracks = bundle.tracks,
                sections = bundle.sections,
                positionMs = native.positionMs().coerceAtLeast(0L),
                durationMs = duration,
                clickEnabled = previous.clickEnabled,
                guideEnabled = previous.guideEnabled,
                clickSubdivision = previous.clickSubdivision,
                clickRoute = previous.clickRoute,
                clickBus = previous.clickBus,
                countInBars = previous.countInBars,
                masterVolume = previous.masterVolume,
                selectedOutputDeviceId = previous.selectedOutputDeviceId,
                requestedOutputChannels = diagnostics.requestedOutputChannelCount,
                outputChannelCount = diagnostics.outputChannelCount,
                outputFallback = diagnostics.multichannelFallback,
                outputNotice = previous.outputNotice,
            )
        }
    }

    /** Prepares a complete second song deck without replacing or pausing the current song. */
    fun preloadSong(songId: String) {
        val current = _state.value
        if (songId == current.song?.id || songId == current.preloadedSongId) return
        scope.launch {
            val bundle = withContext(Dispatchers.IO) { repository.getSongBundle(songId) }
            if (bundle == null) {
                _state.update { it.copy(errorMessage = "Next song could not be found for preload.") }
                return@launch
            }
            val beatsPerBar = bundle.song.timeSignature.substringBefore('/').toIntOrNull()?.coerceIn(1, 32) ?: 4
            val ok = withContext(Dispatchers.Default) {
                native.preloadSong(bundle.tracks, bundle.song.bpm, beatsPerBar, bundle.song.gridOffsetMs)
            }
            if (!ok) {
                preloadedBundle = null
                _state.update {
                    it.copy(
                        preloadedSongId = null,
                        preloadedSongTitle = null,
                        errorMessage = native.diagnostics().lastError.ifBlank { "Next song preload failed." },
                    )
                }
                return@launch
            }
            val snapshot = _state.value
            native.configurePreloaded(
                clickEnabled = snapshot.clickEnabled,
                guideEnabled = snapshot.guideEnabled,
                clickSubdivision = snapshot.clickSubdivision.subdivisionsPerBeat,
                clickRoute = snapshot.clickRoute,
                clickBus = snapshot.clickBus,
            )
            preloadedBundle = bundle
            _state.update {
                it.copy(
                    preloadedSongId = bundle.song.id,
                    preloadedSongTitle = bundle.song.title,
                    errorMessage = null,
                )
            }
        }
    }

    fun clearPreloadedSong() {
        native.clearPreloaded()
        preloadedBundle = null
        _state.update { it.copy(preloadedSongId = null, preloadedSongTitle = null, crossfadeInProgress = false) }
    }

    /**
     * Starts the prepared deck muted, overlaps both native streams, then swaps deck ownership.
     * This is intentionally executed off the main thread because the gain ramp is time based.
     */
    fun promotePreloadedSong(crossfadeMs: Int = 700) {
        val bundle = preloadedBundle ?: return
        val before = _state.value
        if (before.crossfadeInProgress) return
        val focus = audioManager.requestAudioFocus(focusRequest)
        if (focus != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            _state.update { it.copy(errorMessage = "Audio focus was not granted by Android.") }
            return
        }
        val serviceIntent = Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_ENSURE_FOREGROUND)
        ContextCompat.startForegroundService(context, serviceIntent)
        cancelArrangementGuideCue()
        scope.launch {
            _state.update { it.copy(crossfadeInProgress = true, errorMessage = null) }
            val ok = withContext(Dispatchers.Default) {
                native.promotePreloaded(crossfadeMs = crossfadeMs, targetMasterVolume = before.masterVolume)
            }
            if (!ok) {
                _state.update {
                    it.copy(
                        crossfadeInProgress = false,
                        errorMessage = native.diagnostics().lastError.ifBlank { "Preloaded song could not be promoted." },
                    )
                }
                return@launch
            }
            preloadedBundle = null
            val diagnostics = native.diagnostics()
            val duration = native.durationMs().takeIf { it > 0L } ?: bundle.song.durationMs
            _state.value = PlayerState(
                engineState = EngineState.PLAYING,
                song = bundle.song,
                tracks = bundle.tracks,
                sections = bundle.sections,
                positionMs = native.positionMs().coerceAtLeast(0L),
                durationMs = duration,
                clickEnabled = before.clickEnabled,
                guideEnabled = before.guideEnabled,
                clickSubdivision = before.clickSubdivision,
                clickRoute = before.clickRoute,
                clickBus = before.clickBus,
                countInBars = before.countInBars,
                masterVolume = before.masterVolume,
                selectedOutputDeviceId = before.selectedOutputDeviceId,
                requestedOutputChannels = diagnostics.requestedOutputChannelCount,
                outputChannelCount = diagnostics.outputChannelCount,
                outputFallback = diagnostics.multichannelFallback,
                outputNotice = before.outputNotice,
                preloadedSongId = null,
                preloadedSongTitle = null,
                crossfadeInProgress = false,
            )
            scope.launch(Dispatchers.IO) { repository.markPlayed(bundle.song.id) }
        }
    }

    fun play() {
        val current = _state.value
        if (current.song == null || current.engineState == EngineState.LOADING) return
        val focus = audioManager.requestAudioFocus(focusRequest)
        if (focus != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            _state.update { it.copy(errorMessage = "Audio focus was not granted by Android.") }
            return
        }
        val serviceIntent = Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_ENSURE_FOREGROUND)
        ContextCompat.startForegroundService(context, serviceIntent)
        if (native.play()) {
            _state.update { it.copy(engineState = EngineState.PLAYING, errorMessage = null) }
            current.song?.id?.let { id -> scope.launch(Dispatchers.IO) { repository.markPlayed(id) } }
        }
    }

    fun pause() {
        native.pause()
        _state.update { state -> if (state.song == null) state else state.copy(engineState = EngineState.PAUSED) }
    }

    fun stop() {
        cancelArrangementGuideCue()
        _state.update { it.copy(engineState = EngineState.STOPPING) }
        scope.launch(Dispatchers.Default) {
            native.stop()
            audioManager.abandonAudioFocusRequest(focusRequest)
            context.stopService(Intent(context, PlaybackService::class.java))
            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(
                        engineState = EngineState.READY,
                        positionMs = 0,
                        loopSectionId = null,
                        queuedSectionId = null,
                        queuedJumpAtMs = null,
                        countInRemainingMs = 0,
                        countInTargetSectionId = null,
                    )
                }
            }
        }
    }

    /** Stops decoder threads and closes WAV readers before portable restore/delete replaces private files. */
    fun unloadForLibraryRestore() {
        cancelArrangementGuideCue()
        native.pause()
        native.unloadSong()
        preloadedBundle = null
        audioManager.abandonAudioFocusRequest(focusRequest)
        context.stopService(Intent(context, PlaybackService::class.java))
        val previous = _state.value
        _state.value = PlayerState(
            clickEnabled = previous.clickEnabled,
            guideEnabled = previous.guideEnabled,
            clickSubdivision = previous.clickSubdivision,
            clickRoute = previous.clickRoute,
            clickBus = previous.clickBus,
            countInBars = previous.countInBars,
            masterVolume = previous.masterVolume,
            selectedOutputDeviceId = previous.selectedOutputDeviceId,
            requestedOutputChannels = previous.requestedOutputChannels,
            outputChannelCount = previous.outputChannelCount,
            outputFallback = previous.outputFallback,
            outputNotice = previous.outputNotice,
        )
    }

    fun stopAll() {
        native.setMasterVolume(0f)
        stop()
        _state.update { it.copy(masterVolume = 0f) }
    }

    fun seekTo(ms: Long) {
        cancelArrangementGuideCue()
        val duration = _state.value.durationMs
        scope.launch {
            val returnState = if (_state.value.isPlaying) EngineState.PLAYING else EngineState.PAUSED
            _state.update {
                it.copy(
                    engineState = EngineState.SEEKING,
                    countInRemainingMs = 0,
                    countInTargetSectionId = null,
                    queuedJumpAtMs = null,
                    queuedSectionId = null,
                )
            }
            withContext(Dispatchers.Default) { native.seekToMs(ms.coerceIn(0, duration)) }
            _state.update { it.copy(engineState = returnState, positionMs = native.positionMs().coerceAtLeast(0L)) }
        }
    }

    fun setTrackVolume(index: Int, value: Float) {
        native.setTrackVolume(index, value)
        updateTrack(index) { it.copy(volume = value) }
    }

    fun setTrackMute(index: Int, value: Boolean) {
        native.setTrackMute(index, value)
        updateTrack(index) { it.copy(muted = value) }
    }

    fun setTrackSolo(index: Int, value: Boolean) {
        native.setTrackSolo(index, value)
        updateTrack(index) { it.copy(solo = value) }
    }

    fun setTrackPan(index: Int, value: Float) {
        native.setTrackPan(index, value)
        updateTrack(index) { it.copy(pan = value) }
    }

    fun setTrackOutputRoute(index: Int, route: StereoRoute) {
        native.setTrackOutputRoute(index, route)
        updateTrack(index) { it.copy(outputRoute = route.name) }
    }

    fun setTrackOutputBus(index: Int, bus: OutputBus) {
        native.setTrackOutputBus(index, bus)
        updateTrack(index) { it.copy(outputBus = bus.nativeCode) }
    }

    private fun updateTrack(index: Int, transform: (TrackEntity) -> TrackEntity) {
        val old = _state.value.tracks.getOrNull(index) ?: return
        val updated = transform(old)
        _state.update { current ->
            current.copy(tracks = current.tracks.toMutableList().also { it[index] = updated })
        }
        scope.launch(Dispatchers.IO) { repository.updateTrack(updated) }
    }

    fun setMasterVolume(value: Float) {
        native.setMasterVolume(value)
        _state.update { it.copy(masterVolume = value) }
    }

    fun setClickEnabled(enabled: Boolean) {
        native.setClickEnabled(enabled)
        _state.update { it.copy(clickEnabled = enabled) }
    }

    fun setGuideEnabled(enabled: Boolean) {
        native.setGuideEnabled(enabled)
        if (!enabled) cancelArrangementGuideCue()
        _state.update { it.copy(guideEnabled = enabled) }
    }

    fun setClickSubdivision(subdivision: ClickSubdivision) {
        native.setClickSubdivision(subdivision.subdivisionsPerBeat)
        _state.update { it.copy(clickSubdivision = subdivision) }
    }

    fun setClickRoute(route: StereoRoute) {
        native.setClickRoute(route)
        _state.update { it.copy(clickRoute = route) }
    }

    fun setClickOutputBus(bus: OutputBus) {
        native.setClickOutputBus(bus)
        _state.update { it.copy(clickBus = bus) }
    }

    fun setCountInBars(bars: Int) {
        _state.update { it.copy(countInBars = bars.coerceIn(0, 2)) }
    }

    fun playCurrentSectionWithCountIn() {
        cancelArrangementGuideCue()
        val current = _state.value
        val section = current.currentSection ?: return
        val bars = current.countInBars
        val song = current.song ?: return
        if (bars <= 0) {
            seekTo(section.startMs)
            return
        }
        if (MusicalGrid.from(song.bpm, song.timeSignature, song.gridOffsetMs) == null) {
            _state.update { it.copy(errorMessage = "Count-in requires a valid BPM.") }
            return
        }

        scope.launch {
            val prepared = withContext(Dispatchers.Default) { native.prepareCountIn(section.startMs, bars) }
            if (!prepared) {
                _state.update { it.copy(errorMessage = native.diagnostics().lastError) }
                return@launch
            }
            _state.update {
                it.copy(
                    positionMs = section.startMs,
                    countInRemainingMs = native.countInRemainingMs(),
                    countInTargetSectionId = section.id,
                    queuedSectionId = null,
                    queuedJumpAtMs = null,
                    loopSectionId = null,
                    errorMessage = null,
                )
            }
            play()
        }
    }

    fun toggleCurrentSectionLoop() {
        cancelArrangementGuideCue()
        val current = _state.value
        val section = current.currentSection ?: return
        if (current.loopSectionId == section.id) {
            native.setLoop(false, 0, 0)
            _state.update { it.copy(loopSectionId = null, queuedSectionId = null, queuedJumpAtMs = null) }
        } else {
            native.setLoop(true, section.startMs, section.endMs)
            _state.update { it.copy(loopSectionId = section.id, queuedSectionId = null, queuedJumpAtMs = null) }
        }
    }

    fun queueOrJumpSection(section: SectionEntity) {
        val current = _state.value
        if (!current.isPlaying) {
            seekTo(section.startMs)
            return
        }
        val active = current.currentSection
        if (active == null) {
            seekTo(section.startMs)
            return
        }

        val jumpAt = ManualSectionTransition.boundary(
            currentPositionMs = current.positionMs,
            currentSectionEndMs = active.endMs,
        )
        if (jumpAt == null) {
            seekTo(section.startMs)
            return
        }

        val requestSerial = guideCueRequestSerial.incrementAndGet()
        native.clearGuideCue()
        native.scheduleJump(jumpAt, section.startMs, disableLoopAfterJump = true)
        _state.update {
            it.copy(
                queuedSectionId = section.id,
                queuedJumpAtMs = jumpAt,
                countInRemainingMs = 0,
                countInTargetSectionId = null,
            )
        }
        prepareArrangementGuideCue(current, section, jumpAt, requestSerial)
    }

    private fun prepareArrangementGuideCue(
        snapshot: PlayerState,
        targetSection: SectionEntity,
        sectionBoundaryMs: Long,
        requestSerial: Long,
    ) {
        val song = snapshot.song ?: return
        if (!snapshot.guideEnabled) return
        val grid = MusicalGrid.from(song.bpm, song.timeSignature, song.gridOffsetMs) ?: return
        val plan = ArrangementGuideCuePlanner.plan(
            currentPositionMs = snapshot.positionMs,
            sectionBoundaryMs = sectionBoundaryMs,
            barDurationMs = grid.barDurationMs,
        ) ?: return

        scope.launch(Dispatchers.IO) {
            val eventFile = File(context.filesDir, "library/${song.id}/native-guide-events.json")
            val analysis = NativeGuideEventStore.readAnalysis(eventFile) ?: return@launch
            val sectionKey = NativeGuideEventStore.findSectionKey(eventFile, targetSection.startMs, targetSection.name)
                ?: return@launch
            val language = NativeGuideEventStore.readOutputLanguage(eventFile)
                ?: guidePacks.resolveOutputLanguage("auto", analysis.dominantLanguage)
                ?: return@launch
            val guideTrack = snapshot.tracks.firstOrNull { it.name == NativeGuideRenderer.TRACK_NAME }
                ?: snapshot.tracks.firstOrNull { TrackType.fromStorage(it.type) == TrackType.GUIDE }
                ?: return@launch
            val anySolo = snapshot.tracks.any { it.solo }
            if (guideTrack.muted || (anySolo && !guideTrack.solo)) return@launch

            val sampleRate = native.sampleRate().coerceAtLeast(1)
            if (requestSerial != guideCueRequestSerial.get()) return@launch
            val latest = _state.value
            if (latest.queuedSectionId != targetSection.id || latest.queuedJumpAtMs != sectionBoundaryMs) return@launch
            val now = native.positionMs().coerceAtLeast(0L)
            val cueAt = maxOf(plan.cueAtMs, now + GUIDE_PUBLISH_LEAD_MS)
            val availableMs = sectionBoundaryMs - cueAt
            if (availableMs < GUIDE_MINIMUM_USEFUL_WINDOW_MS) return@launch
            val totalFrames = (availableMs * sampleRate.toLong() / 1000L).coerceAtMost(MAX_DYNAMIC_GUIDE_FRAMES.toLong()).toInt()
            if (totalFrames <= 0) return@launch

            val sourceCues = ArrangementGuideSequencePlanner.select(
                analysis = analysis,
                targetSectionStartMs = targetSection.startMs,
                targetSectionKey = sectionKey,
                barDurationMs = grid.barDurationMs,
            )
            if (sourceCues.isEmpty()) return@launch

            val mix = FloatArray(totalFrames)
            var renderedCount = 0
            for (sourceCue in sourceCues) {
                if (requestSerial != guideCueRequestSerial.get()) return@launch
                val sample = guidePacks.findSample(language, sourceCue.key, sourceCue.kind)
                    ?: sourceCue.language?.let { guidePacks.findSample(it, sourceCue.key, sourceCue.kind) }
                    ?: continue
                val pcm = runCatching { GuideCueLoader.loadMonoResampled(sample.file, sampleRate) }.getOrNull()
                    ?: continue
                val offsetFrames = (sourceCue.offsetMs * sampleRate.toLong() / 1000L).toInt()
                if (offsetFrames < 0 || offsetFrames >= totalFrames) continue
                if (offsetFrames + pcm.size > totalFrames) continue
                for (i in pcm.indices) {
                    val index = offsetFrames + i
                    mix[index] = (mix[index] + pcm[i]).coerceIn(-1f, 1f)
                }
                renderedCount++
            }
            if (renderedCount == 0 || requestSerial != guideCueRequestSerial.get()) return@launch

            native.scheduleGuideCue(
                monoSamples = mix,
                atMs = cueAt,
                suppressUntilMs = sectionBoundaryMs,
                route = StereoRoute.fromStorage(guideTrack.outputRoute),
                bus = OutputBus.fromStorage(guideTrack.outputBus),
                volume = guideTrack.volume,
            )
        }
    }

    fun exitLoop() {
        cancelArrangementGuideCue()
        native.setLoop(false, 0, 0)
        _state.update { it.copy(loopSectionId = null, queuedSectionId = null, queuedJumpAtMs = null) }
    }

    fun setOutputDevice(deviceId: Int, requestedChannels: Int) {
        preferredOutputDeviceId = deviceId
        preferredOutputChannels = normalizeChannels(requestedChannels)
        switchOutputDevice(deviceId, preferredOutputChannels, selectedIdAfter = deviceId, notice = null)
    }

    fun handleOutputDevicesChanged(devices: List<AudioDeviceManager.OutputDevice>) {
        if (outputSwitchInProgress) return
        val currentId = _state.value.selectedOutputDeviceId
        if (currentId != null && devices.none { it.id == currentId }) {
            switchOutputDevice(
                deviceId = DEFAULT_OUTPUT_DEVICE,
                requestedChannels = 2,
                selectedIdAfter = null,
                notice = "Selected audio interface disconnected. StageGrid fell back to the Android stereo output.",
            )
            return
        }
        val desiredId = preferredOutputDeviceId ?: return
        if (currentId == null) {
            val reconnected = devices.firstOrNull { it.id == desiredId } ?: return
            preferredOutputChannels = reconnected.preferredStageGridChannels
            switchOutputDevice(
                deviceId = desiredId,
                requestedChannels = preferredOutputChannels,
                selectedIdAfter = desiredId,
                notice = "Audio interface reconnected. StageGrid restored the preferred output automatically.",
            )
        }
    }

    private fun switchOutputDevice(
        deviceId: Int,
        requestedChannels: Int,
        selectedIdAfter: Int?,
        notice: String?,
    ) {
        if (outputSwitchInProgress) return
        outputSwitchInProgress = true
        cancelArrangementGuideCue()
        scope.launch {
            try {
                val ok = withContext(Dispatchers.Default) { native.setOutputDevice(deviceId, requestedChannels) }
                val diagnostics = native.diagnostics()
                _state.update {
                    if (ok) {
                        val fallbackNotice = if (diagnostics.multichannelFallback && diagnostics.requestedOutputChannelCount > diagnostics.outputChannelCount) {
                            "Requested ${diagnostics.requestedOutputChannelCount} outputs, but Android opened ${diagnostics.outputChannelCount}. Routing was safely folded to available buses."
                        } else null
                        it.copy(
                            selectedOutputDeviceId = selectedIdAfter,
                            requestedOutputChannels = diagnostics.requestedOutputChannelCount,
                            outputChannelCount = diagnostics.outputChannelCount,
                            outputFallback = diagnostics.multichannelFallback,
                            outputNotice = notice ?: fallbackNotice,
                            errorMessage = null,
                        )
                    } else {
                        it.copy(errorMessage = diagnostics.lastError.ifBlank { "The selected audio output could not be opened." })
                    }
                }
            } finally {
                outputSwitchInProgress = false
            }
        }
    }

    fun testOutputChannel(channelIndex: Int) {
        scope.launch {
            val ok = withContext(Dispatchers.Default) { native.startOutputTest(channelIndex) }
            if (!ok) _state.update { it.copy(errorMessage = native.diagnostics().lastError) }
        }
    }

    fun diagnostics(): NativeAudioEngine.Diagnostics = native.diagnostics()

    fun mediaContentIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        1,
        context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun close() {
        cancelArrangementGuideCue()
        scope.cancel()
        audioManager.abandonAudioFocusRequest(focusRequest)
        native.close()
    }

    private fun cancelArrangementGuideCue() {
        guideCueRequestSerial.incrementAndGet()
        native.clearGuideCue()
    }

    private fun normalizeChannels(value: Int): Int = when {
        value >= 8 -> 8
        value >= 6 -> 6
        value >= 4 -> 4
        else -> 2
    }

    private companion object {
        const val GUIDE_PUBLISH_LEAD_MS = 90L
        const val GUIDE_MINIMUM_USEFUL_WINDOW_MS = 350L
        const val MAX_DYNAMIC_GUIDE_FRAMES = 48_000 * 12
        const val DEFAULT_OUTPUT_DEVICE = 0
    }
}
