package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.AttendanceRecord
import com.example.data.model.DeliveryProof
import com.example.data.model.DriverEntity
import com.example.data.model.LocationPoint
import com.example.data.model.StopRecord
import com.example.data.model.TruckEntity
import com.example.data.model.VoiceDeliveryNote
import kotlinx.coroutines.flow.Flow

@Dao
interface FleetDao {
    // Trucks
    @Query("SELECT * FROM trucks ORDER BY id ASC")
    fun getAllTrucks(): Flow<List<TruckEntity>>

    @Query("SELECT * FROM trucks WHERE id = :truckId")
    fun getTruckById(truckId: Int): Flow<TruckEntity?>

    @Query("SELECT * FROM trucks WHERE id = :truckId")
    suspend fun getTruckByIdDirect(truckId: Int): TruckEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTruck(truck: TruckEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrucks(trucks: List<TruckEntity>)

    @Update
    suspend fun updateTruck(truck: TruckEntity)

    @Query("DELETE FROM trucks WHERE id = :id")
    suspend fun deleteTruck(id: Int)

    @Query("UPDATE trucks SET currentLat = :lat, currentLng = :lng, currentSpeedKmh = :speed, statusText = :status, lastUpdated = :timestamp WHERE id = :truckId")
    suspend fun updateTruckLocation(truckId: Int, lat: Double, lng: Double, speed: Float, status: String, timestamp: Long)

    @Query("UPDATE trucks SET isShiftActive = :active, statusText = :status, lastUpdated = :timestamp WHERE id = :truckId")
    suspend fun updateTruckShiftStatus(truckId: Int, active: Boolean, status: String, timestamp: Long)

    // Drivers
    @Query("SELECT * FROM drivers ORDER BY id ASC")
    fun getAllDrivers(): Flow<List<DriverEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDriver(driver: DriverEntity): Long

    @Query("DELETE FROM drivers WHERE id = :id")
    suspend fun deleteDriver(id: Long)

    // Attendance
    @Query("SELECT * FROM attendance WHERE truckId = :truckId AND isCompleted = 0 ORDER BY id DESC LIMIT 1")
    fun getActiveAttendance(truckId: Int): Flow<AttendanceRecord?>

    @Query("SELECT * FROM attendance WHERE truckId = :truckId AND isCompleted = 0 ORDER BY id DESC LIMIT 1")
    suspend fun getActiveAttendanceDirect(truckId: Int): AttendanceRecord?

    @Query("SELECT * FROM attendance ORDER BY checkInTime DESC")
    fun getAllAttendance(): Flow<List<AttendanceRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttendance(record: AttendanceRecord): Long

    @Update
    suspend fun updateAttendance(record: AttendanceRecord)

    // Location Points
    @Query("SELECT * FROM location_points WHERE truckId = :truckId ORDER BY timestamp ASC")
    fun getLocationsForTruck(truckId: Int): Flow<List<LocationPoint>>

    @Query("SELECT * FROM location_points WHERE truckId = :truckId AND timestamp >= :sinceTimestamp ORDER BY timestamp ASC")
    fun getLocationsForTruckSince(truckId: Int, sinceTimestamp: Long): Flow<List<LocationPoint>>

    @Query("SELECT * FROM location_points WHERE timestamp >= :sinceTimestamp ORDER BY timestamp ASC")
    fun getLocationsSince(sinceTimestamp: Long): Flow<List<LocationPoint>>

    @Query("SELECT * FROM location_points WHERE truckId = :truckId AND timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp ASC")
    fun getLocationsForTruckBetween(truckId: Int, startTime: Long, endTime: Long): Flow<List<LocationPoint>>

    @Query("SELECT * FROM location_points WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp ASC")
    fun getLocationsBetween(startTime: Long, endTime: Long): Flow<List<LocationPoint>>

    @Query("SELECT * FROM location_points WHERE truckId = :truckId ORDER BY timestamp DESC LIMIT 100")
    suspend fun getRecentLocationsDirect(truckId: Int): List<LocationPoint>

    @Query("SELECT * FROM location_points ORDER BY timestamp ASC")
    fun getAllRecentLocations(): Flow<List<LocationPoint>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocationPoint(point: LocationPoint): Long

    // Stop Records
    @Query("SELECT * FROM stop_records WHERE truckId = :truckId ORDER BY startTime DESC")
    fun getStopsForTruck(truckId: Int): Flow<List<StopRecord>>

    @Query("SELECT * FROM stop_records WHERE truckId = :truckId AND startTime >= :startTime AND startTime <= :endTime ORDER BY startTime DESC")
    fun getStopsForTruckBetween(truckId: Int, startTime: Long, endTime: Long): Flow<List<StopRecord>>

    @Query("SELECT * FROM stop_records WHERE startTime >= :startTime AND startTime <= :endTime ORDER BY startTime DESC")
    fun getStopsBetween(startTime: Long, endTime: Long): Flow<List<StopRecord>>

    @Query("SELECT * FROM stop_records ORDER BY startTime DESC")
    fun getAllStops(): Flow<List<StopRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStop(stop: StopRecord): Long

    // Voice Delivery Notes
    @Query("SELECT * FROM voice_delivery_notes ORDER BY timestamp DESC")
    fun getAllVoiceNotes(): Flow<List<VoiceDeliveryNote>>

    @Query("SELECT * FROM voice_delivery_notes WHERE truckId = :truckId ORDER BY timestamp DESC")
    fun getVoiceNotesForTruck(truckId: Int): Flow<List<VoiceDeliveryNote>>

    @Query("SELECT * FROM voice_delivery_notes WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp DESC")
    fun getVoiceNotesBetween(startTime: Long, endTime: Long): Flow<List<VoiceDeliveryNote>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVoiceNote(note: VoiceDeliveryNote): Long

    @Query("DELETE FROM voice_delivery_notes WHERE id = :id")
    suspend fun deleteVoiceNote(id: Long)

    // Delivery Proofs
    @Query("SELECT * FROM delivery_proofs WHERE truckId = :truckId ORDER BY timestamp DESC")
    fun getProofsForTruck(truckId: Int): Flow<List<DeliveryProof>>

    @Query("SELECT * FROM delivery_proofs ORDER BY timestamp DESC")
    fun getAllProofs(): Flow<List<DeliveryProof>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProof(proof: DeliveryProof): Long

    // Full Reset Operations
    @Query("DELETE FROM trucks")
    suspend fun clearAllTrucks()

    @Query("DELETE FROM drivers")
    suspend fun clearAllDrivers()

    @Query("DELETE FROM attendance")
    suspend fun clearAllAttendance()

    @Query("DELETE FROM location_points")
    suspend fun clearAllLocationPoints()

    @Query("DELETE FROM stop_records")
    suspend fun clearAllStopRecords()

    @Query("DELETE FROM voice_delivery_notes")
    suspend fun clearAllVoiceNotes()

    @Query("DELETE FROM delivery_proofs")
    suspend fun clearAllDeliveryProofs()
}
