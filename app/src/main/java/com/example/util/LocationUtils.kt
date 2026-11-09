package com.example.util

import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Log
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

object LocationUtils {

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(context: Context): Location? = withContext(Dispatchers.IO) {
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)

        // 1. Try high accuracy current location with 5s timeout
        val freshLocation = withTimeoutOrNull(5000L) {
            suspendCancellableCoroutine<Location?> { cont ->
                try {
                    val cts = CancellationTokenSource()
                    fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                        .addOnSuccessListener { loc ->
                            if (cont.isActive) cont.resume(loc)
                        }
                        .addOnFailureListener {
                            if (cont.isActive) cont.resume(null)
                        }
                    cont.invokeOnCancellation { cts.cancel() }
                } catch (e: Exception) {
                    Log.w("LocationUtils", "Exception getting high accuracy location: ${e.message}")
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

        if (freshLocation != null && (freshLocation.latitude != 0.0 || freshLocation.longitude != 0.0)) {
            return@withContext freshLocation
        }

        // 2. Fallback to last known location from fused client
        val lastLocation = withTimeoutOrNull(2000L) {
            suspendCancellableCoroutine<Location?> { cont ->
                try {
                    fusedClient.lastLocation
                        .addOnSuccessListener { loc ->
                            if (cont.isActive) cont.resume(loc)
                        }
                        .addOnFailureListener {
                            if (cont.isActive) cont.resume(null)
                        }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

        if (lastLocation != null && (lastLocation.latitude != 0.0 || lastLocation.longitude != 0.0)) {
            return@withContext lastLocation
        }

        // 3. Fallback to LocationManager (GPS or Network provider)
        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (locationManager != null) {
                val gpsLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (gpsLoc != null && (gpsLoc.latitude != 0.0 || gpsLoc.longitude != 0.0)) return@withContext gpsLoc

                val netLoc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (netLoc != null && (netLoc.latitude != 0.0 || netLoc.longitude != 0.0)) return@withContext netLoc

                val passiveLoc = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                if (passiveLoc != null && (passiveLoc.latitude != 0.0 || passiveLoc.longitude != 0.0)) return@withContext passiveLoc
            }
        } catch (e: Exception) {
            Log.w("LocationUtils", "LocationManager fallback failed: ${e.message}")
        }

        null
    }

    suspend fun getAddressLabel(context: Context, lat: Double, lng: Double): String = withContext(Dispatchers.IO) {
        if (lat == 0.0 && lng == 0.0) return@withContext "Location Unavailable"

        try {
            val geocoder = Geocoder(context, Locale.getDefault())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val addresses = suspendCancellableCoroutine<List<Address>?> { cont ->
                    geocoder.getFromLocation(lat, lng, 1) { list ->
                        if (cont.isActive) cont.resume(list)
                    }
                }
                val address = addresses?.firstOrNull()
                if (address != null) {
                    val thoroughfare = address.thoroughfare ?: address.subLocality ?: address.locality
                    val city = address.locality ?: address.subAdminArea
                    return@withContext listOfNotNull(thoroughfare, city).filter { it.isNotBlank() }.joinToString(", ")
                }
            } else {
                @Suppress("DEPRECATION")
                val list = geocoder.getFromLocation(lat, lng, 1)
                val address = list?.firstOrNull()
                if (address != null) {
                    val thoroughfare = address.thoroughfare ?: address.subLocality ?: address.locality
                    val city = address.locality ?: address.subAdminArea
                    return@withContext listOfNotNull(thoroughfare, city).filter { it.isNotBlank() }.joinToString(", ")
                }
            }
        } catch (e: Exception) {
            Log.w("LocationUtils", "Geocoder failed: ${e.message}")
        }

        // Clean coordinate fallback
        String.format(Locale.US, "GPS: %.4f, %.4f", lat, lng)
    }
}
