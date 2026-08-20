package dev.stagegrid.audio

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
        val lastError: String,
    )

    fun loadSong(tracks: List<TrackEntity>, bpm: Double?, beatsPerBar: Int, gridOffsetMs: Long): Boolean {
        check(handle != 0L)
        val paths = tracks.map { it.filePath }.toTypedArray()
        val types = tracks.map { TrackType.fromStorage(it.type).nativeCode }.toIntArray()
        return nativeLoadSong(handle, paths, types, bpm ?: 0.0, beatsPerBar, gridOffsetMs)
    }

    /**
     * Loads the next song into a second native deck. This performs real WAV reader/decoder-worker
     * preparation, not only filesystem warming. The standby stream remains stopped and muted until
     * promotePreloaded() is called.
     */
    fun preloadSong(tracks: List<TrackEntity>, bpm: Double?, beatsPerBar: Int, gridOffsetMs: Long): Boolean {
        check(standbyHandle != 0L)
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
        if (!ok) return false
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
        return true
    }

    fun configurePreloaded(
        clickEnabled: Boolean,
        guideEnabled: Boolean,
        clickSubdivision: Int,
        clickRoute: StereoRoute,
        clickBus: OutputBus,
    ) {
        if (!standbyReady) return
        nativeSetClickEnabled(standbyHandle, clickEnabled)
        nativeSetGuideEnabled(standbyHandle, guideEnabled)
        nativeSetClickSubdivision(standbyHandle, clickSubdivision)
        nativeSetClickRoute(standbyHandle, clickRoute.nativeCode)
        nativeSetClickOutputBus(standbyHandle, clickBus.nativeCode)
    }

    fun hasPreloadedSong(): Boolean = standbyReady

    fun clearPreloaded() {
        if (standbyHandle == 0L) return
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
        if (!standbyReady || handle == 0L || standbyHandle == 0L) return false
        val target = targetMasterVolume.coerceIn(0f, 1.25f)
        val activeWasPlaying = nativeIsPlaying(handle)

        if (activeWasPlaying) {
            nativeSetMasterVolume(standbyHandle, 0f)
            if (!nativePlay(standbyHandle)) return false

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
        return true
    }

    fun unloadSong() {
        nativeUnloadSong(handle)
        clearPreloaded()
    }
    fun play(): Boolean = nativePlay(handle)
    fun pause() = nativePause(handle)
    fun stop() = nativeStop(handle)
    fun seekToMs(ms: Long) = nativeSeekToMs(handle, ms.coerceAtLeast(0))
    fun positionMs(): Long = nativePositionMs(handle)
    fun durationMs(): Long = nativeDurationMs(handle)
    fun isPlaying(): Boolean = nativeIsPlaying(handle)
    fun sampleRate(): Int = nativeSampleRate(handle).coerceAtLeast(1)
    fun setTrackVolume(index: Int, value: Float) = nativeSetTrackVolume(handle, index, value)
    fun setTrackMute(index: Int, value: Boolean) = nativeSetTrackMute(handle, index, value)
    fun setTrackSolo(index: Int, value: Boolean) = nativeSetTrackSolo(handle, index, value)
    fun setTrackPan(index: Int, value: Float) = nativeSetTrackPan(handle, index, value)
    fun setTrackOutputRoute(index: Int, route: StereoRoute) = nativeSetTrackOutputRoute(handle, index, route.nativeCode)
    fun setTrackOutputBus(index: Int, bus: OutputBus) = nativeSetTrackOutputBus(handle, index, bus.nativeCode)
    fun setMasterVolume(value: Float) = nativeSetMasterVolume(handle, value)
    fun setClickEnabled(value: Boolean) = nativeSetClickEnabled(handle, value)
    fun setGuideEnabled(value: Boolean) = nativeSetGuideEnabled(handle, value)
    fun setClickVolume(value: Float) = nativeSetClickVolume(handle, value)
    fun setClickSubdivision(subdivisionsPerBeat: Int) = nativeSetClickSubdivision(handle, subdivisionsPerBeat)
    fun setClickRoute(route: StereoRoute) = nativeSetClickRoute(handle, route.nativeCode)
    fun setClickOutputBus(bus: OutputBus) = nativeSetClickOutputBus(handle, bus.nativeCode)
    fun setLoop(enabled: Boolean, startMs: Long, endMs: Long) = nativeSetLoop(handle, enabled, startMs, endMs)
    fun scheduleJump(atMs: Long, targetMs: Long, disableLoopAfterJump: Boolean = true) =
        nativeScheduleJump(handle, atMs, targetMs, disableLoopAfterJump)
    fun clearScheduledJump() = nativeClearScheduledJump(handle)
    fun scheduleGuideCue(
        monoSamples: FloatArray,
        atMs: Long,
        suppressUntilMs: Long,
        route: StereoRoute,
        bus: OutputBus,
        volume: Float,
    ): Boolean = nativeScheduleGuideCue(
        handle,
        monoSamples,
        atMs.coerceAtLeast(0L),
        suppressUntilMs.coerceAtLeast(0L),
        route.nativeCode,
        bus.nativeCode,
        volume.coerceIn(0f, 1.5f),
    )
    fun clearGuideCue() = nativeClearGuideCue(handle)
    fun prepareCountIn(targetMs: Long, bars: Int): Boolean =
        nativePrepareCountIn(handle, targetMs.coerceAtLeast(0L), bars.coerceIn(1, 2))
    fun countInRemainingMs(): Long = nativeCountInRemainingMs(handle).coerceAtLeast(0L)
    fun setOutputDevice(deviceId: Int, requestedChannels: Int): Boolean {
        val normalized = normalizeOutputChannels(requestedChannels)
        val activeOk = nativeSetOutputDevice(handle, deviceId, normalized)
        if (standbyReady) runCatching { nativeSetOutputDevice(standbyHandle, deviceId, normalized) }
        return activeOk
    }
    fun startOutputTest(channelIndex: Int, durationMs: Int = 650): Boolean =
        nativeStartOutputTest(handle, channelIndex.coerceAtLeast(0), durationMs.coerceIn(120, 1200))

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
        lastError = nativeLastError(handle),
    )

    override fun close() {
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
    private external fun nativeLastError(handle: Long): String

    private fun normalizeOutputChannels(value: Int): Int = when {
        value >= 8 -> 8
        value >= 6 -> 6
        value >= 4 -> 4
        else -> 2
    }

    companion object {
        init { System.loadLibrary("stagegrid_audio") }
    }
}
