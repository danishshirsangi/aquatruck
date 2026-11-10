package com.example.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "trucks")
data class TruckEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String, // e.g. "Hydro Tanker 1"
    val plateNumber: String, // e.g. "WT-05-A101"
    val driverName: String,
    val capacityLiters: Int, // e.g. 10000
    val currentLat: Double = 0.0,
    val currentLng: Double = 0.0,
    val currentSpeedKmh: Float = 0f,
    val isShiftActive: Boolean = false,
    val statusText: String = "Off Duty", // "En Route", "Discharging", "Idle", "Off Duty"
    val lastUpdated: Long = System.currentTimeMillis()
)

@Entity(tableName = "drivers")
data class DriverEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String = "",
    val assignedTruckId: Int = 1,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "attendance")
data class AttendanceRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val truckId: Int,
    val driverName: String,
    val selfieUri: String?, // local file URI or path
    val checkInTime: Long,
    val checkOutTime: Long? = null,
    val checkInLat: Double = 0.0,
    val checkInLng: Double = 0.0,
    val isCompleted: Boolean = false
)

@Entity(
    tableName = "location_points",
    indices = [
        Index(value = ["isSynced", "timestamp"]),
        Index(value = ["truckId", "timestamp"]),
        Index(value = ["timestamp"])
    ]
)
data class LocationPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val truckId: Int,
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Float,
    val accuracyMeters: Float = 5f,
    val timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val isSynced: Boolean = false,
    @ColumnInfo(defaultValue = "0") val retryCount: Int = 0
)

@Entity(
    tableName = "stop_records",
    indices = [
        Index(value = ["isSynced", "startTime"]),
        Index(value = ["truckId", "startTime"]),
        Index(value = ["startTime"])
    ]
)
data class StopRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val truckId: Int,
    val latitude: Double,
    val longitude: Double,
    val startTime: Long,
    val endTime: Long,
    val durationMinutes: Int,
    val addressOrLabel: String,
    val category: String = "Delivery Stop",
    @ColumnInfo(defaultValue = "0") val isSynced: Boolean = false,
    @ColumnInfo(defaultValue = "0") val retryCount: Int = 0
)

@Entity(tableName = "voice_delivery_notes")
data class VoiceDeliveryNote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val truckId: Int,
    val driverName: String,
    val audioFilePath: String, // Local audio recording path (.m4a/.aac)
    val durationSeconds: Int,
    val latitude: Double,
    val longitude: Double,
    val locationLabel: String = "Water Delivery Site",
    val timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val isSynced: Boolean = false,
    @ColumnInfo(defaultValue = "0") val retryCount: Int = 0
)

@Entity(tableName = "delivery_proofs")
data class DeliveryProof(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val truckId: Int,
    val customerName: String,
    val volumeLiters: Int,
    val photoUri: String?,
    val latitude: Double,
    val longitude: Double,
    val notes: String,
    val timestamp: Long = System.currentTimeMillis()
)
