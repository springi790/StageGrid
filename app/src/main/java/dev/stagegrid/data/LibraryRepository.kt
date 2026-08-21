package dev.stagegrid.data

import androidx.room.withTransaction
import dev.stagegrid.debug.StageGridDebugLog
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

    suspend fun snapshot(): LibrarySnapshot = db.withTransaction {
        StageGridDebugLog.io("LIBRARY", "SNAPSHOT create")
        LibrarySnapshot(
            songs = db.songDao().getAll(),
            tracks = db.trackDao().getAll(),
            sections = db.sectionDao().getAll(),
            setlists = db.setlistDao().getAll(),
            setlistSongs = db.setlistSongDao().getAll(),
        )
    }

    suspend fun restoreSnapshot(snapshot: LibrarySnapshot) = db.withTransaction {
        StageGridDebugLog.io("LIBRARY", "SNAPSHOT restore songs=${snapshot.songs.size} tracks=${snapshot.tracks.size} sections=${snapshot.sections.size} setlists=${snapshot.setlists.size}")
        snapshot.songs.forEach { song ->
            db.trackDao().clearForSong(song.id)
            db.sectionDao().clearForSong(song.id)
        }
        snapshot.setlists.forEach { setlist -> db.setlistSongDao().clear(setlist.id) }
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
        StageGridDebugLog.io("IMPORT", "SAVE song=${song.id} tracks=${tracks.size} sections=${sections.size} bpm=${song.bpm} gridOffsetMs=${song.gridOffsetMs}")
        db.songDao().insert(song)
        db.trackDao().insertAll(tracks)
        db.sectionDao().insertAll(sections)
    }

    suspend fun updateSong(song: SongEntity) {
        StageGridDebugLog.action("LIBRARY", "UPDATE_SONG id=${song.id} title=${song.title} bpm=${song.bpm} gridOffsetMs=${song.gridOffsetMs}")
        db.songDao().update(song)
    }
    suspend fun updateTrack(track: TrackEntity) = db.trackDao().update(track)
    suspend fun saveTrack(track: TrackEntity) {
        StageGridDebugLog.io("LIBRARY", "SAVE_TRACK song=${track.songId} track=${track.id} type=${track.type}")
        db.trackDao().insertAll(listOf(track))
    }
    suspend fun saveSection(section: SectionEntity) {
        StageGridDebugLog.action("SECTIONS", "SAVE song=${section.songId} section=${section.id} name=${section.name} startMs=${section.startMs} endMs=${section.endMs}")
        db.sectionDao().insert(section)
    }
    suspend fun getSections(songId: String): List<SectionEntity> = db.sectionDao().getForSong(songId)

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
        StageGridDebugLog.io("SECTIONS", "REPLACE_PLACEHOLDER song=$songId sections=${sections.size}")
        db.sectionDao().clearForSong(songId)
        db.sectionDao().insertAll(sections)
        true
    }

    suspend fun replaceSectionsIfUnchanged(
        songId: String,
        expectedCurrent: List<SectionEntity>,
        replacements: List<SectionEntity>,
    ): Boolean = db.withTransaction {
        if (replacements.isEmpty()) return@withTransaction false
        val current = db.sectionDao().getForSong(songId)
        if (current != expectedCurrent) return@withTransaction false
        StageGridDebugLog.io("SECTIONS", "REPLACE song=$songId sections=${replacements.size}")
        db.sectionDao().clearForSong(songId)
        db.sectionDao().insertAll(replacements)
        true
    }

    suspend fun deleteSection(section: SectionEntity) {
        StageGridDebugLog.action("SECTIONS", "DELETE song=${section.songId} section=${section.id} name=${section.name}")
        db.sectionDao().delete(section)
    }
    suspend fun deleteSong(song: SongEntity) {
        StageGridDebugLog.action("LIBRARY", "DELETE_SONG id=${song.id} title=${song.title}")
        db.songDao().delete(song)
    }

    suspend fun createSetlist(name: String): SetlistEntity {
        val item = SetlistEntity(name = name.trim().ifBlank { "New Setlist" })
        StageGridDebugLog.action("SETLIST", "CREATE id=${item.id} name=${item.name}")
        db.setlistDao().insert(item)
        return item
    }

    suspend fun deleteSetlist(setlist: SetlistEntity) {
        StageGridDebugLog.action("SETLIST", "DELETE id=${setlist.id} name=${setlist.name}")
        db.setlistDao().delete(setlist)
    }

    suspend fun addSongToSetlist(setlistId: String, songId: String) {
        val nextOrder = db.setlistSongDao().nextSortOrder(setlistId)
        StageGridDebugLog.action("SETLIST", "ADD_SONG setlist=$setlistId song=$songId order=$nextOrder")
        db.setlistSongDao().insert(SetlistSongEntity(setlistId, songId, nextOrder))
    }

    suspend fun removeSongFromSetlist(setlistId: String, songId: String) {
        StageGridDebugLog.action("SETLIST", "REMOVE_SONG setlist=$setlistId song=$songId")
        db.setlistSongDao().remove(setlistId, songId)
    }

    suspend fun getSetlistBundle(setlistId: String): SetlistBundle? = db.withTransaction {
        val setlist = db.setlistDao().get(setlistId) ?: return@withTransaction null
        val entries = db.setlistSongDao().getEntries(setlistId)
        val songsById = entries.mapNotNull { db.songDao().get(it.songId) }
        SetlistBundle(setlist, songsById)
    }

    suspend fun markPlayed(songId: String) {
        val song = db.songDao().get(songId) ?: return
        StageGridDebugLog.state("LIBRARY", "MARK_PLAYED song=$songId count=${song.playCount + 1}")
        db.songDao().update(
            song.copy(
                playCount = song.playCount + 1,
                lastPlayedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }
}
