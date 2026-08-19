package dev.stagegrid.data

import androidx.room.withTransaction
import dev.stagegrid.model.SectionEntity
import dev.stagegrid.model.SetlistBundle
import dev.stagegrid.model.SetlistEntity
import dev.stagegrid.model.SetlistSongEntity
import dev.stagegrid.model.SongBundle
import dev.stagegrid.model.SongEntity
import dev.stagegrid.model.TrackEntity
import kotlinx.coroutines.flow.Flow

class LibraryRepository(private val db: StageGridDatabase) {
    val songs: Flow<List<SongEntity>> = db.songDao().observeAll()
    val setlists: Flow<List<SetlistEntity>> = db.setlistDao().observeAll()

    suspend fun getSong(songId: String): SongEntity? = db.songDao().get(songId)

    suspend fun getSongBundle(songId: String): SongBundle? = db.withTransaction {
        val song = db.songDao().get(songId) ?: return@withTransaction null
        SongBundle(song, db.trackDao().getForSong(songId), db.sectionDao().getForSong(songId))
    }

    suspend fun saveImportedSong(
        song: SongEntity,
        tracks: List<TrackEntity>,
        sections: List<SectionEntity>,
    ) = db.withTransaction {
        db.songDao().insert(song)
        db.trackDao().insertAll(tracks)
        db.sectionDao().insertAll(sections)
    }

    suspend fun updateSong(song: SongEntity) = db.songDao().update(song)

    suspend fun updateTrack(track: TrackEntity) = db.trackDao().update(track)

    suspend fun addSection(section: SectionEntity) = db.sectionDao().insert(section)

    suspend fun deleteSong(song: SongEntity) = db.songDao().delete(song)

    suspend fun createSetlist(name: String): SetlistEntity {
        val item = SetlistEntity(name = name.trim().ifBlank { "New Setlist" })
        db.setlistDao().insert(item)
        return item
    }

    suspend fun deleteSetlist(setlist: SetlistEntity) = db.setlistDao().delete(setlist)

    suspend fun addSongToSetlist(setlistId: String, songId: String) {
        val nextOrder = db.setlistSongDao().nextSortOrder(setlistId)
        db.setlistSongDao().insert(SetlistSongEntity(setlistId, songId, nextOrder))
    }

    suspend fun removeSongFromSetlist(setlistId: String, songId: String) =
        db.setlistSongDao().remove(setlistId, songId)

    suspend fun getSetlistBundle(setlistId: String): SetlistBundle? = db.withTransaction {
        val setlist = db.setlistDao().get(setlistId) ?: return@withTransaction null
        val entries = db.setlistSongDao().getEntries(setlistId)
        val songsById = entries.mapNotNull { db.songDao().get(it.songId) }
        SetlistBundle(setlist, songsById)
    }

    suspend fun markPlayed(songId: String) {
        val song = db.songDao().get(songId) ?: return
        db.songDao().update(
            song.copy(
                playCount = song.playCount + 1,
                lastPlayedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }
}
