package dev.stagegrid.model

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputBusTest {
    @Test fun storageCodesRemainStable() {
        assertEquals(OutputBus.OUT_1_2, OutputBus.fromStorage(0))
        assertEquals(OutputBus.OUT_3_4, OutputBus.fromStorage(1))
        assertEquals(OutputBus.OUT_5_6, OutputBus.fromStorage(2))
        assertEquals(OutputBus.OUT_7_8, OutputBus.fromStorage(3))
        assertEquals(OutputBus.OUT_1_2, OutputBus.fromStorage(99))
    }

    @Test fun availableBusesFollowNegotiatedChannelCount() {
        assertEquals(listOf(OutputBus.OUT_1_2), OutputBus.availableForChannels(2))
        assertEquals(listOf(OutputBus.OUT_1_2, OutputBus.OUT_3_4), OutputBus.availableForChannels(4))
        assertEquals(listOf(OutputBus.OUT_1_2, OutputBus.OUT_3_4, OutputBus.OUT_5_6), OutputBus.availableForChannels(6))
        assertEquals(OutputBus.entries.toList(), OutputBus.availableForChannels(8))
    }

    @Test fun physicalChannelPairsAreOneBasedForUi() {
        assertEquals(1, OutputBus.OUT_1_2.firstPhysicalChannel)
        assertEquals(2, OutputBus.OUT_1_2.lastPhysicalChannel)
        assertEquals(7, OutputBus.OUT_7_8.firstPhysicalChannel)
        assertEquals(8, OutputBus.OUT_7_8.lastPhysicalChannel)
    }
}
