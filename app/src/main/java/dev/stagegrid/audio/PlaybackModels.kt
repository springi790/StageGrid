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

/** Which Guide source is heard while the main Guide toggle is enabled. */
enum class GuideSource {
    /** Play the imported/rendered Guide track stored with the song. */
    ORIGINAL,
    /** Mute the imported Guide and synthesize section cues from the installed Guide Pack. */
    CUE,
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
    val guideSource: GuideSource = GuideSource.ORIGINAL,
    val clickSubdivision: ClickSubdivision = ClickSubdivision.QUARTER,
    val clickRoute: StereoRoute = StereoRoute.BOTH,
    val clickBus: OutputBus = OutputBus.OUT_1_2,
    val masterVolume: Float = 1f,
    val tempoRatio: Float = 1f,
    val pitchSemitones: Float = 0f,
    val dspActive: Boolean = false,
    val dspLatencyMs: Int = 0,
    val dspCpuLoad: Float = 0f,
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
    val preloadedSongId: String? = null,
    val preloadedSongTitle: String? = null,
    val crossfadeInProgress: Boolean = false,
    val errorMessage: String? = null,
) {
    val isPlaying: Boolean get() = engineState == EngineState.PLAYING
    val isCountingIn: Boolean get() = countInRemainingMs > 0L
    val hasPreloadedSong: Boolean get() = preloadedSongId != null
    val effectiveBpm: Double? get() = song?.bpm?.let { it * tempoRatio }
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
