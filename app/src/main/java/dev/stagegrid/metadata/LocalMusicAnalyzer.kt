package dev.stagegrid.metadata

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Conservative local fallback for imported PCM WAV files.
 *
 * Tempo uses an onset-energy autocorrelation. Key uses sparse chroma/Goertzel windows followed by
 * Krumhansl-Schmuckler major/minor profiles. Results below the confidence floor are intentionally
 * discarded so a weak analysis never silently replaces user metadata.
 */
object LocalMusicAnalyzer {
    data class TempoResult(val bpm: Double, val confidence: Float)
    data class KeyResult(val key: String, val confidence: Float)

    fun analyzeTempo(file: File): TempoResult? {
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val wav = parseWav(raf) ?: return@use null
                val hopFrames = max(256, wav.sampleRate / 50) // ~20 ms
                val maxFrames = minOf(wav.frameCount, wav.sampleRate.toLong() * 90L)
                val count = (maxFrames / hopFrames).toInt().coerceAtMost(5_000)
                if (count < 120) return@use null
                val energy = DoubleArray(count)
                raf.seek(wav.dataOffset)
                var cursor = 0L
                for (i in 0 until count) {
                    var sum = 0.0
                    var used = 0
                    while (used < hopFrames && cursor < maxFrames) {
                        val sample = readMonoFrame(raf, wav)
                        sum += sample * sample
                        cursor++
                        used++
                    }
                    energy[i] = sqrt(sum / used.coerceAtLeast(1))
                }
                val onset = DoubleArray(count)
                for (i in 1 until count) onset[i] = max(0.0, energy[i] - energy[i - 1])
                val mean = onset.average()
                var variance = 0.0
                for (value in onset) variance += (value - mean) * (value - mean)
                if (variance <= 1e-12) return@use null

                val minBpm = 60.0
                val maxBpm = 200.0
                val secondsPerHop = hopFrames.toDouble() / wav.sampleRate
                val minLag = (60.0 / maxBpm / secondsPerHop).toInt().coerceAtLeast(1)
                val maxLag = (60.0 / minBpm / secondsPerHop).toInt().coerceAtMost(count / 2)
                var bestLag = 0
                var best = Double.NEGATIVE_INFINITY
                var second = Double.NEGATIVE_INFINITY
                for (lag in minLag..maxLag) {
                    var score = 0.0
                    var normA = 0.0
                    var normB = 0.0
                    for (i in lag until count) {
                        val a = onset[i]
                        val b = onset[i - lag]
                        score += a * b
                        normA += a * a
                        normB += b * b
                    }
                    val normalized = score / sqrt((normA * normB).coerceAtLeast(1e-18))
                    if (normalized > best) {
                        second = best
                        best = normalized
                        bestLag = lag
                    } else if (normalized > second) {
                        second = normalized
                    }
                }
                if (bestLag <= 0 || best < 0.08) return@use null
                var bpm = 60.0 / (bestLag * secondsPerHop)
                while (bpm < 70.0) bpm *= 2.0
                while (bpm > 190.0) bpm /= 2.0
                val separation = ((best - second.coerceAtLeast(0.0)) / best.coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
                val confidence = (best * 0.72 + separation * 0.28).toFloat().coerceIn(0f, 1f)
                if (confidence < 0.20f) null else TempoResult((bpm * 10.0).toInt() / 10.0, confidence)
            }
        }.getOrNull()
    }

    fun analyzeKey(file: File): KeyResult? {
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val wav = parseWav(raf) ?: return@use null
                val window = 4_096
                if (wav.frameCount < window * 4L) return@use null
                val availableSeconds = (wav.frameCount.toDouble() / wav.sampleRate).coerceAtMost(90.0)
                val windows = minOf(28, max(8, (availableSeconds / 3.0).toInt()))
                val chroma = DoubleArray(12)
                val frequencies = Array(12) { pitchClass ->
                    doubleArrayOf(
                        midiToHz(48 + pitchClass),
                        midiToHz(60 + pitchClass),
                        midiToHz(72 + pitchClass),
                    )
                }
                val samples = DoubleArray(window)
                for (w in 0 until windows) {
                    val center = ((w + 0.5) / windows * minOf(wav.frameCount.toDouble(), wav.sampleRate * 90.0)).toLong()
                    val maximumStart = (wav.frameCount - window.toLong()).coerceAtLeast(0L)
                    val start = (center - window / 2L).coerceIn(0L, maximumStart)
                    raf.seek(wav.dataOffset + start * wav.blockAlign)
                    var rms = 0.0
                    for (i in 0 until window) {
                        val hann = 0.5 - 0.5 * cos(2.0 * PI * i / (window - 1))
                        val sample = readMonoFrame(raf, wav) * hann
                        samples[i] = sample
                        rms += sample * sample
                    }
                    if (rms / window < 1e-7) continue
                    for (pc in 0 until 12) {
                        var power = 0.0
                        for (frequency in frequencies[pc]) power += goertzelPower(samples, wav.sampleRate.toDouble(), frequency)
                        chroma[pc] += ln(1.0 + power)
                    }
                }
                val total = chroma.sum()
                if (total <= 1e-9) return@use null
                for (i in chroma.indices) chroma[i] /= total

                var bestLabel = ""
                var best = Double.NEGATIVE_INFINITY
                var second = Double.NEGATIVE_INFINITY
                for (root in 0 until 12) {
                    val major = correlation(chroma, MAJOR_PROFILE, root)
                    val minor = correlation(chroma, MINOR_PROFILE, root)
                    for ((score, suffix) in arrayOf(major to "", minor to "m")) {
                        if (score > best) {
                            second = best
                            best = score
                            bestLabel = NOTE_NAMES[root] + suffix
                        } else if (score > second) {
                            second = score
                        }
                    }
                }
                val margin = (best - second.coerceAtLeast(-1.0)).coerceAtLeast(0.0)
                val confidence = ((best.coerceAtLeast(0.0) * 0.72) + (margin * 1.8).coerceAtMost(0.28)).toFloat().coerceIn(0f, 1f)
                if (bestLabel.isBlank() || confidence < 0.34f) null else KeyResult(bestLabel, confidence)
            }
        }.getOrNull()
    }

    private fun goertzelPower(samples: DoubleArray, sampleRate: Double, frequency: Double): Double {
        val omega = 2.0 * PI * frequency / sampleRate
        val coefficient = 2.0 * cos(omega)
        var s0: Double
        var s1 = 0.0
        var s2 = 0.0
        for (sample in samples) {
            s0 = sample + coefficient * s1 - s2
            s2 = s1
            s1 = s0
        }
        return (s1 * s1 + s2 * s2 - coefficient * s1 * s2).coerceAtLeast(0.0)
    }

    private fun correlation(chroma: DoubleArray, profile: DoubleArray, root: Int): Double {
        var sum = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in 0 until 12) {
            val a = chroma[(i + root) % 12]
            val b = profile[i]
            sum += a * b
            normA += a * a
            normB += b * b
        }
        return sum / sqrt((normA * normB).coerceAtLeast(1e-18))
    }

    private fun midiToHz(note: Int): Double = 440.0 * 2.0.pow((note - 69) / 12.0)

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
                wav.formatCode == 3 && wav.bitsPerSample == 32 -> Float.fromBits(readU32(raf).toInt()).takeIf { it.isFinite() }?.coerceIn(-1f, 1f)?.toDouble() ?: 0.0
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

    private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val MAJOR_PROFILE = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR_PROFILE = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
}
