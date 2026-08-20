package dev.stagegrid.guide

import dev.stagegrid.guide.GuidePackManager.CueKind
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

internal object GuideFingerprintDiskCache {
    data class Entry(
        val language: String,
        val key: String,
        val kind: CueKind,
        val absolutePath: String,
        val fileLength: Long,
        val lastModified: Long,
        val trimStartWindows: Int,
        val fingerprint: FloatArray,
    )

    fun read(file: File, expectedSignature: Long): List<Entry>? {
        if (!file.isFile) return null
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(file), 128 * 1024)).use { input ->
                if (input.readInt() != MAGIC) return@runCatching null
                if (input.readInt() != VERSION) return@runCatching null
                if (input.readLong() != expectedSignature) return@runCatching null
                val count = input.readInt()
                if (count !in 1..MAX_ENTRIES) return@runCatching null
                val entries = ArrayList<Entry>(count)
                repeat(count) {
                    val language = input.readUTF()
                    val key = input.readUTF()
                    val kind = CueKind.entries.getOrNull(input.readInt()) ?: return@runCatching null
                    val absolutePath = input.readUTF()
                    val fileLength = input.readLong()
                    val lastModified = input.readLong()
                    val trim = input.readInt()
                    val size = input.readInt()
                    if (size !in 4..MAX_FINGERPRINT_WINDOWS) return@runCatching null
                    val fingerprint = FloatArray(size) { input.readFloat() }
                    entries += Entry(
                        language = language,
                        key = key,
                        kind = kind,
                        absolutePath = absolutePath,
                        fileLength = fileLength,
                        lastModified = lastModified,
                        trimStartWindows = trim,
                        fingerprint = fingerprint,
                    )
                }
                entries
            }
        }.getOrNull()
    }

    fun write(file: File, signature: Long, entries: List<Entry>) {
        if (entries.isEmpty() || entries.size > MAX_ENTRIES) return
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".${file.name}.${System.nanoTime()}.tmp")
        runCatching {
            DataOutputStream(BufferedOutputStream(FileOutputStream(temp), 128 * 1024)).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeLong(signature)
                out.writeInt(entries.size)
                entries.forEach { entry ->
                    out.writeUTF(entry.language)
                    out.writeUTF(entry.key)
                    out.writeInt(entry.kind.ordinal)
                    out.writeUTF(entry.absolutePath)
                    out.writeLong(entry.fileLength)
                    out.writeLong(entry.lastModified)
                    out.writeInt(entry.trimStartWindows)
                    out.writeInt(entry.fingerprint.size)
                    entry.fingerprint.forEach(out::writeFloat)
                }
            }
            if (file.exists()) file.delete()
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        }.onFailure { temp.delete() }
    }

    private const val MAGIC = 0x53474650 // SGFP
    private const val VERSION = 1
    private const val MAX_ENTRIES = 4_096
    private const val MAX_FINGERPRINT_WINDOWS = 10_000
}
