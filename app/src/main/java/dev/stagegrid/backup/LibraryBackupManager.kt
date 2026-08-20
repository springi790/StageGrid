package dev.stagegrid.backup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import dev.stagegrid.data.LibraryRepository
import dev.stagegrid.data.LibrarySnapshot
import dev.stagegrid.model.SectionEntity
import dev.stagegrid.model.SetlistEntity
import dev.stagegrid.model.SetlistSongEntity
import dev.stagegrid.model.SongEntity
import dev.stagegrid.model.TrackEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

/**
 * Portable, provider-agnostic StageGrid backups.
 *
 * The destination is an Android Storage Access Framework tree, so the same code works with a
 * local folder, USB storage, or providers such as Google Drive when they are exposed by Android's
 * document picker. Backups are self-contained ZIP containers with a `.stagebackup` suffix.
 */
class LibraryBackupManager(
    private val context: Context,
    private val repository: LibraryRepository,
) {
    suspend fun createBackup(
        destinationTree: Uri,
        onProgress: ((BackupProgress) -> Unit)? = null,
    ): BackupResult {
        report(onProgress, 1, BackupStage.PREPARING)
        val snapshot = repository.snapshot()
        require(snapshot.songs.size <= MAX_SONGS) { "Library is too large to back up safely." }

        val sources = collectSources(snapshot)
        val sourceBytes = sources.sumOf { it.file.length().coerceAtLeast(0L) }.coerceAtLeast(1L)
        val destination = DocumentFile.fromTreeUri(context, destinationTree)
            ?: error("The selected backup folder could not be opened.")
        require(destination.isDirectory && destination.canWrite()) { "The selected backup folder is not writable." }

        val fileName = "StageGrid-${timestamp()}.stagebackup"
        val document = destination.createFile(BACKUP_MIME, fileName)
            ?: error("StageGrid could not create a backup file in the selected folder.")
        var completedSourceBytes = 0L
        val fileRecords = mutableListOf<FileRecord>()
        var archiveBytes = 0L

        try {
            val raw = context.contentResolver.openOutputStream(document.uri, "w")
                ?: error("StageGrid could not open the backup destination for writing.")
            val counted = CountingOutputStream(raw)
            ZipOutputStream(BufferedOutputStream(counted, BUFFER_BYTES)).use { zip ->
                zip.setLevel(Deflater.BEST_SPEED)
                for ((index, source) in sources.withIndex()) {
                    val safeEntry = normalizeEntry(source.entry)
                    val entry = ZipEntry(safeEntry).apply { time = source.file.lastModified() }
                    zip.putNextEntry(entry)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var entryBytes = 0L
                    BufferedInputStream(FileInputStream(source.file), BUFFER_BYTES).use { input ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            zip.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            entryBytes += read
                            completedSourceBytes += read
                            val fraction = completedSourceBytes.toDouble() / sourceBytes.toDouble()
                            report(
                                onProgress,
                                (4 + fraction * 91.0).roundToInt(),
                                BackupStage.WRITING,
                                source.displayName,
                            )
                        }
                    }
                    zip.closeEntry()
                    fileRecords += FileRecord(safeEntry, entryBytes, digest.digest().toHex())
                    if (source.file.length() == 0L) {
                        report(
                            onProgress,
                            4 + (((index + 1) * 91f) / sources.size.coerceAtLeast(1)).roundToInt(),
                            BackupStage.WRITING,
                            source.displayName,
                        )
                    }
                }

                val manifest = buildManifest(snapshot, fileRecords)
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                val bytes = manifest.toString(2).toByteArray(Charsets.UTF_8)
                zip.write(bytes)
                zip.closeEntry()
                zip.finish()
                archiveBytes = counted.count
            }
            report(onProgress, 100, BackupStage.COMPLETE, fileName)
            return BackupResult(
                fileName = document.name ?: fileName,
                songs = snapshot.songs.size,
                setlists = snapshot.setlists.size,
                bytes = archiveBytes,
            )
        } catch (t: Throwable) {
            runCatching { document.delete() }
            throw t
        }
    }

    suspend fun restoreBackup(
        backupUri: Uri,
        onProgress: ((BackupProgress) -> Unit)? = null,
    ): RestoreResult {
        report(onProgress, 1, BackupStage.COPYING_ARCHIVE, queryDisplayName(backupUri))
        val cacheArchive = File(context.cacheDir, "restore-${System.nanoTime()}.stagebackup")
        val extractRoot = File(context.cacheDir, "restore-${System.nanoTime()}")
        val rollbackRoot = File(context.cacheDir, "restore-rollback-${System.nanoTime()}")
        cacheArchive.delete()
        extractRoot.deleteRecursively()
        rollbackRoot.deleteRecursively()
        extractRoot.mkdirs()

        try {
            copyArchiveToCache(backupUri, cacheArchive, onProgress)
            val extracted = extractAndHash(cacheArchive, extractRoot, onProgress)
            val manifestFile = File(extractRoot, MANIFEST_ENTRY)
            require(manifestFile.isFile) { "This file is not a complete StageGrid backup." }
            val manifest = JSONObject(manifestFile.readText())
            require(manifest.optString("format") == FORMAT_NAME) { "Unsupported backup format." }
            require(manifest.optInt("version", -1) == FORMAT_VERSION) { "Unsupported StageGrid backup version." }

            report(onProgress, 66, BackupStage.VALIDATING)
            validateFiles(manifest, extracted)
            val decoded = decodeSnapshot(manifest, extractRoot)
            require(decoded.snapshot.songs.size <= MAX_SONGS) { "Backup contains too many songs." }

            val installedTargets = installRestoredFiles(
                decoded = decoded,
                extractRoot = extractRoot,
                rollbackRoot = rollbackRoot,
                onProgress = onProgress,
            )
            try {
                report(onProgress, 95, BackupStage.RESTORING_LIBRARY)
                repository.restoreSnapshot(decoded.snapshot)
            } catch (t: Throwable) {
                rollbackInstalledFiles(installedTargets)
                throw t
            }
            discardRollback(installedTargets)
            report(onProgress, 100, BackupStage.COMPLETE)
            return RestoreResult(
                songs = decoded.snapshot.songs.size,
                setlists = decoded.snapshot.setlists.size,
                files = extracted.size,
            )
        } finally {
            cacheArchive.delete()
            extractRoot.deleteRecursively()
            rollbackRoot.deleteRecursively()
        }
    }

    private fun collectSources(snapshot: LibrarySnapshot): List<SourceFile> {
        val sources = mutableListOf<SourceFile>()
        val libraryRoot = File(context.filesDir, "library")
        for (song in snapshot.songs) {
            val songRoot = File(libraryRoot, song.id)
            require(songRoot.isDirectory) { "Missing local files for '${song.title}'." }
            val trackFiles = snapshot.tracks.filter { it.songId == song.id }
            for (track in trackFiles) {
                val trackFile = File(track.filePath)
                require(trackFile.isFile) { "Missing track '${track.name}' in '${song.title}'." }
                require(relativeInside(songRoot, trackFile) != null) {
                    "Track '${track.name}' is outside StageGrid private storage and cannot be backed up safely."
                }
            }
            songRoot.walkTopDown()
                .filter { it.isFile && ".staging" !in it.relativeTo(songRoot).invariantSeparatorsPath.split('/') }
                .forEach { file ->
                    val relative = relativeInside(songRoot, file)
                        ?: error("Unsafe song file path: ${file.name}")
                    sources += SourceFile(
                        entry = "library/${song.id}/$relative",
                        file = file,
                        displayName = "${song.title} · ${file.name}",
                    )
                }
        }

        // Keep the currently installed user-supplied Guide pack with the library backup so a new
        // device can still change Guide languages without requiring the user to find that ZIP again.
        val guideRoot = File(context.filesDir, "guide-packs/current")
        if (guideRoot.isDirectory) {
            guideRoot.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = relativeInside(guideRoot, file) ?: return@forEach
                sources += SourceFile(
                    entry = "guide-packs/current/$relative",
                    file = file,
                    displayName = "Guide pack · ${file.name}",
                )
            }
        }
        require(sources.size <= MAX_FILES) { "Backup contains too many files." }
        return sources.sortedBy { it.entry }
    }

    private fun buildManifest(snapshot: LibrarySnapshot, files: List<FileRecord>): JSONObject {
        val libraryRoot = File(context.filesDir, "library")
        val root = JSONObject()
            .put("format", FORMAT_NAME)
            .put("version", FORMAT_VERSION)
            .put("createdAtEpochMs", System.currentTimeMillis())
            .put("songs", JSONArray().apply {
                snapshot.songs.forEach { song ->
                    val songRoot = File(libraryRoot, song.id)
                    put(songToJson(song, songRoot))
                }
            })
            .put("tracks", JSONArray().apply {
                snapshot.tracks.forEach { track ->
                    val songRoot = File(libraryRoot, track.songId)
                    val relative = relativeInside(songRoot, File(track.filePath))
                        ?: error("Track '${track.name}' is outside the song folder.")
                    put(trackToJson(track, relative))
                }
            })
            .put("sections", JSONArray().apply { snapshot.sections.forEach { put(sectionToJson(it)) } })
            .put("setlists", JSONArray().apply { snapshot.setlists.forEach { put(setlistToJson(it)) } })
            .put("setlistSongs", JSONArray().apply { snapshot.setlistSongs.forEach { put(setlistSongToJson(it)) } })
            .put("files", JSONArray().apply {
                files.forEach { file ->
                    put(
                        JSONObject()
                            .put("path", file.entry)
                            .put("size", file.size)
                            .put("sha256", file.sha256),
                    )
                }
            })
        return root
    }

    private fun songToJson(song: SongEntity, songRoot: File): JSONObject {
        val artworkRelative = song.artworkPath?.let { relativeInside(songRoot, File(it)) }
        return JSONObject()
            .put("id", song.id)
            .put("title", song.title)
            .put("artist", song.artist)
            .putNullable("bpm", song.bpm)
            .putNullable("musicalKey", song.musicalKey)
            .put("timeSignature", song.timeSignature)
            .put("durationMs", song.durationMs)
            .put("gridOffsetMs", song.gridOffsetMs)
            .putNullable("artworkRelative", artworkRelative)
            .putNullable("category", song.category)
            .put("notes", song.notes)
            .put("importedAtEpochMs", song.importedAtEpochMs)
            .putNullable("lastPlayedAtEpochMs", song.lastPlayedAtEpochMs)
            .put("playCount", song.playCount)
            .put("favorite", song.favorite)
    }

    private fun trackToJson(track: TrackEntity, relative: String): JSONObject = JSONObject()
        .put("id", track.id)
        .put("songId", track.songId)
        .put("name", track.name)
        .put("fileRelative", relative)
        .put("type", track.type)
        .put("channels", track.channels)
        .put("sampleRate", track.sampleRate)
        .put("bitDepth", track.bitDepth)
        .put("durationMs", track.durationMs)
        .put("sortOrder", track.sortOrder)
        .put("volume", track.volume.toDouble())
        .put("muted", track.muted)
        .put("solo", track.solo)
        .put("pan", track.pan.toDouble())
        .put("outputRoute", track.outputRoute)

    private fun sectionToJson(section: SectionEntity): JSONObject = JSONObject()
        .put("id", section.id)
        .put("songId", section.songId)
        .put("name", section.name)
        .put("startMs", section.startMs)
        .put("endMs", section.endMs)
        .put("sortOrder", section.sortOrder)
        .put("colorArgb", section.colorArgb)

    private fun setlistToJson(setlist: SetlistEntity): JSONObject = JSONObject()
        .put("id", setlist.id)
        .put("name", setlist.name)
        .putNullable("eventDateEpochMs", setlist.eventDateEpochMs)
        .putNullable("venue", setlist.venue)
        .put("notes", setlist.notes)
        .put("createdAtEpochMs", setlist.createdAtEpochMs)

    private fun setlistSongToJson(entry: SetlistSongEntity): JSONObject = JSONObject()
        .put("setlistId", entry.setlistId)
        .put("songId", entry.songId)
        .put("sortOrder", entry.sortOrder)
        .put("notes", entry.notes)

    private fun copyArchiveToCache(
        uri: Uri,
        target: File,
        onProgress: ((BackupProgress) -> Unit)?,
    ) {
        val declaredSize = querySize(uri).takeIf { it > 0L }
        var copied = 0L
        val raw = context.contentResolver.openInputStream(uri)
            ?: error("The selected backup could not be opened.")
        BufferedInputStream(raw, BUFFER_BYTES).use { input ->
            BufferedOutputStream(FileOutputStream(target), BUFFER_BYTES).use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    copied += read
                    require(copied <= MAX_ARCHIVE_BYTES) { "Backup file is larger than the restore safety limit." }
                    output.write(buffer, 0, read)
                    if (declaredSize != null) {
                        val fraction = (copied.toDouble() / declaredSize.toDouble()).coerceIn(0.0, 1.0)
                        report(onProgress, (2 + fraction * 18.0).roundToInt(), BackupStage.COPYING_ARCHIVE, queryDisplayName(uri))
                    }
                }
            }
        }
        require(copied > 0L) { "The selected backup is empty." }
    }

    private fun extractAndHash(
        archive: File,
        root: File,
        onProgress: ((BackupProgress) -> Unit)?,
    ): Map<String, FileRecord> {
        val records = linkedMapOf<String, FileRecord>()
        var expandedBytes = 0L
        var entries = 0
        val counting = CountingInputStream(FileInputStream(archive))
        ZipInputStream(BufferedInputStream(counting, BUFFER_BYTES)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                require(entries <= MAX_FILES + 8) { "Backup contains too many entries." }
                val name = normalizeEntry(entry.name)
                val out = safeChild(root, name)
                if (entry.isDirectory) {
                    out.mkdirs()
                    zip.closeEntry()
                    continue
                }
                out.parentFile?.mkdirs()
                val digest = MessageDigest.getInstance("SHA-256")
                var entryBytes = 0L
                BufferedOutputStream(FileOutputStream(out), BUFFER_BYTES).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read <= 0) break
                        expandedBytes += read
                        entryBytes += read
                        require(expandedBytes <= MAX_EXPANDED_BYTES) { "Backup expands beyond the restore safety limit." }
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                    }
                }
                zip.closeEntry()
                if (name != MANIFEST_ENTRY) {
                    records[name] = FileRecord(name, entryBytes, digest.digest().toHex())
                }
                val fraction = (counting.count.toDouble() / archive.length().coerceAtLeast(1L).toDouble()).coerceIn(0.0, 1.0)
                report(onProgress, (20 + fraction * 45.0).roundToInt(), BackupStage.VALIDATING, name.substringAfterLast('/'))
            }
        }
        return records
    }

    private fun validateFiles(manifest: JSONObject, extracted: Map<String, FileRecord>) {
        val expectedArray = manifest.optJSONArray("files") ?: error("Backup file list is missing.")
        require(expectedArray.length() <= MAX_FILES) { "Backup file list is too large." }
        val expected = linkedMapOf<String, FileRecord>()
        for (i in 0 until expectedArray.length()) {
            val item = expectedArray.getJSONObject(i)
            val path = normalizeEntry(item.getString("path"))
            require(path != MANIFEST_ENTRY) { "Invalid backup manifest entry." }
            require(path !in expected) { "Backup contains a duplicate file entry: $path" }
            expected[path] = FileRecord(path, item.getLong("size"), item.getString("sha256").lowercase(Locale.ROOT))
        }
        require(extracted.keys == expected.keys) { "Backup is incomplete or contains unlisted files." }
        expected.forEach { (path, record) ->
            val actual = extracted[path] ?: error("Missing backup file: $path")
            require(actual.size == record.size) { "Backup file size mismatch: $path" }
            require(actual.sha256 == record.sha256) { "Backup integrity check failed: $path" }
        }
    }

    private fun decodeSnapshot(manifest: JSONObject, extractRoot: File): DecodedRestore {
        val libraryRoot = File(context.filesDir, "library")
        val songsJson = manifest.optJSONArray("songs") ?: JSONArray()
        val tracksJson = manifest.optJSONArray("tracks") ?: JSONArray()
        val sectionsJson = manifest.optJSONArray("sections") ?: JSONArray()
        val setlistsJson = manifest.optJSONArray("setlists") ?: JSONArray()
        val entriesJson = manifest.optJSONArray("setlistSongs") ?: JSONArray()

        val songs = buildList {
            for (i in 0 until songsJson.length()) {
                val item = songsJson.getJSONObject(i)
                val id = safeId(item.getString("id"))
                val songRoot = File(libraryRoot, id)
                val artworkRelative = item.optNullableString("artworkRelative")?.let(::normalizeRelative)
                add(
                    SongEntity(
                        id = id,
                        title = item.getString("title"),
                        artist = item.optString("artist"),
                        bpm = item.optNullableDouble("bpm"),
                        musicalKey = item.optNullableString("musicalKey"),
                        timeSignature = item.optString("timeSignature", "4/4"),
                        durationMs = item.optLong("durationMs", 0L),
                        gridOffsetMs = item.optLong("gridOffsetMs", 0L),
                        artworkPath = artworkRelative?.let { File(songRoot, it).absolutePath },
                        category = item.optNullableString("category"),
                        notes = item.optString("notes"),
                        importedAtEpochMs = item.optLong("importedAtEpochMs", System.currentTimeMillis()),
                        lastPlayedAtEpochMs = item.optNullableLong("lastPlayedAtEpochMs"),
                        playCount = item.optInt("playCount", 0),
                        favorite = item.optBoolean("favorite", false),
                    ),
                )
            }
        }
        val songIds = songs.map { it.id }.toSet()
        require(songIds.size == songs.size) { "Backup contains duplicate song IDs." }

        val tracks = buildList {
            for (i in 0 until tracksJson.length()) {
                val item = tracksJson.getJSONObject(i)
                val songId = safeId(item.getString("songId"))
                require(songId in songIds) { "Track references an unknown song." }
                val relative = normalizeRelative(item.getString("fileRelative"))
                val extractedTrack = safeChild(extractRoot, "library/$songId/$relative")
                require(extractedTrack.isFile) { "Backup is missing track audio: ${item.optString("name")}" }
                add(
                    TrackEntity(
                        id = safeId(item.getString("id")),
                        songId = songId,
                        name = item.getString("name"),
                        filePath = File(libraryRoot, "$songId/$relative").absolutePath,
                        type = item.optString("type", "OTHER"),
                        channels = item.getInt("channels"),
                        sampleRate = item.getInt("sampleRate"),
                        bitDepth = item.getInt("bitDepth"),
                        durationMs = item.getLong("durationMs"),
                        sortOrder = item.getInt("sortOrder"),
                        volume = item.optDouble("volume", 1.0).toFloat(),
                        muted = item.optBoolean("muted", false),
                        solo = item.optBoolean("solo", false),
                        pan = item.optDouble("pan", 0.0).toFloat(),
                        outputRoute = item.optString("outputRoute", "BOTH"),
                    ),
                )
            }
        }
        val sections = buildList {
            for (i in 0 until sectionsJson.length()) {
                val item = sectionsJson.getJSONObject(i)
                val songId = safeId(item.getString("songId"))
                require(songId in songIds) { "Section references an unknown song." }
                add(
                    SectionEntity(
                        id = safeId(item.getString("id")),
                        songId = songId,
                        name = item.getString("name"),
                        startMs = item.getLong("startMs"),
                        endMs = item.getLong("endMs"),
                        sortOrder = item.getInt("sortOrder"),
                        colorArgb = item.optLong("colorArgb", 0xFF5B8CFF),
                    ),
                )
            }
        }
        val setlists = buildList {
            for (i in 0 until setlistsJson.length()) {
                val item = setlistsJson.getJSONObject(i)
                add(
                    SetlistEntity(
                        id = safeId(item.getString("id")),
                        name = item.getString("name"),
                        eventDateEpochMs = item.optNullableLong("eventDateEpochMs"),
                        venue = item.optNullableString("venue"),
                        notes = item.optString("notes"),
                        createdAtEpochMs = item.optLong("createdAtEpochMs", System.currentTimeMillis()),
                    ),
                )
            }
        }
        val setlistIds = setlists.map { it.id }.toSet()
        val setlistSongs = buildList {
            for (i in 0 until entriesJson.length()) {
                val item = entriesJson.getJSONObject(i)
                val setlistId = safeId(item.getString("setlistId"))
                val songId = safeId(item.getString("songId"))
                require(setlistId in setlistIds && songId in songIds) { "Setlist entry references missing data." }
                add(
                    SetlistSongEntity(
                        setlistId = setlistId,
                        songId = songId,
                        sortOrder = item.getInt("sortOrder"),
                        notes = item.optString("notes"),
                    ),
                )
            }
        }
        return DecodedRestore(
            snapshot = LibrarySnapshot(songs, tracks, sections, setlists, setlistSongs),
            restoreGuidePack = File(extractRoot, "guide-packs/current").isDirectory,
        )
    }

    private fun installRestoredFiles(
        decoded: DecodedRestore,
        extractRoot: File,
        rollbackRoot: File,
        onProgress: ((BackupProgress) -> Unit)?,
    ): List<InstalledTarget> {
        val targets = mutableListOf<InstalledTarget>()
        val libraryRoot = File(context.filesDir, "library").apply { mkdirs() }
        val total = decoded.snapshot.songs.size + if (decoded.restoreGuidePack) 1 else 0
        var done = 0

        fun install(source: File, target: File, label: String) {
            require(source.isDirectory) { "Backup is missing $label files." }
            val rollback = File(rollbackRoot, "${targets.size}")
            rollback.parentFile?.mkdirs()
            var hadPrevious = false
            if (target.exists()) {
                rollback.deleteRecursively()
                if (!target.renameTo(rollback)) {
                    target.copyRecursively(rollback, overwrite = true)
                    target.deleteRecursively()
                }
                hadPrevious = rollback.exists()
            }
            try {
                target.deleteRecursively()
                source.copyRecursively(target, overwrite = true)
                require(target.isDirectory) { "Could not restore $label files." }
                targets += InstalledTarget(target, rollback, hadPrevious)
                done++
                val fraction = done.toFloat() / total.coerceAtLeast(1)
                report(onProgress, 70 + (fraction * 23f).roundToInt(), BackupStage.RESTORING_FILES, label)
            } catch (t: Throwable) {
                target.deleteRecursively()
                if (hadPrevious) rollback.copyRecursively(target, overwrite = true)
                throw t
            }
        }

        decoded.snapshot.songs.forEach { song ->
            install(
                source = File(extractRoot, "library/${song.id}"),
                target = File(libraryRoot, song.id),
                label = song.title,
            )
        }
        if (decoded.restoreGuidePack) {
            install(
                source = File(extractRoot, "guide-packs/current"),
                target = File(context.filesDir, "guide-packs/current"),
                label = "Guide pack",
            )
        }
        return targets
    }

    private fun rollbackInstalledFiles(targets: List<InstalledTarget>) {
        targets.asReversed().forEach { item ->
            item.target.deleteRecursively()
            if (item.hadPrevious && item.rollback.exists()) {
                item.rollback.copyRecursively(item.target, overwrite = true)
            }
        }
    }

    private fun discardRollback(targets: List<InstalledTarget>) {
        targets.forEach { it.rollback.deleteRecursively() }
    }

    private fun relativeInside(root: File, child: File): String? = runCatching {
        val canonicalRoot = root.canonicalFile
        val canonicalChild = child.canonicalFile
        val relative = canonicalChild.relativeTo(canonicalRoot).invariantSeparatorsPath
        normalizeRelative(relative)
    }.getOrNull()

    private fun safeChild(root: File, relative: String): File {
        val normalized = normalizeEntry(relative)
        val rootCanonical = root.canonicalFile
        val child = File(rootCanonical, normalized).canonicalFile
        require(child.path == rootCanonical.path || child.path.startsWith(rootCanonical.path + File.separator)) {
            "Unsafe path in backup."
        }
        return child
    }

    private fun normalizeEntry(value: String): String {
        val normalized = value.replace('\\', '/').trimStart('/')
        require(normalized.isNotBlank() && normalized.length <= 1024) { "Invalid backup entry path." }
        require(normalized.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Unsafe backup entry path." }
        return normalized
    }

    private fun normalizeRelative(value: String): String {
        val normalized = normalizeEntry(value)
        require(!normalized.startsWith("library/") && !normalized.startsWith("guide-packs/")) { "Invalid relative file path." }
        return normalized
    }

    private fun safeId(value: String): String {
        val trimmed = value.trim()
        require(trimmed.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Invalid identifier in backup." }
        return trimmed
    }

    private fun queryDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun querySize(uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0)
        }
        return -1L
    }

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private fun report(
        callback: ((BackupProgress) -> Unit)?,
        percent: Int,
        stage: BackupStage,
        detail: String? = null,
    ) {
        callback?.invoke(BackupProgress(percent.coerceIn(0, 100), stage, detail))
    }

    private fun MessageDigest.digestFile(file: File): String {
        BufferedInputStream(FileInputStream(file), BUFFER_BYTES).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                update(buffer, 0, read)
            }
        }
        return digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)
    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).trim().takeIf { it.isNotBlank() }
    private fun JSONObject.optNullableLong(key: String): Long? = if (isNull(key) || !has(key)) null else getLong(key)
    private fun JSONObject.optNullableDouble(key: String): Double? = if (isNull(key) || !has(key)) null else getDouble(key)

    private data class SourceFile(val entry: String, val file: File, val displayName: String)
    private data class FileRecord(val entry: String, val size: Long, val sha256: String)
    private data class DecodedRestore(val snapshot: LibrarySnapshot, val restoreGuidePack: Boolean)
    private data class InstalledTarget(val target: File, val rollback: File, val hadPrevious: Boolean)

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count: Long = 0
            private set
        override fun write(b: Int) {
            out.write(b)
            count++
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count: Long = 0
            private set
        override fun read(): Int {
            val value = super.read()
            if (value >= 0) count++
            return value
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val read = super.read(b, off, len)
            if (read > 0) count += read
            return read
        }
    }

    private companion object {
        const val FORMAT_NAME = "stagegrid-portable-backup"
        const val FORMAT_VERSION = 1
        const val MANIFEST_ENTRY = "manifest.json"
        const val BACKUP_MIME = "application/octet-stream"
        const val BUFFER_BYTES = 256 * 1024
        const val MAX_FILES = 20_000
        const val MAX_SONGS = 2_000
        const val MAX_ARCHIVE_BYTES = 64L * 1024L * 1024L * 1024L
        const val MAX_EXPANDED_BYTES = 128L * 1024L * 1024L * 1024L
    }
}
