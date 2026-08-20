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

/**
 * Stereo-pair output bus used by the 0.4 routing matrix.
 *
 * A track keeps its existing [StereoRoute] inside the selected pair, so BOTH means stereo across
 * the pair while LEFT/RIGHT become mono assignments to either physical output. This keeps the
 * simple 1/2 workflow intact while extending the same model through eight outputs.
 */
enum class OutputBus(val nativeCode: Int, val firstPhysicalChannel: Int) {
    OUT_1_2(0, 1),
    OUT_3_4(1, 3),
    OUT_5_6(2, 5),
    OUT_7_8(3, 7);

    val lastPhysicalChannel: Int get() = firstPhysicalChannel + 1

    companion object {
        fun fromStorage(value: Int): OutputBus = entries.firstOrNull { it.nativeCode == value } ?: OUT_1_2
        fun availableForChannels(channelCount: Int): List<OutputBus> =
            entries.filter { it.lastPhysicalChannel <= channelCount.coerceAtLeast(2) }
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
    /** Route inside [outputBus]: BOTH, LEFT or RIGHT. */
    val outputRoute: String = StereoRoute.BOTH.name,
    /** Persistent stereo-pair bus: 0=1/2, 1=3/4, 2=5/6, 3=7/8. */
    val outputBus: Int = OutputBus.OUT_1_2.nativeCode,
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
