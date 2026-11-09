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
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    // Last recorded location state
    private var lastLocationTime: Long = 0L

    // Automatic Drop Point Detection (Still or within 100 meters for 2 minutes / 120s)
    private var anchorLat: Double = 0.0
    private var anchorLng: Double = 0.0
    private var anchorStartTime: Long = 0L
    private var hasLoggedCurrentDropPoint: Boolean = false

    companion object {
        const val CHANNEL_ID = "aquatrack_fleet_tracking"
        const val NOTIFICATION_ID = 1001

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
                startForeground(NOTIFICATION_ID, buildNotification("Shift Active: $activeTruckName", "Live GPS tracking every 10s"))
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

        // 1. Hardware GPS updates configured for 10-second interval
        try {
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000L)
                .setMinUpdateIntervalMillis(10000L)
                .setMaxUpdateDelayMillis(12000L)
                .setMinUpdateDistanceMeters(0f) // Keep logging breadcrumb every 10s even when still
                .build()

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val location = result.lastLocation ?: return
                    if (location.latitude != 0.0 || location.longitude != 0.0) {
                        handleNewLocation(location.latitude, location.longitude, location.speed * 3.6f)
                    }
                }
            }

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.w("LocationTrackingService", "Missing location permissions: ${e.message}")
        }

        // 2. Watchdog coroutine: guarantees tracking point every 10 seconds even if hardware callback stalls
        trackingJob = serviceScope.launch {
            anchorStartTime = System.currentTimeMillis()

            while (isActive) {
                delay(10000L) // 10-second tracking interval

                val now = System.currentTimeMillis()
                // If callback hasn't triggered in the last 9.5 seconds, actively poll current location
                if (now - lastLocationTime >= 9500L) {
                    val location = LocationUtils.getCurrentLocation(applicationContext)
                    if (location != null && (location.latitude != 0.0 || location.longitude != 0.0)) {
                        handleNewLocation(location.latitude, location.longitude, location.speed * 3.6f)
                    }
                }
            }
        }
    }

    private fun handleNewLocation(lat: Double, lng: Double, speedKmh: Float, customStatus: String? = null) {
        val now = System.currentTimeMillis()
        lastLocationTime = now

        serviceScope.launch {
            val dao = AppDatabase.getDatabase(applicationContext).fleetDao()
            val firestoreSync = com.example.data.firebase.FirestoreSyncManager(applicationContext)

            // 1. Insert Location Point breadcrumb for continuous route trail line every 10s
            val point = LocationPoint(
                truckId = activeTruckId,
                latitude = lat,
                longitude = lng,
                speedKmh = speedKmh,
                accuracyMeters = 3f,
                timestamp = now
            )
            val pointId = dao.insertLocationPoint(point)
            firestoreSync.syncLocationPoint(point.copy(id = pointId))

            // 2. Automatic Drop Point Detection (Still or within 100 meters for 2 minutes / 120,000 ms)
            if (anchorLat == 0.0 && anchorLng == 0.0) {
                anchorLat = lat
                anchorLng = lng
                anchorStartTime = now
                hasLoggedCurrentDropPoint = false
            }

            val distFromAnchor = calculateDistanceMeters(anchorLat, anchorLng, lat, lng)
            if (distFromAnchor <= 100.0) {
                // Vehicle is stationary or within 100-meter radius
                val durationAtAnchor = now - anchorStartTime
                if (durationAtAnchor >= 120000L && !hasLoggedCurrentDropPoint) {
                    // Vehicle stayed within 100 meters for 2 minutes -> Record as Auto Drop Point!
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
                    firestoreSync.syncStop(dropPoint.copy(id = stopId))
                }
            } else {
                // Vehicle moved outside 100 meters: reset anchor for the next potential drop point
                anchorLat = lat
                anchorLng = lng
                anchorStartTime = now
                hasLoggedCurrentDropPoint = false
            }

            // 3. Update TruckEntity state with live coordinates
            val status = customStatus ?: if (speedKmh > 2.5f) "En Route (${speedKmh.toInt()} km/h)" else "At Stop / Drop Point"
            dao.updateTruckLocation(
                truckId = activeTruckId,
                lat = lat,
                lng = lng,
                speed = speedKmh,
                status = status,
                timestamp = now
            )
            val truck = dao.getTruckByIdDirect(activeTruckId)
            if (truck != null) {
                firestoreSync.syncTruck(truck)
            }

            // 4. Update notification with 10-second telemetry
            val notification = buildNotification(
                title = "AquaTrack: $activeTruckName",
                content = if (speedKmh > 2f) "En Route (${speedKmh.toInt()} km/h) • Tracking Every 10s" else "At Stop / Drop Point • Tracking Every 10s"
            )
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
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
                description = "Notifies that truck GPS tracking is actively running in background every 10 seconds"
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
