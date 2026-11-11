package com.example.data.tenant

import android.content.Context
import android.net.Uri
import com.example.R
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.io.File
import java.security.SecureRandom
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import com.example.data.model.AttendanceRecord
import com.example.data.model.LocationPoint
import com.example.data.model.StopRecord
import com.example.data.model.VoiceDeliveryNote
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class DriverShiftPolicy(
    val logoutTimeMinutes: Int = 21 * 60,
    val allowEarlyLogout: Boolean = false,
    val timezone: String = "Asia/Kolkata"
) {
    fun formattedLogoutTime(): String = "%02d:%02d".format(logoutTimeMinutes / 60, logoutTimeMinutes % 60)
}

object TenantManager {
    private const val PREFS = "aquatrack_tenant"
    private const val KEY_ORG = "organization_id"
    private const val KEY_UID = "firebase_user_uid"
    private const val KEY_ROLE = "organization_role"
    private const val KEY_BUSINESS_NAME = "business_name"
    private const val KEY_BUSINESS_CODE = "business_code"
    private const val KEY_PIN_SALT = "manager_pin_salt"
    private const val KEY_PIN_HASH = "manager_pin_hash"
    private const val KEY_ASSIGNED_TRUCK_ID = "assigned_truck_id"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun firestore(context: Context): FirebaseFirestore =
        FirebaseFirestore.getInstance(context.getString(R.string.firestore_database_id))

    fun organizationId(context: Context): String? = prefs(context).getString(KEY_ORG, null)
    fun role(context: Context): String? = prefs(context).getString(KEY_ROLE, null)
    fun businessName(context: Context): String? = prefs(context).getString(KEY_BUSINESS_NAME, null)
    fun businessCode(context: Context): String? = prefs(context).getString(KEY_BUSINESS_CODE, null)
    fun assignedTruckId(context: Context): Int? =
        prefs(context).getInt(KEY_ASSIGNED_TRUCK_ID, 0).takeIf { it > 0 }

    fun currentUserUid(): String? = Firebase.auth.currentUser?.uid

    fun hasActiveOrganization(context: Context): Boolean {
        val currentUid = Firebase.auth.currentUser?.uid ?: return false
        val savedUid = prefs(context).getString(KEY_UID, null)
        return savedUid == currentUid &&
            !organizationId(context).isNullOrBlank() &&
            !role(context).isNullOrBlank()
    }

    private suspend fun rememberMembership(
        context: Context,
        orgId: String,
        memberRole: String,
        name: String,
        code: String? = null,
        assignedTruckId: Int? = null,
        preserveLegacyPilotData: Boolean = false
    ) {
        val currentUid = Firebase.auth.currentUser?.uid
            ?: throw IllegalStateException("Sign in before selecting a business.")
        val previousOrgId = organizationId(context)
        val previousUid = prefs(context).getString(KEY_UID, null)
        val isFirstManagerRegistrationWithLegacyPilot =
            preserveLegacyPilotData && previousUid == null && previousOrgId.isNullOrBlank()
        val membershipChanged =
            previousUid != currentUid ||
            (!previousOrgId.isNullOrBlank() && previousOrgId != orgId)
        if (membershipChanged && !isFirstManagerRegistrationWithLegacyPilot) {
            // A device can be shared or change signed-in accounts. Do not let the next
            // business inherit the previous business's local fleet/history cache.
            runCatching {
                com.example.service.LocationTrackingService.stopService(context)
            }
            val dao = com.example.data.db.AppDatabase.getDatabase(context).fleetDao()
            dao.clearAllLocationPoints()
            dao.clearAllStopRecords()
            dao.clearAllVoiceNotes()
            dao.clearAllAttendance()
            dao.clearAllTrucks()
            dao.clearAllDrivers()
            dao.clearAllDeliveryProofs()
            context.getSharedPreferences("aquatrack_session_prefs", Context.MODE_PRIVATE)
                .edit().clear().apply()
            context.getSharedPreferences("aquatrack_tracking_service", Context.MODE_PRIVATE)
                .edit().clear().apply()
            prefs(context).edit()
                .remove(KEY_PIN_SALT)
                .remove(KEY_PIN_HASH)
                .remove(KEY_BUSINESS_CODE)
                .apply()
        }

        prefs(context).edit()
            .putString(KEY_UID, currentUid)
            .putString(KEY_ORG, orgId)
            .putString(KEY_ROLE, memberRole)
            .putString(KEY_BUSINESS_NAME, name)
            .apply {
                if (code != null) putString(KEY_BUSINESS_CODE, code) else remove(KEY_BUSINESS_CODE)
                if (assignedTruckId != null) putInt(KEY_ASSIGNED_TRUCK_ID, assignedTruckId)
                else remove(KEY_ASSIGNED_TRUCK_ID)
            }
            .apply()
    }

    suspend fun restoreMembership(context: Context): Boolean {
        val user = Firebase.auth.currentUser ?: return false
        return try {
            val snapshot = firestore(context).collection("users").document(user.uid).get().await()
            val orgId = snapshot.getString("organizationId") ?: return false
            val profileRole = snapshot.getString("role") ?: return false
            val name = snapshot.getString("businessName") ?: "My Business"
            val root = firestore(context).collection("organizations").document(orgId)
            var member = root.collection("members").document(user.uid).get().await()
            if (!member.exists() && profileRole == "driver") {
                // Repair legacy driver accounts whose user profile exists but whose
                // membership document was never created. The claimed invitation is
                // the proof that this account joined this business.
                val driver = root.collection("drivers").whereEqualTo("uid", user.uid).limit(1).get().await()
                    .documents.firstOrNull() ?: return false
                val invitationId = driver.getString("invitationCode") ?: return false
                val driverId = driver.getLong("id") ?: return false
                val invitation = firestore(context).collection("driver_invitations").document(invitationId).get().await()
                if (invitation.getString("uid") != user.uid || invitation.getBoolean("active") != false) return false
                root.collection("members").document(user.uid).set(mapOf(
                    "uid" to user.uid, "driverId" to driverId, "role" to "driver",
                    "name" to (driver.getString("name") ?: snapshot.getString("displayName") ?: "Driver"),
                    "phone" to (driver.getString("phone") ?: ""),
                    "earlyLogoutEnabled" to (driver.getBoolean("earlyLogoutEnabled") ?: false),
                    "status" to "active", "createdAt" to System.currentTimeMillis(),
                    "invitationId" to invitationId, "activeTruckId" to 0
                )).await()
                member = root.collection("members").document(user.uid).get().await()
            } else if (!member.exists() && profileRole == "manager") {
                // Restore a legacy manager membership only when the organisation's
                // ownerUid and the signed-in user's existing profile agree.
                val org = root.get().await()
                if (org.getString("ownerUid") != user.uid) return false
                root.collection("members").document(user.uid).set(mapOf(
                    "uid" to user.uid, "role" to "manager",
                    "name" to (snapshot.getString("displayName") ?: "Manager"),
                    "status" to "active", "createdAt" to System.currentTimeMillis()
                )).await()
                member = root.collection("members").document(user.uid).get().await()
            }
            if (!member.exists() || member.getString("status") != "active") return false
            val memberRole = member.getString("role") ?: return false
            if (memberRole !in setOf("manager", "driver") || memberRole != profileRole) return false
            rememberMembership(context, orgId, memberRole, name)
            if (memberRole == "manager") loadManagerPin(context, orgId, user.uid)
            true
        } catch (_: Exception) {
            hasActiveOrganization(context)
        }
    }

    suspend fun registerBusiness(
        context: Context,
        businessName: String,
        businessType: String,
        ownerName: String,
        phone: String,
        email: String,
        address: String,
        city: String,
        state: String,
        gstin: String,
        managerPin: String
    ): String {
        val user = Firebase.auth.currentUser
            ?: throw IllegalStateException("Sign in with Google before registering a business.")
        require(businessName.isNotBlank()) { "Business name is required." }
        require(ownerName.isNotBlank()) { "Owner name is required." }
        require(phone.isNotBlank()) { "Contact number is required." }
        require(managerPin.length in 4..8 && managerPin.all { it.isDigit() }) {
            "Manager PIN must contain 4 to 8 digits."
        }

        val db = firestore(context)
        val orgRef = db.collection("organizations").document()
        val code = generateBusinessCode(db)
        val (salt, hash) = hashPin(managerPin)
        val now = System.currentTimeMillis()
        val batch = db.batch()

        batch.set(orgRef, hashMapOf<String, Any>(
            "organizationId" to orgRef.id,
            "businessName" to businessName.trim(),
            "businessType" to businessType.trim(),
            "ownerName" to ownerName.trim(),
            "contactPhone" to phone.trim(),
            "contactEmail" to email.trim(),
            "address" to address.trim(),
            "city" to city.trim(),
            "state" to state.trim(),
            "gstin" to gstin.trim(),
            "ownerUid" to user.uid,
            "businessCode" to code,
            "createdAt" to now,
            "status" to "active"
        ))
        batch.set(orgRef.collection("members").document(user.uid), mapOf(
            "uid" to user.uid,
            "role" to "manager",
            "name" to ownerName.trim(),
            "status" to "active",
            "createdAt" to now
        ))
        batch.set(orgRef.collection("settings").document("driver_policy"), mapOf(
            "logoutTimeMinutes" to 21 * 60,
            "allowEarlyLogout" to false,
            "timezone" to "Asia/Kolkata",
            "updatedAt" to now
        ))
        batch.set(orgRef.collection("manager_secrets").document(user.uid), mapOf(
            "pinSalt" to salt,
            "pinHash" to hash,
            "updatedAt" to now
        ))
        batch.set(db.collection("business_codes").document(code), mapOf(
            "organizationId" to orgRef.id,
            "businessName" to businessName.trim(),
            "active" to true,
            "createdAt" to now
        ))
        batch.set(db.collection("users").document(user.uid), mapOf(
            "uid" to user.uid,
            "organizationId" to orgRef.id,
            "role" to "manager",
            "businessName" to businessName.trim(),
            "displayName" to ownerName.trim(),
            "updatedAt" to now
        ))
        batch.commit().await()

        rememberMembership(
            context,
            orgRef.id,
            "manager",
            businessName.trim(),
            code,
            preserveLegacyPilotData = true
        )
        prefs(context).edit().putString(KEY_PIN_SALT, salt).putString(KEY_PIN_HASH, hash).apply()

        // Carry forward the locally available pilot fleet into the new first business workspace.
        val dao = com.example.data.db.AppDatabase.getDatabase(context).fleetDao()
        val sync = com.example.data.firebase.FirestoreSyncManager(context)
        val legacyTrucks = dao.getAllTrucks().first()
        legacyTrucks.forEach { truck ->
            sync.syncTruck(truck)
            firestore(context).collection("organizations").document(orgRef.id)
                .collection("truck_subscriptions").document(truck.id.toString())
                .set(mapOf(
                    "truckId" to truck.id,
                    "amountPaise" to 0,
                    "currency" to "INR",
                    "billingPeriod" to "monthly",
                    "mode" to "legacy_import",
                    "status" to "legacy_active",
                    "startedAt" to now,
                    "createdBy" to user.uid
                )).await()
        }
        dao.getAllDrivers().first().forEach { sync.syncDriver(it) }
        dao.getLocationsSince(now - 30L * 24L * 60L * 60L * 1000L).first()
            .chunked(450).forEach { sync.syncLocationBatch(it) }
        dao.getAllStops().first().chunked(450).forEach { sync.syncStopBatch(it) }
        dao.getAllAttendance().first().forEach { sync.syncAttendance(it) }
        dao.getAllVoiceNotes().first().forEach { sync.syncVoiceNote(it) }
        return code
    }

    fun generateDriverInviteCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        return buildString { repeat(12) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    suspend fun joinBusiness(context: Context, driverInvitationCode: String, driverName: String, phone: String): String {
        val user = Firebase.auth.currentUser
            ?: throw IllegalStateException("Sign in with Google before joining a business.")
        require(driverName.isNotBlank()) { "Driver name is required." }
        require(phone.filter { it.isDigit() }.length >= 8) { "Enter the phone number registered by your manager." }
        val code = driverInvitationCode.trim().uppercase()
        require(code.length in 10..16) { "Enter the one-time driver invitation code shared by your manager." }

        val db = firestore(context)
        val invitationRef = db.collection("driver_invitations").document(code)
        val invitation = invitationRef.get().await()
        if (!invitation.exists() || invitation.getBoolean("active") != true || !invitation.getString("uid").isNullOrBlank()) {
            throw IllegalArgumentException("This driver invitation is invalid, expired or already used.")
        }
        if (invitation.getString("phoneHash") != hashPhone(phone)) {
            throw IllegalArgumentException("The phone number does not match the number registered by your manager.")
        }

        val orgId = invitation.getString("organizationId")
            ?: throw IllegalStateException("Invitation is missing its business assignment.")
        val businessName = invitation.getString("businessName") ?: "Business"
        val existing = db.collection("users").document(user.uid).get().await()
        val oldOrg = existing.getString("organizationId")
        if (!oldOrg.isNullOrBlank() && oldOrg != orgId) {
            throw IllegalStateException("This Google account is already assigned to another business.")
        }

        // Truck selection happens when the driver starts a shift, never during invitation.
        val driverId = invitation.getLong("driverId")
            ?: throw IllegalStateException("Invitation is missing the driver profile.")
        val orgRef = db.collection("organizations").document(orgId)
        val driverRef = orgRef.collection("drivers").document("driver_" + driverId)
        val now = System.currentTimeMillis()
        val driverDisplayName = invitation.getString("driverName") ?: driverName.trim()
        val batch = db.batch()
        batch.update(invitationRef, mapOf("uid" to user.uid, "claimedAt" to now, "active" to false))
        batch.update(driverRef, mapOf(
            "uid" to user.uid,
            "name" to driverDisplayName,
            "phone" to phone.trim(),
            "assignedTruckId" to 0,
            "status" to "active"
        ))
        batch.set(orgRef.collection("members").document(user.uid), mapOf(
            "uid" to user.uid,
            "driverId" to driverId,
            "role" to "driver",
            "name" to driverDisplayName,
            "phone" to phone.trim(),
            "activeTruckId" to 0,
            "earlyLogoutEnabled" to false,
            "status" to "active",
            "createdAt" to now,
            "invitationId" to code
        ))
        batch.set(db.collection("users").document(user.uid), mapOf(
            "uid" to user.uid,
            "organizationId" to orgId,
            "role" to "driver",
            "businessName" to businessName,
            "displayName" to driverDisplayName,
            "phone" to phone.trim(),
            "updatedAt" to now
        ))
        batch.commit().await()

        rememberMembership(context, orgId, "driver", businessName, code)
        refreshFleetCache(context)
        return businessName
    }

    // Firestore transactions serialize competing claims of the same truck. A driver
    // must be online to start, switch, or end a shift.
    suspend fun changeActiveTruck(context: Context, expectedOldId: Int?, targetId: Int?) {
        require(role(context) == "driver") { "Only drivers can claim trucks." }
        val orgId = organizationId(context) ?: error("Business session missing.")
        val uid = currentUserUid() ?: error("Sign in to continue.")
        val db = firestore(context)
        val root = db.collection("organizations").document(orgId)
        val memberRef = root.collection("members").document(uid)
        val oldRef = expectedOldId?.let { root.collection("trucks").document("truck_$it") }
        val targetRef = targetId?.let { root.collection("trucks").document("truck_$it") }
        require(expectedOldId != targetId) { "Select a different truck." }
        db.runTransaction { tx ->
            val member = tx.get(memberRef)
            check(member.getString("role") == "driver" && member.getString("status") == "active") {
                "Driver membership is not active."
            }
            val active = member.getLong("activeTruckId")?.toInt()?.takeIf { it > 0 }
            val legacyActive = active == null && expectedOldId != null &&
                member.getLong("assignedTruckId")?.toInt() == expectedOldId
            check(active == expectedOldId || legacyActive) {
                "Your truck assignment changed. Refresh and retry."
            }
            val old = oldRef?.let { tx.get(it) }
            val target = targetRef?.let { tx.get(it) }
            if (old != null) {
                val legacyOwned = legacyActive && old.getString("activeDriverUid").isNullOrBlank() &&
                    old.getBoolean("isShiftActive") == true
                check(old.exists() && (old.getString("activeDriverUid") == uid || legacyOwned)) {
                    "You no longer own the previous truck."
                }
            }
            if (target != null) {
                check(target.exists() && target.getLong("id")?.toInt() == targetId) {
                    "Truck not found."
                }
                check(target.getString("activeDriverUid").isNullOrBlank() &&
                    target.getBoolean("isShiftActive") != true) {
                    "This truck is already in use."
                }
            }
            val now = System.currentTimeMillis()
            if (oldRef != null) tx.update(oldRef, mapOf(
                "activeDriverUid" to "", "isShiftActive" to false,
                "statusText" to "Off Duty", "currentSpeedKmh" to 0.0,
                "lastUpdated" to now
            ))
            if (targetRef != null) tx.update(targetRef, mapOf(
                "activeDriverUid" to uid, "isShiftActive" to true,
                "statusText" to "Shift Active - En Route", "lastUpdated" to now
            ))
            tx.update(memberRef, "activeTruckId", targetId ?: 0)
            null
        }.await()
    }

    suspend fun refreshFleetCache(context: Context) {
        val orgId = organizationId(context) ?: return
        val uid = Firebase.auth.currentUser?.uid ?: return
        val memberRole = role(context) ?: return
        val isManager = memberRole == "manager"
        val member = firestore(context).collection("organizations").document(orgId)
            .collection("members").document(uid).get().await()
        check(member.exists() && member.getString("status") == "active") { "Driver membership is not active." }
        val assignedId = member.getLong("activeTruckId")?.toInt()?.takeIf { it > 0 }
        val dao = com.example.data.db.AppDatabase.getDatabase(context).fleetDao()
        val root = firestore(context).collection("organizations").document(orgId)

        // Drivers can browse every truck in their own business. A truck is claimed
        // atomically only when a shift starts; Firestore rules enforce tenant scope.
        val truckQuery = root.collection("trucks")
        val truckSnapshot = truckQuery.get().await()
        val truckDocs = truckSnapshot?.documents.orEmpty()

        truckDocs.forEach { doc ->
            val id = (doc.getLong("id") ?: doc.id.removePrefix("truck_").toLongOrNull() ?: return@forEach).toInt()
            dao.insertTruck(com.example.data.model.TruckEntity(
                id = id,
                name = doc.getString("name") ?: "Truck $id",
                plateNumber = doc.getString("plateNumber") ?: "",
                driverName = doc.getString("driverName") ?: "",
                capacityLiters = (doc.getLong("capacityLiters") ?: 0L).toInt(),
                currentLat = doc.getDouble("currentLat") ?: 0.0,
                currentLng = doc.getDouble("currentLng") ?: 0.0,
                currentSpeedKmh = (doc.getDouble("currentSpeedKmh") ?: 0.0).toFloat(),
                isShiftActive = doc.getBoolean("isShiftActive") ?: false,
                statusText = doc.getString("statusText") ?: "Off Duty",
                lastUpdated = doc.getLong("lastUpdated") ?: System.currentTimeMillis()
            ))
        }

        val driverDocs = if (isManager) {
            root.collection("drivers").get().await().documents
        } else {
            root.collection("drivers").whereEqualTo("uid", uid).limit(1).get().await().documents
        }
        driverDocs.forEach { doc ->
            val id = doc.getLong("id") ?: doc.id.removePrefix("driver_").toLongOrNull()
                ?: doc.getString("uid")?.hashCode()?.toLong()?.let { kotlin.math.abs(it) } ?: return@forEach
            val hydratedDriver = com.example.data.model.DriverEntity(
                id = id.coerceAtLeast(1L),
                name = doc.getString("name") ?: "Driver",
                phone = doc.getString("phone") ?: "",
                assignedTruckId = (doc.getLong("assignedTruckId") ?: 0L).toInt(),
                createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis(),
                userUid = doc.getString("uid")?.takeIf { it.isNotBlank() },
                earlyLogoutEnabled = doc.getBoolean("earlyLogoutEnabled") ?: false,
                invitationCode = doc.getString("invitationCode") ?: if (isManager) generateDriverInviteCode() else null
            )
            dao.insertDriver(hydratedDriver)
            if (isManager && doc.getString("invitationCode").isNullOrBlank()) {
                com.example.data.firebase.FirestoreSyncManager(context).syncDriver(hydratedDriver)
            }
        }

        val cutoff = System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L
        val locationDocs = if (isManager) {
            root.collection("location_points").whereGreaterThanOrEqualTo("timestamp", cutoff).get().await().documents
        } else if (assignedId != null) {
            root.collection("location_points")
                .whereGreaterThanOrEqualTo("timestamp", cutoff)
                .whereEqualTo("truckId", assignedId).get().await().documents
        } else emptyList()
        locationDocs.forEach { doc ->
            val truckId = (doc.getLong("truckId") ?: return@forEach).toInt()
            val timestamp = doc.getLong("timestamp") ?: return@forEach
            val localId = stableLocalRecordId(truckId, timestamp)
            dao.insertLocationPoint(LocationPoint(
                id = localId,
                truckId = truckId,
                latitude = doc.getDouble("latitude") ?: 0.0,
                longitude = doc.getDouble("longitude") ?: 0.0,
                speedKmh = (doc.getDouble("speedKmh") ?: 0.0).toFloat(),
                accuracyMeters = (doc.getDouble("accuracyMeters") ?: 5.0).toFloat(),
                timestamp = timestamp,
                isSynced = true
            ))
        }

        val stopCollection = root.collection("stop_records")
        val stopDocs = if (isManager) stopCollection.get().await().documents
            else if (assignedId != null) stopCollection.whereEqualTo("truckId", assignedId).get().await().documents
            else emptyList()
        stopDocs.forEach { doc ->
            val truckId = (doc.getLong("truckId") ?: return@forEach).toInt()
            val startTime = doc.getLong("startTime") ?: return@forEach
            dao.insertStop(StopRecord(
                id = stableLocalRecordId(truckId, startTime),
                truckId = truckId,
                latitude = doc.getDouble("latitude") ?: 0.0,
                longitude = doc.getDouble("longitude") ?: 0.0,
                startTime = startTime,
                endTime = doc.getLong("endTime") ?: startTime,
                durationMinutes = (doc.getLong("durationMinutes") ?: 0L).toInt(),
                addressOrLabel = doc.getString("addressOrLabel") ?: "",
                category = doc.getString("category") ?: "Delivery Stop",
                isSynced = true
            ))
        }

        val voiceCollection = root.collection("voice_delivery_notes")
        val voiceDocs = if (isManager) voiceCollection.get().await().documents
            else if (assignedId != null) voiceCollection.whereEqualTo("truckId", assignedId).get().await().documents
            else emptyList()
        voiceDocs.forEach { doc ->
            val truckId = (doc.getLong("truckId") ?: return@forEach).toInt()
            val timestamp = doc.getLong("timestamp") ?: return@forEach
            val audioPath = doc.getString("audioFilePath") ?: ""
            dao.insertVoiceNote(VoiceDeliveryNote(
                id = stableLocalRecordId(truckId, timestamp),
                truckId = truckId,
                driverName = doc.getString("driverName") ?: "",
                audioFilePath = audioPath,
                durationSeconds = (doc.getLong("durationSeconds") ?: 0L).toInt(),
                latitude = doc.getDouble("latitude") ?: 0.0,
                longitude = doc.getDouble("longitude") ?: 0.0,
                locationLabel = doc.getString("locationLabel") ?: "Water Delivery Site",
                timestamp = timestamp,
                isSynced = true
            ))
        }

        val attendanceCollection = root.collection("attendance")
        val attendanceDocs = if (isManager) attendanceCollection.get().await().documents
            else if (assignedId != null) attendanceCollection.whereEqualTo("truckId", assignedId).get().await().documents
            else emptyList()
        attendanceDocs.forEach { doc ->
            val truckId = (doc.getLong("truckId") ?: return@forEach).toInt()
            val checkInTime = doc.getLong("checkInTime") ?: return@forEach
            val checkOut = doc.getLong("checkOutTime") ?: 0L
            dao.insertAttendance(AttendanceRecord(
                id = stableLocalRecordId(truckId, checkInTime),
                truckId = truckId,
                driverName = doc.getString("driverName") ?: "",
                selfieUri = doc.getString("selfieUri")?.takeIf { it.isNotBlank() },
                checkInTime = checkInTime,
                checkOutTime = checkOut.takeIf { it > 0L },
                checkInLat = doc.getDouble("checkInLat") ?: 0.0,
                checkInLng = doc.getDouble("checkInLng") ?: 0.0,
                isCompleted = doc.getBoolean("isCompleted") ?: (checkOut > 0L)
            ))
        }
    }

    private fun stableLocalRecordId(truckId: Int, timestamp: Long): Long =
        truckId.toLong().coerceAtLeast(0L) * 3_000_000_000_000L +
            (timestamp % 3_000_000_000_000L)

    suspend fun assignDriverToTruck(context: Context, driver: com.example.data.model.DriverEntity, truckId: Int) {
        require(truckId > 0) { "Select a valid truck." }
        val orgId = organizationId(context) ?: throw IllegalStateException("Business not selected.")
        check(role(context) == "manager") { "Only a manager can assign drivers." }
        val root = firestore(context).collection("organizations").document(orgId)
        val batch = firestore(context).batch()
        val driverRef = root.collection("drivers").document("driver_" + driver.id)
        batch.set(driverRef, mapOf(
            "assignedTruckId" to truckId,
            "earlyLogoutEnabled" to driver.earlyLogoutEnabled
        ), com.google.firebase.firestore.SetOptions.merge())
        driver.userUid?.let { uid ->
            batch.set(root.collection("members").document(uid), mapOf("assignedTruckId" to truckId),
                com.google.firebase.firestore.SetOptions.merge())
        }
        driver.invitationCode?.let { code ->
            batch.set(firestore(context).collection("driver_invitations").document(code),
                mapOf(
                    "organizationId" to orgId,
                    "businessName" to (businessName(context) ?: "Business"),
                    "driverId" to driver.id,
                    "driverName" to driver.name,
                    "phoneHash" to hashPhone(driver.phone),
                    "assignedTruckId" to truckId,
                    "active" to driver.userUid.isNullOrBlank()
                ), com.google.firebase.firestore.SetOptions.merge())
        }
        batch.commit().await()
    }

    suspend fun setDriverEarlyLogout(context: Context, driver: com.example.data.model.DriverEntity, enabled: Boolean) {
        val orgId = organizationId(context) ?: throw IllegalStateException("Business not selected.")
        check(role(context) == "manager") { "Only a manager can change driver permissions." }
        val root = firestore(context).collection("organizations").document(orgId)
        val batch = firestore(context).batch()
        batch.set(root.collection("drivers").document("driver_" + driver.id),
            mapOf("earlyLogoutEnabled" to enabled), com.google.firebase.firestore.SetOptions.merge())
        driver.userUid?.let { uid ->
            batch.set(root.collection("members").document(uid),
                mapOf("earlyLogoutEnabled" to enabled), com.google.firebase.firestore.SetOptions.merge())
        }
        batch.commit().await()
    }

    fun observeAssignedTruckId(context: Context): Flow<Int?> = callbackFlow {
        val uid = Firebase.auth.currentUser?.uid
        val orgId = organizationId(context)
        if (uid.isNullOrBlank() || orgId.isNullOrBlank() || role(context) != "driver") {
            trySend(assignedTruckId(context))
            close()
            return@callbackFlow
        }
        val listener = firestore(context).collection("organizations").document(orgId)
            .collection("members").document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(snapshot?.getLong("activeTruckId")?.toInt()?.takeIf { it > 0 })
            }
        awaitClose { listener.remove() }
    }

    fun observeOwnEarlyLogout(context: Context): Flow<Boolean> = callbackFlow {
        val uid = Firebase.auth.currentUser?.uid
        val orgId = organizationId(context)
        if (uid.isNullOrBlank() || orgId.isNullOrBlank() || role(context) != "driver") {
            trySend(false)
            close()
            return@callbackFlow
        }
        val listener = firestore(context).collection("organizations").document(orgId)
            .collection("members").document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(snapshot?.getBoolean("earlyLogoutEnabled") ?: false)
            }
        awaitClose { listener.remove() }
    }

    private fun hashPhone(phone: String): String {
        val normalized = phone.filter { it.isDigit() }
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    suspend fun saveShiftPolicy(context: Context, policy: DriverShiftPolicy) {
        require(policy.logoutTimeMinutes in 0..1439) { "Choose a valid logout time." }
        val orgId = organizationId(context) ?: throw IllegalStateException("Business not selected.")
        check(role(context) == "manager") { "Only a manager can change shift settings." }
        firestore(context).collection("organizations").document(orgId)
            .collection("settings").document("driver_policy")
            .set(mapOf(
                "logoutTimeMinutes" to policy.logoutTimeMinutes,
                "allowEarlyLogout" to policy.allowEarlyLogout,
                "timezone" to policy.timezone,
                "updatedAt" to System.currentTimeMillis()
            )).await()
    }

    fun observeShiftPolicy(context: Context): Flow<DriverShiftPolicy> = callbackFlow {
        val orgId = organizationId(context)
        if (orgId.isNullOrBlank()) {
            trySend(DriverShiftPolicy())
            close()
            return@callbackFlow
        }
        val listener = firestore(context).collection("organizations").document(orgId)
            .collection("settings").document("driver_policy")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(DriverShiftPolicy(
                    logoutTimeMinutes = (snapshot?.getLong("logoutTimeMinutes") ?: 21L * 60L).toInt().coerceIn(0, 1439),
                    allowEarlyLogout = snapshot?.getBoolean("allowEarlyLogout") ?: false,
                    timezone = snapshot?.getString("timezone") ?: "Asia/Kolkata"
                ))
            }
        awaitClose { listener.remove() }
    }

    suspend fun uploadVoiceAudio(context: Context, localPath: String, noteId: Long): String {
        val orgId = organizationId(context) ?: throw IllegalStateException("Business not selected.")
        val user = Firebase.auth.currentUser ?: throw IllegalStateException("Sign in required.")
        val file = File(localPath)
        require(file.exists() && file.length() > 0L) { "Audio file is missing or empty." }
        val extension = file.extension.ifBlank { "m4a" }
        val ref = FirebaseStorage.getInstance().reference
            .child("organizations/$orgId/voice/voice_$noteId.$extension")
        val metadata = StorageMetadata.Builder()
            .setContentType(if (extension.equals("wav", true)) "audio/wav" else "audio/mp4")
            .setCustomMetadata("organizationId", orgId)
            .setCustomMetadata("ownerUid", user.uid)
            .build()
        ref.putFile(Uri.fromFile(file), metadata).await()
        return ref.downloadUrl.await().toString()
    }

    fun verifyManagerPin(context: Context, pin: String): Boolean {
        if (role(context) != "manager") return false
        val salt = prefs(context).getString(KEY_PIN_SALT, null) ?: return false
        val expected = prefs(context).getString(KEY_PIN_HASH, null) ?: return false
        return runCatching { constantTimeEquals(expected, derivePin(pin, salt)) }.getOrDefault(false)
    }

    private suspend fun loadManagerPin(context: Context, orgId: String, uid: String) {
        val secret = firestore(context).collection("organizations").document(orgId)
            .collection("manager_secrets").document(uid).get().await()
        val salt = secret.getString("pinSalt") ?: return
        val hash = secret.getString("pinHash") ?: return
        prefs(context).edit().putString(KEY_PIN_SALT, salt).putString(KEY_PIN_HASH, hash).apply()
    }

    private suspend fun generateBusinessCode(db: FirebaseFirestore): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        repeat(10) {
            val code = buildString { repeat(8) { append(alphabet[random.nextInt(alphabet.length)]) } }
            if (!db.collection("business_codes").document(code).get().await().exists()) return code
        }
        throw IllegalStateException("Could not create a unique business invitation code. Please try again.")
    }

    private fun hashPin(pin: String): Pair<String, String> {
        val saltBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val salt = android.util.Base64.encodeToString(saltBytes, android.util.Base64.NO_WRAP)
        return salt to derivePin(pin, salt)
    }

    private fun derivePin(pin: String, salt: String): String {
        val spec = PBEKeySpec(pin.toCharArray(), android.util.Base64.decode(salt, android.util.Base64.DEFAULT), 120_000, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
