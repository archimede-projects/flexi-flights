package com.archimedeprojects.volaflex.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "flight_search_cache")
data class FlightSearchCacheEntity(
    @PrimaryKey val cacheKey: String,
    val departureId: String,
    val arrivalId: String,
    val outboundDate: String,
    val returnDate: String,
    val price: Int,
    val currency: String,
    val airlines: String,
    val departureTime: String,
    val arrivalTime: String,
    val stops: Int,
    val searchesLeftBeforeSearch: Int,
    val cachedAtEpochMillis: Long
)

@Entity(tableName = "weekend_search_cache")
data class WeekendSearchCacheEntity(
    @PrimaryKey val cacheKey: String,
    val departureId: String,
    val arrivalId: String,
    val periodKey: String,
    val candidatesJson: String,
    val searchesLeftBeforeSearch: Int,
    val cachedAtEpochMillis: Long
)

@Entity(tableName = "diagnostic_events")
data class DiagnosticEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampEpochMillis: Long,
    val requestType: String,
    val outcome: String,
    val httpStatus: Int?,
    val message: String?
)

@Dao
interface FlightSearchCacheDao {
    @Query(
        """
        SELECT * FROM flight_search_cache
        WHERE cacheKey = :cacheKey
          AND cachedAtEpochMillis >= :minimumTimestamp
        LIMIT 1
        """
    )
    suspend fun findFresh(
        cacheKey: String,
        minimumTimestamp: Long
    ): FlightSearchCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FlightSearchCacheEntity)

    @Query("DELETE FROM flight_search_cache WHERE cachedAtEpochMillis < :minimumTimestamp")
    suspend fun deleteOlderThan(minimumTimestamp: Long)
}

@Dao
interface WeekendSearchCacheDao {
    @Query(
        """
        SELECT * FROM weekend_search_cache
        WHERE cacheKey = :cacheKey
          AND cachedAtEpochMillis >= :minimumTimestamp
        LIMIT 1
        """
    )
    suspend fun findFresh(
        cacheKey: String,
        minimumTimestamp: Long
    ): WeekendSearchCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WeekendSearchCacheEntity)

    @Query("DELETE FROM weekend_search_cache WHERE cachedAtEpochMillis < :minimumTimestamp")
    suspend fun deleteOlderThan(minimumTimestamp: Long)
}

@Dao
interface DiagnosticEventDao {
    @Insert
    suspend fun insert(event: DiagnosticEventEntity)

    @Query("SELECT * FROM diagnostic_events ORDER BY timestampEpochMillis DESC, id DESC LIMIT 20")
    fun observeLatest20(): Flow<List<DiagnosticEventEntity>>

    @Query(
        """
        DELETE FROM diagnostic_events
        WHERE id NOT IN (
            SELECT id FROM diagnostic_events
            ORDER BY timestampEpochMillis DESC, id DESC
            LIMIT 20
        )
        """
    )
    suspend fun trimToLatest20()
}

@Database(
    entities = [
        FlightSearchCacheEntity::class,
        WeekendSearchCacheEntity::class,
        DiagnosticEventEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class VolaFlexDatabase : RoomDatabase() {
    abstract fun flightSearchCacheDao(): FlightSearchCacheDao
    abstract fun weekendSearchCacheDao(): WeekendSearchCacheDao
    abstract fun diagnosticEventDao(): DiagnosticEventDao

    companion object {
        @Volatile
        private var instance: VolaFlexDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `weekend_search_cache` (
                        `cacheKey` TEXT NOT NULL,
                        `departureId` TEXT NOT NULL,
                        `arrivalId` TEXT NOT NULL,
                        `periodKey` TEXT NOT NULL,
                        `candidatesJson` TEXT NOT NULL,
                        `searchesLeftBeforeSearch` INTEGER NOT NULL,
                        `cachedAtEpochMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`cacheKey`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): VolaFlexDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    VolaFlexDatabase::class.java,
                    "volaflex.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { database ->
                        instance = database
                    }
            }
        }
    }
}
