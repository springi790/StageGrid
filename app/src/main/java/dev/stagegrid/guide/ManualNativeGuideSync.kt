package dev.stagegrid.guide

import dev.stagegrid.data.LibraryRepository
import dev.stagegrid.importer.WavMetadataReader
import dev.stagegrid.model.StereoRoute
import dev.stagegrid.model.TrackEntity
import dev.stagegrid.model.TrackType
import java.io.File

/** Regenerates the Native Guide from the authoritative, user-edited section map. */
class ManualNativeGuideSync(
    private val filesDir: File,
    private val repository: LibraryRepository,
    private val guidePacks: GuidePackManager,
) {
    data class Result(
        val generatedSectionCues: Int,
        val missingSectionNames: List<String>,
        val outputLanguage: String?,
        val createdNativeTrack: Boolean,
        val changed: Boolean,
    )

    suspend fun sync(songId: String, preferredLanguage: String): Result {
        val bundle = repository.getSongBundle(songId)
            ?: return Result(0, emptyList(), null, false, false)
        val song = bundle.song
        if (bundle.sections.isEmpty() || song.durationMs <= 0L || song.bpm == null) {
            return Result(0, emptyList(), null, false, false)
        }

        val samples = guidePacks.listSamples()
        if (samples.isEmpty()) return Result(0, emptyList(), null, false, false)

        val sidecar = File(filesDir, "library/$songId/native-guide-events.json")
        val existingAnalysis = NativeGuideEventStore.readAnalysis(sidecar)
        val availableLanguages = guidePacks.status().languages
        val existingOutput = NativeGuideEventStore.readOutputLanguage(sidecar)?.takeIf { it in availableLanguages }
        val outputLanguage = existingOutput
            ?: guidePacks.resolveOutputLanguage(preferredLanguage, existingAnalysis?.dominantLanguage)
            ?: return Result(0, emptyList(), null, false, false)

        val manual = ManualGuideSectionCueBuilder.build(
            sections = bundle.sections,
            bpm = song.bpm,
            timeSignature = song.timeSignature,
            gridOffsetMs = song.gridOffsetMs,
            durationMs = song.durationMs,
            samples = samples,
            outputLanguage = outputLanguage,
        )
        if (manual.cues.isEmpty()) {
            return Result(0, manual.missingSectionNames, outputLanguage, false, false)
        }

        val preservedNonSection = existingAnalysis?.cues.orEmpty()
            .filter { it.kind != GuidePackManager.CueKind.SECTION }
        val combinedCues = (preservedNonSection + manual.cues)
            .sortedBy { it.cueMs }
        val combinedAnalysis = GuideCueAnalyzer.Result(
            cues = combinedCues,
            dominantLanguage = existingAnalysis?.dominantLanguage ?: outputLanguage,
            candidateCount = existingAnalysis?.candidateCount ?: bundle.sections.size,
            diagnostics = existingAnalysis?.diagnostics.orEmpty(),
        )

        val existingNative = bundle.tracks.firstOrNull { it.name == NativeGuideRenderer.TRACK_NAME }
        if (existingNative == null && bundle.tracks.size >= MAX_TRACKS) {
            return Result(
                generatedSectionCues = manual.cues.size,
                missingSectionNames = manual.missingSectionNames,
                outputLanguage = outputLanguage,
                createdNativeTrack = false,
                changed = false,
            )
        }

        val audioDir = File(filesDir, "library/$songId/audio").apply { mkdirs() }
        val target = existingNative?.let { File(it.filePath) }
            ?: File(audioDir, "${NativeGuideRenderer.TRACK_NAME}.wav")
        val temp = File(audioDir, ".manual-native-guide-${System.nanoTime()}.wav")
        val rendered = try {
            NativeGuideRenderer.render(
                outputFile = temp,
                durationMs = song.durationMs,
                cues = combinedCues,
                samples = samples,
                outputLanguage = outputLanguage,
            )
        } catch (t: Throwable) {
            temp.delete()
            throw t
        } ?: run {
            temp.delete()
            return Result(0, manual.missingSectionNames, outputLanguage, false, false)
        }

        val previousAudio = File(audioDir, ".manual-native-guide-previous-${System.nanoTime()}.wav")
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

            bundle.tracks
                .filter { TrackType.fromStorage(it.type) == TrackType.GUIDE && it.name != NativeGuideRenderer.TRACK_NAME && !it.muted }
                .forEach { repository.updateTrack(it.copy(muted = true)) }

            val sidecarTemp = File(sidecar.parentFile, ".manual-native-guide-events-${System.nanoTime()}.json")
            NativeGuideRenderer.writeEventSidecar(
                file = sidecarTemp,
                analysis = combinedAnalysis,
                outputLanguage = rendered.outputLanguage,
                sectionProposals = manual.proposals,
                sectionSource = "manual",
            )
            if (sidecar.exists()) sidecar.delete()
            if (!sidecarTemp.renameTo(sidecar)) {
                sidecarTemp.copyTo(sidecar, overwrite = true)
                sidecarTemp.delete()
            }

            previousAudio.delete()
            return Result(
                generatedSectionCues = manual.cues.size,
                missingSectionNames = manual.missingSectionNames,
                outputLanguage = rendered.outputLanguage,
                createdNativeTrack = existingNative == null,
                changed = true,
            )
        } catch (t: Throwable) {
            target.delete()
            if (backedUp) previousAudio.renameTo(target)
            throw t
        } finally {
            temp.delete()
            if (target.exists()) previousAudio.delete()
        }
    }

    private companion object {
        const val MAX_TRACKS = 64
    }
}
