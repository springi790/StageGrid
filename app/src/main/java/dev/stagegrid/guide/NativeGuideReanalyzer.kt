package dev.stagegrid.guide

import dev.stagegrid.data.LibraryRepository
import dev.stagegrid.importer.WavMetadataReader
import dev.stagegrid.model.SectionEntity
import dev.stagegrid.model.StereoRoute
import dev.stagegrid.model.TrackEntity
import dev.stagegrid.model.TrackType
import java.io.File
import kotlin.math.roundToInt

class NativeGuideReanalyzer(
    private val filesDir: File,
    private val repository: LibraryRepository,
    private val guidePacks: GuidePackManager,
) {
    data class Result(
        val cueCount: Int,
        val outputLanguage: String,
        val sectionsUpdated: Boolean,
        val createdNativeTrack: Boolean,
    )

    suspend fun reanalyze(
        songId: String,
        preferredLanguage: String,
        onProgress: ((Int) -> Unit)? = null,
    ): Result {
        onProgress?.invoke(1)
        val bundle = repository.getSongBundle(songId) ?: error("Song data is no longer available.")
        val song = bundle.song
        val referenceTrack = bundle.tracks.firstOrNull {
            TrackType.fromStorage(it.type) == TrackType.GUIDE && it.name != NativeGuideRenderer.TRACK_NAME
        } ?: error("The original imported Guide track is not available for reanalysis.")
        val referenceFile = File(referenceTrack.filePath)
        require(referenceFile.isFile) { "The original Guide audio file is missing." }

        val samples = guidePacks.listSamples()
        require(samples.isNotEmpty()) { "Install a Guide sample pack before reanalyzing this song." }
        val existingNative = bundle.tracks.firstOrNull { it.name == NativeGuideRenderer.TRACK_NAME }
        if (existingNative == null && bundle.tracks.size >= MAX_TRACKS) {
            error("This song already uses the maximum track count, so a Native Guide track cannot be added.")
        }

        val sidecar = File(filesDir, "library/$songId/native-guide-events.json")
        val previousProposals = NativeGuideEventStore.readSectionProposals(sidecar)
        val autoSectionsUntouched = matchesStoredAutoSections(bundle.sections, previousProposals, song.durationMs)

        val analysis = GuideCueAnalyzer.analyze(referenceFile, samples) { fraction ->
            onProgress?.invoke((5f + fraction * 60f).roundToInt().coerceIn(5, 65))
        }
        require(analysis.cues.isNotEmpty()) { "No Guide calls matched the installed sample pack confidently." }

        val proposals = GuideCueAnalyzer.inferSections(
            result = analysis,
            bpm = song.bpm,
            timeSignature = song.timeSignature,
            gridOffsetMs = song.gridOffsetMs,
            durationMs = song.durationMs,
        )
        val existingLanguage = NativeGuideEventStore.readOutputLanguage(sidecar)
        val availableLanguages = guidePacks.status().languages
        val outputLanguage = existingLanguage?.takeIf { it in availableLanguages }
            ?: guidePacks.resolveOutputLanguage(preferredLanguage, analysis.dominantLanguage)
            ?: error("No compatible Guide output language is installed.")

        val audioDir = File(filesDir, "library/$songId/audio").apply { mkdirs() }
        val target = existingNative?.let { File(it.filePath) } ?: File(audioDir, "${NativeGuideRenderer.TRACK_NAME}.wav")
        val temp = File(audioDir, ".native-guide-reanalysis-${System.nanoTime()}.wav")
        val rendered = try {
            NativeGuideRenderer.render(
                outputFile = temp,
                durationMs = song.durationMs,
                cues = analysis.cues,
                samples = samples,
                outputLanguage = outputLanguage,
                onProgress = { fraction ->
                    onProgress?.invoke((66f + fraction * 28f).roundToInt().coerceIn(66, 94))
                },
            ) ?: error("Recognized calls could not be rendered with the installed sample pack.")
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }

        val previousAudio = File(audioDir, ".native-guide-previous-${System.nanoTime()}.wav")
        var backedUp = false
        try {
            if (target.exists()) {
                if (!target.renameTo(previousAudio)) error("The current Native Guide could not be staged safely.")
                backedUp = true
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            val metadata = WavMetadataReader.read(target)
            val nativeTrack = if (existingNative != null) {
                existingNative.copy(
                    filePath = target.absolutePath,
                    channels = metadata.channels,
                    sampleRate = metadata.sampleRate,
                    bitDepth = metadata.bitDepth,
                    durationMs = metadata.durationMs,
                )
            } else {
                TrackEntity(
                    songId = song.id,
                    name = NativeGuideRenderer.TRACK_NAME,
                    filePath = target.absolutePath,
                    type = TrackType.GUIDE.name,
                    channels = metadata.channels,
                    sampleRate = metadata.sampleRate,
                    bitDepth = metadata.bitDepth,
                    durationMs = metadata.durationMs,
                    sortOrder = bundle.tracks.size,
                    outputRoute = StereoRoute.BOTH.name,
                )
            }
            if (existingNative != null) repository.updateTrack(nativeTrack) else repository.saveTrack(nativeTrack)
            if (!referenceTrack.muted) repository.updateTrack(referenceTrack.copy(muted = true))

            val replacements = proposals.mapIndexed { index, proposal ->
                val end = proposals.getOrNull(index + 1)?.startMs ?: song.durationMs
                SectionEntity(
                    songId = song.id,
                    name = proposal.name,
                    startMs = proposal.startMs,
                    endMs = end.coerceAtLeast(proposal.startMs + 1L),
                    sortOrder = index,
                    colorArgb = AUTO_SECTION_COLORS[index % AUTO_SECTION_COLORS.size],
                )
            }
            val sectionsUpdated = when {
                replacements.isEmpty() -> false
                isPlaceholder(bundle.sections, song.durationMs) ->
                    repository.replacePlaceholderSections(song.id, song.durationMs, replacements)
                autoSectionsUntouched ->
                    repository.replaceSectionsIfUnchanged(song.id, bundle.sections, replacements)
                else -> false
            }

            val sidecarTemp = File(sidecar.parentFile, ".native-guide-events-${System.nanoTime()}.json")
            NativeGuideRenderer.writeEventSidecar(sidecarTemp, analysis, rendered.outputLanguage, proposals)
            if (sidecar.exists()) sidecar.delete()
            if (!sidecarTemp.renameTo(sidecar)) {
                sidecarTemp.copyTo(sidecar, overwrite = true)
                sidecarTemp.delete()
            }
            previousAudio.delete()
            onProgress?.invoke(100)
            return Result(analysis.cues.size, rendered.outputLanguage, sectionsUpdated, existingNative == null)
        } catch (t: Throwable) {
            target.delete()
            if (backedUp) previousAudio.renameTo(target)
            throw t
        } finally {
            temp.delete()
            if (target.exists()) previousAudio.delete()
        }
    }

    internal fun matchesStoredAutoSections(
        current: List<SectionEntity>,
        proposals: List<NativeGuideEventStore.StoredSectionProposal>,
        durationMs: Long,
    ): Boolean {
        if (proposals.isEmpty() || current.size != proposals.size) return false
        return current.indices.all { index ->
            val section = current[index]
            val proposal = proposals[index]
            val expectedEnd = proposals.getOrNull(index + 1)?.startMs ?: durationMs
            section.name == proposal.name &&
                section.startMs == proposal.startMs &&
                section.endMs == expectedEnd.coerceAtLeast(proposal.startMs + 1L) &&
                section.sortOrder == index
        }
    }

    private fun isPlaceholder(sections: List<SectionEntity>, durationMs: Long): Boolean =
        sections.size == 1 && sections[0].name == "Full Song" && sections[0].startMs == 0L && sections[0].endMs == durationMs

    private companion object {
        const val MAX_TRACKS = 32
        val AUTO_SECTION_COLORS = longArrayOf(
            0xFF5B8CFF, 0xFF2FBF9F, 0xFF9C6CFF, 0xFFF39C55, 0xFFE85D75, 0xFF4FB6E9,
        )
    }
}
