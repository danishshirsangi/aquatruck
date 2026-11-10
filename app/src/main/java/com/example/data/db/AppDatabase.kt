package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.AttendanceRecord
import com.example.data.model.DeliveryProof
import com.example.data.model.DriverEntity
import com.example.data.model.LocationPoint
import com.example.data.model.StopRecord
import com.example.data.model.TruckEntity
import com.example.data.model.VoiceDeliveryNote

@Database(
    entities = [
        TruckEntity::class,
        DriverEntity::class,
        AttendanceRecord::class,
        LocationPoint::class,
        StopRecord::class,
        DeliveryProof::class,
        VoiceDeliveryNote::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fleetDao(): FleetDao

    companion object {
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE location_points ADD COLUMN isSynced INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE location_points ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE stop_records ADD COLUMN isSynced INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE stop_records ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0")

                database.execSQL("CREATE INDEX IF NOT EXISTS index_location_points_isSynced_timestamp ON location_points(isSynced, timestamp)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_location_points_truckId_timestamp ON location_points(truckId, timestamp)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_stop_records_isSynced_startTime ON stop_records(isSynced, startTime)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_stop_records_truckId_startTime ON stop_records(truckId, startTime)")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aquatrack_fleet.db"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
