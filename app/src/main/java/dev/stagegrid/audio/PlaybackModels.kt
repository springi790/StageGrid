package dev.stagegrid.audio

import dev.stagegrid.model.OutputBus
import dev.stagegrid.model.SectionEntity
import dev.stagegrid.model.SongEntity
import dev.stagegrid.model.StereoRoute
import dev.stagegrid.model.TrackEntity

enum class EngineState { IDLE, LOADING, READY, PLAYING, PAUSED, SEEKING, STOPPING, ERROR }

enum class ClickSubdivision(val subdivisionsPerBeat: Int, val label: String) {
    QUARTER(1, "1/4"),
    EIGHTH(2, "1/8"),
    TRIPLET(3, "1/8T"),
    SIXTEENTH(4, "1/16"),
}

data class PlayerState(
    val engineState: EngineState = EngineState.IDLE,
    val song: SongEntity? = null,
    val tracks: List<TrackEntity> = emptyList(),
    val sections: List<SectionEntity> = emptyList(),
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val clickEnabled: Boolean = true,
    val guideEnabled: Boolean = true,
    val clickSubdivision: ClickSubdivision = ClickSubdivision.QUARTER,
    val clickRoute: StereoRoute = StereoRoute.BOTH,
    val clickBus: OutputBus = OutputBus.OUT_1_2,
    val masterVolume: Float = 1f,
    val loopSectionId: String? = null,
    val queuedSectionId: String? = null,
    val queuedJumpAtMs: Long? = null,
    val countInBars: Int = 0,
    val countInRemainingMs: Long = 0L,
    val countInTargetSectionId: String? = null,
    val selectedOutputDeviceId: Int? = null,
    val requestedOutputChannels: Int = 2,
    val outputChannelCount: Int = 2,
    val outputFallback: Boolean = false,
    val outputNotice: String? = null,
    val errorMessage: String? = null,
) {
    val isPlaying: Boolean get() = engineState == EngineState.PLAYING
    val isCountingIn: Boolean get() = countInRemainingMs > 0L
    val currentSection: SectionEntity?
        get() = sections.lastOrNull { positionMs >= it.startMs && positionMs < it.endMs }
            ?: sections.lastOrNull { positionMs >= it.startMs }
    val nextSection: SectionEntity?
        get() {
            val current = currentSection ?: return sections.firstOrNull()
            return sections.getOrNull(sections.indexOfFirst { it.id == current.id } + 1)
        }
    val countInTargetSection: SectionEntity?
        get() = countInTargetSectionId?.let { id -> sections.firstOrNull { it.id == id } }
}
