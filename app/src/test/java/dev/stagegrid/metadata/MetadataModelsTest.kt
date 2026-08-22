package dev.stagegrid.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataModelsTest {
    @Test
    fun `normalization removes accents and noisy punctuation`() {
        assertEquals("al que esta sentado", MetadataText.normalize("Al Que Está Sentado!!!"))
    }

    @Test
    fun `folder hint splits title and artist`() {
        assertEquals(
            "Graves Into Gardens" to "Elevation Worship",
            MetadataText.splitTitleArtist("Graves Into Gardens - Elevation Worship.zip"),
        )
    }

    @Test
    fun `worship package hints remove key and dangling featured token`() {
        assertEquals("Glorioso día", MetadataText.cleanTitleHint("Glorioso día (D)"))
        assertEquals("Passion", MetadataText.cleanArtistHint("Passion ft"))
    }

    @Test
    fun `featured artist text does not pollute song title matching`() {
        assertEquals(
            "glorioso dia",
            MetadataText.normalize("Glorioso Día (feat. Kristian Stanfill)"),
        )
    }

    @Test
    fun `expanded artist credit contains package artist`() {
        val score = MetadataText.tokenContainmentSimilarity("Passion", "Passion & Kristian Stanfill")
        assertTrue(score > 0.95f)
    }

    @Test
    fun `token similarity tolerates punctuation and accents`() {
        val score = MetadataText.tokenSimilarity("Jesús, Mi Fiel Amigo", "Jesus Mi Fiel Amigo")
        assertTrue(score > 0.95f)
    }

    @Test
    fun `different songs have low similarity`() {
        val score = MetadataText.tokenSimilarity("Way Maker", "Graves Into Gardens")
        assertTrue(score < 0.25f)
    }
}
