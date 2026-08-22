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
