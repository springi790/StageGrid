package dev.stagegrid.ui

import androidx.lifecycle.viewModelScope
import dev.stagegrid.StageGridApplication
import dev.stagegrid.audio.AudioDeviceManager
import dev.stagegrid.model.OutputBus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class PreferredOutputSignature(
    val productName: String,
    val type: Int,
    val requestedChannels: Int,
)

private var preferredOutputSignature: PreferredOutputSignature? = null

private fun StageGridViewModel.stageGridApp(): StageGridApplication = getApplication()

fun StageGridViewModel.setTrackOutputBus(index: Int, bus: OutputBus) {
    stageGridApp().audio.setTrackOutputBus(index, bus)
}

fun StageGridViewModel.setClickBus(bus: OutputBus) {
    stageGridApp().audio.setClickOutputBus(bus)
    viewModelScope.launch { stageGridApp().settings.setClickBus(bus) }
}

fun StageGridViewModel.applyPersistedClickBus(bus: OutputBus) {
    stageGridApp().audio.setClickOutputBus(bus)
}

fun StageGridViewModel.setMultichannelOutputDevice(id: Int, requestedChannels: Int) {
    outputs.value.firstOrNull { it.id == id }?.let { device ->
        preferredOutputSignature = PreferredOutputSignature(
            productName = device.productName,
            type = device.type,
            requestedChannels = device.preferredStageGridChannels,
        )
    }
    stageGridApp().audio.setOutputDevice(id, requestedChannels)
}

fun StageGridViewModel.testOutputChannel(channelIndex: Int) {
    stageGridApp().audio.testOutputChannel(channelIndex)
}

fun StageGridViewModel.handleOutputDevicesChanged(devices: List<AudioDeviceManager.OutputDevice>) {
    stageGridApp().audio.handleOutputDevicesChanged(devices)
    val preferred = preferredOutputSignature ?: return
    viewModelScope.launch {
        // Allow the controller's safe disconnect fallback to settle first. If Android assigned a
        // new AudioDeviceInfo.id on reconnect, restore by stable user-visible device identity.
        delay(180)
        if (player.value.selectedOutputDeviceId != null) return@launch
        val match = devices.firstOrNull {
            it.productName == preferred.productName && it.type == preferred.type
        } ?: return@launch
        stageGridApp().audio.setOutputDevice(
            match.id,
            minOf(preferred.requestedChannels, match.preferredStageGridChannels),
        )
    }
}
