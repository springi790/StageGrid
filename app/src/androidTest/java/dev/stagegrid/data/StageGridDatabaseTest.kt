package dev.stagegrid.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stagegrid.model.SectionEntity
import dev.stagegrid.model.SetlistEntity
import dev.stagegrid.model.SetlistSongEntity
import dev.stagegrid.model.SongEntity
import dev.stagegrid.model.TrackEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StageGridDatabaseTest {
    @Test
    fun storesSongTracksAndSectionsSeparately() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, StageGridDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val song = SongEntity(title = "Database Test", bpm = 72.0, musicalKey = "C")
            db.songDao().insert(song)
            db.trackDao().insertAll(
                listOf(
                    TrackEntity(
                        songId = song.id,
                        name = "Drums",
                        filePath = "/tmp/drums.wav",
                        channels = 2,
                        sampleRate = 48_000,
                        bitDepth = 24,
                        durationMs = 60_000,
                        sortOrder = 0,
                    ),
                ),
            )
            db.sectionDao().insertAll(
                listOf(SectionEntity(songId = song.id, name = "Intro", startMs = 0, endMs = 10_000, sortOrder = 0)),
            )

            assertNotNull(db.songDao().get(song.id))
            assertEquals(1, db.trackDao().getForSong(song.id).size)
            assertEquals("Intro", db.sectionDao().getForSong(song.id).single().name)
        } finally {
            db.close()
        }
    }
    @Test
    fun nextSetlistOrderUsesMaxInsteadOfEntryCount() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, StageGridDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val setlist = SetlistEntity(name = "Order Test")
            val songA = SongEntity(id = "song-a", title = "A")
            val songB = SongEntity(id = "song-b", title = "B")
            db.setlistDao().insert(setlist)
            db.songDao().insert(songA)
            db.songDao().insert(songB)
            db.setlistSongDao().insert(SetlistSongEntity(setlist.id, songA.id, 0))
            db.setlistSongDao().insert(SetlistSongEntity(setlist.id, songB.id, 2))

            assertEquals(3, db.setlistSongDao().nextSortOrder(setlist.id))
        } finally {
            db.close()
        }
    }

}
