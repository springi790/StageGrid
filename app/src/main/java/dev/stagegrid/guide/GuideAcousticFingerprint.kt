package dev.stagegrid.guide

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Lightweight multi-band speech fingerprint used as a second-stage discriminator for English Guide cues.
 *
 * The existing RMS envelope is very good at locating calls but can confuse short English words with
 * similar amplitude shapes (for example Vamp/Rap/Verse). This extractor keeps time alignment at 10 ms
 * while adding four coarse spectral bands. It intentionally avoids FFT allocations and processes a
 * decimated stream so import-time recognition remains bounded.
 */
internal object GuideAcousticFingerprint {
    const val DIMENSIONS = 5

    data class Series(
        val windows: Int,
        val values: FloatArray,
    ) {
        init {
            require(values.size == windows * DIMENSIONS)
        }
    }

    private data class WavInfo(
        val formatCode: Int,
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val blockAlign: Int,
        val dataOffset: Long,
        val frameCount: Long,
    ) {
        val bytesPerSample: Int get() = (bitsPerSample + 7) / 8
    }

    fun read(file: File, windowMs: Int = 10): Series {
        val wav = RandomAccessFile(file, "r").use { parseWav(it) ?: error("Unsupported WAV: ${file.name}") }
        val framesPerWindow = max(1, wav.sampleRate * windowMs / 1000)
        val countLong = (wav.frameCount + framesPerWindow - 1L) / framesPerWindow
        require(countLong <= Int.MAX_VALUE) { "Guide WAV is too long: ${file.name}" }
        val windows = countLong.toInt()
        val values = FloatArray(windows * DIMENSIONS)
        val buffer = ByteArray(framesPerWindow * wav.blockAlign)

        // Speech information above ~5 kHz is not needed for this coarse discriminator. Decimating
        // reduces the amount of filtering work substantially without changing the 10 ms timeline.
        val stride = max(1, wav.sampleRate / TARGET_ANALYSIS_RATE)
        val analysisRate = wav.sampleRate.toDouble() / stride.toDouble()
        val alpha300 = lowPassAlpha(300.0, analysisRate)
        val alpha1000 = lowPassAlpha(1_000.0, analysisRate)
        val alpha3000 = lowPassAlpha(3_000.0.coerceAtMost(analysisRate * 0.42), analysisRate)
        val lp300 = DoubleArray(wav.channels)
        val lp1000 = DoubleArray(wav.channels)
        val lp3000 = DoubleArray(wav.channels)

        BufferedInputStream(FileInputStream(file), STREAM_BUFFER_BYTES).use { input ->
            skipFully(input, wav.dataOffset)
            var remainingFrames = wav.frameCount
            var windowIndex = 0
            while (remainingFrames > 0 && windowIndex < windows) {
                val requestedFrames = minOf(framesPerWindow.toLong(), remainingFrames).toInt()
                val requestedBytes = requestedFrames * wav.blockAlign
                val read = readFully(input, buffer, requestedBytes)
                val completeFrames = read / wav.blockAlign
                if (completeFrames <= 0) break

                val bandEnergy = DoubleArray(4)
                var processedSamples = 0
                var frame = 0
                while (frame < completeFrames) {
                    val frameOffset = frame * wav.blockAlign
                    for (channel in 0 until wav.channels) {
                        val x = decodeChannelSample(buffer, frameOffset, channel, wav).toDouble()
                        lp300[channel] += alpha300 * (x - lp300[channel])
                        lp1000[channel] += alpha1000 * (x - lp1000[channel])
                        lp3000[channel] += alpha3000 * (x - lp3000[channel])

                        val low = lp300[channel]
                        val lowMid = lp1000[channel] - lp300[channel]
                        val highMid = lp3000[channel] - lp1000[channel]
                        val high = x - lp3000[channel]
                        bandEnergy[0] += low * low
                        bandEnergy[1] += lowMid * lowMid
                        bandEnergy[2] += highMid * highMid
                        bandEnergy[3] += high * high
                    }
                    processedSamples += wav.channels
                    frame += stride
                }

                val denominator = processedSamples.coerceAtLeast(1).toDouble()
                val total = bandEnergy.sum().coerceAtLeast(EPSILON)
                val base = windowIndex * DIMENSIONS
                values[base] = ln(1.0 + sqrt(total / denominator) * 50.0).toFloat()
                for (band in 0 until 4) {
                    // Relative band energy is much less sensitive to gain than raw spectral energy.
                    values[base + 1 + band] = ln((bandEnergy[band] + EPSILON) / (total + 4.0 * EPSILON)).toFloat()
                }

                windowIndex++
                remainingFrames -= completeFrames
                if (completeFrames < requestedFrames) break
            }
            return if (windowIndex == windows) {
                Series(windows, values)
            } else {
                Series(windowIndex, values.copyOf(windowIndex * DIMENSIONS))
            }
        }
    }

    fun sliceAndNormalize(series: Series, startWindow: Int, windowCount: Int): FloatArray? {
        if (windowCount < 4 || startWindow < 0 || startWindow + windowCount > series.windows) return null
        val out = FloatArray(windowCount * DIMENSIONS)
        for (window in 0 until windowCount) {
            val source = (startWindow + window) * DIMENSIONS
            val destination = window * DIMENSIONS
            for (dimension in 0 until DIMENSIONS) out[destination + dimension] = series.values[source + dimension]
        }
        normalizeDimensionsInPlace(out, windowCount)
        return out
    }

    /** Correlation of one normalized template against an un-normalized Guide segment. */
    fun correlation(
        guide: Series,
        startWindow: Int,
        template: FloatArray,
        windowCount: Int,
    ): Float? {
        if (windowCount < 4 || template.size != windowCount * DIMENSIONS) return null
        if (startWindow < 0 || startWindow + windowCount > guide.windows) return null

        val weights = FEATURE_WEIGHTS
        var weightedScore = 0.0
        var usedWeight = 0.0
        for (dimension in 0 until DIMENSIONS) {
            var sum = 0.0
            var sumSq = 0.0
            for (window in 0 until windowCount) {
                val value = guide.values[(startWindow + window) * DIMENSIONS + dimension].toDouble()
                sum += value
                sumSq += value * value
            }
            val mean = sum / windowCount
            val variance = (sumSq / windowCount) - mean * mean
            if (variance <= 1e-10) continue
            val std = sqrt(variance)
            var dot = 0.0
            for (window in 0 until windowCount) {
                val guideValue = guide.values[(startWindow + window) * DIMENSIONS + dimension]
                val templateValue = template[window * DIMENSIONS + dimension]
                dot += templateValue * ((guideValue - mean) / std)
            }
            val score = (dot / windowCount).coerceIn(-1.0, 1.0)
            weightedScore += score * weights[dimension]
            usedWeight += weights[dimension]
        }
        if (usedWeight <= 0.0) return null
        return (weightedScore / usedWeight).toFloat().coerceIn(-1f, 1f)
    }

    private fun normalizeDimensionsInPlace(values: FloatArray, windows: Int) {
        for (dimension in 0 until DIMENSIONS) {
            var sum = 0.0
            var sumSq = 0.0
            for (window in 0 until windows) {
                val value = values[window * DIMENSIONS + dimension].toDouble()
                sum += value
                sumSq += value * value
            }
            val mean = sum / windows
            val variance = (sumSq / windows) - mean * mean
            val std = sqrt(variance.coerceAtLeast(1e-12))
            for (window in 0 until windows) {
                val index = window * DIMENSIONS + dimension
                values[index] = ((values[index] - mean) / std).toFloat()
            }
        }
    }

    private fun lowPassAlpha(cutoff: Double, sampleRate: Double): Double {
        val normalizedCutoff = cutoff.coerceIn(20.0, sampleRate * 0.45)
        return 1.0 - exp(-2.0 * PI * normalizedCutoff / sampleRate)
    }

    private fun decodeChannelSample(bytes: ByteArray, frameOffset: Int, channel: Int, wav: WavInfo): Float {
        val offset = frameOffset + channel * wav.bytesPerSample
        return when {
            wav.formatCode == 3 && wav.bitsPerSample == 32 -> {
                val bits = littleU32(bytes, offset).toInt()
                Float.fromBits(bits).takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
            }
            wav.bitsPerSample == 8 -> ((bytes[offset].toInt() and 0xFF) - 128) / 128f
            wav.bitsPerSample == 16 -> littleS16(bytes, offset) / 32768f
            wav.bitsPerSample == 24 -> littleS24(bytes, offset) / 8388608f
            wav.bitsPerSample == 32 -> littleU32(bytes, offset).toInt() / 2147483648f
            else -> 0f
        }
    }

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
        if (blockAlign < channels * ((bitsPerSample + 7) / 8)) return null
        return WavInfo(formatCode, channels, sampleRate, bitsPerSample, blockAlign, dataOffset, dataBytes / blockAlign)
    }

    private fun readFully(input: BufferedInputStream, buffer: ByteArray, bytes: Int): Int {
        var offset = 0
        while (offset < bytes) {
            val read = input.read(buffer, offset, bytes - offset)
            if (read <= 0) break
            offset += read
        }
        return offset
    }

    private fun skipFully(input: BufferedInputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) remaining -= skipped
            else {
                if (input.read() < 0) error("Unexpected end of WAV before audio data")
                remaining--
            }
        }
    }

    private fun littleS16(bytes: ByteArray, offset: Int): Int {
        val value = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
        return if (value and 0x8000 != 0) value - 0x10000 else value
    }

    private fun littleS24(bytes: ByteArray, offset: Int): Int {
        var value = (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16)
        if (value and 0x800000 != 0) value = value or -0x1000000
        return value
    }

    private fun littleU32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    private fun readFourCc(raf: RandomAccessFile): String {
        val bytes = ByteArray(4)
        raf.readFully(bytes)
        return bytes.toString(Charsets.US_ASCII)
    }

    private fun readU16(raf: RandomAccessFile): Int =
        raf.readUnsignedByte() or (raf.readUnsignedByte() shl 8)

    private fun readU32(raf: RandomAccessFile): Long {
        val b0 = raf.readUnsignedByte().toLong()
        val b1 = raf.readUnsignedByte().toLong()
        val b2 = raf.readUnsignedByte().toLong()
        val b3 = raf.readUnsignedByte().toLong()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private const val TARGET_ANALYSIS_RATE = 12_000
    private const val STREAM_BUFFER_BYTES = 256 * 1024
    private const val EPSILON = 1e-12
    private val FEATURE_WEIGHTS = doubleArrayOf(0.36, 0.13, 0.17, 0.20, 0.14)
}
