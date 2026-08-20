package dev.stagegrid.ui

import androidx.lifecycle.viewModelScope
import dev.stagegrid.StageGridApplication
import dev.stagegrid.audio.AudioDeviceManager
import dev.stagegrid.model.OutputBus
import kotlinx.coroutines.launch

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
    stageGridApp().audio.setOutputDevice(id, requestedChannels)
}

fun StageGridViewModel.testOutputChannel(channelIndex: Int) {
    stageGridApp().audio.testOutputChannel(channelIndex)
}

fun StageGridViewModel.handleOutputDevicesChanged(devices: List<AudioDeviceManager.OutputDevice>) {
    stageGridApp().audio.handleOutputDevicesChanged(devices)
}
