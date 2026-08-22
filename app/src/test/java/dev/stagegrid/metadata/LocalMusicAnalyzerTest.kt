package dev.stagegrid.metadata

import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMusicAnalyzerTest {
    @Test
    fun `detects a synthetic 120 bpm pulse train`() {
        val file = File.createTempFile("stagegrid-tempo-", ".wav")
        try {
            writePulseWav(file, bpm = 120.0, seconds = 24)
            val result = LocalMusicAnalyzer.analyzeTempo(file)
            assertNotNull(result)
            assertTrue(kotlin.math.abs(result!!.bpm - 120.0) <= 2.0)
        } finally {
            file.delete()
        }
    }

    private fun writePulseWav(file: File, bpm: Double, seconds: Int) {
        val sampleRate = 8_000
        val frames = sampleRate * seconds
        val pcm = ShortArray(frames)
        val beatFrames = (sampleRate * 60.0 / bpm).toInt()
        for (beat in 0 until frames step beatFrames) {
            val burst = minOf(sampleRate / 45, frames - beat)
            for (i in 0 until burst) {
                val envelope = 1.0 - i.toDouble() / burst
                pcm[beat + i] = (sin(2.0 * PI * 950.0 * i / sampleRate) * envelope * 26_000).toInt().toShort()
            }
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
