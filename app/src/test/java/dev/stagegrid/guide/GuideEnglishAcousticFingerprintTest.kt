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

class GuideEnglishAcousticFingerprintTest {
    @Test
    fun EnglishCuesWithSameEnergyShapeAreSeparatedByAcousticFingerprint() {
        val root = createTempDirectory("stagegrid-guide-acoustic-").toFile()
        try {
            // All three calls have the same on/off energy envelope. Only their frequency movement
            // differs, which reproduces the weakness of the old RMS-only English classifier.
            val verse = cue(root, "verse", doubleArrayOf(310.0, 520.0, 760.0, 430.0, 920.0))
            val chorus = cue(root, "chorus", doubleArrayOf(690.0, 360.0, 1_080.0, 570.0, 820.0))
            val vamp = cue(root, "vamp", doubleArrayOf(420.0, 980.0, 470.0, 1_240.0, 620.0))
            val guide = File(root, "guide.wav")
            writeGuide(
                guide,
                listOf(
                    Event(500, verse),
                    Event(2_200, chorus),
                    Event(3_900, vamp),
                ),
                durationMs = 5_600,
            )

            val result = GuideCueAnalyzer.analyze(
                guide,
                listOf(
                    GuideSample("en", "verse", CueKind.SECTION, verse),
                    GuideSample("en", "chorus", CueKind.SECTION, chorus),
                    GuideSample("en", "vamp", CueKind.SECTION, vamp),
                ),
            )

            assertEquals("en", result.dominantLanguage)
            assertEquals(listOf("verse", "chorus", "vamp"), result.cues.map { it.key })
            assertTrue(result.diagnostics.any { it.accepted && it.bestKey == "verse" })
            assertTrue(result.diagnostics.any { it.accepted && it.bestKey == "chorus" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun GenericEnglishVersesAreNumberedByOccurrence() {
        val result = GuideCueAnalyzer.Result(
            cues = listOf(
                GuideCueAnalyzer.DetectedCue("verse", CueKind.SECTION, "en", 0L, 0.95f),
                GuideCueAnalyzer.DetectedCue("chorus", CueKind.SECTION, "en", 4_000L, 0.95f),
                GuideCueAnalyzer.DetectedCue("verse", CueKind.SECTION, "en", 8_000L, 0.95f),
            ),
            dominantLanguage = "en",
            candidateCount = 3,
        )

        val sections = GuideCueAnalyzer.inferSections(
            result = result,
            bpm = 120.0,
            timeSignature = "4/4",
            gridOffsetMs = 0L,
            durationMs = 14_000L,
            localeLanguage = "en",
        )

        assertEquals(listOf("Verse 1", "Chorus", "Verse 2"), sections.map { it.name })
    }

    private data class Event(val startMs: Int, val file: File)

    private fun cue(root: File, name: String, frequencies: DoubleArray): File {
        val file = File(root, "$name.wav")
        val sampleRate = 48_000
        val blockFrames = sampleRate / 10
        val samples = ShortArray(sampleRate * 1_100 / 1000)
        frequencies.forEachIndexed { block, frequency ->
            val start = block * blockFrames
            val end = minOf(start + blockFrames, samples.size)
            for (frame in start until end) {
                val local = (frame - start).toDouble() / blockFrames
                val t = frame.toDouble() / sampleRate
                val envelope = 1.0 - local * 0.28
                samples[frame] = (sin(2.0 * PI * frequency * t) * envelope * 11_000.0)
                    .roundToInt().coerceIn(-32768, 32767).toShort()
            }
        }
        writeMono16(file, sampleRate, samples)
        return file
    }

    private fun writeGuide(file: File, events: List<Event>, durationMs: Int) {
        val sampleRate = 48_000
        val mix = IntArray(sampleRate * durationMs / 1000)
        events.forEach { event ->
            val cue = readMono16(event.file)
            val start = event.startMs * sampleRate / 1000
            cue.forEachIndexed { index, sample ->
                val destination = start + index
                if (destination in mix.indices) {
                    mix[destination] = (mix[destination] + sample).coerceIn(-32768, 32767)
                }
            }
        }
        writeMono16(file, sampleRate, ShortArray(mix.size) { mix[it].toShort() })
    }

    private fun readMono16(file: File): IntArray {
        val bytes = file.readBytes()
        return IntArray((bytes.size - 44) / 2) { index ->
            val offset = 44 + index * 2
            val raw = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
            if (raw and 0x8000 != 0) raw - 0x10000 else raw
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
            writeU32(out, sampleRate.toLong() * 2L)
            writeU16(out, 2)
            writeU16(out, 16)
            out.write("data".toByteArray())
            writeU32(out, dataBytes)
            samples.forEach { sample ->
                val value = sample.toInt()
                out.write(value and 0xFF)
                out.write((value ushr 8) and 0xFF)
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
