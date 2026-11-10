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
    private const val KEY_ROLE = "organization_role"
    private const val KEY_BUSINESS_NAME = "business_name"
    private const val KEY_BUSINESS_CODE = "business_code"
    private const val KEY_PIN_SALT = "manager_pin_salt"
    private const val KEY_PIN_HASH = "manager_pin_hash"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun firestore(context: Context): FirebaseFirestore =
        FirebaseFirestore.getInstance(context.getString(R.string.firestore_database_id))

    fun organizationId(context: Context): String? = prefs(context).getString(KEY_ORG, null)
    fun role(context: Context): String? = prefs(context).getString(KEY_ROLE, null)
    fun businessName(context: Context): String? = prefs(context).getString(KEY_BUSINESS_NAME, null)
    fun businessCode(context: Context): String? = prefs(context).getString(KEY_BUSINESS_CODE, null)

    fun hasActiveOrganization(context: Context): Boolean =
        !organizationId(context).isNullOrBlank() && !role(context).isNullOrBlank()

    private fun rememberMembership(context: Context, orgId: String, memberRole: String, name: String, code: String? = null) {
        prefs(context).edit()
            .putString(KEY_ORG, orgId)
            .putString(KEY_ROLE, memberRole)
            .putString(KEY_BUSINESS_NAME, name)
            .apply {
                if (code != null) putString(KEY_BUSINESS_CODE, code)
            }
            .apply()
    }

    suspend fun restoreMembership(context: Context): Boolean {
        val user = Firebase.auth.currentUser ?: return false
        return try {
            val snapshot = firestore(context).collection("users").document(user.uid).get().await()
            val orgId = snapshot.getString("organizationId") ?: return false
            val memberRole = snapshot.getString("role") ?: return false
            val name = snapshot.getString("businessName") ?: "My Business"
            if (memberRole !in setOf("manager", "driver")) return false
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

        rememberMembership(context, orgRef.id, "manager", businessName.trim(), code)
        prefs(context).edit().putString(KEY_PIN_SALT, salt).putString(KEY_PIN_HASH, hash).apply()
        return code
    }

    suspend fun joinBusiness(context: Context, businessCode: String, driverName: String, phone: String): String {
        val user = Firebase.auth.currentUser
            ?: throw IllegalStateException("Sign in with Google before joining a business.")
        require(driverName.isNotBlank()) { "Driver name is required." }
        val code = businessCode.trim().uppercase()
        require(code.length in 6..12) { "Enter a valid business invitation code." }

        val db = firestore(context)
        val codeSnapshot = db.collection("business_codes").document(code).get().await()
        if (!codeSnapshot.exists() || codeSnapshot.getBoolean("active") != true) {
            throw IllegalArgumentException("Business code not found or no longer active.")
        }
        val orgId = codeSnapshot.getString("organizationId")
            ?: throw IllegalStateException("This invitation is incomplete.")
        val businessName = codeSnapshot.getString("businessName") ?: "Business"
        val existing = db.collection("users").document(user.uid).get().await()
        val oldOrg = existing.getString("organizationId")
        if (!oldOrg.isNullOrBlank() && oldOrg != orgId) {
            throw IllegalStateException("This Google account is already assigned to another business.")
        }

        val now = System.currentTimeMillis()
        val rawId = user.uid.hashCode().toLong()
        val numericId = (if (rawId == Long.MIN_VALUE) 1L else kotlin.math.abs(rawId)).coerceAtLeast(1L)
        val driverRef = db.collection("organizations").document(orgId)
            .collection("drivers").document("driver_" + user.uid)
        val batch = db.batch()
        batch.set(db.collection("organizations").document(orgId).collection("members").document(user.uid), mapOf(
            "uid" to user.uid,
            "role" to "driver",
            "name" to driverName.trim(),
            "phone" to phone.trim(),
            "assignedTruckId" to 1,
            "status" to "active",
            "createdAt" to now,
            "invitationCode" to code
        ))
        batch.set(driverRef, mapOf(
            "id" to numericId,
            "uid" to user.uid,
            "name" to driverName.trim(),
            "phone" to phone.trim(),
            "assignedTruckId" to 1,
            "createdAt" to now,
            "status" to "active"
        ))
        batch.set(db.collection("users").document(user.uid), mapOf(
            "uid" to user.uid,
            "organizationId" to orgId,
            "role" to "driver",
            "businessName" to businessName,
            "displayName" to driverName.trim(),
            "phone" to phone.trim(),
            "updatedAt" to now
        ))
        batch.commit().await()

        rememberMembership(context, orgId, "driver", businessName, code)
        refreshFleetCache(context)
        return businessName
    }

    suspend fun refreshFleetCache(context: Context) {
        val orgId = organizationId(context) ?: return
        val dao = com.example.data.db.AppDatabase.getDatabase(context).fleetDao()
        val root = firestore(context).collection("organizations").document(orgId)

        root.collection("trucks").get().await().documents.forEach { doc ->
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

        root.collection("drivers").get().await().documents.forEach { doc ->
            val id = doc.getLong("id") ?: doc.id.removePrefix("driver_").toLongOrNull()
                ?: doc.getString("uid")?.hashCode()?.toLong()?.let { kotlin.math.abs(it) } ?: return@forEach
            dao.insertDriver(com.example.data.model.DriverEntity(
                id = id.coerceAtLeast(1L),
                name = doc.getString("name") ?: "Driver",
                phone = doc.getString("phone") ?: "",
                assignedTruckId = (doc.getLong("assignedTruckId") ?: 1L).toInt(),
                createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
            ))
        }
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
