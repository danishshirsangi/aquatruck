package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.AttendanceRecord
import com.example.data.model.DriverEntity
import com.example.data.model.StopRecord
import com.example.data.model.TruckEntity
import com.example.data.model.VoiceDeliveryNote
import com.example.ui.FleetViewModel
import com.example.ui.components.AddDriverDialog
import com.example.ui.components.AddTruckDialog
import com.example.ui.components.InteractiveFleetMap
import com.example.ui.components.ManagerShiftSettingsScreen
import com.example.ui.components.ManagerDriverAssignmentsScreen
import com.example.ui.theme.CyanWater
import com.example.ui.theme.OceanNavyPrimary
import com.example.ui.theme.StatusActiveGreen
import com.example.ui.theme.StatusAmberWarning
import com.example.ui.theme.StatusOffDutyGrey
import com.example.ui.theme.StatusRedAlert
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ManagerPortalScreen(
    viewModel: FleetViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val trucks by viewModel.allTrucks.collectAsState()
    val drivers by viewModel.allDrivers.collectAsState()
    val allStops by viewModel.allStops.collectAsState()
    val allAttendance by viewModel.allAttendanceHistory.collectAsState()
    val allVoiceNotes by viewModel.allVoiceNotes.collectAsState()
    val isSimulating by viewModel.fleetSimulationActive.collectAsState()

    val locationHistory7Days by viewModel.locationHistory7Days.collectAsState()
    val historyDays by viewModel.historyDaysFilter.collectAsState()

    val locationsByDate by viewModel.locationHistoryByDate.collectAsState()
    val stopsByDate by viewModel.stopsByDate.collectAsState()
    val voiceNotesByDate by viewModel.voiceNotesByDate.collectAsState()
    val selectedDateMillis by viewModel.selectedDateMillis.collectAsState()

    val playingPath by viewModel.audioPlayer.currentlyPlayingPath.collectAsState()
    val isPlaying by viewModel.audioPlayer.isPlaying.collectAsState()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var filterTruckId by remember { mutableStateOf<Int?>(null) }

    var showAddTruckDialog by remember { mutableStateOf(false) }
    var showAddDriverDialog by remember { mutableStateOf(false) }
    var showResetConfirmDialog by remember { mutableStateOf(false) }
    var showTestPaymentDialog by remember { mutableStateOf(false) }
    var pendingTruckName by remember { mutableStateOf("") }
    var pendingTruckPlate by remember { mutableStateOf("") }
    var pendingTruckDriver by remember { mutableStateOf("") }
    var pendingTruckCapacity by remember { mutableIntStateOf(10000) }

    val tabs = listOf("Fleet Overview", "Interactive Map", "Voice Deliveries", "Stop Logs", "Attendance", "Shift Settings", "Drivers & Assignments")

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Tab Navigation Bar
        ScrollableTabRow(
            selectedTabIndex = selectedTabIndex,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = CyanWater,
            edgePadding = 16.dp,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                    color = CyanWater,
                    height = 3.dp
                )
            }
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = {
                        Text(
                            text = title,
                            fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 13.sp,
                            color = if (selectedTabIndex == index) CyanWater else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }
        }

        when (selectedTabIndex) {
            0 -> FleetOverviewTab(
                trucks = trucks,
                drivers = drivers,
                voiceNotes = allVoiceNotes,
                isSimulating = isSimulating,
                onToggleSimulation = { viewModel.toggleFleetSimulation() },
                onAddTruckClicked = { showAddTruckDialog = true },
                onAddDriverClicked = { showAddDriverDialog = true },
                onSelectTruckForMap = { truckId ->
                    filterTruckId = truckId
                    selectedTabIndex = 1
                },
                onDeleteTruck = { viewModel.deleteTruck(it) },
                onEndShift = { viewModel.managerEndTruckShift(it, context) },
                onResetAllClicked = { showResetConfirmDialog = true }
            )
            1 -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    InteractiveFleetMap(
                        trucks = trucks,
                        selectedTruckId = filterTruckId,
                        locations = locationsByDate,
                        stops = stopsByDate,
                        voiceNotes = voiceNotesByDate,
                        selectedDateMillis = selectedDateMillis,
                        onDateSelected = { viewModel.setSelectedDate(it) },
                        selectedHistoryDays = historyDays,
                        onHistoryDaysChanged = { viewModel.setHistoryDays(it) },
                        onTruckSelected = { id ->
                            filterTruckId = if (id == null) null else if (filterTruckId == id) null else id
                        },
                        onPlayVoiceNote = { viewModel.playVoiceNote(it) },
                        isPlaying = isPlaying,
                        currentlyPlayingPath = playingPath
                    )
                }
            }
            2 -> CategorizedVoiceDeliveriesTab(
                trucks = trucks,
                voiceNotes = allVoiceNotes,
                playingPath = playingPath,
                isPlaying = isPlaying,
                onPlayVoiceNote = { viewModel.playVoiceNote(it) },
                onDeleteVoiceNote = { viewModel.deleteVoiceNote(it) }
            )
            3 -> StopsAndTripLogsTab(
                trucks = trucks,
                stops = allStops,
                selectedTruckFilter = filterTruckId,
                onFilterChanged = { filterTruckId = it }
            )
            4 -> DriverAttendanceTab(
                attendanceList = allAttendance,
                trucks = trucks
            )
            5 -> ManagerShiftSettingsScreen(viewModel = viewModel)
            6 -> ManagerDriverAssignmentsScreen(
                drivers = drivers,
                trucks = trucks,
                onAssignTruck = { driverId, truckId -> viewModel.assignDriverToTruck(driverId, truckId) },
                onEarlyLogoutChanged = { driverId, enabled -> viewModel.setDriverEarlyLogout(driverId, enabled) }
            )
        }
    }

    if (showAddTruckDialog) {
        AddTruckDialog(
            nextTruckNumber = trucks.size + 1,
            onDismiss = { showAddTruckDialog = false },
            onAddTruck = { name, plate, driver, cap ->
                pendingTruckName = name
                pendingTruckPlate = plate
                pendingTruckDriver = driver
                pendingTruckCapacity = cap
                showAddTruckDialog = false
                showTestPaymentDialog = true
            }
        )
    }

    if (showAddDriverDialog) {
        AddDriverDialog(
            onDismiss = { showAddDriverDialog = false },
            onAddDriver = { name, phone ->
                viewModel.addDriver(name, phone)
                showAddDriverDialog = false
            }
        )
    }


    if (showTestPaymentDialog) {
        AlertDialog(
            onDismissRequest = { showTestPaymentDialog = false },
            title = { Text("Test truck subscription") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("A new truck requires a ₹99/month subscription.")
                    Text("TEST MODE — no real payment will be collected.", color = StatusAmberWarning, fontWeight = FontWeight.Bold)
                    Text("Truck: \${pendingTruckName} (\${pendingTruckPlate})")
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.addTruck(pendingTruckName, pendingTruckPlate, pendingTruckDriver, pendingTruckCapacity)
                    showTestPaymentDialog = false
                }) { Text("Simulate ₹99 payment") }
            },
            dismissButton = {
                TextButton(onClick = { showTestPaymentDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = StatusRedAlert, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Delete All Firebase Data?",
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            text = {
                Text(
                    text = "This will permanently purge all trucks, drivers, attendance logs, location trails, stop records, and voice delivery notes from both Firebase Cloud Firestore and this device, resetting the fleet to a clean slate.\n\nAre you sure you want to proceed?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.resetAndRecreateAllData(context) {
                            showResetConfirmDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusRedAlert)
                ) {
                    Text("DELETE ALL & RECREATE", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun FleetOverviewTab(
    trucks: List<TruckEntity>,
    drivers: List<DriverEntity>,
    voiceNotes: List<VoiceDeliveryNote>,
    isSimulating: Boolean,
    onToggleSimulation: () -> Unit,
    onAddTruckClicked: () -> Unit,
    onAddDriverClicked: () -> Unit,
    onSelectTruckForMap: (Int) -> Unit,
    onDeleteTruck: (Int) -> Unit,
    onEndShift: (Int) -> Unit = {},
    onResetAllClicked: () -> Unit = {}
) {
    val activeTrucksCount = trucks.count { it.isShiftActive }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // High-level KPI Cards
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Active Shifts Card
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = OceanNavyPrimary)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("Active Fleet", style = MaterialTheme.typography.labelSmall, color = CyanWater)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "$activeTrucksCount / ${trucks.size}",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        Text("Trucks on Duty", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                    }
                }

                // Total Deliveries (Voice Notes Count)
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("Total Deliveries", style = MaterialTheme.typography.labelSmall, color = CyanWater)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${voiceNotes.size}",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text("Voice Confirmed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // Action Buttons: + Add Truck & + Add Driver
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onAddTruckClicked,
                    modifier = Modifier.weight(1.2f).height(48.dp).testTag("add_truck_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = OceanNavyPrimary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Add Truck", fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onAddDriverClicked,
                    modifier = Modifier.weight(1f).height(48.dp).testTag("add_driver_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Driver", fontWeight = FontWeight.Bold)
                }
            }
        }

        // Section Title: Registered Trucks
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Fleet Water Tankers (${trucks.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (trucks.isNotEmpty()) {
                    Text(
                        text = "Connected to Firebase",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusActiveGreen,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (trucks.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.LocalShipping, contentDescription = null, tint = CyanWater, modifier = Modifier.size(42.dp))
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("No trucks added yet", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Tap 'Add Truck' above to add your water tanker trucks.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(trucks) { truck ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectTruckForMap(truck.id) }
                        .testTag("manager_truck_card_${truck.id}"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(if (truck.isShiftActive) OceanNavyPrimary else StatusOffDutyGrey.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "T#${truck.id}",
                                    color = if (truck.isShiftActive) CyanWater else StatusOffDutyGrey,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 12.sp
                                )
                                Icon(
                                    imageVector = Icons.Default.LocalShipping,
                                    contentDescription = null,
                                    tint = if (truck.isShiftActive) Color.White else StatusOffDutyGrey,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = truck.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (truck.isShiftActive) StatusActiveGreen.copy(alpha = 0.15f) else StatusOffDutyGrey.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = if (truck.isShiftActive) "ON SHIFT" else "OFF DUTY",
                                        color = if (truck.isShiftActive) StatusActiveGreen else StatusOffDutyGrey,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            Text(
                                text = "Driver: ${truck.driverName} • Plate: ${truck.plateNumber}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Text(
                                text = "Capacity: ${truck.capacityLiters}L • ${truck.statusText}",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyanWater,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        if (truck.isShiftActive) {
                            Column(horizontalAlignment = Alignment.End) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = StatusRedAlert.copy(alpha = 0.12f),
                                    modifier = Modifier
                                        .clickable { onEndShift(truck.id) }
                                        .testTag("manager_end_shift_${truck.id}")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.PowerSettingsNew,
                                            contentDescription = "End Shift",
                                            tint = StatusRedAlert,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            "End Shift",
                                            color = StatusRedAlert,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                IconButton(
                                    onClick = { onDeleteTruck(truck.id) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        } else {
                            IconButton(onClick = { onDeleteTruck(truck.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Registered drivers (${drivers.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (drivers.isEmpty()) {
                    Text("No drivers added yet. Tap Add Driver to register your first driver.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(drivers) { driver ->
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(driver.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(driver.phone.ifBlank { "No phone number added" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (driver.userUid.isNullOrBlank()) "Waiting for driver to join" else "Account linked", style = MaterialTheme.typography.labelSmall, color = StatusActiveGreen)
                    if (driver.userUid.isNullOrBlank() && !driver.invitationCode.isNullOrBlank()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Code: ${driver.invitationCode}", fontWeight = FontWeight.Bold)
                            TextButton(onClick = { clipboard.setText(AnnotatedString(driver.invitationCode!!)) }) { Text("Copy code") }
                        }
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

// ================= CATEGORIZED VOICE DELIVERIES =================
@Composable
private fun CategorizedVoiceDeliveriesTab(
    trucks: List<TruckEntity>,
    voiceNotes: List<VoiceDeliveryNote>,
    playingPath: String?,
    isPlaying: Boolean,
    onPlayVoiceNote: (String) -> Unit,
    onDeleteVoiceNote: (Long) -> Unit
) {
    // Group voice deliveries by Truck & Driver
    val groupedByTruck = voiceNotes.groupBy { it.truckId }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Voice Deliveries (${voiceNotes.size} Total)",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = OceanNavyPrimary
            )
            Text(
                text = "Categorized by Truck and Driver. Tap play to listen to driver audio recordings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (voiceNotes.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null, tint = CyanWater, modifier = Modifier.size(42.dp))
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("No voice deliveries recorded yet", fontWeight = FontWeight.Bold)
                        Text(
                            text = "When drivers reach delivery points, their voice notes will appear here categorized by truck.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            groupedByTruck.forEach { (truckId, notes) ->
                val truck = trucks.firstOrNull { it.id == truckId }
                val driverName = notes.firstOrNull()?.driverName ?: truck?.driverName ?: "Driver"

                item {
                    // Category Header Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // Category Header
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        shape = CircleShape,
                                        color = OceanNavyPrimary.copy(alpha = 0.1f),
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.LocalShipping, contentDescription = null, tint = OceanNavyPrimary, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = "${truck?.name ?: "Truck #$truckId"} • $driverName",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Plate: ${truck?.plateNumber ?: "N/A"}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = StatusActiveGreen.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = "${notes.size} Deliveries",
                                        color = StatusActiveGreen,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // List of Voice Notes in this Category
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                notes.forEachIndexed { index, note ->
                                    val isCurrentPlaying = (playingPath == note.audioFilePath && isPlaying)
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isCurrentPlaying) CyanWater.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        border = if (isCurrentPlaying) androidx.compose.foundation.BorderStroke(1.dp, CyanWater) else null,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            IconButton(
                                                onClick = { onPlayVoiceNote(note.audioFilePath) },
                                                modifier = Modifier
                                                    .size(44.dp)
                                                    .background(if (isCurrentPlaying) CyanWater else OceanNavyPrimary, CircleShape)
                                            ) {
                                                Icon(
                                                    imageVector = if (isCurrentPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                                    contentDescription = "Play Audio",
                                                    tint = Color.White
                                                )
                                            }

                                            Spacer(modifier = Modifier.width(12.dp))

                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = "Delivery #${notes.size - index}",
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 14.sp
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = "${note.durationSeconds}s Audio",
                                                        color = CyanWater,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 12.sp
                                                    )
                                                }

                                                val timeStr = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault()).format(Date(note.timestamp))
                                                Text(
                                                    text = "$timeStr • GPS: ${String.format("%.4f", note.latitude)}, ${String.format("%.4f", note.longitude)}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }

                                            IconButton(onClick = { onDeleteVoiceNote(note.id) }) {
                                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.outline)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun StopsAndTripLogsTab(
    trucks: List<TruckEntity>,
    stops: List<StopRecord>,
    selectedTruckFilter: Int?,
    onFilterChanged: (Int?) -> Unit
) {
    val filteredStops = if (selectedTruckFilter != null) {
        stops.filter { it.truckId == selectedTruckFilter }
    } else {
        stops
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Vehicle Stop & Delivery Halts",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Recorded automatically when truck is stationary (>60s) or when a driver records a voice delivery.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (trucks.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = selectedTruckFilter == null,
                        onClick = { onFilterChanged(null) },
                        label = { Text("All Trucks") }
                    )
                    trucks.forEach { truck ->
                        FilterChip(
                            selected = selectedTruckFilter == truck.id,
                            onClick = { onFilterChanged(if (selectedTruckFilter == truck.id) null else truck.id) },
                            label = { Text("T#${truck.id}") }
                        )
                    }
                }
            }
        }

        if (filteredStops.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = StatusAmberWarning, modifier = Modifier.size(36.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No stop events recorded yet", fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            items(filteredStops) { stop ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = StatusAmberWarning.copy(alpha = 0.15f),
                            modifier = Modifier.width(62.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "${stop.durationMinutes}",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 18.sp,
                                    color = StatusAmberWarning
                                )
                                Text("MINS", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = StatusAmberWarning)
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Truck #${stop.truckId}",
                                fontWeight = FontWeight.Bold,
                                color = OceanNavyPrimary,
                                fontSize = 12.sp
                            )
                            Text(
                                text = stop.addressOrLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )

                            val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
                            val startStr = timeFormat.format(Date(stop.startTime))
                            val endStr = timeFormat.format(Date(stop.endTime))
                            Text(
                                text = "$startStr - $endStr • GPS: ${String.format("%.4f", stop.latitude)}, ${String.format("%.4f", stop.longitude)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun DriverAttendanceTab(
    attendanceList: List<AttendanceRecord>,
    trucks: List<TruckEntity>
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = "Driver Attendance Selfies (${attendanceList.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Selfie verification photos taken by drivers when clocking into their assigned trucks.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (attendanceList.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Face, contentDescription = null, tint = CyanWater, modifier = Modifier.size(42.dp))
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("No attendance selfies yet", fontWeight = FontWeight.Bold)
                        Text(
                            text = "When drivers clock into a truck, their selfie attendance will appear here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(attendanceList) { att ->
                val truck = trucks.firstOrNull { it.id == att.truckId }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(OceanNavyPrimary),
                            contentAlignment = Alignment.Center
                        ) {
                            if (att.selfieUri != null) {
                                AsyncImage(
                                    model = att.selfieUri,
                                    contentDescription = "Driver Selfie",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(Icons.Default.Face, contentDescription = null, tint = CyanWater, modifier = Modifier.size(32.dp))
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = att.driverName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(Icons.Default.VerifiedUser, contentDescription = "Verified", tint = StatusActiveGreen, modifier = Modifier.size(16.dp))
                            }

                            Text(
                                text = "Truck #${att.truckId} (${truck?.name ?: "Tanker"})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            val checkInTimeStr = SimpleDateFormat("MMM dd • hh:mm a", Locale.getDefault()).format(Date(att.checkInTime))
                            val checkOutTimeStr = att.checkOutTime?.let {
                                if (it > 0) SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(it)) else "Shift In Progress"
                            } ?: "Shift In Progress"

                            Text(
                                text = "Check-in: $checkInTimeStr",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyanWater,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Check-out: $checkOutTimeStr",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}
