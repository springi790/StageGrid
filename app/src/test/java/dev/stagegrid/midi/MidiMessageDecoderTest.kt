package dev.stagegrid.midi

import org.junit.Assert.assertEquals
import org.junit.Test

class MidiMessageDecoderTest {
    @Test
    fun decodesNoteOnAndVelocityZeroAsNoteOff() {
        val decoder = MidiMessageDecoder()
        val events = decoder.accept(
            byteArrayOf(0x90.toByte(), 60, 100, 0x90.toByte(), 60, 0),
        )

        assertEquals(2, events.size)
        assertEquals(MidiMessageKind.NOTE_ON, events[0].kind)
        assertEquals(1, events[0].channel)
        assertEquals(60, events[0].data1)
        assertEquals(100, events[0].data2)
        assertEquals(MidiMessageKind.NOTE_OFF, events[1].kind)
    }

    @Test
    fun preservesRunningStatusAcrossChunks() {
        val decoder = MidiMessageDecoder()
        val first = decoder.accept(byteArrayOf(0xB2.toByte(), 7, 99))
        val second = decoder.accept(byteArrayOf(10, 64, 11, 65))

        assertEquals(1, first.size)
        assertEquals(MidiMessageKind.CONTROL_CHANGE, first.single().kind)
        assertEquals(3, first.single().channel)
        assertEquals(2, second.size)
        assertEquals(10, second[0].data1)
        assertEquals(64, second[0].data2)
        assertEquals(11, second[1].data1)
        assertEquals(65, second[1].data2)
    }

    @Test
    fun realtimeClockDoesNotBreakRunningStatus() {
        val decoder = MidiMessageDecoder()
        val events = decoder.accept(
            byteArrayOf(
                0x90.toByte(), 60, 100,
                0xF8.toByte(),
                61, 101,
                0xFA.toByte(),
                62, 102,
                0xFC.toByte(),
            ),
        )

        assertEquals(6, events.size)
        assertEquals(MidiMessageKind.NOTE_ON, events[0].kind)
        assertEquals(MidiMessageKind.CLOCK, events[1].kind)
        assertEquals(MidiMessageKind.NOTE_ON, events[2].kind)
        assertEquals(61, events[2].data1)
        assertEquals(MidiMessageKind.START, events[3].kind)
        assertEquals(MidiMessageKind.NOTE_ON, events[4].kind)
        assertEquals(62, events[4].data1)
        assertEquals(MidiMessageKind.STOP, events[5].kind)
    }

    @Test
    fun decodesProgramChangeAndPitchBend() {
        val decoder = MidiMessageDecoder()
        val events = decoder.accept(
            byteArrayOf(
                0xC4.toByte(), 12,
                0xE4.toByte(), 0, 64,
            ),
        )

        assertEquals(2, events.size)
        assertEquals(MidiMessageKind.PROGRAM_CHANGE, events[0].kind)
        assertEquals(5, events[0].channel)
        assertEquals(12, events[0].data1)
        assertEquals(MidiMessageKind.PITCH_BEND, events[1].kind)
        assertEquals(0, events[1].data1)
        assertEquals(64, events[1].data2)
    }
}
