package dev.stagegrid.guide

import dev.stagegrid.guide.GuideCueAnalyzer.DetectedCue
import dev.stagegrid.guide.GuidePackManager.GuideSample
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Renders recognized Guide events into a clean app-generated PCM WAV.
 *
 * The resulting track is still played by the shared native multitrack clock, so it stays sample
 * aligned with the stems. The event JSON is kept beside the song so a later arrangement engine can
 * relocate the same native cues when sections are reordered instead of re-analyzing speech.
 */
object NativeGuideRenderer {
    const val TRACK_NAME = "StageGrid Native Guide"

    data class Result(
        val file: File,
        val outputLanguage: String,
        val renderedEvents: Int,
        val missingEvents: Int,
    )

    private data class RenderEvent(val startFrame: Long, val samples: FloatArray)

    fun render(
        outputFile: File,
        durationMs: Long,
        cues: List<DetectedCue>,
        samples: List<GuideSample>,
        outputLanguage: String,
        sampleRate: Int = 48_000,
        onProgress: ((Float) -> Unit)? = null,
    ): Result? {
        if (cues.isEmpty() || samples.isEmpty() || durationMs <= 0) return null
        val exactIndex = samples.associateBy { "${it.language}:${it.kind.name}:${it.key}" }
        val fallbackIndex = samples.associateBy { "${it.language}:${it.key}" }
        val rendered = mutableListOf<RenderEvent>()
        var missing = 0

        onProgress?.invoke(0f)
        for (cue in cues) {
            val outputExact = "$outputLanguage:${cue.kind.name}:${cue.key}"
            val detectedExact = "${cue.language}:${cue.kind.name}:${cue.key}"
            val selected = exactIndex[outputExact]
                ?: exactIndex[detectedExact]
                ?: fallbackIndex["$outputLanguage:${cue.key}"]
                ?: fallbackIndex["${cue.language}:${cue.key}"]
            if (selected == null) {
                missing++
                continue
            }
            val mono = runCatching { GuideAudio.readMono(selected.file) }.getOrNull()
            if (mono == null) {
                missing++
                continue
            }
            val audio = GuideAudio.resampleLinear(mono, sampleRate)
            val startFrame = cue.cueMs.coerceAtLeast(0) * sampleRate.toLong() / 1000L
            rendered += RenderEvent(startFrame, audio)
        }
        if (rendered.isEmpty()) return null
        rendered.sortBy { it.startFrame }

        val totalFrames = (durationMs.coerceAtLeast(1L) * sampleRate.toLong() / 1000L).coerceAtLeast(1L)
        outputFile.parentFile?.mkdirs()
        BufferedOutputStream(FileOutputStream(outputFile), 512 * 1024).use { out ->
            writeWavHeader(out, sampleRate, totalFrames)
            val blockFrames = 16_384
            val silentBytes = ByteArray(blockFrames * 2)
            var blockStart = 0L
            var lastProgressBucket = -1
            while (blockStart < totalFrames) {
                val count = minOf(blockFrames.toLong(), totalFrames - blockStart).toInt()
                val blockEnd = blockStart + count
                val overlapping = rendered.filter { event ->
                    val eventEnd = event.startFrame + event.samples.size
                    event.startFrame < blockEnd && eventEnd > blockStart
                }

                if (overlapping.isEmpty()) {
                    out.write(silentBytes, 0, count * 2)
                } else {
                    val mix = FloatArray(count)
                    for (event in overlapping) {
                        val sourceStart = (blockStart - event.startFrame).coerceAtLeast(0L).toInt()
                        val destinationStart = (event.startFrame - blockStart).coerceAtLeast(0L).toInt()
                        val copy = minOf(event.samples.size - sourceStart, count - destinationStart)
                        for (i in 0 until copy) mix[destinationStart + i] += event.samples[sourceStart + i]
                    }
                    val bytes = ByteArray(count * 2)
                    for (i in 0 until count) {
                        val value = (mix[i].coerceIn(-1f, 1f) * 32767f).roundToInt()
                        bytes[i * 2] = (value and 0xFF).toByte()
                        bytes[i * 2 + 1] = ((value ushr 8) and 0xFF).toByte()
                    }
                    out.write(bytes)
                }

                blockStart += count
                val bucket = ((blockStart * 20L) / totalFrames).toInt().coerceIn(0, 20)
                if (bucket != lastProgressBucket) {
                    lastProgressBucket = bucket
                    onProgress?.invoke((blockStart.toDouble() / totalFrames.toDouble()).toFloat().coerceIn(0f, 1f))
                }
            }
        }
        onProgress?.invoke(1f)
        return Result(outputFile, outputLanguage, rendered.size, missing)
    }

    fun writeEventSidecar(
        file: File,
        analysis: GuideCueAnalyzer.Result,
        outputLanguage: String?,
        sectionProposals: List<GuideCueAnalyzer.SectionProposal>,
        sectionSource: String = "recognized",
    ) {
        val root = JSONObject()
            .put("version", 2)
            .put("detectedLanguage", analysis.dominantLanguage ?: JSONObject.NULL)
            .put("outputLanguage", outputLanguage ?: JSONObject.NULL)
            .put("candidateCount", analysis.candidateCount)
            .put("sectionSource", sectionSource)
        val cues = JSONArray()
        analysis.cues.forEach { cue ->
            cues.put(
                JSONObject()
                    .put("key", cue.key)
                    .put("kind", cue.kind.name)
                    .put("language", cue.language)
                    .put("cueMs", cue.cueMs)
                    .put("confidence", cue.confidence.toDouble()),
            )
        }
        val sections = JSONArray()
        sectionProposals.forEach { section ->
            sections.put(
                JSONObject()
                    .put("key", section.key)
                    .put("name", section.name)
                    .put("startMs", section.startMs)
                    .put("confidence", section.confidence.toDouble()),
            )
        }
        val diagnostics = JSONArray()
        analysis.diagnostics.forEach { diagnostic ->
            diagnostics.put(
                JSONObject()
                    .put("cueMs", diagnostic.cueMs)
                    .put("bestKey", diagnostic.bestKey ?: JSONObject.NULL)
                    .put("bestKind", diagnostic.bestKind?.name ?: JSONObject.NULL)
                    .put("bestLanguage", diagnostic.bestLanguage ?: JSONObject.NULL)
                    .put("bestScore", diagnostic.bestScore.toDouble())
                    .put("secondKey", diagnostic.secondKey ?: JSONObject.NULL)
                    .put("secondScore", diagnostic.secondScore.toDouble())
                    .put("accepted", diagnostic.accepted)
                    .put("reason", diagnostic.reason),
            )
        }
        root.put("cues", cues)
            .put("sections", sections)
            .put("diagnostics", diagnostics)
        file.parentFile?.mkdirs()
        file.writeText(root.toString(2))
    }

    private fun writeWavHeader(out: java.io.OutputStream, sampleRate: Int, frames: Long) {
        val dataBytes = frames * 2L
        require(dataBytes <= 0xFFFF_FFFFL - 36L) { "Native Guide WAV exceeds RIFF size limit" }
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeU32(out, dataBytes + 36L)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        writeU32(out, 16)
        writeU16(out, 1)
        writeU16(out, 1)
        writeU32(out, sampleRate.toLong())
        writeU32(out, sampleRate.toLong() * 2L)
        writeU16(out, 2)
        writeU16(out, 16)
        out.write("data".toByteArray(Charsets.US_ASCII))
        writeU32(out, dataBytes)
    }

    private fun writeU16(out: java.io.OutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }

    private fun writeU32(out: java.io.OutputStream, value: Long) {
        out.write((value and 0xFF).toInt())
        out.write(((value ushr 8) and 0xFF).toInt())
        out.write(((value ushr 16) and 0xFF).toInt())
        out.write(((value ushr 24) and 0xFF).toInt())
    }
}
