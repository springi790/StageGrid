package dev.stagegrid.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AudioDeviceManager(context: Context) {
    data class OutputDevice(
        val id: Int,
        val productName: String,
        val type: Int,
        val channelCounts: List<Int>,
        val sampleRates: List<Int>,
        val isUsb: Boolean,
        val isBluetooth: Boolean,
    ) {
        val maxChannelCount: Int
            get() = channelCounts.filter { it > 0 }.maxOrNull() ?: 2

        /** StageGrid 0.4 uses stereo-pair buses and therefore negotiates an even count up to 8. */
        val preferredStageGridChannels: Int
            get() = when {
                maxChannelCount >= 8 -> 8
                maxChannelCount >= 6 -> 6
                maxChannelCount >= 4 -> 4
                else -> 2
            }

        val supportsMultichannel: Boolean get() = preferredStageGridChannels > 2
    }

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val _outputs = MutableStateFlow<List<OutputDevice>>(emptyList())
    val outputs: StateFlow<List<OutputDevice>> = _outputs.asStateFlow()

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    init {
        audioManager.registerAudioDeviceCallback(callback, null)
        refresh()
    }

    fun refresh() {
        _outputs.value = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .map { info ->
                OutputDevice(
                    id = info.id,
                    productName = info.productName?.toString()?.ifBlank { "Android audio device" } ?: "Android audio device",
                    type = info.type,
                    channelCounts = info.channelCounts.toList(),
                    sampleRates = info.sampleRates.toList(),
                    isUsb = info.type in setOf(
                        AudioDeviceInfo.TYPE_USB_DEVICE,
                        AudioDeviceInfo.TYPE_USB_HEADSET,
                        AudioDeviceInfo.TYPE_USB_ACCESSORY,
                    ),
                    isBluetooth = info.type in setOf(
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        AudioDeviceInfo.TYPE_BLE_HEADSET,
                        AudioDeviceInfo.TYPE_BLE_SPEAKER,
                    ),
                )
            }
            .sortedWith(
                compareByDescending<OutputDevice> { it.isUsb }
                    .thenByDescending { it.preferredStageGridChannels }
                    .thenBy { it.productName },
            )
    }

    fun close() = audioManager.unregisterAudioDeviceCallback(callback)
}
