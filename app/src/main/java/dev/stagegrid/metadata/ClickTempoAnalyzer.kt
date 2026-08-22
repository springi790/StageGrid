package dev.stagegrid.metadata

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Tempo estimator specialized for imported click tracks.
 *
 * A musical autocorrelator can lock onto bar accents or other sub-harmonics. A click track is a
 * much simpler signal: detect its pulse train, normalize missed/double pulses into StageGrid's
 * practical tempo range, then use a robust median cluster. Weak/ambiguous trains return null.
 */
object ClickTempoAnalyzer {
    data class Result(
        val bpm: Double,
        val confidence: Float,
        val pulseCount: Int,
    )

    fun analyze(file: File): Result? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val wav = parseWav(raf) ?: return@use null
            if (wav.frameCount <= 0L) return@use null

            val windowFrames = max(16, wav.sampleRate / 250) // ~4 ms
            val scanFrames = minOf(wav.frameCount, wav.sampleRate.toLong() * MAX_SCAN_SECONDS)
            val windows = ((scanFrames + windowFrames - 1L) / windowFrames).toInt()
            if (windows < 64) return@use null

            val envelope = DoubleArray(windows)
            raf.seek(wav.dataOffset)
            var frame = 0L
            for (i in 0 until windows) {
                val framesHere = minOf(windowFrames.toLong(), scanFrames - frame).toInt()
                if (framesHere <= 0) break
                var sumSq = 0.0
                repeat(framesHere) {
                    val sample = readMonoFrame(raf, wav)
                    sumSq += sample * sample
                }
                envelope[i] = sqrt(sumSq / framesHere)
                frame += framesHere
            }

            val sorted = envelope.sorted()
            val floor = sorted[(sorted.lastIndex * 0.20).toInt().coerceIn(0, sorted.lastIndex)]
            val high = sorted[(sorted.lastIndex * 0.97).toInt().coerceIn(0, sorted.lastIndex)]
            if (high < MIN_SIGNAL_RMS) return@use null
            val threshold = max(MIN_SIGNAL_RMS, max(floor * 3.5, floor + (high - floor) * 0.10))

            val minimumGapWindows = max(1, ((wav.sampleRate * MIN_PULSE_GAP_SECONDS) / windowFrames).toInt())
            val peaks = mutableListOf<Int>()
            for (i in 1 until envelope.lastIndex) {
                val current = envelope[i]
                if (current < threshold || current < envelope[i - 1] || current < envelope[i + 1]) continue
                if (peaks.isEmpty() || i - peaks.last() >= minimumGapWindows) {
                    peaks += i
                } else if (current > envelope[peaks.last()]) {
                    peaks[peaks.lastIndex] = i
                }
            }
            if (peaks.size < MIN_PULSES) return@use null

            val secondsPerWindow = windowFrames.toDouble() / wav.sampleRate
            val normalizedBpms = mutableListOf<Double>()
            for (i in 1 until peaks.size) {
                val intervalSeconds = (peaks[i] - peaks[i - 1]) * secondsPerWindow
                if (intervalSeconds !in MIN_INTERVAL_SECONDS..MAX_INTERVAL_SECONDS) continue
                var bpm = 60.0 / intervalSeconds
                while (bpm < MIN_NORMALIZED_BPM) bpm *= 2.0
                while (bpm > MAX_NORMALIZED_BPM) bpm /= 2.0
                if (bpm in MIN_NORMALIZED_BPM..MAX_NORMALIZED_BPM) normalizedBpms += bpm
            }
            if (normalizedBpms.size < MIN_INTERVALS) return@use null

            val initialMedian = median(normalizedBpms)
            val tolerance = max(2.5, initialMedian * 0.045)
            val cluster = normalizedBpms.filter { abs(it - initialMedian) <= tolerance }
            if (cluster.size < MIN_INTERVALS) return@use null

            val bpm = median(cluster)
            val deviations = cluster.map { abs(it - bpm) }
            val mad = median(deviations)
            val clusterRatio = cluster.size.toDouble() / normalizedBpms.size
            val regularity = (1.0 - mad / max(1.0, bpm * 0.055)).coerceIn(0.0, 1.0)
            val density = (peaks.size / 16.0).coerceIn(0.0, 1.0)
            val confidence = (clusterRatio * 0.58 + regularity * 0.32 + density * 0.10).toFloat().coerceIn(0f, 1f)
            if (confidence < MIN_CONFIDENCE) return@use null

            Result(
                bpm = kotlin.math.round(bpm * 10.0) / 10.0,
                confidence = confidence,
                pulseCount = peaks.size,
            )
        }
    }.getOrNull()

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    private data class WavInfo(
        val formatCode: Int,
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val blockAlign: Int,
        val dataOffset: Long,
        val frameCount: Long,
    )

    private fun parseWav(raf: RandomAccessFile): WavInfo? {
        raf.seek(0)
        if (readFourCc(raf) != "RIFF") return null
        readU32(raf)
        if (readFourCc(raf) != "WAVE") return null
        var formatCode = 0
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var blockAlign = 0
        var dataOffset = -1L
        var dataBytes = -1L
        while (raf.filePointer + 8 <= raf.length()) {
            val id = readFourCc(raf)
            val size = readU32(raf)
            val start = raf.filePointer
            when (id) {
                "fmt " -> {
                    if (size < 16) return null
                    formatCode = readU16(raf)
                    channels = readU16(raf)
                    sampleRate = readU32(raf).toInt()
                    readU32(raf)
                    blockAlign = readU16(raf)
                    bits = readU16(raf)
                    if (formatCode == 0xFFFE && size >= 40) {
                        raf.seek(start + 24)
                        formatCode = readU16(raf)
                    }
                }
                "data" -> {
                    dataOffset = start
                    dataBytes = minOf(size, (raf.length() - start).coerceAtLeast(0L))
                }
            }
            raf.seek((start + size + (size and 1L)).coerceAtMost(raf.length()))
            if (formatCode != 0 && dataOffset >= 0) break
        }
        if (formatCode !in setOf(1, 3) || channels !in 1..8 || sampleRate !in 8_000..384_000) return null
        if (bits !in setOf(8, 16, 24, 32) || blockAlign <= 0 || dataBytes <= 0) return null
        if (formatCode == 3 && bits != 32) return null
        return WavInfo(formatCode, channels, sampleRate, bits, blockAlign, dataOffset, dataBytes / blockAlign)
    }

    private fun readMonoFrame(raf: RandomAccessFile, wav: WavInfo): Double {
        val bytesPerSample = (wav.bitsPerSample + 7) / 8
        var sum = 0.0
        repeat(wav.channels) {
            sum += when {
                wav.formatCode == 3 && wav.bitsPerSample == 32 ->
                    Float.fromBits(readU32(raf).toInt()).takeIf { it.isFinite() }?.coerceIn(-1f, 1f)?.toDouble() ?: 0.0
                wav.bitsPerSample == 8 -> (raf.readUnsignedByte() - 128) / 128.0
                wav.bitsPerSample == 16 -> readS16(raf) / 32768.0
                wav.bitsPerSample == 24 -> readS24(raf) / 8388608.0
                wav.bitsPerSample == 32 -> readU32(raf).toInt() / 2147483648.0
                else -> 0.0
            }
        }
        val used = wav.channels * bytesPerSample
        if (wav.blockAlign > used) raf.skipBytes(wav.blockAlign - used)
        return (sum / wav.channels.coerceAtLeast(1)).coerceIn(-1.0, 1.0)
    }

    private fun readFourCc(raf: RandomAccessFile): String {
        val bytes = ByteArray(4)
        raf.readFully(bytes)
        return bytes.toString(Charsets.US_ASCII)
    }

    private fun readU16(raf: RandomAccessFile): Int = raf.readUnsignedByte() or (raf.readUnsignedByte() shl 8)

    private fun readS16(raf: RandomAccessFile): Int {
        val value = readU16(raf)
        return if (value and 0x8000 != 0) value - 0x10000 else value
    }

    private fun readS24(raf: RandomAccessFile): Int {
        var value = raf.readUnsignedByte() or (raf.readUnsignedByte() shl 8) or (raf.readUnsignedByte() shl 16)
        if (value and 0x800000 != 0) value = value or -0x1000000
        return value
    }

    private fun readU32(raf: RandomAccessFile): Long {
        val b0 = raf.readUnsignedByte().toLong()
        val b1 = raf.readUnsignedByte().toLong()
        val b2 = raf.readUnsignedByte().toLong()
        val b3 = raf.readUnsignedByte().toLong()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private const val MAX_SCAN_SECONDS = 90L
    private const val MIN_SIGNAL_RMS = 0.003
    private const val MIN_PULSE_GAP_SECONDS = 0.07
    private const val MIN_INTERVAL_SECONDS = 0.18
    private const val MAX_INTERVAL_SECONDS = 2.0
    private const val MIN_NORMALIZED_BPM = 70.0
    private const val MAX_NORMALIZED_BPM = 190.0
    private const val MIN_PULSES = 8
    private const val MIN_INTERVALS = 6
    private const val MIN_CONFIDENCE = 0.48f
}
