package com.example.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.AppDatabase
import com.example.data.firebase.FirestoreSyncManager
import com.example.data.model.AttendanceRecord
import com.example.data.model.DeliveryProof
import com.example.data.model.DriverEntity
import com.example.data.model.LocationPoint
import com.example.data.model.StopRecord
import com.example.data.model.TruckEntity
import com.example.data.model.VoiceDeliveryNote
import com.example.data.sync.LocationSyncScheduler
import com.example.data.tenant.DriverShiftPolicy
import com.example.data.tenant.TenantManager
import com.example.service.LocationTrackingService
import com.example.util.AudioPlayerManager
import com.example.util.AudioRecorderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class UserRole {
    DRIVER,
    MANAGER
}

class FleetViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.getDatabase(application).fleetDao()
    private val firestoreSync = FirestoreSyncManager(application)

    val audioRecorder = AudioRecorderManager(application)
    val audioPlayer = AudioPlayerManager()

    val allTrucks: StateFlow<List<TruckEntity>> = dao.getAllTrucks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allDrivers: StateFlow<List<DriverEntity>> = dao.getAllDrivers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Selected Truck for viewing / pre-login
    private val _selectedTruckId = MutableStateFlow<Int?>(null)
    val selectedTruckId: StateFlow<Int?> = _selectedTruckId.asStateFlow()

    // Session Locking: When driver logs in, they are locked to this truck and driver identity until they log out
    private val _loggedInTruckId = MutableStateFlow<Int?>(null)
    val loggedInTruckId: StateFlow<Int?> = _loggedInTruckId.asStateFlow()

    private val _loggedInDriverName = MutableStateFlow<String?>(null)
    val loggedInDriverName: StateFlow<String?> = _loggedInDriverName.asStateFlow()

    // Pre-login Driver Selection
    private val _selectedDriverId = MutableStateFlow<Long?>(null)
    val selectedDriverId: StateFlow<Long?> = _selectedDriverId.asStateFlow()

    fun selectDriver(driverId: Long) {
        if (_loggedInTruckId.value == null) {
            _selectedDriverId.value = driverId
        }
    }

    val isDriverLoggedIn: StateFlow<Boolean> = MutableStateFlow(false)

    val selectedTruck: StateFlow<TruckEntity?> = _selectedTruckId.flatMapLatest { id ->
        if (id != null) dao.getTruckById(id) else kotlinx.coroutines.flow.flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeAttendance: StateFlow<AttendanceRecord?> = _selectedTruckId.flatMapLatest { id ->
        if (id != null) dao.getActiveAttendance(id) else kotlinx.coroutines.flow.flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val allAttendanceHistory: StateFlow<List<AttendanceRecord>> = dao.getAllAttendance()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 7-Day Route History Filter (Default: 7 days)
    private val _historyDaysFilter = MutableStateFlow(7)
    val historyDaysFilter: StateFlow<Int> = _historyDaysFilter.asStateFlow()

    fun setHistoryDays(days: Int) {
        _historyDaysFilter.value = days
    }

    // Calendar Date Selection for Route History
    // Default: Current date (Today)
    private val _selectedDateMillis = MutableStateFlow(System.currentTimeMillis())
    val selectedDateMillis: StateFlow<Long> = _selectedDateMillis.asStateFlow()

    fun setSelectedDate(timeMillis: Long) {
        _selectedDateMillis.value = timeMillis
    }

    fun setDatePreset(daysAgo: Int) {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_YEAR, -daysAgo)
        _selectedDateMillis.value = cal.timeInMillis
    }

    private fun getDayRange(timeMillis: Long): Pair<Long, Long> {
        val cal = java.util.Calendar.getInstance().apply {
            this.timeInMillis = timeMillis
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val startOfDay = cal.timeInMillis
        cal.set(java.util.Calendar.HOUR_OF_DAY, 23)
        cal.set(java.util.Calendar.MINUTE, 59)
        cal.set(java.util.Calendar.SECOND, 59)
        cal.set(java.util.Calendar.MILLISECOND, 999)
        val endOfDay = cal.timeInMillis
        return Pair(startOfDay, endOfDay)
    }

    // Route history filtered by the manager's chosen calendar date
    val locationHistoryByDate: StateFlow<List<LocationPoint>> = _selectedDateMillis.flatMapLatest { dateMillis ->
        val (startOfDay, endOfDay) = getDayRange(dateMillis)
        dao.getLocationsBetween(startOfDay, endOfDay)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Stop records filtered by the manager's chosen calendar date
    val stopsByDate: StateFlow<List<StopRecord>> = _selectedDateMillis.flatMapLatest { dateMillis ->
        val (startOfDay, endOfDay) = getDayRange(dateMillis)
        dao.getStopsBetween(startOfDay, endOfDay)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Voice delivery notes filtered by chosen calendar date
    val voiceNotesByDate: StateFlow<List<VoiceDeliveryNote>> = _selectedDateMillis.flatMapLatest { dateMillis ->
        val (startOfDay, endOfDay) = getDayRange(dateMillis)
        dao.getVoiceNotesBetween(startOfDay, endOfDay)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val locationHistory7Days: StateFlow<List<LocationPoint>> = kotlinx.coroutines.flow.combine(
        _selectedTruckId,
        _historyDaysFilter
    ) { id, days ->
        val since = System.currentTimeMillis() - (days.toLong() * 24L * 60L * 60L * 1000L)
        if (id != null) {
            dao.getLocationsForTruckSince(id, since)
        } else {
            dao.getLocationsSince(since)
        }
    }.flatMapLatest { it }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedTruckStops: StateFlow<List<StopRecord>> = _selectedTruckId.flatMapLatest { id ->
        if (id != null) dao.getStopsForTruck(id) else kotlinx.coroutines.flow.flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allStops: StateFlow<List<StopRecord>> = dao.getAllStops()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Voice Delivery Notes
    val allVoiceNotes: StateFlow<List<VoiceDeliveryNote>> = dao.getAllVoiceNotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedTruckVoiceNotes: StateFlow<List<VoiceDeliveryNote>> = _selectedTruckId.flatMapLatest { id ->
        if (id != null) dao.getVoiceNotesForTruck(id) else kotlinx.coroutines.flow.flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedTruckProofs: StateFlow<List<DeliveryProof>> = _selectedTruckId.flatMapLatest { id ->
        if (id != null) dao.getProofsForTruck(id) else kotlinx.coroutines.flow.flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allDeliveryProofs: StateFlow<List<DeliveryProof>> = dao.getAllProofs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _shiftPolicy = MutableStateFlow(DriverShiftPolicy())
    val shiftPolicy: StateFlow<DriverShiftPolicy> = _shiftPolicy.asStateFlow()
    private val _earlyLogoutAllowed = MutableStateFlow(false)
    val earlyLogoutAllowed: StateFlow<Boolean> = _earlyLogoutAllowed.asStateFlow()
    private val _assignedDriverTruckId = MutableStateFlow<Int?>(TenantManager.assignedTruckId(application))
    val assignedDriverTruckId: StateFlow<Int?> = _assignedDriverTruckId.asStateFlow()
    private var shiftPolicyJob: Job? = null
    private var earlyLogoutJob: Job? = null

    private val _currentRole = MutableStateFlow(UserRole.DRIVER)
    val currentRole: StateFlow<UserRole> = _currentRole.asStateFlow()

    private val _isManagerUnlocked = MutableStateFlow(false)
    val isManagerUnlocked: StateFlow<Boolean> = _isManagerUnlocked.asStateFlow()

    private val _fleetSimulationActive = MutableStateFlow(false)
    val fleetSimulationActive: StateFlow<Boolean> = _fleetSimulationActive.asStateFlow()

    private val sessionPrefs = application.getSharedPreferences("aquatrack_session_prefs", Context.MODE_PRIVATE)

    private var simulationJob: Job? = null

    init {
        // Enforce 30-day local breadcrumb retention even when the device has no network.
        viewModelScope.launch(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(30)
            dao.deleteLocationPointsBefore(cutoff)
        }

        // Restore active driver session across app closes / process death
        val savedTruckId = sessionPrefs.getInt("session_truck_id", -1)
        val savedDriverName = sessionPrefs.getString("session_driver_name", null)
        if (savedTruckId != -1) {
            _loggedInTruckId.value = savedTruckId
            _selectedTruckId.value = savedTruckId
            _loggedInDriverName.value = savedDriverName
        }

        viewModelScope.launch {
            allTrucks.collect { list ->
                val currentLoggedIn = _loggedInTruckId.value
                if (currentLoggedIn != null) {
                    val truck = list.firstOrNull { it.id == currentLoggedIn }
                    if (truck == null || !truck.isShiftActive) {
                        _loggedInTruckId.value = null
                        _loggedInDriverName.value = null
                        sessionPrefs.edit().clear().apply()
                    }
                } else if (TenantManager.role(getApplication()) == "driver") {
                    // A truck can be active on another driver's device. Do not infer that
                    // this Firebase user owns the live shift merely from truck.isShiftActive.
                    val assigned = TenantManager.assignedTruckId(getApplication())
                    _selectedTruckId.value = assigned?.takeIf { id -> list.any { it.id == id } }
                } else if (_selectedTruckId.value == null && list.isNotEmpty()) {
                    _selectedTruckId.value = list.first().id
                }
            }
        }
    }

    fun refreshTenantContext() {
        shiftPolicyJob?.cancel()
        earlyLogoutJob?.cancel()
        shiftPolicyJob = viewModelScope.launch {
            TenantManager.observeShiftPolicy(getApplication()).collect { _shiftPolicy.value = it }
        }
        earlyLogoutJob = viewModelScope.launch {
            TenantManager.observeOwnEarlyLogout(getApplication()).collect { _earlyLogoutAllowed.value = it }
        }
        viewModelScope.launch {
            TenantManager.observeAssignedTruckId(getApplication()).collect { assigned ->
                val previous = _assignedDriverTruckId.value
                _assignedDriverTruckId.value = assigned
                if (assigned != null && assigned > 0) {
                    // Preserve the in-progress session tanker after a driver temporarily
                    // switches away from their manager-default assignment.
                    if (_loggedInTruckId.value == null) {
                        _selectedTruckId.value = assigned
                    }
                    sessionPrefs.edit().putInt("assigned_truck_id", assigned).apply()
                    if (previous != assigned) {
                        runCatching { TenantManager.refreshFleetCache(getApplication()) }
                    }
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { TenantManager.refreshFleetCache(getApplication()) }
        }
    }

    fun saveShiftPolicy(policy: DriverShiftPolicy) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { TenantManager.saveShiftPolicy(getApplication(), policy) }
        }
    }

    fun assignDriverToTruck(driverId: Long, truckId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val driver = allDrivers.value.firstOrNull { it.id == driverId } ?: return@launch
            val updated = driver.copy(assignedTruckId = truckId)
            runCatching { TenantManager.assignDriverToTruck(getApplication(), updated, truckId) }
                .onSuccess { dao.insertDriver(updated) }
        }
    }

    fun setDriverEarlyLogout(driverId: Long, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val driver = allDrivers.value.firstOrNull { it.id == driverId } ?: return@launch
            val updated = driver.copy(earlyLogoutEnabled = enabled)
            runCatching { TenantManager.setDriverEarlyLogout(getApplication(), updated, enabled) }
                .onSuccess { dao.insertDriver(updated) }
        }
    }

    fun setRole(role: UserRole) {
        if (role == UserRole.MANAGER) {
            if (TenantManager.role(getApplication()) != "manager" || !_isManagerUnlocked.value) return
        }
        _currentRole.value = role
    }

    fun unlockManager(pin: String): Boolean {
        return if (TenantManager.verifyManagerPin(getApplication(), pin)) {
            _isManagerUnlocked.value = true
            _currentRole.value = UserRole.MANAGER
            true
        } else {
            false
        }
    }

    fun lockManager() {
        _isManagerUnlocked.value = false
        _currentRole.value = UserRole.DRIVER
    }

    fun selectTruck(truckId: Int) {
        // Can only change truck if not locked in an active shift!
        if (_loggedInTruckId.value == null) {
            _selectedTruckId.value = truckId
        }
    }

    // Manager Actions: Add Truck & Add Driver
    fun addTruck(name: String, plateNumber: String, driverName: String, capacityLiters: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val trucks = allTrucks.value
            val nextId = if (trucks.isEmpty()) 1 else (trucks.maxOf { it.id } + 1)
            val newTruck = TruckEntity(
                id = nextId,
                name = name,
                plateNumber = plateNumber,
                driverName = driverName,
                capacityLiters = capacityLiters,
                currentLat = 0.0,
                currentLng = 0.0,
                isShiftActive = false,
                statusText = "Off Duty"
            )
            val subscriptionSaved = firestoreSync.recordTestTruckSubscription(nextId)
            if (!subscriptionSaved) return@launch
            dao.insertTruck(newTruck)
            firestoreSync.syncTruck(newTruck)
            if (_selectedTruckId.value == null) {
                _selectedTruckId.value = nextId
            }
        }
    }

    fun deleteTruck(truckId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteTruck(truckId)
            firestoreSync.deleteTruck(truckId)
            if (_selectedTruckId.value == truckId) {
                _selectedTruckId.value = allTrucks.value.firstOrNull { it.id != truckId }?.id
            }
        }
    }

    fun addDriver(name: String, phone: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val driver = DriverEntity(
                name = name,
                phone = phone,
                assignedTruckId = _selectedTruckId.value ?: allTrucks.value.firstOrNull()?.id ?: 0,
                invitationCode = TenantManager.generateDriverInviteCode()
            )
            val id = dao.insertDriver(driver)
            firestoreSync.syncDriver(driver.copy(id = id))
        }
    }

    fun deleteDriver(driverId: Long) {
        if (TenantManager.role(getApplication()) != "manager" || !_isManagerUnlocked.value) return
        viewModelScope.launch(Dispatchers.IO) {
            val driver = allDrivers.value.firstOrNull { it.id == driverId } ?: return@launch
            if (!firestoreSync.deleteDriver(driver)) return@launch
            dao.deleteDriver(driverId)
        }
    }

    // Driver Shift Actions
    fun startDriverShift(truckId: Int, driverName: String, selfieUri: String?, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val truck = dao.getTruckByIdDirect(truckId) ?: return@launch
            if (truck.isShiftActive && _loggedInTruckId.value != truckId) return@launch
            val now = System.currentTimeMillis()

            // 1. Lock driver session to this truck & driver identity and persist to SharedPreferences
            _loggedInTruckId.value = truckId
            _selectedTruckId.value = truckId
            _loggedInDriverName.value = driverName

            sessionPrefs.edit()
                .putInt("session_truck_id", truckId)
                .putString("session_driver_name", driverName)
                .putLong("session_start_time", now)
                .apply()

            // 2. Fetch current real GPS location for check-in
            val loc = com.example.util.LocationUtils.getCurrentLocation(context)
            val checkInLat = loc?.latitude ?: truck.currentLat
            val checkInLng = loc?.longitude ?: truck.currentLng

            // 3. Create active attendance record
            val attendance = AttendanceRecord(
                truckId = truckId,
                driverName = driverName,
                selfieUri = selfieUri,
                checkInTime = now,
                checkInLat = checkInLat,
                checkInLng = checkInLng,
                isCompleted = false
            )
            val attId = dao.insertAttendance(attendance)
            firestoreSync.syncAttendance(attendance.copy(id = attId))

            // 4. Mark truck shift active with real coordinates
            val updatedTruck = truck.copy(
                isShiftActive = true,
                driverName = driverName,
                currentLat = checkInLat,
                currentLng = checkInLng,
                statusText = "Shift Active - En Route",
                lastUpdated = now
            )
            dao.updateTruck(updatedTruck)
            firestoreSync.syncTruck(updatedTruck)

            if (checkInLat != 0.0 || checkInLng != 0.0) {
                val pt = LocationPoint(
                    truckId = truckId,
                    latitude = checkInLat,
                    longitude = checkInLng,
                    speedKmh = 0f,
                    accuracyMeters = loc?.accuracy ?: 3f,
                    timestamp = now
                )
                dao.insertLocationPoint(pt)
                LocationSyncScheduler.enqueue(context)
            }

            // 5. Start adaptive foreground GPS tracking
            LocationTrackingService.startService(context, truckId, truck.name)
        }
    }

    fun switchDriverTruck(newTruckId: Int, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val oldTruckId = _loggedInTruckId.value ?: return@launch
            if (newTruckId == oldTruckId) return@launch
            val newTruck = dao.getTruckByIdDirect(newTruckId) ?: return@launch
            if (newTruck.isShiftActive) return@launch

            val now = System.currentTimeMillis()
            val oldAttendance = dao.getActiveAttendanceDirect(oldTruckId)
            if (oldAttendance != null) {
                val completed = oldAttendance.copy(checkOutTime = now, isCompleted = true)
                dao.updateAttendance(completed)
                firestoreSync.syncAttendance(completed)
            }

            val oldTruck = dao.getTruckByIdDirect(oldTruckId)
            if (oldTruck != null) {
                val offDuty = oldTruck.copy(
                    isShiftActive = false,
                    statusText = "Off Duty",
                    currentSpeedKmh = 0f,
                    lastUpdated = now
                )
                dao.updateTruck(offDuty)
                firestoreSync.syncTruck(offDuty)
            }

            val driverName = _loggedInDriverName.value ?: "Driver"
            _loggedInTruckId.value = newTruckId
            _selectedTruckId.value = newTruckId
            sessionPrefs.edit()
                .putInt("session_truck_id", newTruckId)
                .putString("session_driver_name", driverName)
                .putLong("session_start_time", now)
                .apply()

            val location = runCatching { com.example.util.LocationUtils.getCurrentLocation(context) }.getOrNull()
            val lat = location?.latitude ?: newTruck.currentLat
            val lng = location?.longitude ?: newTruck.currentLng
            val attendance = AttendanceRecord(
                truckId = newTruckId,
                driverName = driverName,
                selfieUri = null,
                checkInTime = now,
                checkInLat = lat,
                checkInLng = lng,
                isCompleted = false
            )
            val attendanceId = dao.insertAttendance(attendance)
            firestoreSync.syncAttendance(attendance.copy(id = attendanceId))

            val activeTruck = newTruck.copy(
                isShiftActive = true,
                driverName = driverName,
                currentLat = lat,
                currentLng = lng,
                statusText = "Shift Active - En Route",
                lastUpdated = now
            )
            dao.updateTruck(activeTruck)
            firestoreSync.syncTruck(activeTruck)

            if (lat != 0.0 || lng != 0.0) {
                dao.insertLocationPoint(LocationPoint(
                    truckId = newTruckId,
                    latitude = lat,
                    longitude = lng,
                    speedKmh = 0f,
                    accuracyMeters = location?.accuracy ?: 5f,
                    timestamp = now
                ))
                LocationSyncScheduler.enqueue(context)
            }
            LocationTrackingService.startService(context, newTruckId, newTruck.name)
        }
    }

    fun endDriverShift(truckId: Int, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()

            // 1. Complete active attendance record
            val activeAtt = dao.getActiveAttendanceDirect(truckId)
            if (activeAtt != null) {
                val completedAtt = activeAtt.copy(
                    checkOutTime = now,
                    isCompleted = true
                )
                dao.updateAttendance(completedAtt)
                firestoreSync.syncAttendance(completedAtt)
            }

            // 2. Mark truck off duty
            val truck = dao.getTruckByIdDirect(truckId)
            if (truck != null) {
                val offDutyTruck = truck.copy(
                    isShiftActive = false,
                    statusText = "Off Duty",
                    currentSpeedKmh = 0f,
                    lastUpdated = now
                )
                dao.updateTruck(offDutyTruck)
                firestoreSync.syncTruck(offDutyTruck)
            }

            // 3. Unlock driver session & clear persisted session so driver can choose truck/identity on next shift
            _loggedInTruckId.value = null
            _loggedInDriverName.value = null
            sessionPrefs.edit().clear().apply()

            // 4. Stop Foreground Service
            LocationTrackingService.stopService(context)
        }
    }

    fun managerEndTruckShift(truckId: Int, context: Context) {
        endDriverShift(truckId, context)
    }

    fun verifyManagerPin(pin: String): Boolean {
        return TenantManager.verifyManagerPin(getApplication(), pin)
    }

    fun startVoiceDeliveryRecording(): Boolean {
        return audioRecorder.startRecording()
    }

    fun stopAndSaveVoiceDelivery(truckId: Int, driverName: String, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val recordResult = audioRecorder.stopRecording() ?: return@launch
            val audioPath = recordResult.first
            val durationSec = recordResult.second

            // Fetch live GPS location right at the exact moment of delivery recording
            val freshLocation = com.example.util.LocationUtils.getCurrentLocation(context)
            val truck = dao.getTruckByIdDirect(truckId)

            val lat = if (freshLocation != null && (freshLocation.latitude != 0.0 || freshLocation.longitude != 0.0)) {
                freshLocation.latitude
            } else {
                truck?.currentLat ?: 0.0
            }

            val lng = if (freshLocation != null && (freshLocation.latitude != 0.0 || freshLocation.longitude != 0.0)) {
                freshLocation.longitude
            } else {
                truck?.currentLng ?: 0.0
            }

            val now = System.currentTimeMillis()
            val timeLabel = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(now))
            val addressName = com.example.util.LocationUtils.getAddressLabel(context, lat, lng)
            val locationLabel = "$addressName ($timeLabel)"

            // Update truck coordinates and record location breadcrumb
            if (lat != 0.0 || lng != 0.0) {
                dao.updateTruckLocation(truckId, lat, lng, 0f, "Water Delivered - $timeLabel", now)
                val point = LocationPoint(
                    truckId = truckId,
                    latitude = lat,
                    longitude = lng,
                    speedKmh = 0f,
                    accuracyMeters = freshLocation?.accuracy ?: 3f,
                    timestamp = now
                )
                dao.insertLocationPoint(point)
                LocationSyncScheduler.enqueue(context)
            }

            // Save Voice Delivery Note with exact GPS coordinates and address
            val note = VoiceDeliveryNote(
                truckId = truckId,
                driverName = driverName,
                audioFilePath = audioPath,
                durationSeconds = durationSec,
                latitude = lat,
                longitude = lng,
                locationLabel = locationLabel,
                timestamp = now
            )
            val savedNoteId = dao.insertVoiceNote(note)
            val savedNote = note.copy(id = savedNoteId)

            // Upload the recording immediately while its local file is guaranteed to exist.
            // WorkManager remains the offline/retry fallback.
            if (firestoreSync.syncVoiceNote(savedNote)) {
                dao.markVoiceNotesSynced(listOf(savedNoteId))
            } else {
                LocationSyncScheduler.enqueue(context)
            }

            // Save Stop Record
            val stop = StopRecord(
                truckId = truckId,
                latitude = lat,
                longitude = lng,
                startTime = now - (durationSec * 1000L).coerceAtLeast(30000L),
                endTime = now,
                durationMinutes = ((durationSec / 60) + 1).coerceAtLeast(1),
                addressOrLabel = "Water Delivery: $locationLabel",
                category = "Delivery Stop"
            )
            dao.insertStop(stop)
            LocationSyncScheduler.enqueue(context)
        }
    }

    fun playVoiceNote(filePath: String) {
        if (!com.example.data.firebase.SupabaseAudioStorage.isRemoteReference(filePath)) {
            audioPlayer.togglePlay(filePath)
            return
        }
        viewModelScope.launch {
            try {
                val cachedFile = withContext(Dispatchers.IO) {
                    com.example.data.firebase.SupabaseAudioStorage.downloadToCache(getApplication(), filePath)
                }
                audioPlayer.togglePlay(cachedFile.absolutePath)
            } catch (e: Exception) {
                android.util.Log.e("FleetViewModel", "Unable to download voice recording", e)
            }
        }
    }

    fun deleteVoiceNote(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteVoiceNote(id)
        }
    }

    fun toggleFleetSimulation() {
        if (_fleetSimulationActive.value) {
            simulationJob?.cancel()
            _fleetSimulationActive.value = false
        } else {
            _fleetSimulationActive.value = true
            startFleetSimulation()
        }
    }

    private fun startFleetSimulation() {
        simulationJob?.cancel()
        simulationJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive && _fleetSimulationActive.value) {
                delay(4000L)
                val trucks = allTrucks.value
                trucks.forEach { truck ->
                    if (truck.isShiftActive) {
                        val angle = (System.currentTimeMillis() / 20000.0) + (truck.id * 1.25)
                        val radius = 0.012 + (truck.id * 0.003)
                        val centerLat = if (truck.currentLat != 0.0) truck.currentLat else 28.6139
                        val centerLng = if (truck.currentLng != 0.0) truck.currentLng else 77.2090

                        val newLat = centerLat + (kotlin.math.cos(angle) * 0.002)
                        val newLng = centerLng + (kotlin.math.sin(angle) * 0.002)
                        val speed = 25f + (truck.id * 3)

                        val updated = truck.copy(
                            currentLat = newLat,
                            currentLng = newLng,
                            currentSpeedKmh = speed,
                            statusText = "En Route (${speed.toInt()} km/h)",
                            lastUpdated = System.currentTimeMillis()
                        )
                        dao.updateTruck(updated)
                        firestoreSync.syncTruck(updated)

                        val point = LocationPoint(
                            truckId = truck.id,
                            latitude = newLat,
                            longitude = newLng,
                            speedKmh = speed,
                            accuracyMeters = 3.5f,
                            timestamp = System.currentTimeMillis()
                        )
                        dao.insertLocationPoint(point)
                        LocationSyncScheduler.enqueue(getApplication<Application>())
                    }
                }
            }
        }
    }

    fun resetAndRecreateAllData(context: Context, onComplete: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            // 1. Stop background tracking service
            LocationTrackingService.stopService(context)

            // 2. Clear all collections from Firebase Firestore
            firestoreSync.clearAllCollections()

            // 3. Clear all local Room database tables
            dao.clearAllLocationPoints()
            dao.clearAllStopRecords()
            dao.clearAllVoiceNotes()
            dao.clearAllAttendance()
            dao.clearAllTrucks()
            dao.clearAllDrivers()
            dao.clearAllDeliveryProofs()

            // 4. Clear persisted driver shift sessions
            sessionPrefs.edit().clear().apply()
            _loggedInTruckId.value = null
            _loggedInDriverName.value = null
            _selectedTruckId.value = null
            _selectedDriverId.value = null

            kotlinx.coroutines.withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        simulationJob?.cancel()
        audioPlayer.release()
    }
}
