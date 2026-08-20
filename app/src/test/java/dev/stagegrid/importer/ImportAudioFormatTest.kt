package dev.stagegrid.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportAudioFormatTest {
    @Test fun recognizesPlayable03FormatsCaseInsensitively() {
        assertEquals(ImportAudioFormat.WAV, ImportAudioFormat.fromFileName("Drums.WAV"))
        assertEquals(ImportAudioFormat.MP3, ImportAudioFormat.fromFileName("Bass.Mp3"))
        assertEquals(ImportAudioFormat.M4A, ImportAudioFormat.fromFileName("Guide.M4A"))
        assertEquals(ImportAudioFormat.AAC, ImportAudioFormat.fromFileName("Click.AaC"))
    }

    @Test fun compressedPlatformFormatsNormalizeToWav() {
        listOf(ImportAudioFormat.MP3, ImportAudioFormat.M4A, ImportAudioFormat.AAC).forEach { format ->
            assertTrue(format.playable)
            assertTrue(format.normalizeToWav)
        }
        assertTrue(ImportAudioFormat.WAV.playable)
        assertFalse(ImportAudioFormat.WAV.normalizeToWav)
    }

    @Test fun flacAndOggRemainDetectedButNotPromotedToPlayableYet() {
        assertEquals(ImportAudioFormat.FLAC, ImportAudioFormat.fromFileName("Keys.flac"))
        assertEquals(ImportAudioFormat.OGG, ImportAudioFormat.fromFileName("Vox.ogg"))
        assertFalse(ImportAudioFormat.FLAC.playable)
        assertFalse(ImportAudioFormat.OGG.playable)
    }

    @Test fun unrelatedFilesAreIgnored() {
        assertNull(ImportAudioFormat.fromFileName("song.json"))
        assertNull(ImportAudioFormat.fromFileName("cover.jpg"))
    }
}
