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

    /** Last human-readable reason a voice note failed to upload (shown in the driver UI). */
    @Volatile var lastVoiceSyncError: String? = null

    private fun collection(name: String): CollectionReference {
        val orgId = context.getSharedPreferences("aquatrack_tenant", Context.MODE_PRIVATE)
            .getString("organization_id", null)
        check(!orgId.isNullOrBlank()) { "No business membership is configured; refusing to write to global fleet collections." }
        return firestore.collection("organizations").document(orgId).collection(name)
    }

    suspend fun recordTestTruckSubscription(truckId: Int): Boolean {
        val user = auth.currentUser
            ?: throw IllegalStateException("Your Google account is not signed in. Sign in again and retry.")
        val orgId = context.getSharedPreferences("aquatrack_tenant", Context.MODE_PRIVATE)
            .getString("organization_id", null)
            ?: throw IllegalStateException("No business is selected on this device. Sign out and sign in to your manager account again.")
        try {
            val now = System.currentTimeMillis()
            firestore.collection("organizations").document(orgId)
                .collection("truck_subscriptions").document(truckId.toString())
                .set(hashMapOf(
                    "truckId" to truckId,
                    "amountPaise" to 14900,
                    "currency" to "INR",
                    "billingPeriod" to "monthly",
                    "mode" to "test",
                    "status" to "test_active",
                    "startedAt" to now,
                    "renewsAt" to now + 30L * 24L * 60L * 60L * 1000L,
                    "createdBy" to user.uid
                )).await()
            return true
        } catch (e: Exception) {
            Log.w("FirestoreSync", "Unable to save test truck subscription (orgId=$orgId, uid=${user.uid}): ${e.message}", e)
            throw IllegalStateException(
                "Firestore subscription write failed: ${e.message ?: e.javaClass.simpleName}. " +
                    "Check that the latest firestore.rules are deployed to the configured database and that your membership is active with role 'manager'.",
                e
            )
        }
    }

    private fun truckData(truck: TruckEntity) = hashMapOf(
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

    /**
     * Creates the truck document and its Rs.149 test subscription in ONE atomic batch so the
     * two can never diverge (previously a subscription could exist without a truck, or the
     * UI could show a truck that Firestore never accepted). Throws with a readable reason.
     */
    suspend fun addTruckWithTestSubscription(truck: TruckEntity) {
        val user = auth.currentUser
            ?: throw IllegalStateException("Your Google account is not signed in. Sign in again and retry.")
        val orgId = context.getSharedPreferences("aquatrack_tenant", Context.MODE_PRIVATE)
            .getString("organization_id", null)
        if (orgId.isNullOrBlank()) {
            throw IllegalStateException("No business is selected on this device. Sign out and sign in to your manager account again.")
        }
        val now = System.currentTimeMillis()
        val orgRef = firestore.collection("organizations").document(orgId)
        try {
            val batch = firestore.batch()
            batch.set(
                orgRef.collection("trucks").document("truck_${truck.id}"),
                truckData(truck),
                SetOptions.merge()
            )
            batch.set(
                orgRef.collection("truck_subscriptions").document(truck.id.toString()),
                hashMapOf(
                    "truckId" to truck.id,
                    "amountPaise" to 14900,
                    "currency" to "INR",
                    "billingPeriod" to "monthly",
                    "mode" to "test",
                    "status" to "test_active",
                    "startedAt" to now,
                    "renewsAt" to now + 30L * 24L * 60L * 60L * 1000L,
                    "createdBy" to user.uid
                )
            )
            batch.commit().await()
        } catch (e: Exception) {
            Log.e("FirestoreSync", "Atomic truck + subscription write failed (orgId=$orgId, uid=${user.uid}, truckId=${truck.id}): ${e.message}", e)
            val denied = (e as? com.google.firebase.firestore.FirebaseFirestoreException)?.code ==
                com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED
            throw IllegalStateException(
                if (denied) {
                    "Firestore denied adding this truck (PERMISSION_DENIED). Your account has no active manager " +
                        "membership in this business, or the latest firestore.rules are not deployed to database " +
                        "'${context.getString(R.string.firestore_database_id)}'."
                } else {
                    "Could not save the truck and test subscription: ${e.message ?: e.javaClass.simpleName}"
                },
                e
            )
        }
    }

    suspend fun syncTruck(truck: TruckEntity): Boolean {
        if (auth.currentUser == null) return false
        return try {
            collection("trucks").document("truck_${truck.id}")
                .set(truckData(truck), SetOptions.merge())
                .await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreSync", "Error syncing truck: ${e.message}", e)
            false
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
                "createdAt" to driver.createdAt,
                "uid" to (driver.userUid ?: ""),
                "earlyLogoutEnabled" to driver.earlyLogoutEnabled,
                "invitationCode" to (driver.invitationCode ?: "")
            )
            collection("drivers").document("driver_${driver.id}")
                .set(data, SetOptions.merge())
                .await()
            val normalizedPhone = driver.phone.filter { it.isDigit() }
            val orgId = context.getSharedPreferences("aquatrack_tenant", Context.MODE_PRIVATE)
                .getString("organization_id", null)
            val inviteCode = driver.invitationCode
            if (normalizedPhone.length >= 8 && !orgId.isNullOrBlank() && !inviteCode.isNullOrBlank()) {
                val phoneHash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(normalizedPhone.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                val businessName = context.getSharedPreferences("aquatrack_tenant", Context.MODE_PRIVATE)
                    .getString("business_name", "Business") ?: "Business"
                firestore.collection("driver_invitations").document(inviteCode).set(mapOf(
                    "organizationId" to orgId,
                    "businessName" to businessName,
                    "driverId" to driver.id,
                    "driverName" to driver.name,
                    "phoneHash" to phoneHash,
                    "assignedTruckId" to driver.assignedTruckId,
                    "active" to driver.userUid.isNullOrBlank(),
                    "uid" to (driver.userUid ?: ""),
                    "createdAt" to driver.createdAt
                ), SetOptions.merge()).await()
            }
        } catch (e: Exception) {
            Log.w("FirestoreSync", "Error syncing driver: ${e.message}")
        }
    }

    suspend fun deleteDriver(driver: DriverEntity): Boolean {
        if (auth.currentUser == null) return false
        return try {
            val batch = firestore.batch()
            batch.delete(collection("drivers").document("driver_${driver.id}"))
            driver.invitationCode?.takeIf { it.isNotBlank() }?.let { code ->
                batch.delete(firestore.collection("driver_invitations").document(code))
            }
            driver.userUid?.takeIf { it.isNotBlank() }?.let { uid ->
                batch.delete(collection("members").document(uid))
            }
            batch.commit().await()
            true
        } catch (e: Exception) {
            Log.w("FirestoreSync", "Error deleting driver: ${e.message}", e)
            false
        }
    }

    suspend fun updateDriver(driver: DriverEntity) {
        syncDriver(driver)
    }

    suspend fun syncAttendance(record: AttendanceRecord): Boolean {
        if (auth.currentUser == null) return false
        return try {
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
            true
        } catch (e: Exception) {
            Log.w("FirestoreSync", "Error syncing attendance: ${e.message}", e)
            false
        }
    }

    suspend fun syncVoiceNote(note: VoiceDeliveryNote): Boolean {
        if (auth.currentUser == null) return false
        return try {
            lastVoiceSyncError = null
            val localFile = File(note.audioFilePath)
            val orgId = context.getSharedPreferences("aquatrack_tenant", Context.MODE_PRIVATE)
                .getString("organization_id", null)
            if (!localFile.exists() || orgId.isNullOrBlank()) return false
            val audioReference = SupabaseAudioStorage.upload(
                context = context,
                localPath = note.audioFilePath,
                truckId = note.truckId,
                timestamp = note.timestamp
            )
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
            true
        } catch (e: Exception) {
            Log.w("FirestoreSync", "Error syncing voice note to Supabase: ${e.message}", e)
            lastVoiceSyncError = e.message ?: e.javaClass.simpleName
            false
        }
    }

    suspend fun syncLocationPoint(point: LocationPoint) {
        if (auth.currentUser == null) return
        try {
            val data = hashMapOf(
                "id" to point.id,
                "driverUid" to (auth.currentUser?.uid ?: ""),
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
                        "driverUid" to (auth.currentUser?.uid ?: ""),
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
            val orgId = context.getSharedPreferences("aquatrack_tenant", Context.MODE_PRIVATE)
                .getString("organization_id", null)
            val uid = auth.currentUser?.uid
            Log.e(
                "FirestoreSync",
                "Location batch upload failed (orgId=${orgId}, uid=${uid}, points=${points.size}, " +
                    "truckIds=${points.map { it.truckId }.distinct()}): ${e.message}",
                e
            )
            false
        }
    }

    /**
     * Fallback used when a whole batch is rejected. One bad point (for example a truck that has
     * no Firestore document, or a point from a shift that is no longer claimed) must not block
     * every other point. Returns ids that were written and ids that were permanently denied by
     * the security rules; any other id failed for a transient reason and should be retried.
     */
    suspend fun syncLocationPointsIndividually(points: List<LocationPoint>): Pair<Set<Long>, Set<Long>> {
        val ok = mutableSetOf<Long>()
        val denied = mutableSetOf<Long>()
        val uid = auth.currentUser?.uid ?: return ok to denied
        for (point in points) {
            try {
                collection("location_points").document("loc_${point.truckId}_${point.timestamp}")
                    .set(hashMapOf(
                        "id" to point.id,
                        "driverUid" to uid,
                        "truckId" to point.truckId,
                        "latitude" to point.latitude,
                        "longitude" to point.longitude,
                        "speedKmh" to point.speedKmh,
                        "accuracyMeters" to point.accuracyMeters,
                        "timestamp" to point.timestamp,
                        "expireAt" to java.util.Date(point.timestamp + 30L * 24L * 60L * 60L * 1000L)
                    ), SetOptions.merge()).await()
                ok += point.id
            } catch (e: com.google.firebase.firestore.FirebaseFirestoreException) {
                if (e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    Log.w("FirestoreSync", "Point denied by rules (truckId=${point.truckId}, ts=${point.timestamp}, uid=$uid)")
                    denied += point.id
                } else {
                    Log.w("FirestoreSync", "Transient point upload failure: ${e.code} ${e.message}")
                }
            } catch (e: Exception) {
                Log.w("FirestoreSync", "Point upload failed: ${e.message}")
            }
        }
        return ok to denied
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
