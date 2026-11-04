package de.tetnavigator.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(
    entities = [
        CountryEntity::class, TrackFileEntity::class, SectionEntity::class, TrackPointEntity::class,
        WaypointEntity::class, ProgressEntity::class, HazardPinEntity::class, RideEntity::class,
        RidePointEntity::class, RideCountryEntity::class, JournalEntryEntity::class,
        BlockedRangeEntity::class, StageItemEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class TetDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun progressDao(): ProgressDao
    abstract fun hazardDao(): HazardDao
    abstract fun rideDao(): RideDao
    abstract fun journalDao(): JournalDao
    abstract fun blockedRangeDao(): BlockedRangeDao
    abstract fun stageDao(): StageDao

    companion object {
        const val NAME = "tet.db"

        fun build(context: Context, inMemory: Boolean = false): TetDatabase {
            val builder = if (inMemory) {
                Room.inMemoryDatabaseBuilder(context, TetDatabase::class.java)
            } else {
                Room.databaseBuilder(context, TetDatabase::class.java, NAME)
            }
            return builder.addCallback(RTreeCallback).build()
        }
    }
}

/** Legt den R*Tree für Track-Segmente an (Room kennt keine virtuellen Tabellen). */
internal object RTreeCallback : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        SpatialIndex.ensureTable(db)
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun db(@ApplicationContext ctx: Context): TetDatabase = TetDatabase.build(ctx)

    @Provides fun trackDao(db: TetDatabase) = db.trackDao()
    @Provides fun progressDao(db: TetDatabase) = db.progressDao()
    @Provides fun hazardDao(db: TetDatabase) = db.hazardDao()
    @Provides fun rideDao(db: TetDatabase) = db.rideDao()
    @Provides fun journalDao(db: TetDatabase) = db.journalDao()
    @Provides fun blockedDao(db: TetDatabase) = db.blockedRangeDao()
    @Provides fun stageDao(db: TetDatabase) = db.stageDao()
    @Provides @Singleton fun spatial(db: TetDatabase) = SpatialIndex(db)
}
