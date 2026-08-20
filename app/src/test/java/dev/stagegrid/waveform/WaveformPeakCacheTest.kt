package dev.stagegrid.waveform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

class WaveformPeakCacheTest {
    @Test fun peaksUseAbsoluteSongTimeAcrossDifferentStemDurations() {
        val root = Files.createTempDirectory("stagegrid-waveform-test").toFile()
        try {
            val longStem = File(root, "long.wav")
            val shortStem = File(root, "short.wav")
            writeImpulseWav(longStem, durationMs = 2_000, impulseMs = 1_000, amplitude = 0.9f)
            writeImpulseWav(shortStem, durationMs = 1_000, impulseMs = 500, amplitude = -0.8f)

            val data = WaveformPeakCache.generate(listOf(longStem, shortStem), targetBuckets = 64)

            assertEquals(2_000L, data.durationMs)
            val shortImpulseBucket = 16 // 0.5s on a 2.0s shared timeline
            val longImpulseBucket = 32  // 1.0s on a 2.0s shared timeline
            assertTrue(data.min.sliceArray(14..18).minOrNull()!! < -0.7f)
            assertTrue(data.max.sliceArray(30..34).maxOrNull()!! > 0.8f)
            assertTrue(data.min[shortImpulseBucket] < 0f)
            assertTrue(data.max[longImpulseBucket] > 0f)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun cacheIsRegenerableAndClearOnlyRemovesCacheDirectory() {
        val songRoot = Files.createTempDirectory("stagegrid-waveform-song").toFile()
        try {
            val audioDir = File(songRoot, "audio").apply { mkdirs() }
            val stem = File(audioDir, "track.wav")
            writeImpulseWav(stem, durationMs = 500, impulseMs = 250, amplitude = 0.75f)

            val first = WaveformPeakCache.loadOrGenerate(songRoot, targetBuckets = 64)
            val cache = WaveformPeakCache.cacheFile(songRoot)
            assertTrue(cache.isFile)
            assertEquals(64, first.bucketCount)

            val reclaimed = WaveformPeakCache.clear(songRoot)
            assertTrue(reclaimed > 0L)
            assertFalse(cache.exists())
            assertTrue(stem.isFile)

            val second = WaveformPeakCache.loadOrGenerate(songRoot, targetBuckets = 64)
            assertTrue(cache.isFile)
            assertEquals(first.durationMs, second.durationMs)
        } finally {
            songRoot.deleteRecursively()
        }
    }

    private fun writeImpulseWav(file: File, durationMs: Int, impulseMs: Int, amplitude: Float) {
        val sampleRate = 8_000
        val frames = sampleRate * durationMs / 1_000
        val impulseFrame = (sampleRate * impulseMs / 1_000).coerceIn(0, frames - 1)
        val dataBytes = frames * 2
        BufferedOutputStream(FileOutputStream(file)).use { output ->
            output.write("RIFF".toByteArray(Charsets.US_ASCII))
            writeU32(output, 36L + dataBytes)
            output.write("WAVE".toByteArray(Charsets.US_ASCII))
            output.write("fmt ".toByteArray(Charsets.US_ASCII))
            writeU32(output, 16)
            writeU16(output, 1)
            writeU16(output, 1)
            writeU32(output, sampleRate.toLong())
            writeU32(output, sampleRate * 2L)
            writeU16(output, 2)
            writeU16(output, 16)
            output.write("data".toByteArray(Charsets.US_ASCII))
            writeU32(output, dataBytes.toLong())
            repeat(frames) { index ->
                val sample = if (index == impulseFrame) {
                    (amplitude.coerceIn(-1f, 1f) * 32767f).toInt().coerceIn(-32768, 32767)
                } else 0
                output.write(sample and 0xFF)
                output.write((sample ushr 8) and 0xFF)
            }
        }
    }

    private fun writeU16(output: BufferedOutputStream, value: Int) {
        output.write(value and 0xFF)
        output.write((value ushr 8) and 0xFF)
    }

    private fun writeU32(output: BufferedOutputStream, value: Long) {
        output.write((value and 0xFF).toInt())
        output.write(((value ushr 8) and 0xFF).toInt())
        output.write(((value ushr 16) and 0xFF).toInt())
        output.write(((value ushr 24) and 0xFF).toInt())
    }
}
