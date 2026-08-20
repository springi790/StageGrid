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
import org.junit.Test

class GuideLanguageIsolationTest {
    @Test
    fun EnglishGuideChoosesEnglishTemplatesBeforeFullRecognition() {
        val root = createTempDirectory("stagegrid-guide-lang-en-").toFile()
        try {
            val enVerse = cue(root, "en-verse", intArrayOf(1, 0, 1, 1, 0, 1, 0))
            val enChorus = cue(root, "en-chorus", intArrayOf(1, 1, 0, 1, 0, 1, 1, 0))
            val enBridge = cue(root, "en-bridge", intArrayOf(1, 0, 0, 1, 1, 1, 0, 1))
            val esVamp = cue(root, "es-vamp", intArrayOf(1, 0, 1, 0, 1, 0))
            val esRap = cue(root, "es-rap", intArrayOf(1, 1, 0, 0, 1, 0))
            val esCoro = cue(root, "es-chorus", intArrayOf(1, 0, 1, 1, 1, 0, 0))
            val guide = File(root, "guide-en.wav")
            writeGuide(guide, listOf(Event(500, enVerse), Event(2_200, enChorus), Event(3_900, enBridge)), 5_500)

            val result = GuideCueAnalyzer.analyze(
                guide,
                listOf(
                    GuideSample("en", "verse", CueKind.SECTION, enVerse),
                    GuideSample("en", "chorus", CueKind.SECTION, enChorus),
                    GuideSample("en", "bridge", CueKind.SECTION, enBridge),
                    GuideSample("es", "vamp", CueKind.SECTION, esVamp),
                    GuideSample("es", "rap", CueKind.SECTION, esRap),
                    GuideSample("es", "chorus", CueKind.SECTION, esCoro),
                ),
            )

            assertEquals("en", result.dominantLanguage)
            assertEquals(listOf("verse", "chorus", "bridge"), result.cues.map { it.key })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun SpanishGuideKeepsExistingRecognitionBehavior() {
        val root = createTempDirectory("stagegrid-guide-lang-es-").toFile()
        try {
            val esIntro = cue(root, "es-intro", intArrayOf(1, 1, 0, 1, 0, 0, 1))
            val esVerse = cue(root, "es-verse", intArrayOf(1, 0, 1, 1, 0, 1, 1))
            val esCoro = cue(root, "es-coro", intArrayOf(1, 1, 1, 0, 1, 0, 1))
            val enVamp = cue(root, "en-vamp", intArrayOf(1, 0, 1, 0, 1, 0))
            val enRap = cue(root, "en-rap", intArrayOf(1, 1, 0, 0, 1, 0))
            val enBridge = cue(root, "en-bridge", intArrayOf(1, 0, 0, 1, 1, 0, 1))
            val guide = File(root, "guide-es.wav")
            writeGuide(guide, listOf(Event(400, esIntro), Event(2_000, esVerse), Event(3_600, esCoro)), 5_200)

            val result = GuideCueAnalyzer.analyze(
                guide,
                listOf(
                    GuideSample("es", "intro", CueKind.SECTION, esIntro),
                    GuideSample("es", "verse", CueKind.SECTION, esVerse),
                    GuideSample("es", "chorus", CueKind.SECTION, esCoro),
                    GuideSample("en", "vamp", CueKind.SECTION, enVamp),
                    GuideSample("en", "rap", CueKind.SECTION, enRap),
                    GuideSample("en", "bridge", CueKind.SECTION, enBridge),
                ),
            )

            assertEquals("es", result.dominantLanguage)
            assertEquals(listOf("intro", "verse", "chorus"), result.cues.map { it.key })
        } finally {
            root.deleteRecursively()
        }
    }

    private data class Event(val startMs: Int, val file: File)

    private fun cue(root: File, name: String, pattern: IntArray): File {
        val file = File(root, "$name.wav")
        val sampleRate = 48_000
        val block = sampleRate / 10
        val samples = ShortArray(sampleRate * 1_100 / 1000)
        pattern.forEachIndexed { index, active ->
            if (active == 0) return@forEachIndexed
            val start = index * block
            val end = minOf(start + block, samples.size)
            val frequency = 220.0 + index * 67.0
            for (frame in start until end) {
                val local = (frame - start).toDouble() / block
                val t = frame.toDouble() / sampleRate
                samples[frame] = (sin(2.0 * PI * frequency * t) * (1.0 - local) * 12_000.0)
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
                if (destination in mix.indices) mix[destination] = (mix[destination] + sample).coerceIn(-32768, 32767)
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
