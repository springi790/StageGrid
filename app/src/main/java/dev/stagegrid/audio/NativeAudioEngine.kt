package dev.stagegrid.audio

import dev.stagegrid.model.StereoRoute
import dev.stagegrid.model.TrackEntity
import dev.stagegrid.model.TrackType
import java.io.Closeable

class NativeAudioEngine : Closeable {
    private var handle: Long = nativeCreate()

    data class Diagnostics(
        val sampleRate: Int,
        val framesPerBurst: Int,
        val underruns: Long,
        val cpuLoad: Float,
        val loadedTracks: Int,
        val positionMs: Long,
        val durationMs: Long,
        val lastError: String,
    )

    fun loadSong(tracks: List<TrackEntity>, bpm: Double?, beatsPerBar: Int, gridOffsetMs: Long): Boolean {
        check(handle != 0L)
        val paths = tracks.map { it.filePath }.toTypedArray()
        val types = tracks.map { TrackType.fromStorage(it.type).nativeCode }.toIntArray()
        return nativeLoadSong(handle, paths, types, bpm ?: 0.0, beatsPerBar, gridOffsetMs)
    }

    fun unloadSong() = nativeUnloadSong(handle)
    fun play(): Boolean = nativePlay(handle)
    fun pause() = nativePause(handle)
    fun stop() = nativeStop(handle)
    fun seekToMs(ms: Long) = nativeSeekToMs(handle, ms.coerceAtLeast(0))
    fun positionMs(): Long = nativePositionMs(handle)
    fun durationMs(): Long = nativeDurationMs(handle)
    fun isPlaying(): Boolean = nativeIsPlaying(handle)
    fun setTrackVolume(index: Int, value: Float) = nativeSetTrackVolume(handle, index, value)
    fun setTrackMute(index: Int, value: Boolean) = nativeSetTrackMute(handle, index, value)
    fun setTrackSolo(index: Int, value: Boolean) = nativeSetTrackSolo(handle, index, value)
    fun setTrackPan(index: Int, value: Float) = nativeSetTrackPan(handle, index, value)
    fun setTrackOutputRoute(index: Int, route: StereoRoute) = nativeSetTrackOutputRoute(handle, index, route.nativeCode)
    fun setMasterVolume(value: Float) = nativeSetMasterVolume(handle, value)
    fun setClickEnabled(value: Boolean) = nativeSetClickEnabled(handle, value)
    fun setGuideEnabled(value: Boolean) = nativeSetGuideEnabled(handle, value)
    fun setClickVolume(value: Float) = nativeSetClickVolume(handle, value)
    fun setClickSubdivision(subdivisionsPerBeat: Int) = nativeSetClickSubdivision(handle, subdivisionsPerBeat)
    fun setClickRoute(route: StereoRoute) = nativeSetClickRoute(handle, route.nativeCode)
    fun setLoop(enabled: Boolean, startMs: Long, endMs: Long) = nativeSetLoop(handle, enabled, startMs, endMs)
    fun scheduleJump(atMs: Long, targetMs: Long, disableLoopAfterJump: Boolean = true) =
        nativeScheduleJump(handle, atMs, targetMs, disableLoopAfterJump)
    fun clearScheduledJump() = nativeClearScheduledJump(handle)
    fun prepareCountIn(targetMs: Long, bars: Int): Boolean =
        nativePrepareCountIn(handle, targetMs.coerceAtLeast(0L), bars.coerceIn(1, 2))
    fun countInRemainingMs(): Long = nativeCountInRemainingMs(handle).coerceAtLeast(0L)
    fun setOutputDevice(deviceId: Int): Boolean = nativeSetOutputDevice(handle, deviceId)

    fun diagnostics(): Diagnostics = Diagnostics(
        sampleRate = nativeSampleRate(handle),
        framesPerBurst = nativeFramesPerBurst(handle),
        underruns = nativeUnderruns(handle),
        cpuLoad = nativeCpuLoad(handle),
        loadedTracks = nativeLoadedTracks(handle),
        positionMs = nativePositionMs(handle),
        durationMs = nativeDurationMs(handle),
        lastError = nativeLastError(handle),
    )

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
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
    private external fun nativeSetMasterVolume(handle: Long, value: Float)
    private external fun nativeSetClickEnabled(handle: Long, value: Boolean)
    private external fun nativeSetGuideEnabled(handle: Long, value: Boolean)
    private external fun nativeSetClickVolume(handle: Long, value: Float)
    private external fun nativeSetClickSubdivision(handle: Long, subdivisionsPerBeat: Int)
    private external fun nativeSetClickRoute(handle: Long, route: Int)
    private external fun nativeSetLoop(handle: Long, enabled: Boolean, startMs: Long, endMs: Long)
    private external fun nativeScheduleJump(handle: Long, atMs: Long, targetMs: Long, disableLoopAfterJump: Boolean)
    private external fun nativeClearScheduledJump(handle: Long)
    private external fun nativePrepareCountIn(handle: Long, targetMs: Long, bars: Int): Boolean
    private external fun nativeCountInRemainingMs(handle: Long): Long
    private external fun nativeSetOutputDevice(handle: Long, deviceId: Int): Boolean
    private external fun nativeSampleRate(handle: Long): Int
    private external fun nativeFramesPerBurst(handle: Long): Int
    private external fun nativeUnderruns(handle: Long): Long
    private external fun nativeCpuLoad(handle: Long): Float
    private external fun nativeLoadedTracks(handle: Long): Int
    private external fun nativeLastError(handle: Long): String

    companion object {
        init { System.loadLibrary("stagegrid_audio") }
    }
}
