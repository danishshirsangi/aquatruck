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
    version = 4,
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

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aquatrack_fleet.db"
                )
                    .addMigrations(MIGRATION_3_4)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
