package dev.stagegrid.midi

import android.content.Context
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiDeviceStatus
import android.media.midi.MidiManager
import android.os.Handler
import android.os.Looper
import dev.stagegrid.debug.StageGridDebugLog
import java.io.Closeable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-level Android MIDI discovery.
 *
 * This layer only discovers devices and exposes stable descriptors. Opening ports, MIDI Learn and
 * clock transmission are intentionally built above it so device loss cannot disturb local audio.
 */
class MidiDeviceManager(context: Context) : Closeable {
    private val midiManager = context.getSystemService(MidiManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _devices = MutableStateFlow<List<MidiDeviceDescriptor>>(emptyList())
    val devices: StateFlow<List<MidiDeviceDescriptor>> = _devices.asStateFlow()

    private var started = false

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) {
            StageGridDebugLog.state("MIDI", "DEVICE_ADDED id=${device.id} type=${device.type}")
            refresh()
        }

        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            StageGridDebugLog.state("MIDI", "DEVICE_REMOVED id=${device.id} type=${device.type}")
            refresh()
        }

        override fun onDeviceStatusChanged(status: MidiDeviceStatus) {
            StageGridDebugLog.state("MIDI", "DEVICE_STATUS id=${status.deviceInfo.id}")
            refresh()
        }
    }

    fun start() {
        if (started) return
        started = true
        midiManager.registerDeviceCallback(callback, mainHandler)
        refresh()
        StageGridDebugLog.state("MIDI", "DISCOVERY_STARTED devices=${_devices.value.size}")
    }

    fun refresh() {
        val descriptors = midiManager.devices
            .map(::toDescriptor)
            .sortedWith(
                compareBy<MidiDeviceDescriptor> { it.transport.ordinal }
                    .thenBy { it.name.lowercase() }
                    .thenBy { it.stableKey },
            )
        _devices.value = descriptors
    }

    override fun close() {
        if (!started) return
        started = false
        midiManager.unregisterDeviceCallback(callback)
        _devices.value = emptyList()
        StageGridDebugLog.state("MIDI", "DISCOVERY_STOPPED")
    }

    private fun toDescriptor(info: MidiDeviceInfo): MidiDeviceDescriptor {
        val properties = info.properties
        val manufacturer = properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER)?.trim()?.ifBlank { null }
        val product = properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)?.trim()?.ifBlank { null }
        val explicitName = properties.getString(MidiDeviceInfo.PROPERTY_NAME)?.trim()?.ifBlank { null }
        val serial = properties.getString(MidiDeviceInfo.PROPERTY_SERIAL_NUMBER)?.trim()?.ifBlank { null }
        val transport = when (info.type) {
            MidiDeviceInfo.TYPE_USB -> MidiTransport.USB
            MidiDeviceInfo.TYPE_BLUETOOTH -> MidiTransport.BLUETOOTH
            MidiDeviceInfo.TYPE_VIRTUAL -> MidiTransport.VIRTUAL
            else -> MidiTransport.UNKNOWN
        }
        val inputPorts = info.ports
            .filter { it.type == MidiDeviceInfo.PortInfo.TYPE_INPUT }
            .map { MidiPortDescriptor(it.portNumber, it.name?.trim()?.ifBlank { null }) }
        val outputPorts = info.ports
            .filter { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }
            .map { MidiPortDescriptor(it.portNumber, it.name?.trim()?.ifBlank { null }) }
        val displayName = explicitName ?: product ?: manufacturer ?: "MIDI ${info.id}"
        val stableKey = buildStableMidiKey(
            transport = transport,
            manufacturer = manufacturer,
            product = product,
            name = explicitName,
            serialNumber = serial,
            inputPortCount = inputPorts.size,
            outputPortCount = outputPorts.size,
        )
        return MidiDeviceDescriptor(
            androidDeviceId = info.id,
            stableKey = stableKey,
            name = displayName,
            manufacturer = manufacturer,
            product = product,
            serialNumber = serial,
            transport = transport,
            inputPorts = inputPorts,
            outputPorts = outputPorts,
        )
    }
}
