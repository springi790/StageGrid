package dev.stagegrid.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import dev.stagegrid.data.LibraryRepository
import dev.stagegrid.guide.GuideCueAnalyzer
import dev.stagegrid.guide.GuidePackManager
import dev.stagegrid.guide.NativeGuideRenderer
import dev.stagegrid.model.SectionEntity
import dev.stagegrid.model.SongEntity
import dev.stagegrid.model.TrackEntity
import dev.stagegrid.model.TrackType
import dev.stagegrid.settings.AppSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream

class SongImporter(
    private val context: Context,
    private val repository: LibraryRepository,
    private val guidePacks: GuidePackManager,
    private val settings: AppSettingsRepository,
) {
    data class ImportResult(
        val songId: String,
        val title: String,
        val artist: String,
        val timeSignature: String,
        val notes: String,
        val tracksDetected: Int,
        val clickDetected: Boolean,
        val guideDetected: Boolean,
        val bpm: Double?,
        val key: String?,
        val gridOffsetMs: Long,
        val warnings: List<String>,
    )

    class ImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

    suspend fun importZip(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(uri) ?: "Imported Song.zip"
        val id = UUID.randomUUID().toString()
        val songRoot = File(context.filesDir, "library/$id")
        val stage = File(songRoot, ".staging")
        try {
            stage.mkdirs()
            val input = context.contentResolver.openInputStream(uri)
                ?: throw ImportException("The selected ZIP could not be opened.")
            input.use { raw -> extractZip(raw.buffered(), stage) }
            finalizeImport(id, displayName.substringBeforeLast('.'), stage, songRoot)
        } catch (t: Throwable) {
            songRoot.deleteRecursively()
            if (t is ImportException) throw t
            throw ImportException("The ZIP could not be imported: ${t.message ?: "unknown error"}", t)
        }
    }

    suspend fun importFolder(treeUri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw ImportException("The selected folder could not be opened.")
        val id = UUID.randomUUID().toString()
        val songRoot = File(context.filesDir, "library/$id")
        val stage = File(songRoot, ".staging")
        try {
            stage.mkdirs()
            copyDocumentTree(rootDoc, stage, 0, CopyBudget())
            finalizeImport(id, rootDoc.name ?: "Imported Song", stage, songRoot)
        } catch (t: Throwable) {
            songRoot.deleteRecursively()
            if (t is ImportException) throw t
            throw ImportException("The folder could not be imported: ${t.message ?: "unknown error"}", t)
        }
    }

    suspend fun importFiles(uris: List<Uri>): ImportResult = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) throw ImportException("No files were selected.")
        if (uris.size > MAX_FILES) throw ImportException("Too many files were selected (max $MAX_FILES).")
        val id = UUID.randomUUID().toString()
        val songRoot = File(context.filesDir, "library/$id")
        val stage = File(songRoot, ".staging")
        try {
            stage.mkdirs()
            val budget = CopyBudget()
            uris.forEachIndexed { index, uri ->
                val name = sanitizeFileName(queryDisplayName(uri) ?: "track-${index + 1}.wav")
                val dest = uniqueFile(stage, name)
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw ImportException("Could not open $name")
                input.use { src -> FileOutputStream(dest).use { out -> copyBounded(src, out, budget) } }
            }
            val title = queryDisplayName(uris.first())?.substringBeforeLast('.') ?: "Imported Song"
            finalizeImport(id, title, stage, songRoot)
        } catch (t: Throwable) {
            songRoot.deleteRecursively()
            if (t is ImportException) throw t
            throw ImportException("The selected files could not be imported: ${t.message ?: "unknown error"}", t)
        }
    }

    private suspend fun finalizeImport(
        songId: String,
        fallbackTitle: String,
        stage: File,
        songRoot: File,
    ): ImportResult {
        val allFiles = stage.walkTopDown().filter { it.isFile }.toList()
        val manifestFile = allFiles.firstOrNull { it.name.equals("song.json", true) }
        val manifest = SongManifestParser.parse(manifestFile)
        val warnings = mutableListOf<String>()

        val requestedAudio = allFiles.filter { it.extension.lowercase(Locale.ROOT) in AUDIO_EXTENSIONS }
        if (requestedAudio.isEmpty()) throw ImportException("No compatible audio files were found.")

        val wavFiles = requestedAudio.filter { it.extension.equals("wav", true) }
        val mp3Files = requestedAudio.filter { it.extension.equals("mp3", true) }
        val playableSources = (wavFiles + mp3Files).sortedBy { it.name.lowercase() }
        val unsupportedCompressed = requestedAudio.filter {
            !it.extension.equals("wav", true) && !it.extension.equals("mp3", true)
        }
        if (unsupportedCompressed.isNotEmpty()) {
            warnings += "${unsupportedCompressed.size} compressed track(s) were detected but are not playable yet: " +
                unsupportedCompressed.take(4).joinToString { it.name }
        }
        if (mp3Files.isNotEmpty()) {
            warnings += "${mp3Files.size} MP3 track(s) were converted to 16-bit PCM WAV for reliable live playback. " +
                "WAV exported from one common timeline remains recommended for the best stem alignment."
        }
        if (playableSources.isEmpty()) {
            throw ImportException("This build requires at least one WAV or MP3 audio track.")
        }
        if (playableSources.size > MAX_TRACKS) {
            throw ImportException("This song contains ${playableSources.size} playable tracks; the MVP import limit is $MAX_TRACKS.")
        }

        val audioDir = File(songRoot, "audio").apply { mkdirs() }
        val tracks = mutableListOf<TrackEntity>()
        var maxDuration = 0L
        var clickReferenceFile: File? = null
        var guideReferenceFile: File? = null
        var totalEncoderDelayFrames = 0L
        var mp3WithGaplessMetadata = 0

        playableSources.forEachIndexed { index, source ->
            val isMp3 = source.extension.equals("mp3", true)
            val destinationName = if (isMp3) "${source.nameWithoutExtension}.wav" else source.name
            val safeName = uniqueFile(audioDir, sanitizeFileName(destinationName))
            var mp3Decode: Mp3ToWavDecoder.DecodeResult? = null
            val metadata = try {
                if (isMp3) {
                    Mp3ToWavDecoder.decode(source, safeName).also { mp3Decode = it }.metadata
                } else {
                    source.copyTo(safeName, overwrite = false)
                    WavMetadataReader.read(safeName)
                }
            } catch (t: Throwable) {
                safeName.delete()
                throw ImportException("Track \"${source.name}\" could not be decoded: ${t.message}", t)
            }
            mp3Decode?.let { decode ->
                if (decode.encoderDelayFrames > 0 || decode.encoderPaddingFrames > 0) {
                    mp3WithGaplessMetadata++
                    totalEncoderDelayFrames += decode.encoderDelayFrames
                }
            }
            maxDuration = maxOf(maxDuration, metadata.durationMs)
            val manifestType = manifest.trackTypes[source.name.lowercase()]
            val type = manifestType ?: StemClassifier.classify(source.name)
            if (type == TrackType.CLICK && clickReferenceFile == null) clickReferenceFile = safeName
            if (type == TrackType.GUIDE && guideReferenceFile == null) guideReferenceFile = safeName
            tracks += TrackEntity(
                songId = songId,
                name = source.nameWithoutExtension,
                filePath = safeName.absolutePath,
                type = type.name,
                channels = metadata.channels,
                sampleRate = metadata.sampleRate,
                bitDepth = metadata.bitDepth,
                durationMs = metadata.durationMs,
                sortOrder = index,
                // Imported click files are kept as a reference for grid detection, but the live
                // engine uses its own sample-accurate click generator.
                muted = type == TrackType.CLICK,
            )
        }

        if (mp3WithGaplessMetadata > 0) {
            warnings += "Android gapless metadata removed encoder delay/padding from $mp3WithGaplessMetadata MP3 track(s) " +
                "during import (total leading trim: $totalEncoderDelayFrames decoded frames)."
        }

        val gridAnalysis = clickReferenceFile?.let { runCatching { ClickGridAnalyzer.analyze(it) }.getOrNull() }
        val gridOffsetMs = gridAnalysis?.offsetMs ?: 0L
        if (gridAnalysis != null) {
            warnings += "Imported click was used only as a timing reference. Native click grid aligned at ${gridOffsetMs} ms " +
                "(confidence ${(gridAnalysis.confidence * 100).toInt()}%)."
        } else if (clickReferenceFile != null) {
            warnings += "A click track was detected, but its first transient could not be identified reliably; native click starts at 0 ms until adjusted."
        }

        var guideAnalysis: GuideCueAnalyzer.Result? = null
        var autoSectionProposals = emptyList<GuideCueAnalyzer.SectionProposal>()
        var nativeGuideLanguage: String? = null
        val installedGuideSamples = guidePacks.listSamples()
        if (guideReferenceFile != null && installedGuideSamples.isNotEmpty()) {
            guideAnalysis = runCatching { GuideCueAnalyzer.analyze(guideReferenceFile!!, installedGuideSamples) }
                .onFailure { warnings += "Native Guide analysis failed: ${it.message ?: "unknown error"}. The imported Guide track was kept." }
                .getOrNull()
            val analysis = guideAnalysis
            if (analysis != null && analysis.cues.isNotEmpty()) {
                autoSectionProposals = GuideCueAnalyzer.inferSections(
                    result = analysis,
                    bpm = manifest.bpm,
                    timeSignature = manifest.timeSignature ?: "4/4",
                    gridOffsetMs = gridOffsetMs,
                    durationMs = maxDuration,
                )
                val preferredLanguage = settings.settings.first().nativeGuideLanguage
                nativeGuideLanguage = guidePacks.resolveOutputLanguage(preferredLanguage, analysis.dominantLanguage)
                val outputLanguage = nativeGuideLanguage
                if (outputLanguage != null && tracks.size < MAX_TRACKS) {
                    val nativeGuideFile = uniqueFile(audioDir, "StageGrid Native Guide.wav")
                    val rendered = runCatching {
                        NativeGuideRenderer.render(
                            outputFile = nativeGuideFile,
                            durationMs = maxDuration,
                            cues = analysis.cues,
                            samples = installedGuideSamples,
                            outputLanguage = outputLanguage,
                        )
                    }.getOrNull()
                    if (rendered != null) {
                        val metadata = WavMetadataReader.read(rendered.file)
                        for (i in tracks.indices) {
                            if (tracks[i].type == TrackType.GUIDE.name) tracks[i] = tracks[i].copy(muted = true)
                        }
                        tracks += TrackEntity(
                            songId = songId,
                            name = "StageGrid Native Guide",
                            filePath = rendered.file.absolutePath,
                            type = TrackType.GUIDE.name,
                            channels = metadata.channels,
                            sampleRate = metadata.sampleRate,
                            bitDepth = metadata.bitDepth,
                            durationMs = metadata.durationMs,
                            sortOrder = tracks.size,
                        )
                        warnings += "StageGrid recognized ${analysis.cues.size} Guide cue(s) and generated a native Guide in '${rendered.outputLanguage}'. " +
                            "The imported Guide track was muted but kept as a reference."
                        if (rendered.missingEvents > 0) {
                            warnings += "${rendered.missingEvents} recognized Guide cue(s) had no matching sample in the selected/fallback pack."
                        }
                    } else {
                        nativeGuideFile.delete()
                        warnings += "Guide cues were recognized, but a native Guide WAV could not be rendered; the imported Guide track remains active."
                    }
                } else if (tracks.size >= MAX_TRACKS) {
                    warnings += "Guide cues were recognized, but the native Guide track was skipped because the song already uses the $MAX_TRACKS-track import limit."
                }
                runCatching {
                    NativeGuideRenderer.writeEventSidecar(
                        File(songRoot, "native-guide-events.json"),
                        analysis,
                        nativeGuideLanguage,
                        autoSectionProposals,
                    )
                }
            } else if (analysis != null) {
                warnings += "A Guide track and Guide sample pack were found, but no cue matched the installed templates confidently."
            }
        } else if (guideReferenceFile != null && installedGuideSamples.isEmpty()) {
            warnings += "A Guide track was detected. Install a Guide sample pack in Settings to enable native Guide recognition and automatic section detection on future imports."
        }

        val manifestSectionStarts = manifest.sections.filter { it.startMs < maxDuration }.sortedBy { it.startMs }
        val sections = when {
            manifestSectionStarts.isNotEmpty() -> manifestSectionStarts.mapIndexed { i, section ->
                val end = manifestSectionStarts.getOrNull(i + 1)?.startMs ?: maxDuration
                SectionEntity(
                    songId = songId,
                    name = section.name,
                    startMs = section.startMs,
                    endMs = end.coerceAtLeast(section.startMs + 1),
                    sortOrder = i,
                    colorArgb = section.colorArgb ?: defaultSectionColor(i),
                )
            }
            autoSectionProposals.isNotEmpty() -> {
                warnings += "${autoSectionProposals.size} song section(s) were created automatically from the recognized Guide cues. Review them in Edit sections before live use."
                autoSectionProposals.mapIndexed { i, proposal ->
                    val end = autoSectionProposals.getOrNull(i + 1)?.startMs ?: maxDuration
                    SectionEntity(
                        songId = songId,
                        name = proposal.name,
                        startMs = proposal.startMs,
                        endMs = end.coerceAtLeast(proposal.startMs + 1),
                        sortOrder = i,
                        colorArgb = defaultSectionColor(i),
                    )
                }
            }
            else -> listOf(
                SectionEntity(songId = songId, name = "Full Song", startMs = 0, endMs = maxDuration, sortOrder = 0),
            )
        }

        val title = manifest.title ?: fallbackTitle.ifBlank { "Imported Song" }
        val song = SongEntity(
            id = songId,
            title = title,
            artist = manifest.artist.orEmpty(),
            bpm = manifest.bpm,
            musicalKey = manifest.key,
            timeSignature = manifest.timeSignature ?: "4/4",
            durationMs = maxDuration,
            gridOffsetMs = gridOffsetMs,
        )
        repository.saveImportedSong(song, tracks, sections)
        stage.deleteRecursively()
        File(songRoot, "import-fingerprint.sha256").writeText(hashTrackSet(tracks))

        return ImportResult(
            songId = songId,
            title = title,
            artist = song.artist,
            timeSignature = song.timeSignature,
            notes = song.notes,
            tracksDetected = tracks.size,
            clickDetected = tracks.any { it.type == TrackType.CLICK.name },
            guideDetected = tracks.any { it.type == TrackType.GUIDE.name },
            bpm = song.bpm,
            key = song.musicalKey,
            gridOffsetMs = song.gridOffsetMs,
            warnings = warnings,
        )
    }

    private fun extractZip(input: java.io.InputStream, destination: File) {
        var count = 0
        var totalBytes = 0L
        val canonicalRoot = destination.canonicalFile
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                count++
                if (count > MAX_FILES) throw ImportException("The ZIP contains too many files.")
                if (entry.isDirectory) {
                    zip.closeEntry(); continue
                }
                val requested = File(destination, entry.name)
                val outFile = requested.canonicalFile
                if (!outFile.path.startsWith(canonicalRoot.path + File.separator)) {
                    throw ImportException("The ZIP contains an unsafe path: ${entry.name}")
                }
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { out ->
                    val buffer = ByteArray(COPY_BUFFER)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read <= 0) break
                        totalBytes += read
                        if (totalBytes > MAX_EXTRACTED_BYTES) throw ImportException("The ZIP expands beyond the configured safety limit.")
                        out.write(buffer, 0, read)
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private data class CopyBudget(var files: Int = 0, var bytes: Long = 0L)

    private fun copyDocumentTree(doc: DocumentFile, destination: File, depth: Int, budget: CopyBudget) {
        if (depth > MAX_DEPTH) throw ImportException("The selected folder is nested too deeply.")
        doc.listFiles().forEach { child ->
            val safeName = sanitizeFileName(child.name ?: "item")
            if (child.isDirectory) {
                val next = File(destination, safeName).apply { mkdirs() }
                copyDocumentTree(child, next, depth + 1, budget)
            } else if (child.isFile) {
                val ext = safeName.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (ext in AUDIO_EXTENSIONS || safeName.equals("song.json", true)) {
                    budget.files++
                    if (budget.files > MAX_FILES) throw ImportException("The selected folder contains too many importable files.")
                    val dest = uniqueFile(destination, safeName)
                    val input = context.contentResolver.openInputStream(child.uri)
                        ?: throw ImportException("Could not open ${child.name}")
                    input.use { src -> FileOutputStream(dest).use { out -> copyBounded(src, out, budget) } }
                }
            }
        }
    }

    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, budget: CopyBudget) {
        val buffer = ByteArray(COPY_BUFFER)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            budget.bytes += read
            if (budget.bytes > MAX_EXTRACTED_BYTES) throw ImportException("The selected content exceeds the configured safety limit.")
            output.write(buffer, 0, read)
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun sanitizeFileName(input: String): String {
        val clean = input.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(180)
        return clean.ifBlank { "track.wav" }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 2
        while (candidate.exists()) {
            candidate = File(dir, if (ext.isBlank()) "$base ($i)" else "$base ($i).$ext")
            i++
        }
        return candidate
    }

    private fun hashTrackSet(tracks: List<TrackEntity>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        tracks.sortedBy { it.name }.forEach { track ->
            digest.update(track.name.toByteArray())
            digest.update(track.durationMs.toString().toByteArray())
            digest.update(File(track.filePath).length().toString().toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun defaultSectionColor(index: Int): Long = SECTION_COLORS[index % SECTION_COLORS.size]

    companion object {
        private const val COPY_BUFFER = 128 * 1024
        private const val MAX_FILES = 512
        private const val MAX_TRACKS = 64
        private const val MAX_DEPTH = 8
        private const val MAX_EXTRACTED_BYTES = 50L * 1024L * 1024L * 1024L
        private val AUDIO_EXTENSIONS = setOf("wav", "flac", "mp3", "m4a", "aac", "ogg")
        private val SECTION_COLORS = longArrayOf(
            0xFF5B8CFF, 0xFF2FBF9F, 0xFF9C6CFF, 0xFFF39C55, 0xFFE85D75, 0xFF4FB6E9,
        )
    }
}
