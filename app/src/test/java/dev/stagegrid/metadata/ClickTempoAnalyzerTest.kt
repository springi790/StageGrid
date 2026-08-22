package dev.stagegrid.metadata

import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickTempoAnalyzerTest {
    @Test
    fun `detects 110 bpm with accents and occasional missing pulses`() {
        val file = File.createTempFile("stagegrid-click-110-", ".wav")
        try {
            writeClickWav(file, bpm = 110.0, seconds = 32)
            val result = ClickTempoAnalyzer.analyze(file)
            assertNotNull(result)
            assertTrue("Expected ~110 BPM but got ${result?.bpm}", kotlin.math.abs(result!!.bpm - 110.0) <= 1.5)
            assertTrue(result.confidence >= 0.48f)
        } finally {
            file.delete()
        }
    }

    private fun writeClickWav(file: File, bpm: Double, seconds: Int) {
        val sampleRate = 8_000
        val frames = sampleRate * seconds
        val pcm = ShortArray(frames)
        val beatFrames = (sampleRate * 60.0 / bpm).toInt()
        var beatNumber = 0
        for (beat in 0 until frames step beatFrames) {
            // Simulate a real guide/click export: bar accents are louder and an occasional weak beat
            // is effectively absent. A click-specific estimator should still recover quarter notes.
            val omit = beatNumber > 0 && beatNumber % 11 == 0
            if (!omit) {
                val accented = beatNumber % 4 == 0
                val amplitude = if (accented) 28_000 else 12_000
                val frequency = if (accented) 1_250.0 else 920.0
                val burst = minOf(sampleRate / 55, frames - beat)
                for (i in 0 until burst) {
                    val envelope = 1.0 - i.toDouble() / burst
                    pcm[beat + i] = (sin(2.0 * PI * frequency * i / sampleRate) * envelope * amplitude).toInt().toShort()
                }
            }
            beatNumber++
        }

        FileOutputStream(file).use { out ->
            fun le16(value: Int) {
                out.write(value and 0xff)
                out.write((value ushr 8) and 0xff)
            }
            fun le32(value: Int) {
                out.write(value and 0xff)
                out.write((value ushr 8) and 0xff)
                out.write((value ushr 16) and 0xff)
                out.write((value ushr 24) and 0xff)
            }
            val dataBytes = pcm.size * 2
            out.write("RIFF".toByteArray())
            le32(36 + dataBytes)
            out.write("WAVEfmt ".toByteArray())
            le32(16)
            le16(1)
            le16(1)
            le32(sampleRate)
            le32(sampleRate * 2)
            le16(2)
            le16(16)
            out.write("data".toByteArray())
            le32(dataBytes)
            pcm.forEach { sample -> le16(sample.toInt() and 0xffff) }
        }
    }
}
