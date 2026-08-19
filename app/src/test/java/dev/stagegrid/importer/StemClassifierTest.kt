package dev.stagegrid.importer

import dev.stagegrid.model.TrackType
import org.junit.Assert.assertEquals
import org.junit.Test

class StemClassifierTest {
    @Test fun detectsCoreStemNamesInEnglishAndSpanish() {
        assertEquals(TrackType.CLICK, StemClassifier.classify("01 Click.wav"))
        assertEquals(TrackType.GUIDE, StemClassifier.classify("02 Guía.wav"))
        assertEquals(TrackType.DRUMS, StemClassifier.classify("03 Batería.wav"))
        assertEquals(TrackType.BASS, StemClassifier.classify("04 Bajo.wav"))
        assertEquals(TrackType.GUITAR, StemClassifier.classify("05 EG Guitar 1.wav"))
        assertEquals(TrackType.KEYS, StemClassifier.classify("06 Piano Keys.wav"))
        assertEquals(TrackType.VOCALS, StemClassifier.classify("07 BGV Vox.wav"))
        assertEquals(TrackType.PERCUSSION, StemClassifier.classify("08 Percusión.wav"))
    }

    @Test fun unknownNameDoesNotInventAType() {
        assertEquals(TrackType.OTHER, StemClassifier.classify("Mystery Stem.wav"))
    }
}
