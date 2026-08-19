package dev.stagegrid.importer

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Offline MP3 -> PCM WAV normalizer used only during import.
 *
 * The real-time engine intentionally never decodes MP3 in the Oboe callback. Compressed sources
 * are normalized once into the private StageGrid library so playback remains deterministic and
 * filesystem/codec work stays away from the audio thread.
 */
object Mp3ToWavDecoder {
    private const val TIMEOUT_US = 10_000L
    private const val MIME_MP3 = "audio/mpeg"
    private const val KEY_ENCODER_DELAY_COMPAT = "encoder-delay"
    private const val KEY_ENCODER_PADDING_COMPAT = "encoder-padding"
    private const val COPY_BUFFER = 128 * 1024
    private const val MAX_RIFF_DATA_BYTES = 0xFFFF_FFFFL - 36L

    data class DecodeResult(
        val metadata: WavMetadataReader.Metadata,
        val encoderDelayFrames: Int,
        val encoderPaddingFrames: Int,
    )

    fun decode(source: File, destination: File): DecodeResult {
        require(source.isFile) { "MP3 source does not exist: ${source.name}" }
        destination.parentFile?.mkdirs()

        val pcmTemp = File(destination.parentFile, ".${destination.name}.decode-${System.nanoTime()}.pcm")
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(source.absolutePath)
            val trackIndex = findMp3Track(extractor)
            require(trackIndex >= 0) { "No MP3 audio track was found in ${source.name}" }
            extractor.selectTrack(trackIndex)

            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: error("MP3 track has no MIME type")
            require(mime == MIME_MP3 || mime.startsWith("audio/")) {
                "Unsupported MP3 MIME type: $mime"
            }

            var sampleRate = inputFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 0
            var channels = inputFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 0
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            val encoderDelay = inputFormat.intOrNull(KEY_ENCODER_DELAY_COMPAT)?.coerceAtLeast(0) ?: 0
            val encoderPadding = inputFormat.intOrNull(KEY_ENCODER_PADDING_COMPAT)?.coerceAtLeast(0) ?: 0

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            var inputEnded = false
            var outputEnded = false
            val info = MediaCodec.BufferInfo()

            BufferedOutputStream(FileOutputStream(pcmTemp), COPY_BUFFER).use { pcmOut ->
                while (!outputEnded) {
                    if (!inputEnded) {
                        val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputIndex)
                                ?: error("Decoder returned a null input buffer")
                            inputBuffer.clear()
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputEnded = true
                            } else {
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    sampleSize,
                                    extractor.sampleTime.coerceAtLeast(0L),
                                    extractor.sampleFlags,
                                )
                                extractor.advance()
                            }
                        }
                    }

                    when (val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outputFormat = codec.outputFormat
                            sampleRate = outputFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: sampleRate
                            channels = outputFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: channels
                            pcmEncoding = outputFormat.intOrNull(MediaFormat.KEY_PCM_ENCODING)
                                ?: AudioFormat.ENCODING_PCM_16BIT
                        }

                        MediaCodec.INFO_TRY_AGAIN_LATER,
                        MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit

                        else -> if (outputIndex >= 0) {
                            if (info.size > 0) {
                                val outputBuffer = codec.getOutputBuffer(outputIndex)
                                    ?: error("Decoder returned a null output buffer")
                                outputBuffer.position(info.offset)
                                outputBuffer.limit(info.offset + info.size)
                                writeAsPcm16(outputBuffer.slice(), pcmEncoding, pcmOut)
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }

            require(sampleRate in 8_000..384_000) { "Unsupported decoded MP3 sample rate: $sampleRate Hz" }
            require(channels in 1..8) { "Unsupported decoded MP3 channel count: $channels" }

            val bytesPerFrame = channels * 2L
            val totalFrames = pcmTemp.length() / bytesPerFrame
            val startTrim = encoderDelay.toLong().coerceAtMost(totalFrames)
            val remainingAfterStart = (totalFrames - startTrim).coerceAtLeast(0)
            val endTrim = encoderPadding.toLong().coerceAtMost(remainingAfterStart)
            val keptFrames = (remainingAfterStart - endTrim).coerceAtLeast(0)
            val dataBytes = keptFrames * bytesPerFrame
            require(dataBytes > 0) { "MP3 decoder produced no audible PCM frames for ${source.name}" }
            require(dataBytes <= MAX_RIFF_DATA_BYTES) {
                "Decoded MP3 is too large for the StageGrid RIFF/WAV cache"
            }

            writePcm16Wave(
                pcmTemp = pcmTemp,
                destination = destination,
                sampleRate = sampleRate,
                channels = channels,
                startByte = startTrim * bytesPerFrame,
                dataBytes = dataBytes,
            )

            val metadata = WavMetadataReader.read(destination)
            return DecodeResult(
                metadata = metadata,
                encoderDelayFrames = startTrim.toInt(),
                encoderPaddingFrames = endTrim.toInt(),
            )
        } catch (t: Throwable) {
            destination.delete()
            throw t
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            pcmTemp.delete()
        }
    }

    private fun findMp3Track(extractor: MediaExtractor): Int {
        var firstAudio = -1
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime == MIME_MP3) return index
            if (firstAudio < 0 && mime.startsWith("audio/")) firstAudio = index
        }
        return firstAudio
    }

    private fun writeAsPcm16(
        buffer: ByteBuffer,
        encoding: Int,
        output: BufferedOutputStream,
    ) {
        when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> {
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                output.write(bytes)
            }

            AudioFormat.ENCODING_PCM_FLOAT -> {
                buffer.order(ByteOrder.nativeOrder())
                val pair = ByteArray(2)
                while (buffer.remaining() >= 4) {
                    val sample = buffer.float.coerceIn(-1f, 1f)
                    val pcm = (sample * 32767f).roundToInt().coerceIn(-32768, 32767)
                    pair[0] = (pcm and 0xFF).toByte()
                    pair[1] = ((pcm ushr 8) and 0xFF).toByte()
                    output.write(pair)
                }
            }

            else -> error("Android MP3 decoder returned unsupported PCM encoding $encoding")
        }
    }

    private fun writePcm16Wave(
        pcmTemp: File,
        destination: File,
        sampleRate: Int,
        channels: Int,
        startByte: Long,
        dataBytes: Long,
    ) {
        BufferedOutputStream(FileOutputStream(destination), COPY_BUFFER).use { output ->
            writeWavHeader(output, sampleRate, channels, dataBytes)
            BufferedInputStream(FileInputStream(pcmTemp), COPY_BUFFER).use { input ->
                skipFully(input, startByte)
                var remaining = dataBytes
                val buffer = ByteArray(COPY_BUFFER)
                while (remaining > 0) {
                    val requested = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = input.read(buffer, 0, requested)
                    if (read < 0) error("Decoded MP3 PCM cache ended unexpectedly")
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
    }

    private fun writeWavHeader(
        output: BufferedOutputStream,
        sampleRate: Int,
        channels: Int,
        dataBytes: Long,
    ) {
        val byteRate = sampleRate.toLong() * channels * 2L
        val blockAlign = channels * 2
        val riffSize = 36L + dataBytes
        output.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeUInt32Le(output, riffSize)
        output.write("WAVE".toByteArray(Charsets.US_ASCII))
        output.write("fmt ".toByteArray(Charsets.US_ASCII))
        writeUInt32Le(output, 16)
        writeUInt16Le(output, 1) // PCM
        writeUInt16Le(output, channels)
        writeUInt32Le(output, sampleRate.toLong())
        writeUInt32Le(output, byteRate)
        writeUInt16Le(output, blockAlign)
        writeUInt16Le(output, 16)
        output.write("data".toByteArray(Charsets.US_ASCII))
        writeUInt32Le(output, dataBytes)
    }

    private fun writeUInt16Le(output: BufferedOutputStream, value: Int) {
        output.write(value and 0xFF)
        output.write((value ushr 8) and 0xFF)
    }

    private fun writeUInt32Le(output: BufferedOutputStream, value: Long) {
        output.write((value and 0xFF).toInt())
        output.write(((value ushr 8) and 0xFF).toInt())
        output.write(((value ushr 16) and 0xFF).toInt())
        output.write(((value ushr 24) and 0xFF).toInt())
    }

    private fun skipFully(input: BufferedInputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (input.read() < 0) error("Decoded MP3 PCM cache ended while trimming encoder delay")
                remaining--
            }
        }
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null
}
