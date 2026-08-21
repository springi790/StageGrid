package dev.stagegrid.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MidiModelsTest {
    @Test
    fun reconnectWithSameMetadataKeepsStableKey() {
        val first = buildStableMidiKey(
            transport = MidiTransport.USB,
            manufacturer = "Example Audio",
            product = "Foot Controller",
            name = "Foot Controller MIDI",
            serialNumber = "ABC-123",
            inputPortCount = 1,
            outputPortCount = 1,
        )
        val second = buildStableMidiKey(
            transport = MidiTransport.USB,
            manufacturer = " Example Audio ",
            product = "Foot Controller",
            name = "Foot Controller MIDI",
            serialNumber = "ABC-123",
            inputPortCount = 1,
            outputPortCount = 1,
        )

        assertEquals(first, second)
    }

    @Test
    fun differentSerialsDoNotCollapseToSameMappingIdentity() {
        val first = buildStableMidiKey(
            transport = MidiTransport.USB,
            manufacturer = "Example Audio",
            product = "Foot Controller",
            name = "Foot Controller MIDI",
            serialNumber = "ABC-123",
            inputPortCount = 1,
            outputPortCount = 1,
        )
        val second = buildStableMidiKey(
            transport = MidiTransport.USB,
            manufacturer = "Example Audio",
            product = "Foot Controller",
            name = "Foot Controller MIDI",
            serialNumber = "ABC-124",
            inputPortCount = 1,
            outputPortCount = 1,
        )

        assertNotEquals(first, second)
    }

    @Test
    fun serialLessIdentityIncludesPortShape() {
        val onePort = buildStableMidiKey(
            transport = MidiTransport.USB,
            manufacturer = "Example Audio",
            product = "Controller",
            name = "Controller",
            serialNumber = null,
            inputPortCount = 1,
            outputPortCount = 1,
        )
        val fourPorts = buildStableMidiKey(
            transport = MidiTransport.USB,
            manufacturer = "Example Audio",
            product = "Controller",
            name = "Controller",
            serialNumber = null,
            inputPortCount = 4,
            outputPortCount = 4,
        )

        assertNotEquals(onePort, fourPorts)
    }
}
