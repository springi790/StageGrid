package dev.stagegrid.guide

import java.io.File

/** Loads only short installed Guide samples; never use this for full song stems. */
object GuideCueLoader {
    fun loadMonoResampled(file: File, targetSampleRate: Int): FloatArray {
        require(targetSampleRate in 8_000..384_000) { "Invalid output sample rate." }
        val audio = GuideAudio.readMono(file)
        return GuideAudio.resampleLinear(audio, targetSampleRate)
    }
}
