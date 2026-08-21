package dev.stagegrid.music

/**
 * Small pitch-class helper for live transposition controls.
 *
 * Song metadata remains the source of truth. The live selector canonicalizes enharmonic roots to
 * sharps for a compact 12-note menu, while preserving a minor-mode suffix when the stored key is
 * clearly minor (for example Am, A minor, C#m).
 */
object MusicalKey {
    private val sharpNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val pitchClasses = mapOf(
        "C" to 0,
        "B#" to 0,
        "C#" to 1,
        "DB" to 1,
        "D" to 2,
        "D#" to 3,
        "EB" to 3,
        "E" to 4,
        "FB" to 4,
        "E#" to 5,
        "F" to 5,
        "F#" to 6,
        "GB" to 6,
        "G" to 7,
        "G#" to 8,
        "AB" to 8,
        "A" to 9,
        "A#" to 10,
        "BB" to 10,
        "B" to 11,
        "CB" to 11,
    )

    data class Parsed(val pitchClass: Int, val minor: Boolean)

    fun parse(value: String?): Parsed? {
        val normalized = value
            ?.trim()
            ?.replace('♯', '#')
            ?.replace('♭', 'b')
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val match = Regex("^([A-Ga-g])([#b]?)(.*)$").find(normalized) ?: return null
        val root = (match.groupValues[1].uppercase() + match.groupValues[2]).uppercase()
        val pitchClass = pitchClasses[root] ?: return null
        val suffix = match.groupValues[3].trim().lowercase()
        val minor = suffix == "m" || suffix.startsWith("min") || suffix.startsWith("minor")
        return Parsed(pitchClass = pitchClass, minor = minor)
    }

    fun optionsFor(baseValue: String?): List<String> {
        val parsed = parse(baseValue) ?: return emptyList()
        val suffix = if (parsed.minor) "m" else ""
        return sharpNames.map { "$it$suffix" }
    }

    fun transpose(baseValue: String?, semitones: Float): String? {
        val parsed = parse(baseValue) ?: return null
        val target = floorMod(parsed.pitchClass + kotlin.math.round(semitones).toInt(), 12)
        return sharpNames[target] + if (parsed.minor) "m" else ""
    }

    /** Returns the shortest chromatic movement from [baseValue] to [targetValue]. */
    fun semitoneDelta(baseValue: String?, targetValue: String?): Int? {
        val base = parse(baseValue) ?: return null
        val target = parse(targetValue) ?: return null
        var delta = target.pitchClass - base.pitchClass
        if (delta > 6) delta -= 12
        if (delta < -6) delta += 12
        return delta
    }

    private fun floorMod(value: Int, modulus: Int): Int = ((value % modulus) + modulus) % modulus
}
