package dev.stagegrid.guide

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import kotlin.math.max
import kotlin.math.sqrt

internal object GuideAudio {
    data class MonoAudio(val sampleRate: Int, val samples: FloatArray)

    private data class WavInfo(
        val formatCode: Int,
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val blockAlign: Int,
        val dataOffset: Long,
        val frameCount: Long,
    )

    /**
     * Produces a compact energy fingerprint without loading a full Guide stem into memory.
     * File I/O is buffered and happens only during import-time analysis.
     */
    fun rmsEnvelope(file: File, windowMs: Int = 10): FloatArray {
        val wav = readWavInfo(file)
        val framesPerWindow = max(1, wav.sampleRate * windowMs / 1000)
        val countLong = (wav.frameCount + framesPerWindow - 1L) / framesPerWindow
        require(countLong <= Int.MAX_VALUE) { "Guide WAV is too long: ${file.name}" }
        val result = FloatArray(countLong.toInt())
        val windowBuffer = ByteArray(framesPerWindow * wav.blockAlign)

        BufferedInputStream(FileInputStream(file), STREAM_BUFFER_BYTES).use { input ->
            skipFully(input, wav.dataOffset)
            var remainingFrames = wav.frameCount
            var index = 0
            while (remainingFrames > 0 && index < result.size) {
                val frames = minOf(framesPerWindow.toLong(), remainingFrames).toInt()
                val bytes = frames * wav.blockAlign
                val read = readFully(input, windowBuffer, bytes)
                val completeFrames = read / wav.blockAlign
                if (completeFrames <= 0) break
                var sumSq = 0.0
                var offset = 0
                repeat(completeFrames) {
                    val sample = decodeMonoFrame(windowBuffer, offset, wav)
                    sumSq += sample * sample
                    offset += wav.blockAlign
                }
                result[index++] = sqrt(sumSq / completeFrames).toFloat()
                remainingFrames -= completeFrames
                if (completeFrames < frames) break
            }
            return if (index == result.size) result else result.copyOf(index)
        }
    }

    /** Intended for the short installed Guide cue samples, not full multitrack stems. */
    fun readMono(file: File): MonoAudio {
        val wav = readWavInfo(file)
        require(wav.frameCount <= Int.MAX_VALUE) { "Guide sample is too long: ${file.name}" }
        val samples = FloatArray(wav.frameCount.toInt())
        val chunkFrames = minOf(4096, samples.size.coerceAtLeast(1))
        val buffer = ByteArray(chunkFrames * wav.blockAlign)

        BufferedInputStream(FileInputStream(file), STREAM_BUFFER_BYTES).use { input ->
            skipFully(input, wav.dataOffset)
            var destination = 0
            while (destination < samples.size) {
                val requestedFrames = minOf(chunkFrames, samples.size - destination)
                val read = readFully(input, buffer, requestedFrames * wav.blockAlign)
                val completeFrames = read / wav.blockAlign
                if (completeFrames <= 0) break
                var offset = 0
                repeat(completeFrames) {
                    samples[destination++] = decodeMonoFrame(buffer, offset, wav)
                    offset += wav.blockAlign
                }
                if (completeFrames < requestedFrames) break
            }
            return MonoAudio(wav.sampleRate, if (destination == samples.size) samples else samples.copyOf(destination))
        }
    }

    fun resampleLinear(audio: MonoAudio, targetRate: Int): FloatArray {
        if (audio.sampleRate == targetRate) return audio.samples
        if (audio.samples.isEmpty()) return FloatArray(0)
        val outputSize = ((audio.samples.size.toLong() * targetRate) / audio.sampleRate)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val out = FloatArray(outputSize)
        val ratio = audio.sampleRate.toDouble() / targetRate.toDouble()
        for (i in out.indices) {
            val position = i * ratio
            val left = position.toInt().coerceIn(0, audio.samples.lastIndex)
            val right = (left + 1).coerceAtMost(audio.samples.lastIndex)
            val fraction = (position - left).toFloat()
            out[i] = audio.samples[left] + (audio.samples[right] - audio.samples[left]) * fraction
        }
        return out
    }

    private fun readWavInfo(file: File): WavInfo = RandomAccessFile(file, "r").use { raf ->
        parseWav(raf) ?: error("Unsupported WAV: ${file.name}")
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
        val minimumAlign = channels * ((bitsPerSample + 7) / 8)
        if (blockAlign < minimumAlign) return null
        return WavInfo(formatCode, channels, sampleRate, bitsPerSample, blockAlign, dataOffset, dataBytes / blockAlign)
    }

    private fun decodeMonoFrame(bytes: ByteArray, frameOffset: Int, wav: WavInfo): Float {
        val bytesPerSample = (wav.bitsPerSample + 7) / 8
        var sum = 0f
        for (channel in 0 until wav.channels) {
            val offset = frameOffset + channel * bytesPerSample
            val value = when {
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
            sum += value
        }
        return (sum / wav.channels).coerceIn(-1f, 1f)
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
            if (skipped > 0) {
                remaining -= skipped
            } else {
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

    private const val STREAM_BUFFER_BYTES = 256 * 1024
}
