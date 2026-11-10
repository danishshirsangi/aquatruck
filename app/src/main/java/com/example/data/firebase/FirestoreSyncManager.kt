package com.example.data.firebase

import android.content.Context
import android.util.Log
import com.example.R
import com.example.data.model.AttendanceRecord
import com.example.data.model.DriverEntity
import com.example.data.model.LocationPoint
import com.example.data.model.StopRecord
import com.example.data.model.TruckEntity
import com.example.data.model.VoiceDeliveryNote
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import java.io.File
import android.net.Uri
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

class FirestoreSyncManager(private val context: Context) {

    private val firestore: FirebaseFirestore by lazy {
        val dbId = context.getString(R.string.firestore_database_id)
        FirebaseFirestore.getInstance(dbId)
    }

    private val auth get() = Firebase.auth

    private fun collection(name: String): CollectionReference {
        val orgId = context.getSharedPreferences("aquatrack_tenant", Context.MODE_PRIVATE)
            .getString("organization_id", null)
        return if (orgId.isNullOrBlank()) firestore.collection(name) else {
            firestore.collection("organizations").document(orgId).collection(name)
        }
    }

    suspend fun recordTestTruckSubscription(truckId: Int): Boolean {
        val user = auth.currentUser ?: return false
        val orgId = context.getSharedPreferences("aquatrack_tenant", Context.MODE_PRIVATE)
            .getString("organization_id", null) ?: return false
        return try {
            val now = System.currentTimeMillis()
            firestore.collection("organizations").document(orgId)
                .collection("truck_subscriptions").document(truckId.toString())
                .set(hashMapOf(
                    "truckId" to truckId,
                    "amountPaise" to 9900,
                    "currency" to "INR",
                    "billingPeriod" to "monthly",
                    "mode" to "test",
                    "status" to "test_active",
                    "startedAt" to now,
                    "renewsAt" to now + 30L * 24L * 60L * 60L * 1000L,
                    "createdBy" to user.uid
                )).await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreSync", "Unable to save test truck subscription: ${e.message}", e)
            false
        }
    }

    suspend fun syncTruck(truck: TruckEntity) {
        if (auth.currentUser == null) return
        try {
            val data = hashMapOf(
                "id" to truck.id,
                "truckNumber" to truck.id,
                "name" to truck.name,
                "plateNumber" to truck.plateNumber,
                "driverName" to truck.driverName,
                "capacityLiters" to truck.capacityLiters,
                "currentLat" to truck.currentLat,
                "currentLng" to truck.currentLng,
                "currentSpeedKmh" to truck.currentSpeedKmh,
                "isShiftActive" to truck.isShiftActive,
                "statusText" to truck.statusText,
                "lastUpdated" to truck.lastUpdated
            )
            collection("trucks").document("truck_${truck.id}")
                .set(data, SetOptions.merge())
                .await()
        } catch (e: Exception) {
            Log.w("FirestoreSync", "Error syncing truck: ${e.message}")
        }
    }

    suspend fun deleteTruck(truckId: Int) {
        if (auth.currentUser == null) return
        try {
            collection("trucks").document("truck_$truckId").delete().await()
        } catch (e: Exception) {
            Log.w("FirestoreSync", "Error deleting truck: ${e.message}")
        }
    }

    suspend fun syncDriver(driver: DriverEntity) {
        if (auth.currentUser == null) return
        try {
            val data = hashMapOf(
                "id" to driver.id,
                "name" to driver.name,
                "phone" to driver.phone,
                "assignedTruckId" to driver.assignedTruckId,
                "createdAt" to driver.createdAt
            )
            collection("drivers").document("driver_${driver.id}")
                .set(data, SetOptions.merge())
                .await()
        } catch (e: Exception) {
            Log.w("FirestoreSync", "Error syncing driver: ${e.message}")
        }
    }

    suspend fun deleteDriver(driverId: Long) {
        if (auth.currentUser == null) return
        try {
            collection("drivers").document("driver_$driverId").delete().await()
        } catch (e: Exception) {
            Log.w("FirestoreSync", "Error deleting driver: ${e.message}")
        }
    }

    suspend fun syncAttendance(record: AttendanceRecord) {
        if (auth.currentUser == null) return
        try {
            val data = hashMapOf(
                "id" to record.id,
                "truckId" to record.truckId,
                "driverName" to record.driverName,
                "selfieUri" to (record.selfieUri ?: ""),
                "checkInTime" to record.checkInTime,
                "checkOutTime" to (record.checkOutTime ?: 0L),
                "checkInLat" to record.checkInLat,
                "checkInLng" to record.checkInLng,
                "isCompleted" to record.isCompleted
            )
            collection("attendance").document("att_${record.truckId}_${record.checkInTime}")
                .set(data, SetOptions.merge())
                .await()
        } catch (e: Exception) {
            Log.w("FirestoreSync", "Error syncing attendance: ${e.message}")
        }
    }

    suspend fun syncVoiceNote(note: VoiceDeliveryNote): Boolean {
        if (auth.currentUser == null) return false
        try {
            var audioReference = note.audioFilePath
            val localFile = File(note.audioFilePath)
            val orgId = context.getSharedPreferences("aquatrack_tenant", Context.MODE_PRIVATE)
                .getString("organization_id", null)
            val user = auth.currentUser
            if (localFile.exists() && !orgId.isNullOrBlank() && user != null) {
                val extension = localFile.extension.ifBlank { "m4a" }
                val storageRef = FirebaseStorage.getInstance().reference
                    .child("organizations/" + orgId + "/voice/voice_" + note.truckId + "_" + note.timestamp + "." + extension)
                val metadata = StorageMetadata.Builder()
                    .setContentType(if (extension.equals("wav", true)) "audio/wav" else "audio/mp4")
                    .setCustomMetadata("organizationId", orgId)
                    .setCustomMetadata("ownerUid", user.uid)
                    .build()
                storageRef.putFile(Uri.fromFile(localFile), metadata).await()
                audioReference = storageRef.downloadUrl.await().toString()
            }
            val data = hashMapOf(
                "id" to note.id,
                "truckId" to note.truckId,
                "driverName" to note.driverName,
                "audioFilePath" to audioReference,
                "durationSeconds" to note.durationSeconds,
                "latitude" to note.latitude,
                "longitude" to note.longitude,
                "locationLabel" to note.locationLabel,
                "timestamp" to note.timestamp
            )
            collection("voice_delivery_notes").document("voice_${note.truckId}_${note.timestamp}")
                .set(data, SetOptions.merge())
                .await()
        } catch (e: Exception) {
            Log.w("FirestoreSync", "Error syncing voice note: ${e.message}")
            false
        }
    }

    suspend fun syncLocationPoint(point: LocationPoint) {
        if (auth.currentUser == null) return
        try {
            val data = hashMapOf(
                "id" to point.id,
                "truckId" to point.truckId,
                "latitude" to point.latitude,
                "longitude" to point.longitude,
                "speedKmh" to point.speedKmh,
                "accuracyMeters" to point.accuracyMeters,
                "timestamp" to point.timestamp,
                "expireAt" to java.util.Date(point.timestamp + 30L * 24L * 60L * 60L * 1000L)
            )
            collection("location_points").document("loc_${point.truckId}_${point.timestamp}")
                .set(data, SetOptions.merge())
                .await()
        } catch (e: Exception) {
            Log.w("FirestoreSync", "Error syncing location point: ${e.message}")
        }
    }

    /**
     * Uploads historical breadcrumbs in Firestore batches. A false result means none of
     * the supplied points should be marked synced by the caller.
     */
    suspend fun syncLocationBatch(points: List<LocationPoint>): Boolean {
        if (points.isEmpty()) return true
        if (auth.currentUser == null) return false
        return try {
            points.chunked(450).forEach { chunk ->
                val batch = firestore.batch()
                chunk.forEach { point ->
                    val data = hashMapOf(
                        "id" to point.id,
                        "truckId" to point.truckId,
                        "latitude" to point.latitude,
                        "longitude" to point.longitude,
                        "speedKmh" to point.speedKmh,
                        "accuracyMeters" to point.accuracyMeters,
                        "timestamp" to point.timestamp,
                        "expireAt" to java.util.Date(point.timestamp + 30L * 24L * 60L * 60L * 1000L)
                    )
                    batch.set(
                        collection("location_points")
                            .document("loc_${point.truckId}_${point.timestamp}"),
                        data,
                        SetOptions.merge()
                    )
                }
                batch.commit().await()
            }
            true
        } catch (e: Exception) {
            Log.w("FirestoreSync", "Location batch upload failed: ${e.message}")
            false
        }
    }

    suspend fun syncStop(stop: StopRecord): Boolean {
        if (auth.currentUser == null) return false
        return try {
            val data = hashMapOf(
                "id" to stop.id,
                "truckId" to stop.truckId,
                "latitude" to stop.latitude,
                "longitude" to stop.longitude,
                "startTime" to stop.startTime,
                "endTime" to stop.endTime,
                "durationMinutes" to stop.durationMinutes,
                "addressOrLabel" to stop.addressOrLabel,
                "category" to stop.category
            )
            collection("stop_records").document("stop_${stop.truckId}_${stop.startTime}")
                .set(data, SetOptions.merge())
                .await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreSync", "Error syncing stop: ${e.message}", e)
            false
        }
    }

    /**
     * Uploads pending stop records in one Firestore batch per chunk.
     * The caller marks records synced only after this returns true.
     */
    suspend fun syncStopBatch(stops: List<StopRecord>): Boolean {
        if (stops.isEmpty()) return true
        if (auth.currentUser == null) return false
        return try {
            stops.chunked(450).forEach { chunk ->
                val batch = firestore.batch()
                chunk.forEach { stop ->
                    val data = hashMapOf(
                        "id" to stop.id,
                        "truckId" to stop.truckId,
                        "latitude" to stop.latitude,
                        "longitude" to stop.longitude,
                        "startTime" to stop.startTime,
                        "endTime" to stop.endTime,
                        "durationMinutes" to stop.durationMinutes,
                        "addressOrLabel" to stop.addressOrLabel,
                        "category" to stop.category
                    )
                    batch.set(
                        collection("stop_records").document("stop_${stop.truckId}_${stop.startTime}"),
                        data,
                        SetOptions.merge()
                    )
                }
                batch.commit().await()
            }
            true
        } catch (e: Exception) {
            Log.w("FirestoreSync", "Stop batch upload failed: ${e.message}", e)
            false
        }
    }

    suspend fun clearAllCollections(): Boolean {
        if (auth.currentUser == null) return false
        return try {
            val collections = listOf(
                "trucks",
                "drivers",
                "attendance",
                "voice_delivery_notes",
                "location_points",
                "stop_records",
                "delivery_proofs"
            )
            for (colName in collections) {
                val snapshot = collection(colName).get().await()
                for (doc in snapshot.documents) {
                    doc.reference.delete().await()
                }
            }
            Log.i("FirestoreSync", "Successfully deleted all Firestore collections.")
            true
        } catch (e: Exception) {
            Log.e("FirestoreSync", "Error clearing Firestore collections: ${e.message}")
            false
        }
    }
}
