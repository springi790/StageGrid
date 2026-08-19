package dev.stagegrid.importer

import dev.stagegrid.model.TrackType
import java.text.Normalizer
import java.util.Locale

object StemClassifier {
    fun classify(fileName: String): TrackType {
        val n = Normalizer.normalize(fileName.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
        val tokens = n.split(' ').filter { it.isNotBlank() }.toSet()

        return when {
            tokens.any { it in setOf("click", "clicks", "metronome", "metronomo") } -> TrackType.CLICK
            tokens.any { it in setOf("guide", "guides", "cue", "cues", "guia", "guias") } -> TrackType.GUIDE
            tokens.any { it in setOf("drum", "drums", "bateria", "kit") } -> TrackType.DRUMS
            tokens.any { it in setOf("bass", "bajo") } -> TrackType.BASS
            tokens.any { it in setOf("guitar", "guitars", "gtr", "guitarra", "guitarras", "acoustic", "electric") } -> TrackType.GUITAR
            tokens.any { it in setOf("keys", "key", "keyboard", "piano", "teclas") } -> TrackType.KEYS
            tokens.any { it in setOf("synth", "synths", "synthesizer") } -> TrackType.SYNTH
            tokens.any { it in setOf("strings", "string", "cuerdas") } -> TrackType.STRINGS
            tokens.any { it in setOf("vox", "vocal", "vocals", "bgv", "voice", "voices", "voz", "voces") } -> TrackType.VOCALS
            tokens.any { it in setOf("perc", "percussion", "percusion") } -> TrackType.PERCUSSION
            tokens.any { it in setOf("pad", "pads", "ambient", "atmosphere") } -> TrackType.PAD
            else -> TrackType.OTHER
        }
    }
}
