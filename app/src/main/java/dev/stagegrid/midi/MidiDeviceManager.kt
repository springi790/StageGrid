package dev.stagegrid.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiDeviceStatus
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import dev.stagegrid.debug.StageGridDebugLog
import java.io.Closeable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-level Android MIDI discovery + one monitored receive connection.
 *
 * Android calls a hardware port that sends data *from* the device an OUTPUT port. StageGrid opens
 * that output as its input stream. The selected device is remembered by stable identity so a USB
 * disconnect/reconnect can reopen the equivalent port even when Android assigns a new runtime id.
 */
class MidiDeviceManager(context: Context) : Closeable {
    private val midiManager = context.getSystemService(MidiManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _devices = MutableStateFlow<List<MidiDeviceDescriptor>>(emptyList())
    val devices: StateFlow<List<MidiDeviceDescriptor>> = _devices.asStateFlow()
    private val _monitor = MutableStateFlow(MidiMonitorState())
    val monitor: StateFlow<MidiMonitorState> = _monitor.asStateFlow()

    private val decoder = MidiMessageDecoder()
    private val eventLock = Any()
    private var receivedMessages = 0L
    private var receivedClocks = 0L
    private var started = false
    private var desiredStableKey: String? = null
    private var desiredPortNumber: Int? = null
    private var preferredAndroidDeviceId: Int? = null
    private var openedDevice: MidiDevice? = null
    private var openedPort: MidiOutputPort? = null
    private var openedAndroidDeviceId: Int? = null
    private var openGeneration = 0L
    private var openInFlight = false

    private val receiver = object : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            val events = synchronized(decoder) { decoder.accept(msg, offset, count, timestamp) }
            events.forEach(::publishEvent)
        }
    }

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) {
            StageGridDebugLog.state("MIDI", "DEVICE_ADDED id=${device.id} type=${device.type}")
            refresh()
            maybeOpenDesired()
        }

        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            StageGridDebugLog.state("MIDI", "DEVICE_REMOVED id=${device.id} type=${device.type}")
            if (openedAndroidDeviceId == device.id || preferredAndroidDeviceId == device.id) {
                closeCurrentConnection(keepDesired = true)
                val current = _monitor.value
                _monitor.value = current.copy(
                    connectionState = if (desiredStableKey == null) MidiConnectionState.DISCONNECTED else MidiConnectionState.OPENING,
                    androidDeviceId = null,
                    error = null,
                )
            }
            refresh()
            maybeOpenDesired()
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

    /** Opens a hardware/virtual device output so StageGrid can receive its MIDI messages. */
    fun connectInput(device: MidiDeviceDescriptor, portNumber: Int) {
        require(device.outputPorts.any { it.number == portNumber }) { "MIDI receive port does not exist." }
        desiredStableKey = device.stableKey
        desiredPortNumber = portNumber
        preferredAndroidDeviceId = device.androidDeviceId
        receivedMessages = 0L
        receivedClocks = 0L
        StageGridDebugLog.action(
            "MIDI",
            "CONNECT_REQUEST device=${device.name} stableKey=${device.stableKey} androidId=${device.androidDeviceId} port=$portNumber",
        )
        closeCurrentConnection(keepDesired = true)
        _monitor.value = MidiMonitorState(
            connectionState = MidiConnectionState.OPENING,
            deviceStableKey = device.stableKey,
            deviceName = device.name,
            androidDeviceId = device.androidDeviceId,
            portNumber = portNumber,
        )
        maybeOpenDesired()
    }

    fun disconnectInput() {
        if (desiredStableKey != null || openedDevice != null) StageGridDebugLog.action("MIDI", "DISCONNECT")
        desiredStableKey = null
        desiredPortNumber = null
        preferredAndroidDeviceId = null
        closeCurrentConnection(keepDesired = false)
        receivedMessages = 0L
        receivedClocks = 0L
        _monitor.value = MidiMonitorState()
    }

    override fun close() {
        if (!started) return
        started = false
        midiManager.unregisterDeviceCallback(callback)
        disconnectInput()
        _devices.value = emptyList()
        StageGridDebugLog.state("MIDI", "DISCOVERY_STOPPED")
    }

    private fun maybeOpenDesired() {
        if (!started || openInFlight || openedDevice != null) return
        val stableKey = desiredStableKey ?: return
        val portNumber = desiredPortNumber ?: return
        val candidates = midiManager.devices
            .map { info -> info to toDescriptor(info) }
            .filter { (_, descriptor) -> descriptor.stableKey == stableKey && descriptor.outputPorts.any { it.number == portNumber } }
        if (candidates.isEmpty()) {
            val current = _monitor.value
            _monitor.value = current.copy(connectionState = MidiConnectionState.OPENING, androidDeviceId = null, error = null)
            return
        }
        if (candidates.size > 1) {
            StageGridDebugLog.warning("MIDI", "AMBIGUOUS_DEVICE stableKey=$stableKey matches=${candidates.size}")
        }
        val selected = candidates.firstOrNull { (info, _) -> info.id == preferredAndroidDeviceId } ?: candidates.first()
        val info = selected.first
        val descriptor = selected.second
        preferredAndroidDeviceId = info.id
        openInFlight = true
        val generation = ++openGeneration
        _monitor.value = _monitor.value.copy(
            connectionState = MidiConnectionState.OPENING,
            deviceName = descriptor.name,
            androidDeviceId = info.id,
            error = null,
        )
        StageGridDebugLog.state("MIDI", "OPEN_DEVICE id=${info.id} name=${descriptor.name} port=$portNumber")

        midiManager.openDevice(
            info,
            { device ->
                openInFlight = false
                if (generation != openGeneration || stableKey != desiredStableKey || portNumber != desiredPortNumber) {
                    runCatching { device?.close() }
                    return@openDevice
                }
                if (device == null) {
                    _monitor.value = _monitor.value.copy(
                        connectionState = MidiConnectionState.ERROR,
                        error = "Android could not open the MIDI device.",
                    )
                    StageGridDebugLog.error("MIDI", "OPEN_DEVICE failed id=${info.id}")
                    return@openDevice
                }

                val port = device.openOutputPort(portNumber)
                if (port == null) {
                    runCatching { device.close() }
                    _monitor.value = _monitor.value.copy(
                        connectionState = MidiConnectionState.ERROR,
                        error = "The selected MIDI receive port could not be opened.",
                    )
                    StageGridDebugLog.error("MIDI", "OPEN_PORT failed id=${info.id} port=$portNumber")
                    return@openDevice
                }

                synchronized(decoder) { decoder.reset() }
                port.connect(receiver)
                openedDevice = device
                openedPort = port
                openedAndroidDeviceId = info.id
                _monitor.value = _monitor.value.copy(
                    connectionState = MidiConnectionState.CONNECTED,
                    deviceStableKey = stableKey,
                    deviceName = descriptor.name,
                    androidDeviceId = info.id,
                    portNumber = portNumber,
                    error = null,
                )
                StageGridDebugLog.state("MIDI", "CONNECTED id=${info.id} name=${descriptor.name} port=$portNumber")
            },
            mainHandler,
        )
    }

    private fun closeCurrentConnection(keepDesired: Boolean) {
        openGeneration++
        openInFlight = false
        runCatching { openedPort?.disconnect(receiver) }
        runCatching { openedPort?.close() }
        runCatching { openedDevice?.close() }
        openedPort = null
        openedDevice = null
        openedAndroidDeviceId = null
        synchronized(decoder) { decoder.reset() }
        if (!keepDesired) {
            desiredStableKey = null
            desiredPortNumber = null
            preferredAndroidDeviceId = null
        }
    }

    private fun publishEvent(event: MidiMessageEvent) {
        synchronized(eventLock) {
            receivedMessages++
            if (event.kind == MidiMessageKind.CLOCK) {
                receivedClocks++
                // MIDI Clock runs at 24 PPQN. Update/log once per quarter note instead of 24 times.
                if (receivedClocks % 24L == 0L) {
                    _monitor.value = _monitor.value.copy(messageCount = receivedMessages, clockCount = receivedClocks)
                    StageGridDebugLog.state("MIDI", "CLOCK quarter=${receivedClocks / 24L} messages=$receivedMessages")
                }
                return
            }

            _monitor.value = _monitor.value.copy(
                lastMessage = event,
                messageCount = receivedMessages,
                clockCount = receivedClocks,
            )
            val detail = buildString {
                append("RX kind=${event.kind.name}")
                event.channel?.let { append(" ch=$it") }
                event.data1?.let { append(" d1=$it") }
                event.data2?.let { append(" d2=$it") }
            }
            when (event.kind) {
                MidiMessageKind.START, MidiMessageKind.CONTINUE, MidiMessageKind.STOP -> StageGridDebugLog.action("MIDI", detail)
                else -> StageGridDebugLog.state("MIDI", detail)
            }
        }
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
