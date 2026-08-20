package dev.stagegrid.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StorageCacheManagerTest {
    @Test fun clearRegenerableCachesPreservesAudioAndGuideContent() {
        val appRoot = Files.createTempDirectory("stagegrid-storage-test").toFile()
        try {
            val filesDir = File(appRoot, "files").apply { mkdirs() }
            val songRoot = File(filesDir, "library/song-1")
            val audio = File(songRoot, "audio/track.wav").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(1024))
            }
            val waveform = File(songRoot, "cache/waveform-overview.sgpk").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(256))
            }
            val guide = File(filesDir, "guide-packs/current/en/SECTION/chorus.wav").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(512))
            }
            File(appRoot, "databases/stagegrid.db").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(128))
            }

            val manager = StorageCacheManager(filesDir)
            val before = manager.snapshot()
            assertEquals(1, before.songCount)
            assertTrue(before.playbackAudioBytes >= 1024L)
            assertTrue(before.regenerableCacheBytes >= 256L)
            assertTrue(before.guideBytes >= 512L)

            val reclaimed = manager.clearRegenerableCaches()
            val after = manager.snapshot()

            assertTrue(reclaimed >= 256L)
            assertTrue(audio.isFile)
            assertTrue(guide.isFile)
            assertFalse(waveform.exists())
            assertEquals(0L, after.regenerableCacheBytes)
            assertTrue(after.totalBytes < before.totalBytes)
        } finally {
            appRoot.deleteRecursively()
        }
    }
}
