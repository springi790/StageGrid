package dev.stagegrid.guide

import dev.stagegrid.guide.GuidePackManager.CueKind
import dev.stagegrid.guide.GuidePackManager.GuideSample
import java.io.File
import java.io.FileOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideCueAnalyzerRobustnessTest {
    @Test
    fun recognizesQuietCuesInAntiphaseStereoAfterLoudCue() {
        val root = createTempDirectory("stagegrid-guide-robust-").toFile()
        try {
            val intro = File(root, "intro.wav")
            val verse = File(root, "verse.wav")
            val chorus = File(root, "chorus.wav")
            writeCue(intro, intArrayOf(1, 0, 1, 1, 0))
            writeCue(verse, intArrayOf(1, 1, 0, 0, 1, 0, 1))
            writeCue(chorus, intArrayOf(1, 0, 1, 0, 1, 1, 0, 1))

            val guide = File(root, "guide-antiphase.wav")
            writeAntiphaseStereoGuide(
                guide,
                events = listOf(
                    Event(0L, intro, 1.0f),
                    Event(2_000L, verse, 0.035f),
                    Event(4_000L, chorus, 0.055f),
                ),
                durationMs = 6_000L,
            )

            val result = GuideCueAnalyzer.analyze(
                guide,
                listOf(
                    GuideSample("en", "intro", CueKind.SECTION, intro),
                    GuideSample("en", "verse", CueKind.SECTION, verse),
                    GuideSample("en", "chorus", CueKind.SECTION, chorus),
                ),
            )

            assertEquals(listOf("intro", "verse", "chorus"), result.cues.map { it.key })
            assertEquals("en", result.dominantLanguage)
            assertTrue(result.candidateCount >= 3)
        } finally {
            root.deleteRecursively()
        }
    }

    private data class Event(val startMs: Long, val file: File, val gain: Float)

    private fun writeCue(file: File, pattern: IntArray) {
        val sampleRate = 48_000
        val samples = ShortArray(sampleRate * 1_200 / 1000)
        val block = sampleRate / 10
        for (i in pattern.indices) {
            if (pattern[i] == 0) continue
            val start = i * block
            val end = minOf(samples.size, start + block)
            for (frame in start until end) {
                val t = frame.toDouble() / sampleRate
                val envelope = 1.0 - ((frame - start).toDouble() / block)
                samples[frame] = (sin(2.0 * PI * (230.0 + i * 83.0) * t) * envelope * 12_000.0)
                    .roundToInt().coerceIn(-32768, 32767).toShort()
            }
        }
        writeMono16(file, sampleRate, samples)
    }

    private fun writeAntiphaseStereoGuide(file: File, events: List<Event>, durationMs: Long) {
        val sampleRate = 48_000
        val frames = (durationMs * sampleRate / 1000L).toInt()
        val mix = IntArray(frames)
        events.forEach { event ->
            val cue = readMono16(event.file)
            val start = (event.startMs * sampleRate / 1000L).toInt()
            for (i in cue.indices) {
                val destination = start + i
                if (destination in mix.indices) {
                    mix[destination] = (mix[destination] + (cue[i] * event.gain).roundToInt()).coerceIn(-32768, 32767)
                }
            }
        }

        FileOutputStream(file).use { out ->
            val dataBytes = frames * 4L
            writeHeader(out, sampleRate, channels = 2, dataBytes = dataBytes)
            mix.forEach { sample ->
                writeS16(out, sample)
                writeS16(out, -sample)
            }
        }
    }

    private fun readMono16(file: File): ShortArray {
        val bytes = file.readBytes()
        return ShortArray((bytes.size - 44) / 2) { index ->
            val offset = 44 + index * 2
            ((bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)).toShort()
        }
    }

    private fun writeMono16(file: File, sampleRate: Int, samples: ShortArray) {
        FileOutputStream(file).use { out ->
            writeHeader(out, sampleRate, channels = 1, dataBytes = samples.size * 2L)
            samples.forEach { writeS16(out, it.toInt()) }
        }
    }

    private fun writeHeader(out: FileOutputStream, sampleRate: Int, channels: Int, dataBytes: Long) {
        out.write("RIFF".toByteArray())
        writeU32(out, dataBytes + 36)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        writeU32(out, 16)
        writeU16(out, 1)
        writeU16(out, channels)
        writeU32(out, sampleRate.toLong())
        writeU32(out, sampleRate.toLong() * channels * 2L)
        writeU16(out, channels * 2)
        writeU16(out, 16)
        out.write("data".toByteArray())
        writeU32(out, dataBytes)
    }

    private fun writeS16(out: FileOutputStream, value: Int) {
        val v = value.coerceIn(-32768, 32767)
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
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
