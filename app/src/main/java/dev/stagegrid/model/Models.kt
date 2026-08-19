package dev.stagegrid.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

enum class TrackType(val nativeCode: Int) {
    OTHER(0), DRUMS(1), BASS(2), GUITAR(3), KEYS(4), SYNTH(5), STRINGS(6),
    VOCALS(7), PERCUSSION(8), CLICK(9), GUIDE(10), PAD(11);

    companion object {
        fun fromStorage(value: String): TrackType = entries.firstOrNull { it.name == value } ?: OTHER
    }
}

enum class StereoRoute(val nativeCode: Int) {
    BOTH(0), LEFT(1), RIGHT(2);

    companion object {
        fun fromStorage(value: String): StereoRoute = entries.firstOrNull { it.name == value } ?: BOTH
    }
}

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val artist: String = "",
    val bpm: Double? = null,
    val musicalKey: String? = null,
    val timeSignature: String = "4/4",
    val durationMs: Long = 0L,
    /** Musical grid origin detected from an imported click/reference track. */
    val gridOffsetMs: Long = 0L,
    val artworkPath: String? = null,
    val category: String? = null,
    val notes: String = "",
    val importedAtEpochMs: Long = System.currentTimeMillis(),
    val lastPlayedAtEpochMs: Long? = null,
    val playCount: Int = 0,
    val favorite: Boolean = false,
)

@Entity(
    tableName = "tracks",
    foreignKeys = [ForeignKey(
        entity = SongEntity::class,
        parentColumns = ["id"],
        childColumns = ["songId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("songId")],
)
data class TrackEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val songId: String,
    val name: String,
    val filePath: String,
    val type: String = TrackType.OTHER.name,
    val channels: Int,
    val sampleRate: Int,
    val bitDepth: Int,
    val durationMs: Long,
    val sortOrder: Int,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val solo: Boolean = false,
    val pan: Float = 0f,
    /** Stereo output assignment: BOTH, LEFT or RIGHT. */
    val outputRoute: String = StereoRoute.BOTH.name,
)

@Entity(
    tableName = "sections",
    foreignKeys = [ForeignKey(
        entity = SongEntity::class,
        parentColumns = ["id"],
        childColumns = ["songId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("songId")],
)
data class SectionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val songId: String,
    val name: String,
    val startMs: Long,
    val endMs: Long,
    val sortOrder: Int,
    val colorArgb: Long = 0xFF5B8CFF,
)

@Entity(tableName = "setlists")
data class SetlistEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val eventDateEpochMs: Long? = null,
    val venue: String? = null,
    val notes: String = "",
    val createdAtEpochMs: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "setlist_songs",
    primaryKeys = ["setlistId", "songId"],
    foreignKeys = [
        ForeignKey(
            entity = SetlistEntity::class,
            parentColumns = ["id"],
            childColumns = ["setlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("setlistId"), Index("songId")],
)
data class SetlistSongEntity(
    val setlistId: String,
    val songId: String,
    val sortOrder: Int,
    val notes: String = "",
)

data class SongBundle(
    val song: SongEntity,
    val tracks: List<TrackEntity>,
    val sections: List<SectionEntity>,
)

data class SetlistBundle(
    val setlist: SetlistEntity,
    val songs: List<SongEntity>,
)
