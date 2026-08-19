package dev.stagegrid.importer

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

class ClickGridAnalyzerTest {
    @Test
    fun detectsFirstClickTransientWithoutTrimmingMusicalTimeline() {
        val file = File.createTempFile("stagegrid-click-grid", ".wav")
        try {
            writeMono16Wave(file, sampleRate = 48_000, clickAtMs = 123)
            val result = ClickGridAnalyzer.analyze(file)
            assertNotNull(result)
            assertTrue(abs(result!!.offsetMs - 123L) <= 3L)
            assertTrue(result.confidence > 0.5f)
        } finally {
            file.delete()
        }
    }

    private fun writeMono16Wave(file: File, sampleRate: Int, clickAtMs: Int) {
        val frames = sampleRate
        val dataBytes = frames * 2
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

            val clickFrame = sampleRate * clickAtMs / 1000
            repeat(frames) { frame ->
                val sample = if (frame in clickFrame until clickFrame + 200) 24_000 else 0
                write16(out, sample)
            }
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
