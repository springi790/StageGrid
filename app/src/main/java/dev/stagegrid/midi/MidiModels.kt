package dev.stagegrid.midi

enum class MidiTransport {
    USB,
    BLUETOOTH,
    VIRTUAL,
    UNKNOWN,
}

data class MidiPortDescriptor(
    val number: Int,
    val name: String?,
)

data class MidiDeviceDescriptor(
    /** Android runtime id. Useful while connected, but not stable across reconnects. */
    val androidDeviceId: Int,
    /** Best-effort persistent identity used by future MIDI Learn mappings. */
    val stableKey: String,
    val name: String,
    val manufacturer: String?,
    val product: String?,
    val serialNumber: String?,
    val transport: MidiTransport,
    val inputPorts: List<MidiPortDescriptor>,
    val outputPorts: List<MidiPortDescriptor>,
)

/**
 * Android's MIDI device id can change after reconnect, so mappings must not persist that id alone.
 * A hardware serial is preferred when available; otherwise StageGrid falls back to normalized
 * manufacturer/product/name plus the port shape. Two identical serial-less devices can still be
 * ambiguous, which the MIDI Learn UI will surface instead of silently choosing one.
 */
fun buildStableMidiKey(
    transport: MidiTransport,
    manufacturer: String?,
    product: String?,
    name: String?,
    serialNumber: String?,
    inputPortCount: Int,
    outputPortCount: Int,
): String {
    val serial = serialNumber.normalizedMidiIdentityPart()
    val parts = buildList {
        add(transport.name.lowercase())
        add(manufacturer.normalizedMidiIdentityPart().ifBlank { "unknown-maker" })
        add(product.normalizedMidiIdentityPart().ifBlank { "unknown-product" })
        add(name.normalizedMidiIdentityPart().ifBlank { "midi-device" })
        if (serial.isNotBlank()) add("serial-$serial")
        add("in-${inputPortCount.coerceAtLeast(0)}")
        add("out-${outputPortCount.coerceAtLeast(0)}")
    }
    return parts.joinToString(":")
}

private fun String?.normalizedMidiIdentityPart(): String =
    this.orEmpty()
        .trim()
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
