package dev.stagegrid.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.stagegrid.model.SectionEntity
import dev.stagegrid.model.SetlistEntity
import dev.stagegrid.model.SetlistSongEntity
import dev.stagegrid.model.SongEntity
import dev.stagegrid.model.TrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Query("SELECT * FROM songs ORDER BY favorite DESC, importedAtEpochMs DESC")
    fun observeAll(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs ORDER BY importedAtEpochMs, id")
    suspend fun getAll(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE id = :id LIMIT 1")
    suspend fun get(id: String): SongEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(song: SongEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(songs: List<SongEntity>)

    @Update
    suspend fun update(song: SongEntity)

    @Delete
    suspend fun delete(song: SongEntity)
}

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY songId, sortOrder, id")
    suspend fun getAll(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE songId = :songId ORDER BY sortOrder")
    fun observeForSong(songId: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE songId = :songId ORDER BY sortOrder")
    suspend fun getForSong(songId: String): List<TrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<TrackEntity>)

    @Update
    suspend fun update(track: TrackEntity)
}

@Dao
interface SectionDao {
    @Query("SELECT * FROM sections ORDER BY songId, sortOrder, startMs, id")
    suspend fun getAll(): List<SectionEntity>

    @Query("SELECT * FROM sections WHERE songId = :songId ORDER BY sortOrder, startMs")
    fun observeForSong(songId: String): Flow<List<SectionEntity>>

    @Query("SELECT * FROM sections WHERE songId = :songId ORDER BY sortOrder, startMs")
    suspend fun getForSong(songId: String): List<SectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sections: List<SectionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(section: SectionEntity)

    @Query("DELETE FROM sections WHERE songId = :songId")
    suspend fun clearForSong(songId: String)

    @Delete
    suspend fun delete(section: SectionEntity)
}

@Dao
interface SetlistDao {
    @Query("SELECT * FROM setlists ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<SetlistEntity>>

    @Query("SELECT * FROM setlists ORDER BY createdAtEpochMs, id")
    suspend fun getAll(): List<SetlistEntity>

    @Query("SELECT * FROM setlists WHERE id = :id LIMIT 1")
    suspend fun get(id: String): SetlistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(setlist: SetlistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(setlists: List<SetlistEntity>)

    @Update
    suspend fun update(setlist: SetlistEntity)

    @Delete
    suspend fun delete(setlist: SetlistEntity)
}

@Dao
interface SetlistSongDao {
    @Query("SELECT * FROM setlist_songs ORDER BY setlistId, sortOrder, songId")
    suspend fun getAll(): List<SetlistSongEntity>

    @Query("SELECT * FROM setlist_songs WHERE setlistId = :setlistId ORDER BY sortOrder")
    suspend fun getEntries(setlistId: String): List<SetlistSongEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: SetlistSongEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<SetlistSongEntity>)

    @Query("SELECT COALESCE(MAX(sortOrder) + 1, 0) FROM setlist_songs WHERE setlistId = :setlistId")
    suspend fun nextSortOrder(setlistId: String): Int

    @Query("DELETE FROM setlist_songs WHERE setlistId = :setlistId AND songId = :songId")
    suspend fun remove(setlistId: String, songId: String)

    @Query("DELETE FROM setlist_songs WHERE setlistId = :setlistId")
    suspend fun clear(setlistId: String)
}
