package dev.stagegrid.importer

import java.io.File
import java.io.RandomAccessFile

/** Lightweight RIFF/WAVE parser used during import. The C++ engine performs its own validation too. */
object WavMetadataReader {
    data class Metadata(
        val channels: Int,
        val sampleRate: Int,
        val bitDepth: Int,
        val durationMs: Long,
        val formatCode: Int,
    )

    fun read(file: File): Metadata {
        RandomAccessFile(file, "r").use { raf ->
            require(readFourCc(raf) == "RIFF") { "Not a RIFF WAV: ${file.name}" }
            readUInt32LE(raf) // RIFF size
            require(readFourCc(raf) == "WAVE") { "Not a WAVE file: ${file.name}" }

            var channels = 0
            var sampleRate = 0
            var bitDepth = 0
            var formatCode = 0
            var byteRate = 0L
            var dataSize = -1L

            while (raf.filePointer + 8 <= raf.length()) {
                val id = readFourCc(raf)
                val size = readUInt32LE(raf)
                val chunkStart = raf.filePointer
                when (id) {
                    "fmt " -> {
                        require(size >= 16) { "Invalid fmt chunk in ${file.name}" }
                        formatCode = readUInt16LE(raf)
                        channels = readUInt16LE(raf)
                        sampleRate = readUInt32LE(raf).toInt()
                        byteRate = readUInt32LE(raf)
                        readUInt16LE(raf) // block align
                        bitDepth = readUInt16LE(raf)
                        if (formatCode == 0xFFFE && size >= 40) {
                            raf.seek(chunkStart + 24)
                            formatCode = readUInt16LE(raf) // first two bytes of SubFormat GUID
                        }
                    }
                    "data" -> dataSize = size
                }
                val padded = size + (size and 1L)
                raf.seek((chunkStart + padded).coerceAtMost(raf.length()))
                if (channels > 0 && sampleRate > 0 && dataSize >= 0) break
            }

            require(formatCode == 1 || formatCode == 3) {
                "Unsupported WAV encoding $formatCode in ${file.name}; PCM or IEEE float required"
            }
            require(channels in 1..8 && sampleRate in 8_000..384_000 && bitDepth in setOf(8, 16, 24, 32)) {
                "Unsupported WAV format in ${file.name}"
            }
            require(dataSize >= 0 && byteRate > 0) { "Missing WAV data chunk in ${file.name}" }
            return Metadata(
                channels = channels,
                sampleRate = sampleRate,
                bitDepth = bitDepth,
                durationMs = (dataSize * 1000L / byteRate).coerceAtLeast(0),
                formatCode = formatCode,
            )
        }
    }

    private fun readFourCc(raf: RandomAccessFile): String {
        val b = ByteArray(4)
        raf.readFully(b)
        return b.toString(Charsets.US_ASCII)
    }

    private fun readUInt16LE(raf: RandomAccessFile): Int {
        val b0 = raf.readUnsignedByte()
        val b1 = raf.readUnsignedByte()
        return b0 or (b1 shl 8)
    }

    private fun readUInt32LE(raf: RandomAccessFile): Long {
        val b0 = raf.readUnsignedByte().toLong()
        val b1 = raf.readUnsignedByte().toLong()
        val b2 = raf.readUnsignedByte().toLong()
        val b3 = raf.readUnsignedByte().toLong()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }
}
