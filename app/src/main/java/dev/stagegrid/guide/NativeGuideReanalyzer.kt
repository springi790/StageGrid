package dev.stagegrid.guide

import dev.stagegrid.data.LibraryRepository
import dev.stagegrid.debug.StageGridDebugLog
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
        val startedNs = System.nanoTime()
        val progressLock = Any()
        var lastLoggedProgressBucket = -1
        var highestProgress = 0
        fun progress(percent: Int, stage: String) {
            synchronized(progressLock) {
                val safe = maxOf(highestProgress, percent.coerceIn(0, 100))
                highestProgress = safe
                onProgress?.invoke(safe)
                val bucket = safe / 5
                if (bucket != lastLoggedProgressBucket || safe == 100) {
                    lastLoggedProgressBucket = bucket
                    StageGridDebugLog.state(
                        "NATIVE_GUIDE",
                        "REANALYZE_PROGRESS percent=$safe stage=$stage elapsedMs=${(System.nanoTime() - startedNs) / 1_000_000L}",
                    )
                }
            }
        }

        StageGridDebugLog.action("NATIVE_GUIDE", "REANALYZE_START song=$songId preferredLanguage=$preferredLanguage")
        progress(1, "load_song")
        var progressSessionId: Long? = null
        try {
            val bundle = repository.getSongBundle(songId) ?: error("Song data is no longer available.")
            val song = bundle.song
            val referenceTrack = bundle.tracks.firstOrNull {
                TrackType.fromStorage(it.type) == TrackType.GUIDE && it.name != NativeGuideRenderer.TRACK_NAME
            } ?: error("The original imported Guide track is not available for reanalysis.")
            val referenceFile = File(referenceTrack.filePath)
            require(referenceFile.isFile) { "The original Guide audio file is missing." }
            StageGridDebugLog.state(
                "NATIVE_GUIDE",
                "REFERENCE_READY file=${referenceFile.name} bytes=${referenceFile.length()} durationMs=${referenceTrack.durationMs}",
            )

            val samples = guidePacks.listSamples()
            require(samples.isNotEmpty()) { "Install a Guide sample pack before reanalyzing this song." }
            StageGridDebugLog.state(
                "NATIVE_GUIDE",
                "PACK_READY samples=${samples.size} languages=${samples.map { it.language }.distinct().sorted().joinToString(",")}",
            )
            val existingNative = bundle.tracks.firstOrNull { it.name == NativeGuideRenderer.TRACK_NAME }
            if (existingNative == null && bundle.tracks.size >= MAX_TRACKS) {
                error("This song already uses the maximum track count, so a Native Guide track cannot be added.")
            }

            progressSessionId = NativeGuideProgressTracker.start(
                referenceFile = referenceFile,
                templateFiles = samples.map { it.file },
            ) { snapshot ->
                when (snapshot.phase) {
                    NativeGuideProgressTracker.Phase.TEMPLATES -> {
                        val mapped = (3f + snapshot.fraction * 19f).roundToInt().coerceIn(3, 22)
                        progress(mapped, "prepare_templates")
                    }
                    NativeGuideProgressTracker.Phase.REFERENCE -> {
                        val mapped = (23f + snapshot.fraction * 11f).roundToInt().coerceIn(23, 34)
                        progress(mapped, "fingerprint_reference")
                    }
                }
            }

            // Preparing templates first is deliberate. Previously startup cache creation and CUES.wav
            // fingerprinting could run at the same time on separate workers, saturating an emulator
            // and making the UI appear stuck at 6%. If another worker already owns template creation,
            // prepare() waits for that synchronized cache build to finish before CUES.wav is touched.
            progress(3, "prepare_templates")
            StageGridDebugLog.state("NATIVE_GUIDE", "TEMPLATE_PREPARE_WAIT samples=${samples.size}")
            val preparedTemplates = GuideCueAnalyzer.prepare(samples)
            require(preparedTemplates > 0) { "The installed Guide pack did not produce any usable fingerprints." }
            StageGridDebugLog.state(
                "NATIVE_GUIDE",
                "TEMPLATE_PREPARE_DONE templates=$preparedTemplates elapsedMs=${(System.nanoTime() - startedNs) / 1_000_000L}",
            )
            progress(22, "templates_ready")

            val sidecar = File(filesDir, "library/$songId/native-guide-events.json")
            val previousProposals = NativeGuideEventStore.readSectionProposals(sidecar)
            val autoSectionsUntouched = matchesStoredAutoSections(bundle.sections, previousProposals, song.durationMs)

            progress(23, "fingerprint_reference")
            StageGridDebugLog.state("NATIVE_GUIDE", "ANALYZE_START reference=${referenceFile.name}")
            val analysis = GuideCueAnalyzer.analyze(referenceFile, samples) { fraction ->
                // 0.00..0.12 is the reference WAV fingerprint/candidate setup and is reported by the
                // low-level tracker above. From 0.12 onward this callback represents language probe
                // and candidate/template matching, so map only that portion to avoid fake jumps.
                if (fraction >= 0.12f) {
                    val normalized = ((fraction - 0.12f) / 0.88f).coerceIn(0f, 1f)
                    progress((35f + normalized * 30f).roundToInt().coerceIn(35, 65), "match")
                }
            }
            StageGridDebugLog.state(
                "NATIVE_GUIDE",
                "ANALYZE_DONE candidates=${analysis.candidateCount} cues=${analysis.cues.size} detectedLanguage=${analysis.dominantLanguage ?: "none"} elapsedMs=${(System.nanoTime() - startedNs) / 1_000_000L}",
            )
            require(analysis.cues.isNotEmpty()) { "No Guide calls matched the installed sample pack confidently." }

            StageGridDebugLog.state("NATIVE_GUIDE", "SECTIONS_INFER_START bpm=${song.bpm} signature=${song.timeSignature} gridOffsetMs=${song.gridOffsetMs}")
            val proposals = GuideCueAnalyzer.inferSections(
                result = analysis,
                bpm = song.bpm,
                timeSignature = song.timeSignature,
                gridOffsetMs = song.gridOffsetMs,
                durationMs = song.durationMs,
            )
            StageGridDebugLog.state("NATIVE_GUIDE", "SECTIONS_INFER_DONE proposals=${proposals.size}")
            val existingLanguage = NativeGuideEventStore.readOutputLanguage(sidecar)
            val availableLanguages = guidePacks.status().languages
            val outputLanguage = existingLanguage?.takeIf { it in availableLanguages }
                ?: guidePacks.resolveOutputLanguage(preferredLanguage, analysis.dominantLanguage)
                ?: error("No compatible Guide output language is installed.")
            StageGridDebugLog.state("NATIVE_GUIDE", "OUTPUT_LANGUAGE value=$outputLanguage existing=${existingLanguage ?: "none"}")

            val audioDir = File(filesDir, "library/$songId/audio").apply { mkdirs() }
            val target = existingNative?.let { File(it.filePath) } ?: File(audioDir, "${NativeGuideRenderer.TRACK_NAME}.wav")
            val temp = File(audioDir, ".native-guide-reanalysis-${System.nanoTime()}.wav")
            StageGridDebugLog.state("NATIVE_GUIDE", "RENDER_PIPELINE_START target=${target.name} temp=${temp.name}")
            val rendered = try {
                NativeGuideRenderer.render(
                    outputFile = temp,
                    durationMs = song.durationMs,
                    cues = analysis.cues,
                    samples = samples,
                    outputLanguage = outputLanguage,
                    onProgress = { fraction ->
                        progress((66f + fraction * 28f).roundToInt().coerceIn(66, 94), "render")
                    },
                ) ?: error("Recognized calls could not be rendered with the installed sample pack.")
            } catch (t: Throwable) {
                temp.delete()
                StageGridDebugLog.error("NATIVE_GUIDE", "RENDER_PIPELINE_FAILED ${t.message ?: t::class.java.simpleName}", t)
                throw t
            }
            StageGridDebugLog.state(
                "NATIVE_GUIDE",
                "RENDER_PIPELINE_DONE rendered=${rendered.renderedEvents} missing=${rendered.missingEvents} bytes=${temp.length()}",
            )

            val previousAudio = File(audioDir, ".native-guide-previous-${System.nanoTime()}.wav")
            var backedUp = false
            try {
                progress(95, "replace_audio")
                if (target.exists()) {
                    StageGridDebugLog.io("NATIVE_GUIDE", "AUDIO_STAGE_PREVIOUS target=${target.name}")
                    if (!target.renameTo(previousAudio)) error("The current Native Guide could not be staged safely.")
                    backedUp = true
                }
                if (!temp.renameTo(target)) {
                    StageGridDebugLog.warning("NATIVE_GUIDE", "AUDIO_RENAME_FALLBACK copy target=${target.name}")
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                StageGridDebugLog.state("NATIVE_GUIDE", "AUDIO_REPLACED target=${target.name} bytes=${target.length()}")

                progress(96, "validate_wav")
                StageGridDebugLog.state("NATIVE_GUIDE", "VALIDATE_WAV_START target=${target.name}")
                val metadata = WavMetadataReader.read(target)
                StageGridDebugLog.state(
                    "NATIVE_GUIDE",
                    "VALIDATE_WAV_DONE channels=${metadata.channels} sampleRate=${metadata.sampleRate} bitDepth=${metadata.bitDepth} durationMs=${metadata.durationMs}",
                )
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
                StageGridDebugLog.state("NATIVE_GUIDE", "TRACK_DB_SAVED created=${existingNative == null} referenceMuted=${!referenceTrack.muted}")

                progress(97, "update_sections")
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
                StageGridDebugLog.state("NATIVE_GUIDE", "SECTIONS_UPDATE_DONE updated=$sectionsUpdated count=${replacements.size}")

                progress(98, "write_sidecar")
                val sidecarTemp = File(sidecar.parentFile, ".native-guide-events-${System.nanoTime()}.json")
                NativeGuideRenderer.writeEventSidecar(sidecarTemp, analysis, rendered.outputLanguage, proposals)
                if (sidecar.exists()) sidecar.delete()
                if (!sidecarTemp.renameTo(sidecar)) {
                    StageGridDebugLog.warning("NATIVE_GUIDE", "SIDECAR_RENAME_FALLBACK copy")
                    sidecarTemp.copyTo(sidecar, overwrite = true)
                    sidecarTemp.delete()
                }
                StageGridDebugLog.state("NATIVE_GUIDE", "SIDECAR_COMMITTED file=${sidecar.name} bytes=${sidecar.length()}")
                previousAudio.delete()
                progress(100, "done")
                val result = Result(analysis.cues.size, rendered.outputLanguage, sectionsUpdated, existingNative == null)
                StageGridDebugLog.state(
                    "NATIVE_GUIDE",
                    "REANALYZE_DONE song=$songId cues=${result.cueCount} language=${result.outputLanguage} sectionsUpdated=${result.sectionsUpdated} createdTrack=${result.createdNativeTrack} elapsedMs=${(System.nanoTime() - startedNs) / 1_000_000L}",
                )
                return result
            } catch (t: Throwable) {
                StageGridDebugLog.error("NATIVE_GUIDE", "COMMIT_FAILED ${t.message ?: t::class.java.simpleName}", t)
                target.delete()
                if (backedUp) previousAudio.renameTo(target)
                throw t
            } finally {
                temp.delete()
                if (target.exists()) previousAudio.delete()
            }
        } catch (t: Throwable) {
            StageGridDebugLog.error(
                "NATIVE_GUIDE",
                "REANALYZE_FAILED song=$songId elapsedMs=${(System.nanoTime() - startedNs) / 1_000_000L} message=${t.message ?: t::class.java.simpleName}",
                t,
            )
            throw t
        } finally {
            progressSessionId?.let(NativeGuideProgressTracker::stop)
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
                section.sortOrder == index &&
                section.colorArgb == AUTO_SECTION_COLORS[index % AUTO_SECTION_COLORS.size]
        }
    }

    private fun isPlaceholder(sections: List<SectionEntity>, durationMs: Long): Boolean =
        sections.size == 1 && sections[0].name == "Full Song" && sections[0].startMs == 0L && sections[0].endMs == durationMs

    private companion object {
        const val MAX_TRACKS = 64
        val AUTO_SECTION_COLORS = longArrayOf(
            0xFF5B8CFF, 0xFF2FBF9F, 0xFF9C6CFF, 0xFFF39C55, 0xFFE85D75, 0xFF4FB6E9,
        )
    }
}
