package dev.stagegrid.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportAudioFormatTest {
    @Test fun recognizes03FormatsCaseInsensitively() {
        assertEquals(ImportAudioFormat.WAV, ImportAudioFormat.fromFileName("Drums.WAV"))
        assertEquals(ImportAudioFormat.MP3, ImportAudioFormat.fromFileName("Bass.Mp3"))
        assertEquals(ImportAudioFormat.M4A, ImportAudioFormat.fromFileName("Guide.M4A"))
        assertEquals(ImportAudioFormat.AAC, ImportAudioFormat.fromFileName("Click.AaC"))
        assertEquals(ImportAudioFormat.FLAC, ImportAudioFormat.fromFileName("Keys.FLaC"))
        assertEquals(ImportAudioFormat.OGG, ImportAudioFormat.fromFileName("Vox.OgG"))
    }

    @Test fun everyNonWavFormatUsesImportTimeNormalization() {
        ImportAudioFormat.entries.filter { it != ImportAudioFormat.WAV }.forEach { format ->
            assertTrue(format.playable)
            assertTrue(format.normalizeToWav)
        }
        assertTrue(ImportAudioFormat.WAV.playable)
        assertFalse(ImportAudioFormat.WAV.normalizeToWav)
    }

    @Test fun detectedAndPlayableSetsCoverAll03Formats() {
        val expected = setOf("wav", "mp3", "m4a", "aac", "flac", "ogg")
        assertEquals(expected, ImportAudioFormat.detectedExtensions)
        assertEquals(expected, ImportAudioFormat.playableExtensions)
    }

    @Test fun unrelatedFilesAreIgnored() {
        assertNull(ImportAudioFormat.fromFileName("song.json"))
        assertNull(ImportAudioFormat.fromFileName("cover.jpg"))
    }
}
