package dev.stagegrid.importer

import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickGridAnalyzerTest {
    @Test
    fun detectsSingleClickWhenNoPulseTrainExists() {
        val file = File.createTempFile("stagegrid-click-grid", ".wav")
        try {
            writeMono16Wave(file, sampleRate = 48_000, durationMs = 1_000) { samples, sampleRate ->
                addClick(samples, sampleRate, 123, 0.75)
            }
            val result = ClickGridAnalyzer.analyze(file)
            assertNotNull(result)
            assertTrue(abs(result!!.offsetMs - 123L) <= 4L)
            assertTrue(result.confidence > 0f)
        } finally {
            file.delete()
        }
    }

    @Test
    fun ignoresIsolatedEarlySpikeAndChoosesStableClickTrain() {
        val file = File.createTempFile("stagegrid-click-train", ".wav")
        try {
            writeMono16Wave(file, sampleRate = 48_000, durationMs = 3_200) { samples, sampleRate ->
                addClick(samples, sampleRate, 100, 0.95)
                listOf(1_000, 1_500, 2_000, 2_500, 3_000).forEach {
                    addClick(samples, sampleRate, it, 0.55)
                }
            }
            val result = ClickGridAnalyzer.analyze(file) ?: error("Click grid was not detected")
            assertTrue("offset=${result.offsetMs}", result.offsetMs in 960L..1_040L)
            assertTrue(result.confidence > 0f)
        } finally {
            file.delete()
        }
    }

    private fun writeMono16Wave(
        file: File,
        sampleRate: Int,
        durationMs: Int,
        fill: (ShortArray, Int) -> Unit,
    ) {
        val samples = ShortArray(sampleRate * durationMs / 1000)
        fill(samples, sampleRate)
        val dataBytes = samples.size * 2
        FileOutputStream(file).use { out ->
            out.write("RIFF".toByteArray())
            write32(out, 36 + dataBytes)
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            write32(out, 16)
            write16(out, 1)
            write16(out, 1)
            write32(out, sampleRate)
            write32(out, sampleRate * 2)
            write16(out, 2)
            write16(out, 16)
            out.write("data".toByteArray())
            write32(out, dataBytes)
            samples.forEach { write16(out, it.toInt()) }
        }
    }

    private fun addClick(samples: ShortArray, sampleRate: Int, atMs: Int, gain: Double) {
        val start = atMs * sampleRate / 1000
        val length = sampleRate / 100
        for (i in 0 until length) {
            val frame = start + i
            if (frame !in samples.indices) break
            val envelope = 1.0 - i.toDouble() / length
            samples[frame] = (sin(2.0 * PI * 1_900.0 * i / sampleRate) * envelope * gain * 32767.0)
                .toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun write16(out: FileOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }

    private fun write32(out: FileOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }
}
