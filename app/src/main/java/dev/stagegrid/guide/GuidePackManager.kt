package dev.stagegrid.guide

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.stagegrid.importer.WavMetadataReader
import dev.stagegrid.settings.NativeGuideFeatureGate
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.text.Normalizer
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Installs user-supplied Guide cue samples into app-private storage.
 *
 * StageGrid deliberately does not bundle third-party Guide audio. Users install a sample pack
 * they are licensed to use through Android's document picker. Only WAV files inside recognized
 * Guide folders are copied; click samples, Ableton sidecars and macOS metadata are ignored.
 *
 * Native Guide analysis remains experimental and its analysis-driven emission is gated. Manual
 * section Cue playback is a core player feature, however, so it can resolve already-installed
 * Guide Pack samples even when Native Guide Beta is disabled.
 */
class GuidePackManager(private val context: Context) {
    enum class CueKind { SECTION, COUNT, DYNAMIC }

    data class GuideSample(
        val language: String,
        val key: String,
        val kind: CueKind,
        val file: File,
    )

    data class Status(
        val installed: Boolean,
        val sampleCount: Int,
        val languages: List<String>,
        val sourceName: String? = null,
    )

    data class InstallResult(
        val status: Status,
        val skippedFiles: Int,
    )

    private val root = File(context.filesDir, "guide-packs/current")

    @Volatile
    private var cachedSamples: List<GuideSample>? = null

    /** Reports what is installed even when the experimental feature is switched off. */
    fun status(): Status {
        val samples = installedSamples()
        return Status(
            installed = samples.isNotEmpty(),
            sampleCount = samples.size,
            languages = samples.map { it.language }.distinct().sorted(),
            sourceName = File(root, "source-name.txt").takeIf { it.isFile }?.readText()?.trim()?.ifBlank { null },
        )
    }

    /** Returns the installed sample index for analysis/cache preparation. */
    fun listSamples(): List<GuideSample> = installedSamples()

    private fun installedSamples(): List<GuideSample> {
        cachedSamples?.let { return it }
        return synchronized(this) {
            cachedSamples?.let { return@synchronized it }
            val indexed = if (!root.isDirectory) {
                emptyList()
            } else {
                root.walkTopDown()
                    .filter { it.isFile && it.extension.equals("wav", true) }
                    .mapNotNull { file ->
                        val relative = file.relativeTo(root).invariantSeparatorsPath.split('/')
                        if (relative.size < 3) return@mapNotNull null
                        val language = relative[0]
                        val kind = runCatching { CueKind.valueOf(relative[1].uppercase(Locale.ROOT)) }.getOrNull()
                            ?: return@mapNotNull null
                        GuideSample(language, file.nameWithoutExtension, kind, file)
                    }
                    .sortedWith(compareBy<GuideSample> { it.language }.thenBy { it.kind.ordinal }.thenBy { it.key })
                    .toList()
            }
            cachedSamples = indexed
            indexed
        }
    }

    /** Native Guide Beta resolver. Analysis-driven cue emission remains feature-gated. */
    fun findSample(language: String, key: String): GuideSample? {
        if (!NativeGuideFeatureGate.enabled) return null
        return findInstalledSample(language, key, null)
    }

    /** Native Guide Beta resolver. Analysis-driven cue emission remains feature-gated. */
    fun findSample(language: String, key: String, kind: CueKind): GuideSample? {
        if (!NativeGuideFeatureGate.enabled) return null
        return findInstalledSample(language, key, kind)
    }

    /**
     * Core manual-section Cue resolver. This deliberately does not depend on Native Guide Beta:
     * the user explicitly selected Cue as the Guide source and the samples are already installed.
     */
    fun findCueSample(language: String, key: String, kind: CueKind): GuideSample? =
        findInstalledSample(language, canonicalKey(key), kind)

    private fun findInstalledSample(language: String, key: String, kind: CueKind?): GuideSample? {
        val normalizedKey = canonicalKey(key)
        if (normalizedKey.isBlank()) return null
        val samples = installedSamples()
        val exact = if (kind == null) {
            samples.firstOrNull { it.language == language && it.key == normalizedKey }
        } else {
            samples.firstOrNull { it.language == language && it.key == normalizedKey && it.kind == kind }
                ?: samples.firstOrNull { it.language == language && it.key == normalizedKey }
        }
        if (exact != null) return exact

        // A manually named "Verse 1" normally maps to verse_1, but packs differ. Fall back to the
        // unnumbered family when an exact numbered sample is not present.
        val family = normalizedKey.replace(Regex("_[0-9]+$"), "")
        if (family == normalizedKey) return null
        return if (kind == null) {
            samples.firstOrNull { it.language == language && it.key == family }
        } else {
            samples.firstOrNull { it.language == language && it.key == family && it.kind == kind }
                ?: samples.firstOrNull { it.language == language && it.key == family }
        }
    }

    /** Canonical key used by manual section names, e.g. "Pre Chorus 2" -> "pre_chorus_2". */
    fun canonicalCueKey(value: String): String = canonicalKey(value)

    /** Language choice for manual Cue playback; intentionally independent of Native Guide Beta. */
    fun resolveCueLanguage(preferred: String = "auto"): String? {
        val available = status().languages
        if (available.isEmpty()) return null
        if (preferred != "auto" && preferred in available) return preferred
        val locale = Locale.getDefault().language.lowercase(Locale.ROOT)
        return available.firstOrNull { it == locale }
            ?: available.firstOrNull { it == "en" }
            ?: available.first()
    }

    /** Call after a backup restore replaces the private Guide-pack directory. */
    fun invalidateCache() {
        cachedSamples = null
        GuideCueAnalyzer.invalidateMemoryCache()
    }

    fun resolveOutputLanguage(preferred: String, detected: String?): String? {
        if (!NativeGuideFeatureGate.enabled) return null
        val available = status().languages
        if (available.isEmpty()) return null
        if (preferred != "auto" && preferred in available) return preferred
        if (detected != null && detected in available) return detected
        val locale = Locale.getDefault().language.lowercase(Locale.ROOT)
        return available.firstOrNull { it == locale }
            ?: available.firstOrNull { it == "en" }
            ?: available.first()
    }

    fun installZip(uri: Uri): InstallResult {
        val stage = File(context.cacheDir, "guide-pack-staging-${System.nanoTime()}")
        stage.deleteRecursively()
        stage.mkdirs()
        var installed = 0
        var skipped = 0
        var entries = 0
        var extractedBytes = 0L
        val used = mutableSetOf<String>()

        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: error("Could not open the selected Guide pack.")
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    require(entries <= MAX_ZIP_ENTRIES) { "Guide pack contains too many files." }
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    val parsed = parseEntry(entry.name)
                    if (parsed == null) {
                        skipped++
                        zip.closeEntry()
                        continue
                    }
                    val relative = "${parsed.language}/${parsed.kind.name.lowercase(Locale.ROOT)}/${parsed.key}.wav"
                    if (!used.add(relative)) {
                        skipped++
                        zip.closeEntry()
                        continue
                    }
                    val out = File(stage, relative)
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read <= 0) break
                            extractedBytes += read
                            require(extractedBytes <= MAX_EXTRACTED_BYTES) { "Guide pack expands beyond the configured safety limit." }
                            output.write(buffer, 0, read)
                        }
                    }
                    zip.closeEntry()
                    val valid = runCatching { WavMetadataReader.read(out) }.isSuccess
                    if (!valid) {
                        out.delete()
                        skipped++
                    } else {
                        installed++
                        require(installed <= MAX_GUIDE_SAMPLES) { "Guide pack contains too many Guide samples." }
                    }
                }
            }
            require(installed > 0) { "No compatible Guide WAV samples were found in this ZIP." }

            val sourceName = queryDisplayName(uri) ?: "Guide pack"
            File(stage, "source-name.txt").writeText(sourceName)
            val parent = root.parentFile ?: error("Invalid Guide pack storage path")
            parent.mkdirs()
            val backup = File(parent, "previous")
            backup.deleteRecursively()
            if (root.exists() && !root.renameTo(backup)) root.deleteRecursively()
            if (!stage.renameTo(root)) {
                root.mkdirs()
                stage.copyRecursively(root, overwrite = true)
                stage.deleteRecursively()
            }
            backup.deleteRecursively()
            cachedSamples = null
            GuideCueAnalyzer.invalidateMemoryCache()
            return InstallResult(status(), skipped)
        } catch (t: Throwable) {
            stage.deleteRecursively()
            throw t
        }
    }

    private data class ParsedEntry(val language: String, val key: String, val kind: CueKind)

    private fun parseEntry(path: String): ParsedEntry? {
        val normalizedPath = path.replace('\\', '/')
        val lower = normalizedPath.lowercase(Locale.ROOT)
        if (!lower.endsWith(".wav") || lower.startsWith("__macosx/") || "guides/" !in lower) return null

        val language = when {
            "spanish guides/" in lower -> "es"
            "english guides/" in lower -> "en"
            "french guides/" in lower -> "fr"
            "portugese guides/" in lower || "portuguese guides/" in lower -> "pt"
            else -> return null
        }
        val rawBase = File(normalizedPath).nameWithoutExtension
        val label = rawBase.substringAfterLast(" - ").trim()
        if (label.isBlank()) return null

        val isSongSection = "/song sections/" in lower
        val englishCanonical = if (language == "es" && isSongSection) {
            val inParentheses = Regex("\\(([^()]*)\\)\\s*$").find(label)?.groupValues?.getOrNull(1)?.trim()
            inParentheses?.takeIf { it.isNotBlank() } ?: label.substringBefore(" (").trim()
        } else {
            label.substringBefore(" (").trim()
        }

        val key = canonicalKey(englishCanonical)
        if (key.isBlank()) return null
        val kind = when {
            isSongSection && key.toIntOrNull() != null -> CueKind.COUNT
            isSongSection -> CueKind.SECTION
            "/dynamic cues/" in lower || "/guide cues/" in lower -> CueKind.DYNAMIC
            else -> return null
        }
        return ParsedEntry(language, key, kind)
    }

    private fun canonicalKey(value: String): String {
        val ascii = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace("a capella", "acapella")
        return ascii.replace(Regex("[^a-z0-9]+"), "_").trim('_')
    }

    private fun queryDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private companion object {
        const val MAX_ZIP_ENTRIES = 4_096
        const val MAX_GUIDE_SAMPLES = 2_048
        const val MAX_EXTRACTED_BYTES = 1024L * 1024L * 1024L
    }
}
