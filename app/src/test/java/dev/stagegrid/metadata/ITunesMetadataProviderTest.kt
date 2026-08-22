package dev.stagegrid.metadata

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ITunesMetadataProviderTest {
    @Test
    fun `parses worship song metadata and artwork`() {
        val payload = JSONObject(
            """
            {
              "resultCount": 1,
              "results": [
                {
                  "wrapperType": "track",
                  "kind": "song",
                  "trackId": 1443165166,
                  "artistName": "Passion & Kristian Stanfill",
                  "collectionName": "Glorioso Día",
                  "trackName": "Glorioso Día",
                  "trackTimeMillis": 267000,
                  "artworkUrl100": "https://example.test/100x100bb.jpg"
                }
              ]
            }
            """.trimIndent(),
        )

        val result = ITunesMetadataProvider().parse(payload).single()
        assertEquals("Glorioso Día", result.title)
        assertEquals("Passion & Kristian Stanfill", result.artist)
        assertEquals("Glorioso Día", result.album)
        assertEquals(267000L, result.durationMs)
        assertEquals("iTunes", result.provider)
        assertEquals("1443165166", result.providerId)
        assertNotNull(result.artworkUrl)
    }
}
