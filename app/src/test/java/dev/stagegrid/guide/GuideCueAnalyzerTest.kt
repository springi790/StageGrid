package dev.stagegrid.guide

import dev.stagegrid.guide.GuidePackManager.CueKind
import dev.stagegrid.guide.GuidePackManager.GuideSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.sin

class GuideCueAnalyzerTest {
    @Test
    fun recognizesTemplateCuesAndInfersSectionsOneBarAhead() {
        val root = createTempDir(prefix = "stagegrid-guide-test-")
        try {
            val intro = File(root, "intro.wav")
            val chorus = File(root, "chorus.wav")
            val guide = File(root, "guide.wav")
            writeCue(intro, pattern = intArrayOf(1, 0, 1, 1, 0))
            writeCue(chorus, pattern = intArrayOf(1, 1, 0, 1, 0, 1))
            writeGuide(guide, listOf(0L to intro, 2_000L to chorus), durationMs = 5_500L)

            val result = GuideCueAnalyzer.analyze(
                guide,
                listOf(
                    GuideSample("en", "intro", CueKind.SECTION, intro),
                    GuideSample("en", "chorus", CueKind.SECTION, chorus),
                ),
            )

            assertEquals(2, result.cues.size)
            assertEquals(listOf("intro", "chorus"), result.cues.map { it.key })
            assertTrue(result.cues.all { it.confidence >= 0.82f })

            val sections = GuideCueAnalyzer.inferSections(
                result = result,
                bpm = 120.0,
                timeSignature = "4/4",
                gridOffsetMs = 0L,
                durationMs = 6_000L,
                localeLanguage = "en",
            )
            assertEquals(listOf(2_000L, 4_000L), sections.map { it.startMs })
            assertEquals(listOf("Intro", "Chorus"), sections.map { it.name })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeCue(file: File, pattern: IntArray) {
        val sampleRate = 48_000
        val durationMs = 1_200
        val samples = ShortArray(sampleRate * durationMs / 1000)
        val block = sampleRate / 10 // 100 ms blocks
        for (i in pattern.indices) {
            if (pattern[i] == 0) continue
            val start = i * block
            val end = minOf(samples.size, start + block)
            for (frame in start until end) {
                val t = frame.toDouble() / sampleRate
                val envelope = 1.0 - ((frame - start).toDouble() / block)
                samples[frame] = (sin(2.0 * PI * (260.0 + i * 70.0) * t) * envelope * 12_000.0).toInt().toShort()
            }
        }
        writeMono16(file, sampleRate, samples)
    }

    private fun writeGuide(file: File, events: List<Pair<Long, File>>, durationMs: Long) {
        val sampleRate = 48_000
        val frames = (durationMs * sampleRate / 1000L).toInt()
        val mix = IntArray(frames)
        events.forEach { (startMs, cueFile) ->
            val cue = readMono16(cueFile)
            val start = (startMs * sampleRate / 1000L).toInt()
            for (i in cue.indices) {
                val destination = start + i
                if (destination in mix.indices) mix[destination] = (mix[destination] + cue[i]).coerceIn(-32768, 32767)
            }
        }
        writeMono16(file, sampleRate, ShortArray(frames) { mix[it].toShort() })
    }

    private fun readMono16(file: File): ShortArray {
        val bytes = file.readBytes()
        val frames = (bytes.size - 44) / 2
        return ShortArray(frames) { i ->
            val offset = 44 + i * 2
            val value = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
            value.toShort()
        }
    }

    private fun writeMono16(file: File, sampleRate: Int, samples: ShortArray) {
        FileOutputStream(file).use { out ->
            val dataBytes = samples.size * 2L
            out.write("RIFF".toByteArray())
            writeU32(out, dataBytes + 36)
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            writeU32(out, 16)
            writeU16(out, 1)
            writeU16(out, 1)
            writeU32(out, sampleRate.toLong())
            writeU32(out, sampleRate.toLong() * 2)
            writeU16(out, 2)
            writeU16(out, 16)
            out.write("data".toByteArray())
            writeU32(out, dataBytes)
            samples.forEach { value ->
                val v = value.toInt()
                out.write(v and 0xFF)
                out.write((v ushr 8) and 0xFF)
            }
        }
    }

    private fun writeU16(out: FileOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }

    private fun writeU32(out: FileOutputStream, value: Long) {
        out.write((value and 0xFF).toInt())
        out.write(((value ushr 8) and 0xFF).toInt())
        out.write(((value ushr 16) and 0xFF).toInt())
        out.write(((value ushr 24) and 0xFF).toInt())
    }
}
