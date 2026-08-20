package dev.stagegrid.waveform

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Regenerable waveform overview cache.
 *
 * The cache is deliberately outside the realtime engine: it reads the app-private WAV files on an
 * I/O dispatcher, reduces them to bounded min/max peak buckets, and persists only the small peak
 * envelope. Deleting this cache never removes song audio and it can always be rebuilt.
 */
object WaveformPeakCache {
    private const val MAGIC = 0x5347504B // SGPK
    private const val VERSION = 1
    const val DEFAULT_BUCKETS = 1024
    const val CACHE_FILE_NAME = "waveform-overview.sgpk"

    data class PeakData(
        val durationMs: Long,
        val min: FloatArray,
        val max: FloatArray,
    ) {
        init {
            require(min.size == max.size) { "Waveform min/max bucket counts must match" }
        }

        val bucketCount: Int get() = min.size
    }

    fun cacheFile(songRoot: File): File = File(songRoot, "cache/$CACHE_FILE_NAME")

    fun loadOrGenerate(songRoot: File, targetBuckets: Int = DEFAULT_BUCKETS): PeakData {
        val audioDir = File(songRoot, "audio")
        val wavFiles = audioDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("wav", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
        require(wavFiles.isNotEmpty()) { "No playback WAV files are available for waveform generation" }

        val signature = sourceSignature(wavFiles)
        val cache = cacheFile(songRoot)
        read(cache, expectedSignature = signature)?.let { return it }

        val data = generate(wavFiles, targetBuckets)
        writeAtomic(cache, signature, data)
        return data
    }

    fun read(cache: File, expectedSignature: Long? = null): PeakData? {
        if (!cache.isFile) return null
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(cache), 64 * 1024)).use { input ->
                if (input.readInt() != MAGIC) return null
                if (input.readInt() != VERSION) return null
                val signature = input.readLong()
                if (expectedSignature != null && signature != expectedSignature) return null
                val durationMs = input.readLong().coerceAtLeast(0L)
                val bucketCount = input.readInt()
                if (bucketCount !in 1..16_384) return null
                val mins = FloatArray(bucketCount)
                val maxs = FloatArray(bucketCount)
                repeat(bucketCount) { index ->
                    mins[index] = input.readFloat().coerceIn(-1f, 1f)
                    maxs[index] = input.readFloat().coerceIn(-1f, 1f)
                }
                PeakData(durationMs, mins, maxs)
            }
        }.getOrNull()
    }

    fun generate(files: List<File>, targetBuckets: Int = DEFAULT_BUCKETS): PeakData {
        require(files.isNotEmpty()) { "Waveform generation requires at least one WAV" }
        require(targetBuckets in 64..16_384) { "Waveform bucket count is outside the supported range" }
        val infos = files.map { file -> file to parseWav(file) }
        val maxDurationUs = infos.maxOf { (_, info) ->
            ((info.frameCount * 1_000_000L) / info.sampleRate.coerceAtLeast(1)).coerceAtLeast(1L)
        }
        val globalMin = FloatArray(targetBuckets)
        val globalMax = FloatArray(targetBuckets)

        infos.forEach { (file, info) ->
            scan(
                file = file,
                info = info,
                buckets = targetBuckets,
                globalDurationUs = maxDurationUs,
                mins = globalMin,
                maxs = globalMax,
            )
        }

        return PeakData(
            durationMs = (maxDurationUs / 1000L).coerceAtLeast(1L),
            min = globalMin,
            max = globalMax,
        )
    }

    fun clear(songRoot: File): Long {
        val cacheDir = File(songRoot, "cache")
        val bytes = directorySize(cacheDir)
        if (cacheDir.exists()) cacheDir.deleteRecursively()
        return bytes
    }

    private fun writeAtomic(cache: File, signature: Long, data: PeakData) {
        cache.parentFile?.mkdirs()
        val temp = File(cache.parentFile, ".${cache.name}.tmp-${System.nanoTime()}")
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(temp), 64 * 1024)).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeLong(signature)
                output.writeLong(data.durationMs)
                output.writeInt(data.bucketCount)
                for (index in 0 until data.bucketCount) {
                    output.writeFloat(data.min[index])
                    output.writeFloat(data.max[index])
                }
                output.flush()
            }
            if (cache.exists() && !cache.delete()) error("Could not replace waveform cache")
            if (!temp.renameTo(cache)) {
                temp.copyTo(cache, overwrite = true)
                temp.delete()
            }
        } finally {
            temp.delete()
        }
    }

    private data class WavInfo(
        val formatCode: Int,
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val blockAlign: Int,
        val dataOffset: Long,
        val frameCount: Long,
    )

    private fun parseWav(file: File): WavInfo = RandomAccessFile(file, "r").use { raf ->
        require(readFourCc(raf) == "RIFF") { "Unsupported WAV container: ${file.name}" }
        readU32(raf)
        require(readFourCc(raf) == "WAVE") { "Unsupported WAV header: ${file.name}" }
        var formatCode = 0
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var blockAlign = 0
        var dataOffset = -1L
        var dataBytes = -1L

        while (raf.filePointer + 8 <= raf.length()) {
            val id = readFourCc(raf)
            val size = readU32(raf)
            val chunkStart = raf.filePointer
            when (id) {
                "fmt " -> {
                    require(size >= 16) { "Invalid WAV fmt chunk: ${file.name}" }
                    formatCode = readU16(raf)
                    channels = readU16(raf)
                    sampleRate = readU32(raf).toInt()
                    readU32(raf)
                    blockAlign = readU16(raf)
                    bitsPerSample = readU16(raf)
                    if (formatCode == 0xFFFE && size >= 40) {
                        raf.seek(chunkStart + 24)
                        formatCode = readU16(raf)
                    }
                }
                "data" -> {
                    dataOffset = chunkStart
                    dataBytes = minOf(size, (raf.length() - chunkStart).coerceAtLeast(0L))
                }
            }
            raf.seek((chunkStart + size + (size and 1L)).coerceAtMost(raf.length()))
            if (formatCode != 0 && dataOffset >= 0) break
        }

        require(formatCode in setOf(1, 3)) { "Unsupported WAV sample format: ${file.name}" }
        require(channels in 1..8 && sampleRate in 8_000..384_000) { "Invalid WAV stream: ${file.name}" }
        require(bitsPerSample in setOf(8, 16, 24, 32)) { "Unsupported WAV bit depth: ${file.name}" }
        require(formatCode != 3 || bitsPerSample == 32) { "Unsupported float WAV: ${file.name}" }
        require(blockAlign > 0 && dataBytes > 0) { "WAV contains no audio data: ${file.name}" }
        WavInfo(formatCode, channels, sampleRate, bitsPerSample, blockAlign, dataOffset, dataBytes / blockAlign)
    }

    private fun scan(
        file: File,
        info: WavInfo,
        buckets: Int,
        globalDurationUs: Long,
        mins: FloatArray,
        maxs: FloatArray,
    ) {
        val framesPerChunk = 4096
        val buffer = ByteArray(framesPerChunk * info.blockAlign)
        BufferedInputStream(FileInputStream(file), 256 * 1024).use { input ->
            skipFully(input, info.dataOffset)
            var frameIndex = 0L
            while (frameIndex < info.frameCount) {
                val requestedFrames = minOf(framesPerChunk.toLong(), info.frameCount - frameIndex).toInt()
                val requestedBytes = requestedFrames * info.blockAlign
                val readBytes = readFully(input, buffer, requestedBytes)
                val completeFrames = readBytes / info.blockAlign
                if (completeFrames <= 0) break
                var offset = 0
                repeat(completeFrames) {
                    val sample = decodeMonoFrame(buffer, offset, info)
                    val timeUs = (frameIndex * 1_000_000L) / info.sampleRate.coerceAtLeast(1)
                    val bucket = ((timeUs * buckets) / globalDurationUs.coerceAtLeast(1L))
                        .toInt()
                        .coerceIn(0, buckets - 1)
                    if (sample < mins[bucket]) mins[bucket] = sample
                    if (sample > maxs[bucket]) maxs[bucket] = sample
                    frameIndex++
                    offset += info.blockAlign
                }
                if (completeFrames < requestedFrames) break
            }
        }
    }

    private fun decodeMonoFrame(bytes: ByteArray, frameOffset: Int, info: WavInfo): Float {
        val bytesPerSample = (info.bitsPerSample + 7) / 8
        var sum = 0f
        for (channel in 0 until info.channels) {
            val offset = frameOffset + channel * bytesPerSample
            val value = when {
                info.formatCode == 3 && info.bitsPerSample == 32 -> {
                    Float.fromBits(littleU32(bytes, offset).toInt()).takeIf(Float::isFinite)?.coerceIn(-1f, 1f) ?: 0f
                }
                info.bitsPerSample == 8 -> ((bytes[offset].toInt() and 0xFF) - 128) / 128f
                info.bitsPerSample == 16 -> littleS16(bytes, offset) / 32768f
                info.bitsPerSample == 24 -> littleS24(bytes, offset) / 8388608f
                info.bitsPerSample == 32 -> littleU32(bytes, offset).toInt() / 2147483648f
                else -> 0f
            }
            sum += value
        }
        return (sum / info.channels).coerceIn(-1f, 1f)
    }

    private fun sourceSignature(files: List<File>): Long {
        var hash = 1125899906842597L
        files.forEach { file ->
            hash = hash * 31L + file.name.hashCode().toLong()
            hash = hash * 31L + file.length()
            hash = hash * 31L + file.lastModified()
        }
        return hash
    }

    private fun directorySize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf(::directorySize) ?: 0L
    }

    private fun readFully(input: BufferedInputStream, buffer: ByteArray, bytes: Int): Int {
        var offset = 0
        while (offset < bytes) {
            val read = input.read(buffer, offset, bytes - offset)
            if (read <= 0) break
            offset += read
        }
        return offset
    }

    private fun skipFully(input: BufferedInputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) remaining -= skipped
            else {
                if (input.read() < 0) throw EOFException("Unexpected end of WAV before audio data")
                remaining--
            }
        }
    }

    private fun littleS16(bytes: ByteArray, offset: Int): Int {
        val value = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
        return if (value and 0x8000 != 0) value - 0x10000 else value
    }

    private fun littleS24(bytes: ByteArray, offset: Int): Int {
        var value = (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16)
        if (value and 0x800000 != 0) value = value or -0x1000000
        return value
    }

    private fun littleU32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    private fun readFourCc(raf: RandomAccessFile): String {
        val bytes = ByteArray(4)
        raf.readFully(bytes)
        return bytes.toString(Charsets.US_ASCII)
    }

    private fun readU16(raf: RandomAccessFile): Int = raf.readUnsignedByte() or (raf.readUnsignedByte() shl 8)

    private fun readU32(raf: RandomAccessFile): Long {
        val b0 = raf.readUnsignedByte().toLong()
        val b1 = raf.readUnsignedByte().toLong()
        val b2 = raf.readUnsignedByte().toLong()
        val b3 = raf.readUnsignedByte().toLong()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }
}
