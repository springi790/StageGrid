package dev.stagegrid.importer

enum class ImportStage {
    PREPARING,
    COPYING,
    PROCESSING_TRACK,
    DECODING_COMPRESSED_AUDIO,
    ANALYZING_CLICK,
    ANALYZING_GUIDE,
    RENDERING_GUIDE,
    BUILDING_SECTIONS,
    SAVING_LIBRARY,
    COMPLETE,
}

data class ImportProgress(
    val percent: Int,
    val stage: ImportStage,
    val detail: String? = null,
) {
    val fraction: Float get() = percent.coerceIn(0, 100) / 100f
}
