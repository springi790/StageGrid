package dev.stagegrid.midi

/** Stateful MIDI 1.0 byte-stream decoder with running-status and realtime-byte support. */
class MidiMessageDecoder {
    private var runningStatus: Int = -1
    private var pendingStatus: Int = -1
    private var pendingExpected: Int = 0
    private val pendingData = IntArray(2)
    private var pendingCount: Int = 0
    private var inSysEx: Boolean = false

    fun reset() {
        runningStatus = -1
        pendingStatus = -1
        pendingExpected = 0
        pendingCount = 0
        inSysEx = false
    }

    fun accept(
        bytes: ByteArray,
        offset: Int = 0,
        count: Int = bytes.size - offset,
        timestampNanos: Long = 0L,
    ): List<MidiMessageEvent> {
        if (count <= 0 || offset !in bytes.indices) return emptyList()
        val end = (offset + count).coerceAtMost(bytes.size)
        val output = ArrayList<MidiMessageEvent>(4)

        for (index in offset until end) {
            val value = bytes[index].toInt() and 0xFF

            // MIDI realtime bytes can appear between any other bytes and never disturb running status.
            if (value >= 0xF8) {
                output += eventFor(value, null, null, timestampNanos)
                continue
            }

            if (inSysEx) {
                if (value == 0xF7) {
                    output += MidiMessageEvent(MidiMessageKind.SYSEX, 0xF0, timestampNanos = timestampNanos)
                    inSysEx = false
                }
                continue
            }

            if (value and 0x80 != 0) {
                if (value == 0xF0) {
                    inSysEx = true
                    pendingStatus = -1
                    pendingCount = 0
                    runningStatus = -1
                    continue
                }

                pendingStatus = value
                pendingExpected = dataLength(value)
                pendingCount = 0
                if (value < 0xF0) runningStatus = value else runningStatus = -1
                if (pendingExpected == 0) {
                    output += eventFor(value, null, null, timestampNanos)
                    pendingStatus = -1
                }
                continue
            }

            if (pendingStatus < 0) {
                if (runningStatus < 0) continue
                pendingStatus = runningStatus
                pendingExpected = dataLength(runningStatus)
                pendingCount = 0
            }

            if (pendingCount < pendingData.size) pendingData[pendingCount] = value and 0x7F
            pendingCount++
            if (pendingCount >= pendingExpected) {
                val d1 = pendingData.getOrNull(0)?.takeIf { pendingExpected >= 1 }
                val d2 = pendingData.getOrNull(1)?.takeIf { pendingExpected >= 2 }
                output += eventFor(pendingStatus, d1, d2, timestampNanos)
                pendingStatus = -1
                pendingCount = 0
            }
        }
        return output
    }

    private fun dataLength(status: Int): Int = when (status and 0xF0) {
        0x80, 0x90, 0xA0, 0xB0, 0xE0 -> 2
        0xC0, 0xD0 -> 1
        else -> when (status) {
            0xF1, 0xF3 -> 1
            0xF2 -> 2
            0xF6, 0xF7 -> 0
            else -> 0
        }
    }

    private fun eventFor(status: Int, data1: Int?, data2: Int?, timestampNanos: Long): MidiMessageEvent {
        if (status < 0xF0) {
            val channel = (status and 0x0F) + 1
            val kind = when (status and 0xF0) {
                0x80 -> MidiMessageKind.NOTE_OFF
                0x90 -> if ((data2 ?: 0) == 0) MidiMessageKind.NOTE_OFF else MidiMessageKind.NOTE_ON
                0xA0 -> MidiMessageKind.POLY_PRESSURE
                0xB0 -> MidiMessageKind.CONTROL_CHANGE
                0xC0 -> MidiMessageKind.PROGRAM_CHANGE
                0xD0 -> MidiMessageKind.CHANNEL_PRESSURE
                0xE0 -> MidiMessageKind.PITCH_BEND
                else -> MidiMessageKind.UNKNOWN
            }
            return MidiMessageEvent(kind, status, channel, data1, data2, timestampNanos)
        }

        val kind = when (status) {
            0xF2 -> MidiMessageKind.SONG_POSITION
            0xF8 -> MidiMessageKind.CLOCK
            0xFA -> MidiMessageKind.START
            0xFB -> MidiMessageKind.CONTINUE
            0xFC -> MidiMessageKind.STOP
            else -> MidiMessageKind.SYSTEM
        }
        return MidiMessageEvent(kind, status, data1 = data1, data2 = data2, timestampNanos = timestampNanos)
    }
}
