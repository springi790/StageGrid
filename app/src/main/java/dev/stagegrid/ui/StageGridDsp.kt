package dev.stagegrid.ui

import androidx.lifecycle.viewModelScope
import dev.stagegrid.StageGridApplication
import dev.stagegrid.audio.NativeAudioEngine
import dev.stagegrid.debug.StageGridDebugLog
import java.util.IdentityHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class StageGridDspUiState(
    val songId: String? = null,
    val tempoRatio: Float = 1f,
    val pitchSemitones: Float = 0f,
    val active: Boolean = false,
    val latencyMs: Int = 0,
    val dspCpuLoad: Float = 0f,
    val applying: Boolean = false,
    val error: String? = null,
) {
    val tempoPercent: Int get() = (tempoRatio * 100f).toInt()
}

private class StageGridDspController(private val viewModel: StageGridViewModel) {
    private val app: StageGridApplication = viewModel.getApplication()
    private val applyMutex = Mutex()
    private val _state = MutableStateFlow(StageGridDspUiState())
    val state: StateFlow<StageGridDspUiState> = _state.asStateFlow()

    init {
        viewModel.viewModelScope.launch {
            viewModel.player
                .map { it.song?.id }
                .distinctUntilChanged()
                .collect { songId ->
                    if (songId == null) {
                        _state.value = StageGridDspUiState()
                    } else {
                        refreshFromNative(songId)
                    }
                }
        }
        viewModel.viewModelScope.launch {
            while (true) {
                delay(DSP_DIAGNOSTICS_INTERVAL_MS)
                val current = _state.value
                if (current.songId != null && current.active && !current.applying) {
                    refreshFromNative(current.songId, keepError = true)
                }
            }
        }
    }

    fun setTempoRatio(value: Float) {
        val songId = viewModel.player.value.song?.id ?: return
        apply(songId, "tempo") {
            app.nativeAudio.setTempoRatio(value.coerceIn(NativeAudioEngine.MIN_TEMPO_RATIO, NativeAudioEngine.MAX_TEMPO_RATIO))
        }
    }

    fun setPitchSemitones(value: Float) {
        val songId = viewModel.player.value.song?.id ?: return
        apply(songId, "pitch") {
            app.nativeAudio.setPitchSemitones(
                value.coerceIn(NativeAudioEngine.MIN_PITCH_SEMITONES, NativeAudioEngine.MAX_PITCH_SEMITONES),
            )
        }
    }

    fun reset() {
        val songId = viewModel.player.value.song?.id ?: return
        apply(songId, "reset") { app.nativeAudio.resetDsp() }
    }

    private fun apply(
        songId: String,
        operation: String,
        block: () -> NativeAudioEngine.Diagnostics,
    ) {
        viewModel.viewModelScope.launch {
            applyMutex.withLock {
                if (viewModel.player.value.song?.id != songId) return@withLock
                _state.value = _state.value.copy(songId = songId, applying = true, error = null)
                try {
                    val diagnostics = withContext(Dispatchers.Default) { block() }
                    if (viewModel.player.value.song?.id == songId) {
                        _state.value = diagnostics.toUiState(songId = songId, applying = false)
                    }
                } catch (t: Throwable) {
                    StageGridDebugLog.error(
                        "DSP",
                        "APPLY_FAILED operation=$operation song=$songId message=${t.message ?: t::class.java.simpleName}",
                        t,
                    )
                    if (viewModel.player.value.song?.id == songId) {
                        _state.value = _state.value.copy(
                            songId = songId,
                            applying = false,
                            error = t.message ?: "DSP change failed.",
                        )
                    }
                }
            }
        }
    }

    private suspend fun refreshFromNative(songId: String, keepError: Boolean = false) {
        val diagnostics = withContext(Dispatchers.Default) { app.nativeAudio.diagnostics() }
        if (viewModel.player.value.song?.id != songId) return
        val previousError = _state.value.error
        _state.value = diagnostics.toUiState(songId).copy(error = if (keepError) previousError else null)
    }

    private fun NativeAudioEngine.Diagnostics.toUiState(
        songId: String,
        applying: Boolean = false,
    ): StageGridDspUiState = StageGridDspUiState(
        songId = songId,
        tempoRatio = tempoRatio.coerceIn(NativeAudioEngine.MIN_TEMPO_RATIO, NativeAudioEngine.MAX_TEMPO_RATIO),
        pitchSemitones = pitchSemitones.coerceIn(
            NativeAudioEngine.MIN_PITCH_SEMITONES,
            NativeAudioEngine.MAX_PITCH_SEMITONES,
        ),
        active = dspActive,
        latencyMs = dspLatencyMs.coerceAtLeast(0),
        dspCpuLoad = dspCpuLoad.coerceAtLeast(0f),
        applying = applying,
        error = lastError.takeIf { it.isNotBlank() },
    )

    private companion object {
        const val DSP_DIAGNOSTICS_INTERVAL_MS = 750L
    }
}

private object StageGridDspRegistry {
    private val controllers = IdentityHashMap<StageGridViewModel, StageGridDspController>()

    @Synchronized
    fun controller(viewModel: StageGridViewModel): StageGridDspController =
        controllers.getOrPut(viewModel) { StageGridDspController(viewModel) }
}

val StageGridViewModel.dspState: StateFlow<StageGridDspUiState>
    get() = StageGridDspRegistry.controller(this).state

fun StageGridViewModel.setTempoRatio(value: Float) = StageGridDspRegistry.controller(this).setTempoRatio(value)
fun StageGridViewModel.setPitchSemitones(value: Float) = StageGridDspRegistry.controller(this).setPitchSemitones(value)
fun StageGridViewModel.resetDsp() = StageGridDspRegistry.controller(this).reset()
