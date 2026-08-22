package dev.stagegrid.metadata

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SongMetadataEnricherTest {
    @Test
    fun `glorioso dia package metadata accepts expanded iTunes credit`() = runBlocking {
        val provider = object : MetadataProvider {
            override suspend fun search(query: MetadataQuery): List<MetadataCandidate> {
                assertEquals("Glorioso día", query.title)
                assertEquals("Passion", query.artist)
                return listOf(
                    MetadataCandidate(
                        title = "Glorioso Día (feat. Kristian Stanfill)",
                        artist = "Passion & Kristian Stanfill",
                        album = "Glorioso Día",
                        durationMs = null,
                        artworkUrl = "https://example.invalid/cover.jpg",
                        provider = "iTunes",
                        providerId = "1443165166",
                        providerScore = 82,
                    ),
                )
            }
        }
        val root = createTempDir(prefix = "stagegrid-metadata-")
        try {
            val result = SongMetadataEnricher(provider).enrich(
                SongMetadataEnricher.Request(
                    songId = "glorioso-dia-test",
                    seedTitle = "Glorioso día (D)",
                    seedArtist = "Passion ft",
                    durationMs = 0L,
                    existingBpm = 110.5,
                    existingKey = "D",
                    tracks = emptyList(),
                    songRoot = root,
                    titleLocked = false,
                    artistLocked = false,
                    bpmLocked = true,
                    keyLocked = true,
                    onlineEnabled = true,
                    localAnalysisEnabled = false,
                    downloadArtwork = false,
                ),
            )

            assertEquals("Glorioso Día", result.title)
            assertEquals("Passion & Kristian Stanfill", result.artist)
            assertEquals(110.5, result.bpm!!, 0.001)
            assertEquals("D", result.musicalKey)
            assertTrue(result.onlineConfidence >= 0.82f)
        } finally {
            root.deleteRecursively()
        }
    }
}
