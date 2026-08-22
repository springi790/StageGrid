package dev.stagegrid.audio

import dev.stagegrid.debug.StageGridDebugLog
import dev.stagegrid.model.OutputBus
import dev.stagegrid.model.StereoRoute
import dev.stagegrid.model.TrackEntity
import dev.stagegrid.model.TrackType
import java.io.Closeable

class NativeAudioEngine : Closeable {
    private var handle: Long = nativeCreate()
    private var standbyHandle: Long = nativeCreate()
    @Volatile private var standbyReady: Boolean = false

    data class Diagnostics(
        val sampleRate: Int,
        val framesPerBurst: Int,
        val outputChannelCount: Int,
        val requestedOutputChannelCount: Int,
        val multichannelFallback: Boolean,
        val outputDeviceId: Int,
        val underruns: Long,
        val cpuLoad: Float,
        val loadedTracks: Int,
        val positionMs: Long,
        val durationMs: Long,
        val pathSwaps: Long,
        val pathSwapMisses: Long,
        val pathChangePending: Boolean,
        val tempoRatio: Float,
        val pitchSemitones: Float,
        val dspActive: Boolean,
        val dspCpuLoad: Float,
        val dspLatencyMs: Int,
        val lastError: String,
    )

    fun loadSong(tracks: List<TrackEntity>, bpm: Double?, beatsPerBar: Int, gridOffsetMs: Long): Boolean {
        check(handle != 0L)
        StageGridDebugLog.audio("LOAD tracks=${tracks.size} bpm=${bpm ?: 0.0} meter=$beatsPerBar gridOffsetMs=$gridOffsetMs")
        val paths = tracks.map { it.filePath }.toTypedArray()
        val types = tracks.map { TrackType.fromStorage(it.type).nativeCode }.toIntArray()
        val ok = nativeLoadSong(handle, paths, types, bpm ?: 0.0, beatsPerBar, gridOffsetMs)
        if (!ok) StageGridDebugLog.error("AUDIO", "LOAD failed: ${nativeLastError(handle)}")
        else StageGridDebugLog.state("AUDIO", "LOAD ready durationMs=${nativeDurationMs(handle)}")
        return ok
    }

    /**
     * Loads the next song into a second native deck. This performs real WAV reader/decoder-worker
     * preparation, not only filesystem warming. The standby stream remains stopped and muted until
     * promotePreloaded() is called.
     */
    fun preloadSong(tracks: List<TrackEntity>, bpm: Double?, beatsPerBar: Int, gridOffsetMs: Long): Boolean {
        check(standbyHandle != 0L)
        StageGridDebugLog.audio("PRELOAD tracks=${tracks.size} bpm=${bpm ?: 0.0} meter=$beatsPerBar gridOffsetMs=$gridOffsetMs")
        clearPreloaded()
        val active = diagnostics()
        if (active.outputDeviceId >= 0) {
            nativeSetOutputDevice(
                standbyHandle,
                active.outputDeviceId,
                normalizeOutputChannels(active.requestedOutputChannelCount),
            )
        }
        val paths = tracks.map { it.filePath }.toTypedArray()
        val types = tracks.map { TrackType.fromStorage(it.type).nativeCode }.toIntArray()
        val ok = nativeLoadSong(standbyHandle, paths, types, bpm ?: 0.0, beatsPerBar, gridOffsetMs)
        if (!ok) {
            StageGridDebugLog.error("AUDIO", "PRELOAD failed: ${nativeLastError(standbyHandle)}")
            return false
        }
        tracks.forEachIndexed { index, track ->
            nativeSetTrackVolume(standbyHandle, index, track.volume)
            nativeSetTrackMute(standbyHandle, index, track.muted)
            nativeSetTrackSolo(standbyHandle, index, track.solo)
            nativeSetTrackPan(standbyHandle, index, track.pan)
            nativeSetTrackOutputRoute(standbyHandle, index, StereoRoute.fromStorage(track.outputRoute).nativeCode)
            nativeSetTrackOutputBus(standbyHandle, index, OutputBus.fromStorage(track.outputBus).nativeCode)
        }
        nativeSetMasterVolume(standbyHandle, 0f)
        standbyReady = true
        StageGridDebugLog.state("AUDIO", "PRELOAD ready")
        return true
    }

    fun configurePreloaded(
        clickEnabled: Boolean,
        guideEnabled: Boolean,
        clickSubdivision: Int,
        clickRoute: StereoRoute,
        clickBus: OutputBus,
        tempoRatio: Float = 1f,
        pitchSemitones: Float = 0f,
    ) {
        if (!standbyReady) return
        StageGridDebugLog.audio(
            "PRELOAD_CONFIG click=$clickEnabled guide=$guideEnabled subdivision=$clickSubdivision route=$clickRoute bus=$clickBus tempo=$tempoRatio pitch=$pitchSemitones",
        )
        nativeSetClickEnabled(standbyHandle, clickEnabled)
        nativeSetGuideEnabled(standbyHandle, guideEnabled)
        nativeSetClickSubdivision(standbyHandle, clickSubdivision)
        nativeSetClickRoute(standbyHandle, clickRoute.nativeCode)
        nativeSetClickOutputBus(standbyHandle, clickBus.nativeCode)
        nativeSetTempoRatio(standbyHandle, tempoRatio.coerceIn(MIN_TEMPO_RATIO, MAX_TEMPO_RATIO))
        nativeSetPitchSemitones(standbyHandle, pitchSemitones.coerceIn(MIN_PITCH_SEMITONES, MAX_PITCH_SEMITONES))
    }

    fun hasPreloadedSong(): Boolean = standbyReady

    fun clearPreloaded() {
        if (standbyHandle == 0L) return
        if (standbyReady) StageGridDebugLog.audio("PRELOAD_CLEAR")
        runCatching { nativePause(standbyHandle) }
        runCatching { nativeUnloadSong(standbyHandle) }
        standbyReady = false
    }

    /**
     * Promotes the prepared deck. If the active deck is playing, both streams overlap and a gain
     * crossfade is performed. If transport is stopped/paused, ownership swaps silently and the new
     * deck remains stopped. Call from a background dispatcher because the gain ramp is time based.
     */
    fun promotePreloaded(crossfadeMs: Int, targetMasterVolume: Float): Boolean {
        if (!standbyReady || handle == 0L || standbyHandle == 0L) {
            StageGridDebugLog.warning("AUDIO", "PROMOTE ignored standbyReady=$standbyReady")
            return false
        }
        val target = targetMasterVolume.coerceIn(0f, 1.25f)
        val activeWasPlaying = nativeIsPlaying(handle)
        StageGridDebugLog.audio("PROMOTE crossfadeMs=$crossfadeMs activeWasPlaying=$activeWasPlaying targetMaster=$target")

        if (activeWasPlaying) {
            nativeSetMasterVolume(standbyHandle, 0f)
            if (!nativePlay(standbyHandle)) {
                StageGridDebugLog.error("AUDIO", "PROMOTE standby play failed: ${nativeLastError(standbyHandle)}")
                return false
            }

            val duration = crossfadeMs.coerceIn(0, 5_000)
            if (duration == 0) {
                nativeSetMasterVolume(handle, 0f)
                nativeSetMasterVolume(standbyHandle, target)
            } else {
                val steps = (duration / 20).coerceIn(8, 100)
                val sleepMs = (duration / steps).coerceAtLeast(1)
                for (step in 1..steps) {
                    val fraction = step.toFloat() / steps.toFloat()
                    nativeSetMasterVolume(handle, target * (1f - fraction))
                    nativeSetMasterVolume(standbyHandle, target * fraction)
                    Thread.sleep(sleepMs.toLong())
                }
            }
        } else {
            nativePause(standbyHandle)
            nativeSetMasterVolume(handle, 0f)
            nativeSetMasterVolume(standbyHandle, target)
        }

        val previous = handle
        handle = standbyHandle
        standbyHandle = previous
        standbyReady = false
        runCatching { nativePause(standbyHandle) }
        runCatching { nativeUnloadSong(standbyHandle) }
        StageGridDebugLog.state("AUDIO", "PROMOTE complete playing=$activeWasPlaying")
        return true
    }

    fun unloadSong() {
        StageGridDebugLog.audio("UNLOAD")
        nativeUnloadSong(handle)
        clearPreloaded()
    }
    fun play(): Boolean {
        StageGridDebugLog.action("TRANSPORT", "PLAY")
        val ok = nativePlay(handle)
        if (!ok) StageGridDebugLog.error("TRANSPORT", "PLAY failed: ${nativeLastError(handle)}")
        return ok
    }
    fun pause() {
        StageGridDebugLog.action("TRANSPORT", "PAUSE")
        nativePause(handle)
    }
    fun stop() {
        StageGridDebugLog.action("TRANSPORT", "STOP")
        nativeStop(handle)
    }
    fun seekToMs(ms: Long) {
        val target = ms.coerceAtLeast(0)
        StageGridDebugLog.action("TRANSPORT", "SEEK targetMs=$target")
        nativeSeekToMs(handle, target)
    }
    fun positionMs(): Long = nativePositionMs(handle)
    fun durationMs(): Long = nativeDurationMs(handle)
    fun isPlaying(): Boolean = nativeIsPlaying(handle)
    fun sampleRate(): Int = nativeSampleRate(handle).coerceAtLeast(1)
    fun setTrackVolume(index: Int, value: Float) {
        StageGridDebugLog.action("MIXER", "VOLUME track=$index value=$value")
        nativeSetTrackVolume(handle, index, value)
    }
    fun setTrackMute(index: Int, value: Boolean) {
        StageGridDebugLog.action("MIXER", "MUTE track=$index enabled=$value")
        nativeSetTrackMute(handle, index, value)
    }
    fun setTrackSolo(index: Int, value: Boolean) {
        StageGridDebugLog.action("MIXER", "SOLO track=$index enabled=$value")
        nativeSetTrackSolo(handle, index, value)
    }
    fun setTrackPan(index: Int, value: Float) {
        StageGridDebugLog.action("MIXER", "PAN track=$index value=$value")
        nativeSetTrackPan(handle, index, value)
    }
    fun setTrackOutputRoute(index: Int, route: StereoRoute) {
        StageGridDebugLog.action("ROUTING", "TRACK_ROUTE track=$index route=$route")
        nativeSetTrackOutputRoute(handle, index, route.nativeCode)
    }
    fun setTrackOutputBus(index: Int, bus: OutputBus) {
        StageGridDebugLog.action("ROUTING", "TRACK_BUS track=$index bus=$bus")
        nativeSetTrackOutputBus(handle, index, bus.nativeCode)
    }
    fun setMasterVolume(value: Float) {
        StageGridDebugLog.action("MIXER", "MASTER value=$value")
        nativeSetMasterVolume(handle, value)
    }
    fun setClickEnabled(value: Boolean) {
        StageGridDebugLog.action("CLICK", "ENABLED=$value")
        nativeSetClickEnabled(handle, value)
    }
    fun setGuideEnabled(value: Boolean) {
        StageGridDebugLog.action("GUIDE", "ENABLED=$value")
        nativeSetGuideEnabled(handle, value)
    }
    fun setClickVolume(value: Float) {
        StageGridDebugLog.action("CLICK", "VOLUME=$value")
        nativeSetClickVolume(handle, value)
    }
    fun setClickSubdivision(subdivisionsPerBeat: Int) {
        StageGridDebugLog.action("CLICK", "SUBDIVISION=$subdivisionsPerBeat")
        nativeSetClickSubdivision(handle, subdivisionsPerBeat)
    }
    fun setClickRoute(route: StereoRoute) {
        StageGridDebugLog.action("ROUTING", "CLICK_ROUTE=$route")
        nativeSetClickRoute(handle, route.nativeCode)
    }
    fun setClickOutputBus(bus: OutputBus) {
        StageGridDebugLog.action("ROUTING", "CLICK_BUS=$bus")
        nativeSetClickOutputBus(handle, bus.nativeCode)
    }
    fun setTempoRatio(value: Float): Diagnostics {
        val safe = value.coerceIn(MIN_TEMPO_RATIO, MAX_TEMPO_RATIO)
        val before = nativeTempoRatio(handle)
        StageGridDebugLog.action("DSP", "TEMPO ratio=$before -> $safe percent=${(safe * 100f)}")
        nativeSetTempoRatio(handle, safe)
        val result = diagnostics()
        StageGridDebugLog.state(
            "DSP",
            "TEMPO_APPLIED ratio=${result.tempoRatio} active=${result.dspActive} latencyMs=${result.dspLatencyMs} dspCpu=${result.dspCpuLoad}",
        )
        return result
    }
    fun setPitchSemitones(value: Float): Diagnostics {
        val safe = value.coerceIn(MIN_PITCH_SEMITONES, MAX_PITCH_SEMITONES)
        val before = nativePitchSemitones(handle)
        StageGridDebugLog.action("DSP", "PITCH semitones=$before -> $safe")
        nativeSetPitchSemitones(handle, safe)
        val result = diagnostics()
        StageGridDebugLog.state(
            "DSP",
            "PITCH_APPLIED semitones=${result.pitchSemitones} active=${result.dspActive} latencyMs=${result.dspLatencyMs} dspCpu=${result.dspCpuLoad}",
        )
        return result
    }
    fun resetDsp(): Diagnostics {
        StageGridDebugLog.action("DSP", "RESET tempo=1.0 pitch=0.0")
        nativeSetTempoRatio(handle, 1f)
        nativeSetPitchSemitones(handle, 0f)
        val result = diagnostics()
        StageGridDebugLog.state("DSP", "RESET_APPLIED active=${result.dspActive}")
        return result
    }
    fun setLoop(enabled: Boolean, startMs: Long, endMs: Long) {
        StageGridDebugLog.action("LOOP", "SET enabled=$enabled startMs=$startMs endMs=$endMs")
        nativeSetLoop(handle, enabled, startMs, endMs)
    }
    fun scheduleJump(atMs: Long, targetMs: Long, disableLoopAfterJump: Boolean = true) {
        StageGridDebugLog.action("ARRANGE", "SCHEDULE_JUMP atMs=$atMs targetMs=$targetMs disableLoop=$disableLoopAfterJump")
        nativeScheduleJump(handle, atMs, targetMs, disableLoopAfterJump)
    }
    fun clearScheduledJump() {
        StageGridDebugLog.action("ARRANGE", "CLEAR_SCHEDULED_JUMP")
        nativeClearScheduledJump(handle)
    }
    fun scheduleGuideCue(
        monoSamples: FloatArray,
        atMs: Long,
        suppressUntilMs: Long,
        route: StereoRoute,
        bus: OutputBus,
        volume: Float,
    ): Boolean {
        StageGridDebugLog.action("GUIDE", "SCHEDULE_CUE samples=${monoSamples.size} atMs=$atMs suppressUntilMs=$suppressUntilMs route=$route bus=$bus volume=$volume")
        val ok = nativeScheduleGuideCue(
            handle,
            monoSamples,
            atMs.coerceAtLeast(0L),
            suppressUntilMs.coerceAtLeast(0L),
            route.nativeCode,
            bus.nativeCode,
            volume.coerceIn(0f, 1.5f),
        )
        if (!ok) StageGridDebugLog.warning("GUIDE", "SCHEDULE_CUE rejected")
        return ok
    }
    fun clearGuideCue() {
        StageGridDebugLog.action("GUIDE", "CLEAR_CUE")
        nativeClearGuideCue(handle)
    }
    fun prepareCountIn(targetMs: Long, bars: Int): Boolean {
        StageGridDebugLog.action("COUNT_IN", "PREPARE targetMs=$targetMs bars=$bars")
        val ok = nativePrepareCountIn(handle, targetMs.coerceAtLeast(0L), bars.coerceIn(1, 2))
        if (!ok) StageGridDebugLog.error("COUNT_IN", "PREPARE failed: ${nativeLastError(handle)}")
        return ok
    }
    fun countInRemainingMs(): Long = nativeCountInRemainingMs(handle).coerceAtLeast(0L)
    fun setOutputDevice(deviceId: Int, requestedChannels: Int): Boolean {
        val normalized = normalizeOutputChannels(requestedChannels)
        StageGridDebugLog.action("ROUTING", "OUTPUT_DEVICE id=$deviceId requestedChannels=$normalized")
        val activeOk = nativeSetOutputDevice(handle, deviceId, normalized)
        if (standbyReady) runCatching { nativeSetOutputDevice(standbyHandle, deviceId, normalized) }
        if (!activeOk) StageGridDebugLog.error("ROUTING", "OUTPUT_DEVICE failed: ${nativeLastError(handle)}")
        else StageGridDebugLog.state("ROUTING", "OUTPUT_DEVICE activeChannels=${nativeOutputChannelCount(handle)} fallback=${nativeMultichannelFallback(handle)}")
        return activeOk
    }
    fun startOutputTest(channelIndex: Int, durationMs: Int = 650): Boolean {
        StageGridDebugLog.action("ROUTING", "OUTPUT_TEST channel=$channelIndex durationMs=$durationMs")
        return nativeStartOutputTest(handle, channelIndex.coerceAtLeast(0), durationMs.coerceIn(120, 1200))
    }

    /**
     * Offline stereo fold-down for rehearsal sharing. This never touches either live deck.
     * Multi-output buses are folded to stereo while each track's route/pan/mute/solo is preserved.
     */
    fun renderRehearsalMix(
        outputPath: String,
        tracks: List<TrackEntity>,
        bpm: Double?,
        beatsPerBar: Int,
        gridOffsetMs: Long,
        masterVolume: Float,
        tempoRatio: Float,
        pitchSemitones: Float,
        clickEnabled: Boolean,
        guideEnabled: Boolean,
        clickSubdivision: Int,
        clickRoute: StereoRoute,
    ): String? {
        if (tracks.isEmpty()) return "No tracks are available to export."
        StageGridDebugLog.action(
            "EXPORT",
            "REHEARSAL_MIX_START tracks=${tracks.size} tempo=$tempoRatio pitch=$pitchSemitones click=$clickEnabled guide=$guideEnabled",
        )
        val error = nativeRenderRehearsalMix(
            outputPath,
            tracks.map { it.filePath }.toTypedArray(),
            tracks.map { TrackType.fromStorage(it.type).nativeCode }.toIntArray(),
            tracks.map { it.volume }.toFloatArray(),
            tracks.map { if (it.muted) 1 else 0 }.toIntArray(),
            tracks.map { if (it.solo) 1 else 0 }.toIntArray(),
            tracks.map { it.pan }.toFloatArray(),
            tracks.map { StereoRoute.fromStorage(it.outputRoute).nativeCode }.toIntArray(),
            bpm ?: 0.0,
            beatsPerBar.coerceIn(1, 32),
            gridOffsetMs.coerceAtLeast(0L),
            masterVolume.coerceIn(0f, 1.25f),
            tempoRatio.coerceIn(MIN_TEMPO_RATIO, MAX_TEMPO_RATIO),
            pitchSemitones.coerceIn(MIN_PITCH_SEMITONES, MAX_PITCH_SEMITONES),
            clickEnabled,
            guideEnabled,
            clickSubdivision.coerceIn(1, 4),
            clickRoute.nativeCode,
        ).trim()
        return if (error.isBlank()) {
            StageGridDebugLog.state("EXPORT", "REHEARSAL_MIX_COMPLETE path=$outputPath")
            null
        } else {
            StageGridDebugLog.error("EXPORT", "REHEARSAL_MIX_FAILED error=$error")
            error
        }
    }

    fun diagnostics(): Diagnostics = Diagnostics(
        sampleRate = nativeSampleRate(handle),
        framesPerBurst = nativeFramesPerBurst(handle),
        outputChannelCount = nativeOutputChannelCount(handle).coerceIn(2, 8),
        requestedOutputChannelCount = nativeRequestedOutputChannelCount(handle).coerceIn(2, 8),
        multichannelFallback = nativeMultichannelFallback(handle),
        outputDeviceId = nativeOutputDeviceId(handle),
        underruns = nativeUnderruns(handle),
        cpuLoad = nativeCpuLoad(handle),
        loadedTracks = nativeLoadedTracks(handle),
        positionMs = nativePositionMs(handle),
        durationMs = nativeDurationMs(handle),
        pathSwaps = nativePathSwaps(handle),
        pathSwapMisses = nativePathSwapMisses(handle),
        pathChangePending = nativePathChangePending(handle),
        tempoRatio = nativeTempoRatio(handle),
        pitchSemitones = nativePitchSemitones(handle),
        dspActive = nativeDspActive(handle),
        dspCpuLoad = nativeDspCpuLoad(handle),
        dspLatencyMs = nativeDspLatencyMs(handle).coerceAtLeast(0),
        lastError = nativeLastError(handle),
    )

    override fun close() {
        StageGridDebugLog.audio("DESTROY active=${handle != 0L} standby=${standbyHandle != 0L}")
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
        if (standbyHandle != 0L) {
            nativeDestroy(standbyHandle)
            standbyHandle = 0L
        }
        standbyReady = false
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeLoadSong(handle: Long, paths: Array<String>, types: IntArray, bpm: Double, beatsPerBar: Int, gridOffsetMs: Long): Boolean
    private external fun nativeUnloadSong(handle: Long)
    private external fun nativePlay(handle: Long): Boolean
    private external fun nativePause(handle: Long)
    private external fun nativeStop(handle: Long)
    private external fun nativeSeekToMs(handle: Long, ms: Long)
    private external fun nativePositionMs(handle: Long): Long
    private external fun nativeDurationMs(handle: Long): Long
    private external fun nativeIsPlaying(handle: Long): Boolean
    private external fun nativeSetTrackVolume(handle: Long, index: Int, value: Float)
    private external fun nativeSetTrackMute(handle: Long, index: Int, value: Boolean)
    private external fun nativeSetTrackSolo(handle: Long, index: Int, value: Boolean)
    private external fun nativeSetTrackPan(handle: Long, index: Int, value: Float)
    private external fun nativeSetTrackOutputRoute(handle: Long, index: Int, route: Int)
    private external fun nativeSetTrackOutputBus(handle: Long, index: Int, bus: Int)
    private external fun nativeSetMasterVolume(handle: Long, value: Float)
    private external fun nativeSetClickEnabled(handle: Long, value: Boolean)
    private external fun nativeSetGuideEnabled(handle: Long, value: Boolean)
    private external fun nativeSetClickVolume(handle: Long, value: Float)
    private external fun nativeSetClickSubdivision(handle: Long, subdivisionsPerBeat: Int)
    private external fun nativeSetClickRoute(handle: Long, route: Int)
    private external fun nativeSetClickOutputBus(handle: Long, bus: Int)
    private external fun nativeSetTempoRatio(handle: Long, ratio: Float)
    private external fun nativeSetPitchSemitones(handle: Long, semitones: Float)
    private external fun nativeSetLoop(handle: Long, enabled: Boolean, startMs: Long, endMs: Long)
    private external fun nativeScheduleJump(handle: Long, atMs: Long, targetMs: Long, disableLoopAfterJump: Boolean)
    private external fun nativeClearScheduledJump(handle: Long)
    private external fun nativeScheduleGuideCue(handle: Long, samples: FloatArray, atMs: Long, suppressUntilMs: Long, route: Int, bus: Int, volume: Float): Boolean
    private external fun nativeClearGuideCue(handle: Long)
    private external fun nativePrepareCountIn(handle: Long, targetMs: Long, bars: Int): Boolean
    private external fun nativeCountInRemainingMs(handle: Long): Long
    private external fun nativeSetOutputDevice(handle: Long, deviceId: Int, requestedChannels: Int): Boolean
    private external fun nativeStartOutputTest(handle: Long, channelIndex: Int, durationMs: Int): Boolean
    private external fun nativeSampleRate(handle: Long): Int
    private external fun nativeFramesPerBurst(handle: Long): Int
    private external fun nativeOutputChannelCount(handle: Long): Int
    private external fun nativeRequestedOutputChannelCount(handle: Long): Int
    private external fun nativeMultichannelFallback(handle: Long): Boolean
    private external fun nativeOutputDeviceId(handle: Long): Int
    private external fun nativeUnderruns(handle: Long): Long
    private external fun nativeCpuLoad(handle: Long): Float
    private external fun nativeLoadedTracks(handle: Long): Int
    private external fun nativePathSwaps(handle: Long): Long
    private external fun nativePathSwapMisses(handle: Long): Long
    private external fun nativePathChangePending(handle: Long): Boolean
    private external fun nativeTempoRatio(handle: Long): Float
    private external fun nativePitchSemitones(handle: Long): Float
    private external fun nativeDspActive(handle: Long): Boolean
    private external fun nativeDspCpuLoad(handle: Long): Float
    private external fun nativeDspLatencyMs(handle: Long): Int
    private external fun nativeLastError(handle: Long): String
    private external fun nativeRenderRehearsalMix(
        outputPath: String,
        paths: Array<String>,
        types: IntArray,
        volumes: FloatArray,
        mutes: IntArray,
        solos: IntArray,
        pans: FloatArray,
        routes: IntArray,
        bpm: Double,
        beatsPerBar: Int,
        gridOffsetMs: Long,
        masterVolume: Float,
        tempoRatio: Float,
        pitchSemitones: Float,
        clickEnabled: Boolean,
        guideEnabled: Boolean,
        clickSubdivision: Int,
        clickRoute: Int,
    ): String

    private fun normalizeOutputChannels(value: Int): Int = when {
        value >= 8 -> 8
        value >= 6 -> 6
        value >= 4 -> 4
        else -> 2
    }

    companion object {
        const val MIN_TEMPO_RATIO = 0.75f
        const val MAX_TEMPO_RATIO = 1.50f
        const val MIN_PITCH_SEMITONES = -12f
        const val MAX_PITCH_SEMITONES = 12f

        init { System.loadLibrary("stagegrid_audio") }
    }
}
