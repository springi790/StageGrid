package dev.stagegrid.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stagegrid.R
import dev.stagegrid.StageGridApplication
import dev.stagegrid.midi.MidiConnectionState
import dev.stagegrid.midi.MidiDeviceDescriptor
import dev.stagegrid.midi.MidiMessageEvent
import dev.stagegrid.midi.MidiMessageKind
import dev.stagegrid.midi.MidiTransport
import dev.stagegrid.ui.components.StageGridMetric
import dev.stagegrid.ui.components.StageGridPanel
import dev.stagegrid.ui.components.StageGridPill
import dev.stagegrid.ui.theme.StageGridColors

@Composable
fun MidiMonitorPanelHost(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as StageGridApplication
    val devices by app.midiDevices.devices.collectAsStateWithLifecycle()
    val monitor by app.midiDevices.monitor.collectAsStateWithLifecycle()

    StageGridPanel(modifier = modifier, accent = StageGridColors.Violet) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.midi_settings_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        stringResource(R.string.midi_settings_desc),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StageGridPill(
                    text = when (monitor.connectionState) {
                        MidiConnectionState.CONNECTED -> stringResource(R.string.midi_connected)
                        MidiConnectionState.OPENING -> stringResource(R.string.midi_opening)
                        MidiConnectionState.ERROR -> stringResource(R.string.midi_error)
                        MidiConnectionState.DISCONNECTED -> stringResource(R.string.midi_disconnected)
                    },
                    accent = when (monitor.connectionState) {
                        MidiConnectionState.CONNECTED -> StageGridColors.Mint
                        MidiConnectionState.OPENING -> StageGridColors.Amber
                        MidiConnectionState.ERROR -> MaterialTheme.colorScheme.error
                        MidiConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            if (devices.isEmpty()) {
                Text(stringResource(R.string.midi_no_devices), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                devices.forEach { device ->
                    MidiDeviceRow(
                        device = device,
                        connectedStableKey = monitor.deviceStableKey,
                        connectedPort = monitor.portNumber,
                        connectionState = monitor.connectionState,
                        onConnect = { port -> app.midiDevices.connectInput(device, port) },
                    )
                }
            }

            if (monitor.connectionState != MidiConnectionState.DISCONNECTED) {
                OutlinedButton(
                    onClick = app.midiDevices::disconnectInput,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.midi_disconnect))
                }
            }

            Text(stringResource(R.string.midi_monitor_title), fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                StageGridMetric(
                    stringResource(R.string.midi_messages),
                    monitor.messageCount.toString(),
                    Modifier.weight(1f),
                    MaterialTheme.colorScheme.primary,
                )
                StageGridMetric(
                    stringResource(R.string.midi_clock_pulses),
                    monitor.clockCount.toString(),
                    Modifier.weight(1f),
                    StageGridColors.Amber,
                )
            }
            val last = monitor.lastMessage
            Text(
                if (last == null) stringResource(R.string.midi_monitor_waiting)
                else stringResource(R.string.midi_monitor_last, describeMidi(last)),
                color = if (last == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            monitor.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun MidiDeviceRow(
    device: MidiDeviceDescriptor,
    connectedStableKey: String?,
    connectedPort: Int?,
    connectionState: MidiConnectionState,
    onConnect: (Int) -> Unit,
) {
    val transportText = transportLabel(device.transport)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(device.name, fontWeight = FontWeight.Bold)
                Text(
                    if (device.manufacturer.isNullOrBlank()) transportText else "$transportText · ${device.manufacturer}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (connectedStableKey == device.stableKey && connectionState == MidiConnectionState.CONNECTED) {
                StageGridPill(stringResource(R.string.midi_connected), StageGridColors.Mint)
            }
        }

        if (device.outputPorts.isEmpty()) {
            Text(
                stringResource(R.string.midi_device_no_receive_ports),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(stringResource(R.string.midi_receive_ports), fontWeight = FontWeight.SemiBold)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                device.outputPorts.forEach { port ->
                    val selected = connectedStableKey == device.stableKey && connectedPort == port.number
                    FilterChip(
                        selected = selected,
                        onClick = { onConnect(port.number) },
                        label = {
                            Text(port.name ?: stringResource(R.string.midi_port_number, port.number + 1))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun transportLabel(transport: MidiTransport): String = when (transport) {
    MidiTransport.USB -> stringResource(R.string.midi_transport_usb)
    MidiTransport.BLUETOOTH -> stringResource(R.string.midi_transport_bluetooth)
    MidiTransport.VIRTUAL -> stringResource(R.string.midi_transport_virtual)
    MidiTransport.UNKNOWN -> stringResource(R.string.midi_transport_unknown)
}

private fun describeMidi(event: MidiMessageEvent): String = when (event.kind) {
    MidiMessageKind.NOTE_ON -> "Note On · CH ${event.channel} · ${noteLabel(event.data1)} · VEL ${event.data2 ?: 0}"
    MidiMessageKind.NOTE_OFF -> "Note Off · CH ${event.channel} · ${noteLabel(event.data1)}"
    MidiMessageKind.CONTROL_CHANGE -> "CC · CH ${event.channel} · ${event.data1 ?: 0} = ${event.data2 ?: 0}"
    MidiMessageKind.PROGRAM_CHANGE -> "Program · CH ${event.channel} · ${event.data1 ?: 0}"
    MidiMessageKind.PITCH_BEND -> {
        val raw = ((event.data2 ?: 0) shl 7) or (event.data1 ?: 0)
        "Pitch Bend · CH ${event.channel} · ${raw - 8192}"
    }
    MidiMessageKind.POLY_PRESSURE -> "Poly Pressure · CH ${event.channel} · ${event.data1 ?: 0} = ${event.data2 ?: 0}"
    MidiMessageKind.CHANNEL_PRESSURE -> "Channel Pressure · CH ${event.channel} · ${event.data1 ?: 0}"
    MidiMessageKind.SONG_POSITION -> {
        val value = ((event.data2 ?: 0) shl 7) or (event.data1 ?: 0)
        "Song Position · $value"
    }
    MidiMessageKind.START -> "Start"
    MidiMessageKind.CONTINUE -> "Continue"
    MidiMessageKind.STOP -> "Stop"
    MidiMessageKind.CLOCK -> "Clock"
    MidiMessageKind.SYSEX -> "SysEx"
    MidiMessageKind.SYSTEM -> "System 0x${event.status.toString(16).uppercase()}"
    MidiMessageKind.UNKNOWN -> "MIDI 0x${event.status.toString(16).uppercase()}"
}

private fun noteLabel(note: Int?): String {
    val value = note ?: return "—"
    val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    return "${names[value.coerceIn(0, 127) % 12]}${value.coerceIn(0, 127) / 12 - 1} ($value)"
}
