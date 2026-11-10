package com.example.data.firebase

import android.content.Context
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.tasks.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Private Supabase Storage bridge. Firebase remains the identity provider;
 * the Edge Function validates Firebase ID tokens and tenant membership.
 */
object SupabaseAudioStorage {
    private const val PROJECT_URL = "https://wnojkztmktgpzsiabsjs.supabase.co"
    private const val PUBLISHABLE_KEY = "sb_publishable_D2puAllRETai5xJeusUwrg_1-42XnxR"
    private const val FUNCTION_URL = "$PROJECT_URL/functions/v1/voice-audio"
    private const val BUCKET_PREFIX = "supabase-audio://"
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun upload(context: Context, localPath: String, truckId: Int, timestamp: Long): String {
        val file = File(localPath)
        require(file.exists() && file.length() in 1..(20L * 1024L * 1024L)) {
            "Audio file is missing, empty, or exceeds 20 MB."
        }
        val orgId = organizationId(context)
        val extension = file.extension.lowercase().ifBlank { "m4a" }
        require(extension in setOf("m4a", "mp4", "wav")) { "Unsupported audio format: $extension" }
        val token = firebaseToken()
        val mediaType = if (extension == "wav") "audio/wav" else "audio/mp4"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("orgId", orgId)
            .addFormDataPart("truckId", truckId.toString())
            .addFormDataPart("timestamp", timestamp.toString())
            .addFormDataPart("extension", extension)
            .addFormDataPart("file", file.name, file.asRequestBody(mediaType.toMediaType()))
            .build()
        val request = Request.Builder()
            .url(FUNCTION_URL)
            .header("apikey", PUBLISHABLE_KEY)
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(errorMessage(responseText, response.code))
            return "$BUCKET_PREFIX$truckId/$timestamp.$extension"
        }
    }

    suspend fun downloadToCache(context: Context, remoteReference: String): File {
        require(remoteReference.startsWith(BUCKET_PREFIX)) { "Not a Supabase audio reference." }
        val relative = remoteReference.removePrefix(BUCKET_PREFIX)
        val parts = relative.split("/", limit = 2)
        require(parts.size == 2) { "Invalid audio reference." }
        val truckId = parts[0].toIntOrNull() ?: throw IllegalArgumentException("Invalid truck reference.")
        val filename = parts[1]
        val timestamp = filename.substringBeforeLast(".").toLongOrNull()
            ?: throw IllegalArgumentException("Invalid recording timestamp.")
        val extension = filename.substringAfterLast(".", "").lowercase()
        require(extension in setOf("m4a", "mp4", "wav")) { "Unsupported audio format." }
        val token = firebaseToken()
        val orgId = organizationId(context)
        val payload = JSONObject()
            .put("action", "download")
            .put("orgId", orgId)
            .put("truckId", truckId)
            .put("timestamp", timestamp)
            .put("extension", extension)
            .toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(FUNCTION_URL)
            .header("apikey", PUBLISHABLE_KEY)
            .header("Authorization", "Bearer $token")
            .post(payload)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val message = response.body?.string().orEmpty()
                throw IllegalStateException(errorMessage(message, response.code))
            }
            val bytes = response.body?.bytes() ?: throw IllegalStateException("Empty audio response.")
            val directory = File(context.cacheDir, "voice_deliveries").apply { mkdirs() }
            val output = File(directory, "voice_${truckId}_${timestamp}.${extension}")
            output.writeBytes(bytes)
            return output
        }
    }

    fun isRemoteReference(path: String): Boolean = path.startsWith(BUCKET_PREFIX)

    private fun organizationId(context: Context): String =
        context.getSharedPreferences("aquatrack_tenant", Context.MODE_PRIVATE)
            .getString("organization_id", null)
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Business membership is not configured.")

    private suspend fun firebaseToken(): String =
        Firebase.auth.currentUser?.getIdToken(false)?.await()?.token
            ?: throw IllegalStateException("Sign in to AquaTruck again.")

    private fun errorMessage(body: String, status: Int): String = try {
        JSONObject(body).optString("error").ifBlank { "Audio service failed ($status)." }
    } catch (_: Exception) {
        "Audio service failed ($status)."
    }
}
