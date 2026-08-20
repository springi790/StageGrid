package dev.stagegrid.importer

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Locates the beginning of a stable Click pulse train instead of trusting one isolated transient.
 *
 * This deliberately does NOT trim leading silence from stems. The selected offset is only the
 * musical-grid origin used by StageGrid's native Click and section snapping.
 */
object ClickGridAnalyzer {
    data class Result(
        val offsetMs: Long,
        val confidence: Float,
        val peak: Float,
        val noiseFloor: Float,
    )

    private data class Onset(val frame: Long, val energy: Float)

    private const val MAX_SCAN_SECONDS = 30
    private const val MIN_ABSOLUTE_THRESHOLD = 0.008f

    fun analyze(file: File): Result? {
        RandomAccessFile(file, "r").use { raf ->
            val wav = parseWav(raf) ?: return null
            if (wav.frameCount <= 0) return null

            val scanFrames = minOf(wav.frameCount, wav.sampleRate.toLong() * MAX_SCAN_SECONDS)
            val windowFrames = max(8, wav.sampleRate / 500) // ~2 ms
            val windows = ((scanFrames + windowFrames - 1) / windowFrames).toInt().coerceAtLeast(1)
            val envelope = FloatArray(windows)

            var globalPeak = 0f
            var frame = 0L
            var windowIndex = 0
            raf.seek(wav.dataOffset)
            while (frame < scanFrames && windowIndex < windows) {
                val framesThisWindow = minOf(windowFrames.toLong(), scanFrames - frame).toInt()
                var sumSq = 0.0
                repeat(framesThisWindow) {
                    val peak = readNextFramePeak(raf, wav)
                    sumSq += peak * peak
                    globalPeak = max(globalPeak, peak)
                }
                envelope[windowIndex] = sqrt(sumSq / framesThisWindow.coerceAtLeast(1)).toFloat()
                frame += framesThisWindow
                windowIndex++
            }
            if (windowIndex == 0 || globalPeak < MIN_ABSOLUTE_THRESHOLD) return null

            val sorted = envelope.copyOf(windowIndex).sorted()
            val floorCount = max(1, sorted.size / 5)
            val noiseFloor = sorted.take(floorCount).average().toFloat()
            // A later accented click should not hide quieter early pulses.
            val threshold = max(MIN_ABSOLUTE_THRESHOLD, max(noiseFloor * 6f, globalPeak * 0.06f))

            val coarse = mutableListOf<Pair<Long, Float>>()
            var previous = 0f
            var lastCandidateWindow = -100
            for (i in 0 until windowIndex) {
                val current = envelope[i]
                val crossed = current >= threshold && previous < threshold * 0.78f
                val sharpRise = current >= threshold && current >= previous * 1.35f
                if ((crossed || sharpRise) && i - lastCandidateWindow >= 3) {
                    coarse += i.toLong() * windowFrames to current
                    lastCandidateWindow = i
                }
                previous = current
            }
            if (coarse.isEmpty()) return null

            val onsets = coarse.map { (coarseFrame, energy) ->
                Onset(
                    frame = refineTransient(raf, wav, coarseFrame, threshold * 0.45f),
                    energy = energy,
                )
            }.distinctBy { it.frame / max(1, wav.sampleRate / 500) }

            val selected = chooseStableTrainStart(onsets, wav.sampleRate) ?: onsets.first()
            val offsetMs = selected.frame * 1000L / wav.sampleRate
            val separation = selected.energy / noiseFloor.coerceAtLeast(0.00001f)
            val baseConfidence = ((separation - 2f) / 18f).coerceIn(0f, 1f)
            val periodicBonus = if (isStableTrainStart(selected.frame, onsets, wav.sampleRate)) 0.20f else 0f
            return Result(
                offsetMs = offsetMs,
                confidence = (baseConfidence + periodicBonus).coerceIn(0f, 1f),
                peak = globalPeak,
                noiseFloor = noiseFloor,
            )
        }
    }

    private fun chooseStableTrainStart(onsets: List<Onset>, sampleRate: Int): Onset? {
        if (onsets.size < 4) return null
        for (i in 0 until minOf(onsets.size, 32)) {
            if (isStableTrainStart(onsets[i].frame, onsets.drop(i), sampleRate)) return onsets[i]
        }
        return null
    }

    private fun isStableTrainStart(startFrame: Long, onsets: List<Onset>, sampleRate: Int): Boolean {
        if (onsets.size < 4) return false
        val minimumInterval = sampleRate * 80L / 1000L
        val maximumInterval = sampleRate * 2_000L / 1000L
        val startIndex = onsets.indexOfFirst { it.frame == startFrame }.takeIf { it >= 0 } ?: 0
        val maxBaseIndex = minOf(onsets.lastIndex, startIndex + 4)
        for (j in startIndex + 1..maxBaseIndex) {
            val interval = onsets[j].frame - startFrame
            if (interval !in minimumInterval..maximumInterval) continue
            val tolerance = max(sampleRate / 100L, (interval * 12L) / 100L) // >=10 ms, 12%.
            var hits = 0
            for (multiple in 1..4) {
                val expected = startFrame + interval * multiple
                if (onsets.any { abs(it.frame - expected) <= tolerance }) hits++
            }
            if (hits >= 3) return true
        }
        return false
    }

    private fun refineTransient(raf: RandomAccessFile, wav: WavInfo, coarseFrame: Long, threshold: Float): Long {
        val radius = max(1, wav.sampleRate / 100) // 10 ms
        val start = (coarseFrame - radius).coerceAtLeast(0)
        val end = minOf(wav.frameCount, coarseFrame + radius)
        raf.seek(wav.dataOffset + start * wav.blockAlign)
        var frame = start
        while (frame < end) {
            if (readNextFramePeak(raf, wav) >= threshold) return frame
            frame++
        }
        return coarseFrame
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
        var bitsPerSample = 0
        var blockAlign = 0
        var dataOffset = -1L
        var dataBytes = -1L

        while (raf.filePointer + 8 <= raf.length()) {
            val id = readFourCc(raf)
            val size = readU32(raf)
            val chunkStart = raf.filePointer
            when (id) {
                "fmt " -> {
                    if (size < 16) return null
                    formatCode = readU16(raf)
                    channels = readU16(raf)
                    sampleRate = readU32(raf).toInt()
                    readU32(raf)
                    blockAlign = readU16(raf)
                    bitsPerSample = readU16(raf)
                    if (formatCode == 0xFFFE && size >= 40) {
                        raf.seek(chunkStart + 24)
                        formatCode = readU16(raf)
                    }
                }
                "data" -> {
                    dataOffset = chunkStart
                    dataBytes = minOf(size, (raf.length() - chunkStart).coerceAtLeast(0L))
                }
            }
            raf.seek((chunkStart + size + (size and 1L)).coerceAtMost(raf.length()))
            if (formatCode != 0 && dataOffset >= 0) break
        }

        if (formatCode !in setOf(1, 3)) return null
        if (channels !in 1..8 || sampleRate !in 8_000..384_000) return null
        if (bitsPerSample !in setOf(8, 16, 24, 32) || blockAlign <= 0 || dataBytes <= 0) return null
        if (formatCode == 3 && bitsPerSample != 32) return null
        val minimumAlign = channels * ((bitsPerSample + 7) / 8)
        if (blockAlign < minimumAlign) return null
        return WavInfo(formatCode, channels, sampleRate, bitsPerSample, blockAlign, dataOffset, dataBytes / blockAlign)
    }

    private fun readNextFramePeak(raf: RandomAccessFile, wav: WavInfo): Float {
        val bytesPerSample = (wav.bitsPerSample + 7) / 8
        var peak = 0f
        repeat(wav.channels) {
            val sample = when {
                wav.formatCode == 3 && wav.bitsPerSample == 32 -> {
                    Float.fromBits(readU32(raf).toInt()).takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
                }
                wav.bitsPerSample == 8 -> (raf.readUnsignedByte() - 128) / 128f
                wav.bitsPerSample == 16 -> readS16(raf) / 32768f
                wav.bitsPerSample == 24 -> readS24(raf) / 8388608f
                wav.bitsPerSample == 32 -> readU32(raf).toInt() / 2147483648f
                else -> 0f
            }
            peak = max(peak, abs(sample))
        }
        val used = wav.channels * bytesPerSample
        if (wav.blockAlign > used) raf.skipBytes(wav.blockAlign - used)
        return peak
    }

    private fun readFourCc(raf: RandomAccessFile): String {
        val bytes = ByteArray(4)
        raf.readFully(bytes)
        return bytes.toString(Charsets.US_ASCII)
    }

    private fun readU16(raf: RandomAccessFile): Int =
        raf.readUnsignedByte() or (raf.readUnsignedByte() shl 8)

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
}
