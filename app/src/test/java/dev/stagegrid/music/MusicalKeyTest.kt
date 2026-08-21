package dev.stagegrid.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicalKeyTest {
    @Test
    fun transposeUsesSongKeyAsReference() {
        assertEquals("D", MusicalKey.transpose("C", 2f))
        assertEquals("B", MusicalKey.transpose("C", -1f))
        assertEquals("C#m", MusicalKey.transpose("Am", 4f))
    }

    @Test
    fun flatsAreCanonicalizedToSharpMenuNames() {
        assertEquals("A#", MusicalKey.transpose("Bb", 0f))
        assertEquals(-1, MusicalKey.semitoneDelta("C", "B"))
        assertEquals(2, MusicalKey.semitoneDelta("C", "D"))
    }

    @Test
    fun invalidMetadataDoesNotInventAKey() {
        assertNull(MusicalKey.parse("Unknown"))
        assertEquals(emptyList<String>(), MusicalKey.optionsFor(null))
    }
}
