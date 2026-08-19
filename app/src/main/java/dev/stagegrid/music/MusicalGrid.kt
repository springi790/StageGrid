package dev.stagegrid.music

import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Deterministic musical-time conversion used by the UI and arrangement layer.
 *
 * StageGrid currently treats BPM as the duration of one displayed beat, which
 * matches the native click engine. The time-signature denominator is retained
 * for notation/display while the numerator defines beats per bar.
 */
data class MusicalTimeSignature(
    val beatsPerBar: Int,
    val denominator: Int,
) {
    companion object {
        fun parse(value: String): MusicalTimeSignature {
            val parts = value.trim().split('/')
            val numerator = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(1, 32) ?: 4
            val denominator = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in setOf(1, 2, 4, 8, 16, 32) } ?: 4
            return MusicalTimeSignature(numerator, denominator)
        }
    }
}

data class MusicalPosition(
    val bar: Int,
    val beat: Int,
) {
    init {
        require(bar >= 1)
        require(beat >= 1)
    }

    override fun toString(): String = "$bar.$beat"
}

enum class GridSnap { BEAT, BAR }

class MusicalGrid(
    bpm: Double,
    timeSignature: String,
    val offsetMs: Long,
) {
    val bpm: Double = bpm.coerceIn(20.0, 400.0)
    val signature: MusicalTimeSignature = MusicalTimeSignature.parse(timeSignature)
    val beatDurationMs: Double = 60_000.0 / this.bpm
    val barDurationMs: Double = beatDurationMs * signature.beatsPerBar

    fun positionAt(ms: Long): MusicalPosition {
        val relative = (ms - offsetMs).coerceAtLeast(0L).toDouble()
        val beatIndex = floor(relative / beatDurationMs).toLong().coerceAtLeast(0L)
        val bar = (beatIndex / signature.beatsPerBar).toInt() + 1
        val beat = (beatIndex % signature.beatsPerBar).toInt() + 1
        return MusicalPosition(bar, beat)
    }

    fun msAt(position: MusicalPosition): Long {
        val beat = position.beat.coerceIn(1, signature.beatsPerBar)
        val beatIndex = (position.bar - 1L) * signature.beatsPerBar + (beat - 1L)
        return (offsetMs + beatIndex * beatDurationMs).roundToLong().coerceAtLeast(0L)
    }

    fun snap(ms: Long, snap: GridSnap = GridSnap.BEAT): Long {
        if (ms <= offsetMs) return offsetMs.coerceAtLeast(0L)
        val relative = ms - offsetMs
        val quantum = when (snap) {
            GridSnap.BEAT -> beatDurationMs
            GridSnap.BAR -> barDurationMs
        }
        val snapped = (relative / quantum).roundToLong() * quantum
        return (offsetMs + snapped).roundToLong().coerceAtLeast(0L)
    }

    fun endOfBarContaining(ms: Long): Long {
        val position = positionAt(ms)
        return msAt(MusicalPosition(position.bar + 1, 1))
    }

    fun totalBars(durationMs: Long): Int {
        if (durationMs <= offsetMs) return 1
        val relative = durationMs - offsetMs
        return (floor(relative / barDurationMs).toInt() + 1).coerceAtLeast(1)
    }

    fun clampPosition(position: MusicalPosition, durationMs: Long): MusicalPosition {
        val maxBar = totalBars(durationMs)
        return MusicalPosition(
            bar = position.bar.coerceIn(1, maxBar),
            beat = position.beat.coerceIn(1, signature.beatsPerBar),
        )
    }

    companion object {
        fun from(bpm: Double?, timeSignature: String, offsetMs: Long): MusicalGrid? =
            bpm?.takeIf { it in 20.0..400.0 }?.let { MusicalGrid(it, timeSignature, offsetMs) }
    }
}
