package dev.stagegrid.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.stagegrid.model.SectionEntity
import dev.stagegrid.model.SetlistEntity
import dev.stagegrid.model.SetlistSongEntity
import dev.stagegrid.model.SongEntity
import dev.stagegrid.model.TrackEntity

@Database(
    entities = [
        SongEntity::class,
        TrackEntity::class,
        SectionEntity::class,
        SetlistEntity::class,
        SetlistSongEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class StageGridDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun trackDao(): TrackDao
    abstract fun sectionDao(): SectionDao
    abstract fun setlistDao(): SetlistDao
    abstract fun setlistSongDao(): SetlistSongDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN gridOffsetMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tracks ADD COLUMN outputRoute TEXT NOT NULL DEFAULT 'BOTH'")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN outputBus INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun create(context: Context): StageGridDatabase =
            Room.databaseBuilder(context, StageGridDatabase::class.java, "stagegrid.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
