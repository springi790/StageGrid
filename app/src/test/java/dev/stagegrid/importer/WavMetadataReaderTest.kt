package dev.stagegrid.importer

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavMetadataReaderTest {
    @Test fun readsPcm16StereoMetadata() {
        val file = File.createTempFile("stagegrid-metadata-", ".wav")
        try {
            file.writeBytes(makePcm16Wav(sampleRate = 48_000, channels = 2, frames = 48_000))
            val metadata = WavMetadataReader.read(file)
            assertEquals(2, metadata.channels)
            assertEquals(48_000, metadata.sampleRate)
            assertEquals(16, metadata.bitDepth)
            assertEquals(1_000, metadata.durationMs)
            assertEquals(1, metadata.formatCode)
        } finally {
            file.delete()
        }
    }

    private fun makePcm16Wav(sampleRate: Int, channels: Int, frames: Int): ByteArray {
        val bytesPerSample = 2
        val dataSize = frames * channels * bytesPerSample
        val out = ByteArrayOutputStream(44 + dataSize)
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun le16(v: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
        fun le32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        ascii("RIFF"); le32(36 + dataSize); ascii("WAVE")
        ascii("fmt "); le32(16); le16(1); le16(channels); le32(sampleRate)
        le32(sampleRate * channels * bytesPerSample); le16(channels * bytesPerSample); le16(16)
        ascii("data"); le32(dataSize)
        out.write(ByteArray(dataSize))
        return out.toByteArray()
    }
}
