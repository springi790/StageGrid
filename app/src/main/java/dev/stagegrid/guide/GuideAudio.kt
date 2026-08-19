package dev.stagegrid.guide

import java.io.File
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

    fun rmsEnvelope(file: File, windowMs: Int = 10): FloatArray {
        RandomAccessFile(file, "r").use { raf ->
            val wav = parseWav(raf) ?: error("Unsupported WAV: ${file.name}")
            val framesPerWindow = max(1, wav.sampleRate * windowMs / 1000)
            val count = ((wav.frameCount + framesPerWindow - 1) / framesPerWindow).toInt()
            val result = FloatArray(count)
            raf.seek(wav.dataOffset)
            var frame = 0L
            var index = 0
            while (frame < wav.frameCount && index < result.size) {
                val thisWindow = minOf(framesPerWindow.toLong(), wav.frameCount - frame).toInt()
                var sumSq = 0.0
                repeat(thisWindow) {
                    val sample = readNextMono(raf, wav)
                    sumSq += sample * sample
                }
                result[index] = sqrt(sumSq / thisWindow.coerceAtLeast(1)).toFloat()
                frame += thisWindow
                index++
            }
            return if (index == result.size) result else result.copyOf(index)
        }
    }

    /** Intended for short Guide cue samples, not full multitrack stems. */
    fun readMono(file: File): MonoAudio {
        RandomAccessFile(file, "r").use { raf ->
            val wav = parseWav(raf) ?: error("Unsupported WAV: ${file.name}")
            require(wav.frameCount <= Int.MAX_VALUE) { "Guide sample is too long: ${file.name}" }
            val samples = FloatArray(wav.frameCount.toInt())
            raf.seek(wav.dataOffset)
            for (i in samples.indices) samples[i] = readNextMono(raf, wav)
            return MonoAudio(wav.sampleRate, samples)
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
                    dataBytes = size
                }
            }
            raf.seek((chunkStart + size + (size and 1L)).coerceAtMost(raf.length()))
            if (formatCode != 0 && dataOffset >= 0) break
        }

        if (formatCode !in setOf(1, 3)) return null
        if (channels !in 1..8 || sampleRate !in 8_000..384_000) return null
        if (bitsPerSample !in setOf(8, 16, 24, 32) || blockAlign <= 0 || dataBytes <= 0) return null
        val minimumAlign = channels * ((bitsPerSample + 7) / 8)
        if (blockAlign < minimumAlign) return null
        return WavInfo(formatCode, channels, sampleRate, bitsPerSample, blockAlign, dataOffset, dataBytes / blockAlign)
    }

    private fun readNextMono(raf: RandomAccessFile, wav: WavInfo): Float {
        val bytesPerSample = (wav.bitsPerSample + 7) / 8
        var sum = 0f
        repeat(wav.channels) {
            val value = when {
                wav.formatCode == 3 && wav.bitsPerSample == 32 -> {
                    val bits = readU32(raf).toInt()
                    Float.fromBits(bits).takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
                }
                wav.bitsPerSample == 8 -> (raf.readUnsignedByte() - 128) / 128f
                wav.bitsPerSample == 16 -> readS16(raf) / 32768f
                wav.bitsPerSample == 24 -> readS24(raf) / 8388608f
                wav.bitsPerSample == 32 -> readS32(raf) / 2147483648f
                else -> 0f
            }
            sum += value
        }
        val used = wav.channels * bytesPerSample
        if (wav.blockAlign > used) raf.skipBytes(wav.blockAlign - used)
        return (sum / wav.channels.coerceAtLeast(1)).coerceIn(-1f, 1f)
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

    private fun readS32(raf: RandomAccessFile): Int = readU32(raf).toInt()

    private fun readU32(raf: RandomAccessFile): Long {
        val b0 = raf.readUnsignedByte().toLong()
        val b1 = raf.readUnsignedByte().toLong()
        val b2 = raf.readUnsignedByte().toLong()
        val b3 = raf.readUnsignedByte().toLong()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }
}
