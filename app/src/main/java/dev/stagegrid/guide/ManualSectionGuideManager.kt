package dev.stagegrid.guide

import dev.stagegrid.data.LibraryRepository
import dev.stagegrid.guide.GuidePackManager.CueKind
import dev.stagegrid.importer.WavMetadataReader
import dev.stagegrid.model.SectionEntity
import dev.stagegrid.model.StereoRoute
import dev.stagegrid.model.TrackEntity
import dev.stagegrid.model.TrackType
import dev.stagegrid.music.MusicalGrid
import dev.stagegrid.settings.AppSettingsRepository
import java.io.File
import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToLong
import kotlinx.coroutines.flow.first

/**
 * Keeps Native Guide SECTION calls aligned with sections edited manually by the musician.
 *
 * Automatic speech/template recognition is intentionally optional. Once a user edits the section
 * map, that Room section map becomes authoritative for SECTION calls. Existing COUNT/DYNAMIC cues
 * are preserved, while SECTION cues are rebuilt deterministically from section names and rendered
 * from the installed Guide sample pack.
 */
class ManualSectionGuideManager(
    private val filesDir: File,
    private val repository: LibraryRepository,
    private val guidePacks: GuidePackManager,
    private val settings: AppSettingsRepository,
) {
    data class SyncResult(
        val sectionCueCount: Int,
        val missingSectionNames: List<String>,
        val outputLanguage: String,
        val createdNativeTrack: Boolean,
    )

    suspend fun syncSong(songId: String): SyncResult? {
        val bundle = repository.getSongBundle(songId) ?: return null
        val song = bundle.song
        if (song.durationMs <= 0L || bundle.sections.isEmpty()) return null

        val samples = guidePacks.listSamples()
        if (samples.isEmpty()) return null

        val sidecar = File(filesDir, "library/$songId/native-guide-events.json")
        val previousAnalysis = NativeGuideEventStore.readAnalysis(sidecar)
        val preferredLanguage = settings.settings.first().nativeGuideLanguage
        val availableLanguages = guidePacks.status().languages
        val outputLanguage = NativeGuideEventStore.readOutputLanguage(sidecar)
            ?.takeIf { it in availableLanguages }
            ?: guidePacks.resolveOutputLanguage(preferredLanguage, previousAnalysis?.dominantLanguage)
            ?: return null

        val sortedSections = bundle.sections.sortedWith(compareBy<SectionEntity> { it.startMs }.thenBy { it.sortOrder })
        val cuePlan = buildSectionCuePlan(song.bpm, song.timeSignature, song.gridOffsetMs, sortedSections, samples, outputLanguage)
        val preservedNonSection = previousAnalysis?.cues?.filter { it.kind != CueKind.SECTION }.orEmpty()
        val mergedCues = (preservedNonSection + cuePlan.cues).sortedBy { it.cueMs }
        if (mergedCues.isEmpty()) return null

        val analysis = GuideCueAnalyzer.Result(
            cues = mergedCues,
            dominantLanguage = previousAnalysis?.dominantLanguage ?: outputLanguage,
            candidateCount = previousAnalysis?.candidateCount ?: 0,
            diagnostics = previousAnalysis?.diagnostics.orEmpty(),
        )
        val proposals = cuePlan.proposals

        val existingNative = bundle.tracks.firstOrNull { it.name == NativeGuideRenderer.TRACK_NAME }
        if (existingNative == null && bundle.tracks.size >= MAX_TRACKS) return null

        val audioDir = File(filesDir, "library/$songId/audio").apply { mkdirs() }
        val target = existingNative?.let { File(it.filePath) } ?: File(audioDir, "${NativeGuideRenderer.TRACK_NAME}.wav")
        val temp = File(audioDir, ".manual-section-guide-${System.nanoTime()}.wav")
        val rendered = try {
            NativeGuideRenderer.render(
                outputFile = temp,
                durationMs = song.durationMs,
                cues = mergedCues,
                samples = samples,
                outputLanguage = outputLanguage,
            ) ?: run {
                temp.delete()
                return null
            }
        } catch (_: Throwable) {
            temp.delete()
            return null
        }

        if (!replaceFileAtomically(temp, target)) return null
        val metadata = runCatching { WavMetadataReader.read(target) }.getOrNull() ?: return null
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

        val sidecarTemp = File(sidecar.parentFile, ".native-guide-manual-${System.nanoTime()}.json")
        NativeGuideRenderer.writeEventSidecar(sidecarTemp, analysis, rendered.outputLanguage, proposals)
        NativeGuideEventStore.markManualSectionCues(sidecarTemp)
        if (!replaceFileAtomically(sidecarTemp, sidecar)) return null

        return SyncResult(
            sectionCueCount = cuePlan.cues.size,
            missingSectionNames = cuePlan.missingNames,
            outputLanguage = rendered.outputLanguage,
            createdNativeTrack = existingNative == null,
        )
    }

    private data class CuePlan(
        val cues: List<GuideCueAnalyzer.DetectedCue>,
        val proposals: List<GuideCueAnalyzer.SectionProposal>,
        val missingNames: List<String>,
    )

    private fun buildSectionCuePlan(
        bpm: Double?,
        timeSignature: String,
        gridOffsetMs: Long,
        sections: List<SectionEntity>,
        samples: List<GuidePackManager.GuideSample>,
        outputLanguage: String,
    ): CuePlan {
        val grid = MusicalGrid.from(bpm, timeSignature, gridOffsetMs)
        val leadMs = grid?.barDurationMs?.roundToLong()?.coerceAtLeast(1L) ?: DEFAULT_CUE_LEAD_MS
        val cues = mutableListOf<GuideCueAnalyzer.DetectedCue>()
        val proposals = mutableListOf<GuideCueAnalyzer.SectionProposal>()
        val missing = mutableListOf<String>()

        sections.forEach { section ->
            val canonical = canonicalSectionKey(section.name)
            val resolved = resolveInstalledKey(canonical, outputLanguage, samples)
            val proposalKey = resolved ?: canonical
            proposals += GuideCueAnalyzer.SectionProposal(
                key = proposalKey,
                name = section.name,
                startMs = section.startMs.coerceAtLeast(0L),
                confidence = 1f,
            )
            if (resolved == null) {
                missing += section.name
                return@forEach
            }
            cues += GuideCueAnalyzer.DetectedCue(
                key = resolved,
                kind = CueKind.SECTION,
                language = outputLanguage,
                cueMs = (section.startMs - leadMs).coerceAtLeast(0L),
                confidence = 1f,
            )
        }
        return CuePlan(cues, proposals, missing.distinct())
    }

    internal fun canonicalSectionKey(name: String): String {
        val normalized = Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .trim()
        val compact = normalized.replace(Regex("[^a-z0-9]+"), " ").trim()
        if (compact.isBlank()) return "section"

        // Common chart/editor shorthand: V1, C2, In2, It, P, etc. Superscript repeat marks
        // are intentionally ignored by normalization because they describe repetitions, not cue names.
        val noSpaces = compact.replace(" ", "")
        Regex("^(v|c|b|p|in|it|pc)(\\d+)$").matchEntire(noSpaces)?.let { match ->
            val base = when (match.groupValues[1]) {
                "v" -> "verse"
                "c" -> "chorus"
                "b", "p" -> "bridge"
                "in" -> "instrumental"
                "it" -> "interlude"
                "pc" -> "pre_chorus"
                else -> "section"
            }
            return "${base}_${match.groupValues[2]}"
        }

        val number = Regex("(?:^| )(\\d+)$").find(compact)?.groupValues?.getOrNull(1)
        val withoutNumber = compact.replace(Regex("(?:^| )\\d+$"), "").trim()
        val base = when (withoutNumber) {
            "i", "intro", "introduccion" -> "intro"
            "v", "verse", "verso" -> "verse"
            "vp", "vamp" -> "vamp"
            "pc", "pre chorus", "prechorus", "pre coro", "precoro" -> "pre_chorus"
            "c", "chorus", "coro" -> "chorus"
            "post chorus", "postchorus", "post coro", "postcoro" -> "post_chorus"
            "b", "bridge", "p", "puente" -> "bridge"
            "it", "interlude", "interludio" -> "interlude"
            "in", "instrumental", "instrumental break" -> "instrumental"
            "ending", "end", "final", "fin", "f" -> "ending"
            "outro" -> "outro"
            "rap" -> "rap"
            "tag", "repetir" -> "tag"
            "solo" -> "solo"
            "turnaround", "vuelta" -> "turnaround"
            "refrain", "refran" -> "refrain"
            "breakdown", "baja intensidad" -> "breakdown"
            "exhortation", "exhortacion" -> "exhortation"
            "acapella", "a capella" -> "acapella"
            else -> withoutNumber.replace(' ', '_')
        }
        return if (number == null) base else "${base}_$number"
    }

    private fun resolveInstalledKey(
        requested: String,
        language: String,
        samples: List<GuidePackManager.GuideSample>,
    ): String? {
        val sectionKeys = samples.asSequence()
            .filter { it.language == language && it.kind == CueKind.SECTION }
            .map { it.key }
            .toSet()
        if (requested in sectionKeys) return requested
        val generic = requested.replace(Regex("_\\d+$"), "")
        if (generic in sectionKeys) return generic
        val firstNumbered = sectionKeys.firstOrNull { it == "${generic}_1" }
        if (firstNumbered != null) return firstNumbered
        return null
    }

    private fun replaceFileAtomically(temp: File, target: File): Boolean {
        val parent = target.parentFile ?: return false
        parent.mkdirs()
        val backup = File(parent, ".${target.name}.previous-${System.nanoTime()}")
        var backedUp = false
        return try {
            if (target.exists()) {
                if (!target.renameTo(backup)) return false
                backedUp = true
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            if (!target.isFile || target.length() <= 0L) error("Replacement file is empty")
            if (backedUp) backup.delete()
            true
        } catch (_: Throwable) {
            target.delete()
            if (backedUp) backup.renameTo(target)
            false
        } finally {
            temp.delete()
            if (target.exists()) backup.delete()
        }
    }

    private companion object {
        const val DEFAULT_CUE_LEAD_MS = 2_000L
        const val MAX_TRACKS = 64
    }
}
