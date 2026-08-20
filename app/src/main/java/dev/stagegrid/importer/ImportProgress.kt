package dev.stagegrid.importer

enum class ImportStage {
    PREPARING,
    COPYING,
    PROCESSING_TRACK,
    /**
     * Legacy enum name retained for UI/source compatibility. In 0.3 this stage covers every
     * Android-platform compressed source normalized during import (MP3, M4A and AAC).
     */
    DECODING_MP3,
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
