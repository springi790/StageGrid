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
import dev.stagegrid.model.SectionEntity
import dev.stagegrid.model.StereoRoute
import dev.stagegrid.model.TrackEntity
import dev.stagegrid.music.MusicalGrid
import dev.stagegrid.service.PlaybackService
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

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
                    _state.update { previous ->
                        val crossedQueued = previous.queuedSectionId?.let { queuedId ->
                            val queued = previous.sections.firstOrNull { it.id == queuedId }
                            queued != null && position >= queued.startMs && position < queued.endMs
                        } ?: false
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

    fun loadSong(songId: String) {
        scope.launch {
            _state.update { it.copy(engineState = EngineState.LOADING, errorMessage = null) }
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
            }
            val previous = _state.value
            native.setClickSubdivision(previous.clickSubdivision.subdivisionsPerBeat)
            native.setClickRoute(previous.clickRoute)
            native.setClickEnabled(previous.clickEnabled)
            native.setGuideEnabled(previous.guideEnabled)
            _state.value = PlayerState(
                engineState = EngineState.READY,
                song = bundle.song,
                tracks = bundle.tracks,
                sections = bundle.sections,
                durationMs = native.durationMs().takeIf { it > 0 } ?: bundle.song.durationMs,
                clickEnabled = previous.clickEnabled,
                guideEnabled = previous.guideEnabled,
                clickSubdivision = previous.clickSubdivision,
                clickRoute = previous.clickRoute,
                countInBars = previous.countInBars,
                masterVolume = previous.masterVolume,
            )
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

    fun stopAll() {
        native.setMasterVolume(0f)
        stop()
        _state.update { it.copy(masterVolume = 0f) }
    }

    fun seekTo(ms: Long) {
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

    fun setCountInBars(bars: Int) {
        _state.update { it.copy(countInBars = bars.coerceIn(0, 2)) }
    }

    fun playCurrentSectionWithCountIn() {
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

        val song = current.song
        val grid = song?.let { MusicalGrid.from(it.bpm, it.timeSignature, it.gridOffsetMs) }
        val quantizedBoundary = grid?.nextBarBoundaryAtLeast(current.positionMs, PATH_PRELOAD_LEAD_MS)
        val jumpAt = when {
            quantizedBoundary != null && quantizedBoundary > current.positionMs && quantizedBoundary < current.durationMs -> quantizedBoundary
            active.endMs - current.positionMs >= PATH_PRELOAD_LEAD_MS -> active.endMs
            active.endMs > current.positionMs -> active.endMs
            else -> current.positionMs + PATH_PRELOAD_LEAD_MS
        }

        native.scheduleJump(jumpAt, section.startMs, disableLoopAfterJump = true)
        _state.update {
            it.copy(
                queuedSectionId = section.id,
                queuedJumpAtMs = jumpAt,
                countInRemainingMs = 0,
                countInTargetSectionId = null,
            )
        }
    }

    fun exitLoop() {
        native.setLoop(false, 0, 0)
        _state.update { it.copy(loopSectionId = null, queuedSectionId = null, queuedJumpAtMs = null) }
    }

    fun setOutputDevice(deviceId: Int) {
        scope.launch {
            val ok = withContext(Dispatchers.Default) { native.setOutputDevice(deviceId) }
            _state.update {
                if (ok) it.copy(selectedOutputDeviceId = deviceId, errorMessage = null)
                else it.copy(errorMessage = native.diagnostics().lastError)
            }
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
        scope.cancel()
        audioManager.abandonAudioFocusRequest(focusRequest)
        native.close()
    }

    private companion object {
        // Leaves the decoder bank enough time to prepare even on songs with many stems. If the tap
        // lands closer than this to the next bar line, MusicalGrid queues the following bar instead.
        const val PATH_PRELOAD_LEAD_MS = 180L
    }
}
