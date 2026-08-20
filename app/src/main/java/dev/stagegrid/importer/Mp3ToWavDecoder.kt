package dev.stagegrid.importer

import java.io.File

/**
 * Compatibility facade for the pre-0.3 MP3-specific import path.
 *
 * New code should use [PlatformAudioToWavDecoder]. Keeping this facade avoids breaking callers while
 * MP3, M4A and AAC now share the same Android MediaExtractor/MediaCodec normalization pipeline.
 */
@Deprecated("Use PlatformAudioToWavDecoder for compressed import audio")
object Mp3ToWavDecoder {
    data class DecodeResult(
        val metadata: WavMetadataReader.Metadata,
        val encoderDelayFrames: Int,
        val encoderPaddingFrames: Int,
    )

    fun decode(
        source: File,
        destination: File,
        onProgress: ((Float) -> Unit)? = null,
    ): DecodeResult {
        val decoded = PlatformAudioToWavDecoder.decode(source, destination, onProgress)
        return DecodeResult(
            metadata = decoded.metadata,
            encoderDelayFrames = decoded.encoderDelayFrames,
            encoderPaddingFrames = decoded.encoderPaddingFrames,
        )
    }
}
