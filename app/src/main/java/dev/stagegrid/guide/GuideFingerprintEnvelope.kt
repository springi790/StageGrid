package dev.stagegrid.guide

import java.io.RandomAccessFile
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Phase-robust energy envelope used only by Guide recognition.
 *
 * Unlike a simple stereo downmix, channel energy is accumulated before averaging, so a Guide stem
 * with inverted or decorrelated stereo channels cannot cancel its own speech during fingerprinting.
 */
internal object GuideFingerprintEnvelope {
    fun read(file: java.io.File, windowMs: Int): FloatArray {
        RandomAccessFile(file, "r").use { raf ->
            val wav = parseWav(raf) ?: error("Unsupported WAV: ${file.name}")
            val framesPerWindow = max(1, wav.sampleRate * windowMs / 1000)
            val windowCount = ((wav.frameCount + framesPerWindow - 1L) / framesPerWindow)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            val envelope = FloatArray(windowCount)
            raf.seek(wav.dataOffset)
            var remaining = wav.frameCount
            var index = 0
            while (remaining > 0 && index < envelope.size) {
                val frames = minOf(framesPerWindow.toLong(), remaining).toInt()
                var sumFrameEnergy = 0.0
                repeat(frames) {
                    var channelEnergy = 0.0
                    repeat(wav.channels) {
                        val value = readSample(raf, wav)
                        channelEnergy += value * value
                    }
                    val used = wav.channels * wav.bytesPerSample
                    if (wav.blockAlign > used) raf.skipBytes(wav.blockAlign - used)
                    sumFrameEnergy += channelEnergy / wav.channels.coerceAtLeast(1)
                }
                envelope[index++] = sqrt(sumFrameEnergy / frames.coerceAtLeast(1)).toFloat()
                remaining -= frames
            }
            return if (index == envelope.size) envelope else envelope.copyOf(index)
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

    private fun readSample(raf: RandomAccessFile, wav: WavInfo): Double = when {
        wav.formatCode == 3 && wav.bitsPerSample == 32 -> {
            Float.fromBits(readU32(raf).toInt()).takeIf { it.isFinite() }?.coerceIn(-1f, 1f)?.toDouble() ?: 0.0
        }
        wav.bitsPerSample == 8 -> (raf.readUnsignedByte() - 128) / 128.0
        wav.bitsPerSample == 16 -> readS16(raf) / 32768.0
        wav.bitsPerSample == 24 -> readS24(raf) / 8388608.0
        wav.bitsPerSample == 32 -> readU32(raf).toInt() / 2147483648.0
        else -> 0.0
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
