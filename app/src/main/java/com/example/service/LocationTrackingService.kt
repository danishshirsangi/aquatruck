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
import com.example.data.model.LocationPoint
import com.example.data.model.StopRecord
import com.example.util.LocationUtils
import com.example.data.sync.LocationSyncScheduler
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class LocationTrackingService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var trackingJob: Job? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    private var activeTruckId: Int = 1
    private var activeTruckName: String = "Water Tanker"

    private var currentIntervalMillis = MOVING_INTERVAL_MS
    private var previousLocation: Location? = null
    private var stationarySamples = 0
    private var lastNotificationStatus: String? = null
    private var lastTruckSyncAt: Long = 0L

    // Automatic Drop Point Detection (Still or within 100 meters for 2 minutes / 120s)
    private var anchorLat: Double = 0.0
    private var anchorLng: Double = 0.0
    private var anchorStartTime: Long = 0L
    private var hasLoggedCurrentDropPoint: Boolean = false

    companion object {
        const val CHANNEL_ID = "aquatrack_fleet_tracking"
        const val NOTIFICATION_ID = 1001
        private const val MOVING_INTERVAL_MS = 20_000L
        private const val STATIONARY_INTERVAL_MS = 3 * 60_000L
        private const val MOVEMENT_DISTANCE_METERS = 25f
        private const val STATIONARY_SPEED_KMH = 2.5f
        private const val STATIONARY_SAMPLES_REQUIRED = 3

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TRACKING -> {
                activeTruckId = intent.getIntExtra(EXTRA_TRUCK_ID, 1)
                activeTruckName = intent.getStringExtra(EXTRA_TRUCK_NAME) ?: "Water Tanker $activeTruckId"
                startForeground(NOTIFICATION_ID, buildNotification("Shift Active: $activeTruckName", "Live GPS tracking • adaptive 20s / 3m"))
                startTrackingLoop()
            }
            ACTION_STOP_TRACKING -> {
                stopTrackingLoop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startTrackingLoop() {
        trackingJob?.cancel()
        previousLocation = null
        stationarySamples = 0
        currentIntervalMillis = MOVING_INTERVAL_MS

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { location ->
                    if (location.latitude != 0.0 || location.longitude != 0.0) {
                        updateTrackingMode(location)
                        handleNewLocation(location)
                    }
                }
            }
        }
        requestLocationUpdates(currentIntervalMillis)
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates(intervalMillis: Long) {
        val callback = locationCallback ?: return
        try {
            fusedLocationClient.removeLocationUpdates(callback)
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMillis)
                .setMinUpdateIntervalMillis(intervalMillis)
                .setMinUpdateDistanceMeters(if (intervalMillis == MOVING_INTERVAL_MS) 10f else 0f)
                .build()
            fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            currentIntervalMillis = intervalMillis
            Log.i("LocationTrackingService", "Location interval set to $intervalMillis ms")
        } catch (e: SecurityException) {
            Log.w("LocationTrackingService", "Missing location permissions: ${e.message}")
        }
    }

    private fun updateTrackingMode(location: Location) {
        val previous = previousLocation
        val movedMeters = if (previous == null) Float.MAX_VALUE else previous.distanceTo(location)
        val isMoving = location.speed * 3.6f > STATIONARY_SPEED_KMH ||
            movedMeters >= MOVEMENT_DISTANCE_METERS

        if (isMoving) {
            stationarySamples = 0
            if (currentIntervalMillis != MOVING_INTERVAL_MS) {
                requestLocationUpdates(MOVING_INTERVAL_MS)
            }
        } else {
            stationarySamples++
            if (stationarySamples >= STATIONARY_SAMPLES_REQUIRED &&
                currentIntervalMillis != STATIONARY_INTERVAL_MS
            ) {
                requestLocationUpdates(STATIONARY_INTERVAL_MS)
            }
        }
        previousLocation = Location(location)
    }

    private fun handleNewLocation(location: Location, customStatus: String? = null) {
        val lat = location.latitude
        val lng = location.longitude
        val speedKmh = location.speed * 3.6f
        val now = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()

        serviceScope.launch {
            val dao = AppDatabase.getDatabase(applicationContext).fleetDao()
            val point = LocationPoint(
                truckId = activeTruckId,
                latitude = lat,
                longitude = lng,
                speedKmh = speedKmh,
                accuracyMeters = location.accuracy,
                timestamp = now
            )
            dao.insertLocationPoint(point)

            // Persist first; the independent worker uploads pending points when connected.
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
                if (durationAtAnchor >= 120000L && !hasLoggedCurrentDropPoint) {
                    hasLoggedCurrentDropPoint = true
                    val durationMin = (durationAtAnchor / 60000L).toInt().coerceAtLeast(2)
                    val label = LocationUtils.getAddressLabel(applicationContext, lat, lng)
                    val dropPoint = StopRecord(
                        truckId = activeTruckId,
                        latitude = lat,
                        longitude = lng,
                        startTime = anchorStartTime,
                        endTime = now,
                        durationMinutes = durationMin,
                        addressOrLabel = "Auto Drop Point: $label (${durationMin} min stationary)",
                        category = "Auto Drop Point"
                    )
                    val stopId = dao.insertStop(dropPoint)
                    com.example.data.firebase.FirestoreSyncManager(applicationContext)
                        .syncStop(dropPoint.copy(id = stopId))
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
                truckId = activeTruckId,
                lat = lat,
                lng = lng,
                speed = speedKmh,
                status = status,
                timestamp = now
            )

            // Sync the live truck document at most once per minute. Breadcrumbs use the batch queue.
            if (now - lastTruckSyncAt >= 60_000L) {
                dao.getTruckByIdDirect(activeTruckId)?.let { truck ->
                    com.example.data.firebase.FirestoreSyncManager(applicationContext).syncTruck(truck)
                }
                lastTruckSyncAt = now
            }

            // Keep the live document lightweight; historical breadcrumbs are batch-synced separately.
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
    }

    private fun stopTrackingLoop() {
        trackingJob?.cancel()
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        serviceScope.launch {
            val dao = AppDatabase.getDatabase(applicationContext).fleetDao()
            dao.updateTruckShiftStatus(
                truckId = activeTruckId,
                active = false,
                status = "Off Duty",
                timestamp = System.currentTimeMillis()
            )
        }
    }

    override fun onDestroy() {
        stopTrackingLoop()
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

    private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        if (lat1 == 0.0 && lon1 == 0.0) return 0.0
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
