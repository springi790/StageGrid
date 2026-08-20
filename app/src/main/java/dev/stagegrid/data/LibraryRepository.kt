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

data class LibrarySnapshot(
    val songs: List<SongEntity>,
    val tracks: List<TrackEntity>,
    val sections: List<SectionEntity>,
    val setlists: List<SetlistEntity>,
    val setlistSongs: List<SetlistSongEntity>,
)

class LibraryRepository(private val db: StageGridDatabase) {
    val songs: Flow<List<SongEntity>> = db.songDao().observeAll()
    val setlists: Flow<List<SetlistEntity>> = db.setlistDao().observeAll()

    suspend fun getSong(songId: String): SongEntity? = db.songDao().get(songId)

    suspend fun getSongBundle(songId: String): SongBundle? = db.withTransaction {
        val song = db.songDao().get(songId) ?: return@withTransaction null
        SongBundle(song, db.trackDao().getForSong(songId), db.sectionDao().getForSong(songId))
    }

    /** Stable, transactionally consistent snapshot used by portable backups. */
    suspend fun snapshot(): LibrarySnapshot = db.withTransaction {
        LibrarySnapshot(
            songs = db.songDao().getAll(),
            tracks = db.trackDao().getAll(),
            sections = db.sectionDao().getAll(),
            setlists = db.setlistDao().getAll(),
            setlistSongs = db.setlistSongDao().getAll(),
        )
    }

    /**
     * Merges a validated portable snapshot by stable IDs. Matching songs/setlists are replaced
     * exactly (including their child rows), while unrelated local library records remain intact.
     */
    suspend fun restoreSnapshot(snapshot: LibrarySnapshot) = db.withTransaction {
        snapshot.songs.forEach { song ->
            db.trackDao().clearForSong(song.id)
            db.sectionDao().clearForSong(song.id)
        }
        snapshot.setlists.forEach { setlist ->
            db.setlistSongDao().clear(setlist.id)
        }
        db.songDao().insertAll(snapshot.songs)
        db.trackDao().insertAll(snapshot.tracks)
        db.sectionDao().insertAll(snapshot.sections)
        db.setlistDao().insertAll(snapshot.setlists)
        db.setlistSongDao().insertAll(snapshot.setlistSongs)
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

    /** Insert or replace a section. Section IDs are stable across manual edits. */
    suspend fun saveSection(section: SectionEntity) = db.sectionDao().insert(section)

    suspend fun getSections(songId: String): List<SectionEntity> = db.sectionDao().getForSong(songId)

    /**
     * Replaces only the untouched import fallback section. This prevents metadata edits from
     * overwriting a section map the user already created or adjusted manually.
     */
    suspend fun replacePlaceholderSections(
        songId: String,
        durationMs: Long,
        sections: List<SectionEntity>,
    ): Boolean = db.withTransaction {
        if (sections.isEmpty()) return@withTransaction false
        val current = db.sectionDao().getForSong(songId)
        val placeholder = current.size == 1 &&
            current[0].name == "Full Song" &&
            current[0].startMs == 0L &&
            current[0].endMs == durationMs
        if (!placeholder) return@withTransaction false
        db.sectionDao().clearForSong(songId)
        db.sectionDao().insertAll(sections)
        true
    }

    suspend fun deleteSection(section: SectionEntity) = db.sectionDao().delete(section)

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
