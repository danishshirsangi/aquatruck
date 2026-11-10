package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.db.AppDatabase
import com.example.data.firebase.FirestoreSyncManager
import com.example.data.model.LocationPoint
import com.example.data.model.StopRecord
import com.example.data.sync.LocationSyncScheduler
import com.example.util.LocationUtils
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class LocationTrackingService : Service() {

    private data class TrackingSample(val truckId: Int, val location: Location)

    // A single consumer serializes GPS samples and keeps each sample attached to the
    // tanker that was active when Android delivered that GPS callback.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val locationChannel = Channel<TrackingSample>(Channel.UNLIMITED)
    private var locationProcessorJob: Job? = null
    private var liveTruckSyncJob: Job? = null

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    @Volatile
    private var activeTruckId: Int = 1
    private var activeTruckName: String = "Water Tanker"

    private var currentIntervalMillis = 0L
    private var currentPriority = -1
    private var isTracking = false
    private var previousLocation: Location? = null
    private var stationarySamples = 0
    private var lastNotificationStatus: String? = null
    private var lastTruckSyncAt: Long = 0L

    // Automatic drop-point detection: within 100m for at least 2 minutes.
    private var anchorLat: Double = 0.0
    private var anchorLng: Double = 0.0
    private var anchorStartTime: Long = 0L
    private var hasLoggedCurrentDropPoint: Boolean = false

    private val sessionPrefs by lazy {
        getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
    }

    companion object {
        const val CHANNEL_ID = "aquatrack_fleet_tracking"
        const val NOTIFICATION_ID = 1001
        private const val MOVING_INTERVAL_MS = 20_000L
        private const val STATIONARY_INTERVAL_MS = 3 * 60_000L
        private const val MOVEMENT_DISTANCE_METERS = 25f
        private const val STATIONARY_SPEED_KMH = 2.5f
        private const val STATIONARY_SAMPLES_REQUIRED = 3

        private const val SESSION_PREFS = "aquatrack_tracking_service"
        private const val PREF_TRUCK_ID = "active_truck_id"
        private const val PREF_TRUCK_NAME = "active_truck_name"

        const val ACTION_START_TRACKING = "com.example.aquatrack.START_TRACKING"
        const val ACTION_STOP_TRACKING = "com.example.aquatrack.STOP_TRACKING"
        const val EXTRA_TRUCK_ID = "extra_truck_id"
        const val EXTRA_TRUCK_NAME = "extra_truck_name"

        fun startService(context: Context, truckId: Int, truckName: String) {
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = ACTION_START_TRACKING
                putExtra(EXTRA_TRUCK_ID, truckId)
                putExtra(EXTRA_TRUCK_NAME, truckName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = ACTION_STOP_TRACKING
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                // Sort a delivered batch before enqueueing it, preserving chronological order.
                result.locations.sortedBy { it.time }.forEach { location ->
                    if (location.latitude != 0.0 || location.longitude != 0.0) {
                        val queued = locationChannel.trySend(TrackingSample(activeTruckId, Location(location)))
                        if (queued.isFailure) {
                            Log.w("LocationTrackingService", "Location queue is closed; sample was not queued.")
                        }
                    }
                }
            }
        }

        locationProcessorJob = serviceScope.launch {
            for (sample in locationChannel) {
                try {
                    updateTrackingMode(sample.location)
                    handleNewLocation(sample.location, sample.truckId)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // One bad sample must not terminate the processor for all later GPS updates.
                    Log.e("LocationTrackingService", "Failed to process location sample", e)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TRACKING -> {
                activeTruckId = intent.getIntExtra(EXTRA_TRUCK_ID, 1)
                activeTruckName = intent.getStringExtra(EXTRA_TRUCK_NAME) ?: "Water Tanker $activeTruckId"
                sessionPrefs.edit()
                    .putInt(PREF_TRUCK_ID, activeTruckId)
                    .putString(PREF_TRUCK_NAME, activeTruckName)
                    .apply()

                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("Shift Active: $activeTruckName", "GPS tracking • adaptive 20s / 3m")
                )
                startTrackingLoop()
            }

            ACTION_STOP_TRACKING -> {
                stopLocationUpdates()
                isTracking = false
                locationChannel.close()

                // Drain samples already received before marking the truck off duty and stopping.
                serviceScope.launch {
                    try {
                        locationProcessorJob?.join()
                        val dao = AppDatabase.getDatabase(applicationContext).fleetDao()
                        dao.updateTruckShiftStatus(
                            truckId = activeTruckId,
                            active = false,
                            status = "Off Duty",
                            timestamp = System.currentTimeMillis()
                        )
                        // Flush the final locally saved locations/stops after shift end.
                        LocationSyncScheduler.enqueue(applicationContext)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("LocationTrackingService", "Failed while stopping tracking cleanly", e)
                    } finally {
                        sessionPrefs.edit().remove(PREF_TRUCK_ID).remove(PREF_TRUCK_NAME).apply()
                        withContext(Dispatchers.Main) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelfResult(startId)
                        }
                    }
                }
                return START_NOT_STICKY
            }

            null -> {
                // START_STICKY may recreate this service with a null intent after process death.
                val restoredTruckId = sessionPrefs.getInt(PREF_TRUCK_ID, -1)
                if (restoredTruckId <= 0) {
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }

                activeTruckId = restoredTruckId
                activeTruckName = sessionPrefs.getString(PREF_TRUCK_NAME, "Water Tanker $restoredTruckId")
                    ?: "Water Tanker $restoredTruckId"

                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("Shift Active: $activeTruckName", "Restoring GPS tracking • adaptive 20s / 3m")
                )
                startTrackingLoop()
            }

            else -> return START_NOT_STICKY
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startTrackingLoop() {
        if (isTracking) stopLocationUpdates()

        previousLocation = null
        stationarySamples = 0
        currentIntervalMillis = 0L
        currentPriority = -1
        lastNotificationStatus = null
        anchorLat = 0.0
        anchorLng = 0.0
        anchorStartTime = 0L
        hasLoggedCurrentDropPoint = false
        isTracking = true

        requestLocationUpdates(MOVING_INTERVAL_MS, Priority.PRIORITY_HIGH_ACCURACY)
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates(intervalMillis: Long, priority: Int) {
        val callback = locationCallback ?: return
        if (!isTracking) return
        if (currentIntervalMillis == intervalMillis && currentPriority == priority) return

        try {
            fusedLocationClient.removeLocationUpdates(callback)
            val request = LocationRequest.Builder(priority, intervalMillis)
                .setMinUpdateIntervalMillis(intervalMillis)
                .setMinUpdateDistanceMeters(if (intervalMillis == MOVING_INTERVAL_MS) 10f else 15f)
                .build()

            fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            currentIntervalMillis = intervalMillis
            currentPriority = priority
            Log.i(
                "LocationTrackingService",
                "Location mode updated: interval=${intervalMillis}ms priority=$priority"
            )
        } catch (e: SecurityException) {
            Log.w("LocationTrackingService", "Missing location permissions: ${e.message}", e)
        } catch (e: Exception) {
            Log.e("LocationTrackingService", "Unable to request location updates", e)
        }
    }

    private fun updateTrackingMode(location: Location) {
        val previous = previousLocation
        val movedMeters = if (previous == null) Float.MAX_VALUE else previous.distanceTo(location)
        val isMoving = location.speed * 3.6f > STATIONARY_SPEED_KMH ||
            movedMeters >= MOVEMENT_DISTANCE_METERS

        if (isMoving) {
            stationarySamples = 0
            requestLocationUpdates(MOVING_INTERVAL_MS, Priority.PRIORITY_HIGH_ACCURACY)
        } else {
            stationarySamples++
            if (stationarySamples >= STATIONARY_SAMPLES_REQUIRED) {
                // Balanced power plus a modest stationary distance threshold reduces unnecessary
                // callbacks. The three-minute interval remains unchanged.
                requestLocationUpdates(
                    STATIONARY_INTERVAL_MS,
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY
                )
            }
        }
        previousLocation = Location(location)
    }

    private suspend fun handleNewLocation(
        location: Location,
        sampleTruckId: Int,
        customStatus: String? = null
    ) {
        val truckId = sampleTruckId
        val lat = location.latitude
        val lng = location.longitude
        val speedKmh = location.speed * 3.6f
        val now = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
        val dao = AppDatabase.getDatabase(applicationContext).fleetDao()

        // Local persistence always completes before any network scheduling or upload.
        dao.insertLocationPoint(
            LocationPoint(
                truckId = truckId,
                latitude = lat,
                longitude = lng,
                speedKmh = speedKmh,
                accuracyMeters = location.accuracy,
                timestamp = now
            )
        )
        LocationSyncScheduler.enqueue(applicationContext)

        if (anchorLat == 0.0 && anchorLng == 0.0) {
            anchorLat = lat
            anchorLng = lng
            anchorStartTime = now
            hasLoggedCurrentDropPoint = false
        }

        val distFromAnchor = calculateDistanceMeters(anchorLat, anchorLng, lat, lng)
        if (distFromAnchor <= 100.0) {
            val durationAtAnchor = now - anchorStartTime
            if (durationAtAnchor >= 120_000L && !hasLoggedCurrentDropPoint) {
                hasLoggedCurrentDropPoint = true
                val durationMin = (durationAtAnchor / 60_000L).toInt().coerceAtLeast(2)
                val label = LocationUtils.getAddressLabel(applicationContext, lat, lng)
                val dropPoint = StopRecord(
                    truckId = truckId,
                    latitude = lat,
                    longitude = lng,
                    startTime = anchorStartTime,
                    endTime = now,
                    durationMinutes = durationMin,
                    addressOrLabel = "Auto Drop Point: $label (${durationMin} min stationary)",
                    category = "Auto Drop Point"
                )
                dao.insertStop(dropPoint)
                LocationSyncScheduler.enqueue(applicationContext)
            }
        } else {
            anchorLat = lat
            anchorLng = lng
            anchorStartTime = now
            hasLoggedCurrentDropPoint = false
        }

        val status = customStatus ?: if (speedKmh > STATIONARY_SPEED_KMH)
            "En Route (${speedKmh.toInt()} km/h)" else "At Stop / Drop Point"
        dao.updateTruckLocation(
            truckId = truckId,
            lat = lat,
            lng = lng,
            speed = speedKmh,
            status = status,
            timestamp = now
        )

        // Do not let a slow Firestore call block the sequential local location pipeline.
        if (now - lastTruckSyncAt >= 60_000L && liveTruckSyncJob?.isActive != true) {
            lastTruckSyncAt = now
            liveTruckSyncJob = serviceScope.launch {
                dao.getTruckByIdDirect(truckId)?.let { truck ->
                    FirestoreSyncManager(applicationContext).syncTruck(truck)
                }
            }
        }

        val notificationStatus = if (speedKmh > STATIONARY_SPEED_KMH) "En Route" else "At Stop / Drop Point"
        if (notificationStatus != lastNotificationStatus) {
            lastNotificationStatus = notificationStatus
            val notification = buildNotification(
                title = "AquaTrack: $activeTruckName",
                content = "$notificationStatus • Adaptive GPS"
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { callback ->
            fusedLocationClient.removeLocationUpdates(callback)
        }
    }

    override fun onDestroy() {
        isTracking = false
        stopLocationUpdates()
        locationChannel.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AquaTrack Fleet Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifies that truck GPS tracking is actively running in the background"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun calculateDistanceMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        if (lat1 == 0.0 && lon1 == 0.0) return 0.0
        val radiusMeters = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return radiusMeters * c
    }
}
