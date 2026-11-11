package com.example.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.ui.FleetViewModel
import com.example.data.tenant.TenantManager
import com.example.ui.components.SelfieAttendanceDialog
import com.example.ui.theme.CyanWater
import com.example.ui.theme.OceanNavyPrimary
import com.example.ui.theme.StatusActiveGreen
import com.example.ui.theme.StatusAmberWarning
import com.example.ui.theme.StatusOffDutyGrey
import com.example.ui.theme.StatusRedAlert
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun DriverPortalScreen(
    viewModel: FleetViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val trucks by viewModel.allTrucks.collectAsState()
    val drivers by viewModel.allDrivers.collectAsState()
    val selectedTruckId by viewModel.selectedTruckId.collectAsState()
    val loggedInTruckId by viewModel.loggedInTruckId.collectAsState()
    val loggedInDriverName by viewModel.loggedInDriverName.collectAsState()
    val selectedTruck by viewModel.selectedTruck.collectAsState()
    val activeAttendance by viewModel.activeAttendance.collectAsState()
    val selectedTruckStops by viewModel.selectedTruckStops.collectAsState()
    val shiftPolicy by viewModel.shiftPolicy.collectAsState()
    val earlyLogoutAllowed by viewModel.earlyLogoutAllowed.collectAsState()
    val assignedTruckId by viewModel.assignedDriverTruckId.collectAsState()
    val isDriverAccount = TenantManager.role(context) == "driver"
    // Drivers can choose any available tanker in their own business; identity remains fixed to their account.
    val visibleTrucks = trucks
    val currentUid = TenantManager.currentUserUid()
    val visibleDrivers = if (isDriverAccount) drivers.filter { it.userUid == currentUid } else drivers

    // Driver identity selection state
    var selectedDriverName by remember { mutableStateOf<String?>(null) }
    var customDriverInput by remember { mutableStateOf("") }
    var isEnteringCustomDriver by remember { mutableStateOf(false) }

    val currentTruck = selectedTruck?.takeIf { candidate -> visibleTrucks.any { it.id == candidate.id } } ?: visibleTrucks.firstOrNull()
    val effectiveTruck = if (loggedInTruckId != null) {
        visibleTrucks.firstOrNull { it.id == loggedInTruckId } ?: currentTruck
    } else {
        currentTruck
    }

    val effectiveDriverName = if (isDriverAccount) {
        visibleDrivers.firstOrNull()?.name ?: loggedInDriverName ?: "Driver"
    } else {
        loggedInDriverName
            ?: selectedDriverName
            ?: (if (customDriverInput.isNotBlank()) customDriverInput.trim() else null)
            ?: visibleDrivers.firstOrNull()?.name
            ?: effectiveTruck?.driverName
            ?: "Driver"
    }

    // Session lock & shift active status: persists across app closes
    val isShiftActive = (loggedInTruckId != null && effectiveTruck?.isShiftActive == true)

    // The button gate refreshes independently from GPS callbacks; time unlock never ends the shift.
    var clockNowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            clockNowMillis = System.currentTimeMillis()
            delay(15_000L)
        }
    }
    val currentCalendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone(shiftPolicy.timezone)).apply {
        timeInMillis = clockNowMillis
    }
    val currentMinutes = currentCalendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
        currentCalendar.get(java.util.Calendar.MINUTE)
    val isLogoutAllowed = earlyLogoutAllowed || currentMinutes >= shiftPolicy.logoutTimeMinutes

    // Voice recording & playback state
    val isRecording by viewModel.audioRecorder.isRecording.collectAsState()
    val voiceUploadStatus by viewModel.voiceUploadStatus.collectAsState()
    val recordDurationSec by viewModel.audioRecorder.recordingDurationSec.collectAsState()
    val voiceNotes by viewModel.selectedTruckVoiceNotes.collectAsState()
    val playingPath by viewModel.audioPlayer.currentlyPlayingPath.collectAsState()
    val isPlaying by viewModel.audioPlayer.isPlaying.collectAsState()

    var showSelfieDialog by remember { mutableStateOf(false) }
    var switchTruckMenuExpanded by remember { mutableStateOf(false) }

    // Pulse animation for recording mic
    val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mic_scale"
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Zero Trucks Fallback
        if (visibleTrucks.isEmpty()) {
            item {
                Spacer(modifier = Modifier.height(30.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = StatusAmberWarning.copy(alpha = 0.15f),
                            modifier = Modifier.size(64.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.LocalShipping, contentDescription = null, tint = StatusAmberWarning, modifier = Modifier.size(32.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "No Trucks Registered Yet",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = OceanNavyPrimary
                        )
                        Text(
                            text = "Please ask your fleet manager to register your driver profile and add trucks to the business. You can choose any available truck when you start your shift.",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        } else if (!isShiftActive) {
            // ================= PRE-LOGIN FLOW: SELECT DRIVER & TRUCK =================
            item {
                Spacer(modifier = Modifier.height(6.dp))

                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // A driver account is bound to the identity claimed through its one-time invitation.
                    if (isDriverAccount) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = StatusActiveGreen, modifier = Modifier.size(26.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text("SIGNED IN DRIVER", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(effectiveDriverName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text("Your identity is linked to your invitation and cannot be changed here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    } else {
                    // 1. SELECT WHO YOU ARE
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Person, contentDescription = null, tint = CyanWater, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "1. WHO ARE YOU? (DRIVER)",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = OceanNavyPrimary,
                                    letterSpacing = 0.5.sp
                                )
                            }
                            if (drivers.isNotEmpty()) {
                                Text(
                                    text = "${drivers.size} drivers registered",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        if (drivers.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(visibleDrivers) { driver ->
                                    val isSelected = (selectedDriverName == driver.name) ||
                                            (selectedDriverName == null && !isEnteringCustomDriver && drivers.firstOrNull()?.name == driver.name)

                                    Card(
                                        modifier = Modifier
                                            .width(155.dp)
                                            .clickable {
                                                selectedDriverName = driver.name
                                                isEnteringCustomDriver = false
                                            }
                                            .testTag("select_driver_${driver.id}"),
                                        shape = RoundedCornerShape(18.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isSelected) OceanNavyPrimary else MaterialTheme.colorScheme.surface
                                        ),
                                        border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFCBD5E1)),
                                        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 6.dp else 2.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Surface(
                                                shape = CircleShape,
                                                color = if (isSelected) CyanWater.copy(alpha = 0.25f) else OceanNavyPrimary.copy(alpha = 0.1f),
                                                modifier = Modifier.size(44.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        imageVector = Icons.Default.Person,
                                                        contentDescription = null,
                                                        tint = if (isSelected) CyanWater else OceanNavyPrimary,
                                                        modifier = Modifier.size(26.dp)
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                text = driver.name,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1
                                            )
                                            Text(
                                                text = if (driver.phone.isNotBlank()) driver.phone else "Driver ID: #${driver.id}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isSelected) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            if (isSelected) {
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = StatusActiveGreen
                                                ) {
                                                    Text(
                                                        text = "SELECTED",
                                                        color = Color.White,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        fontSize = 9.sp,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            OutlinedTextField(
                                value = customDriverInput,
                                onValueChange = {
                                    customDriverInput = it
                                    selectedDriverName = it
                                    isEnteringCustomDriver = true
                                },
                                label = { Text("Enter Your Driver Name") },
                                placeholder = { Text("e.g. Danish / Alex") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp)
                            )
                        }
                    }

                    }

                    // 2. WHICH TRUCK ARE YOU GONNA DRIVE?
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.LocalShipping, contentDescription = null, tint = CyanWater, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "2. WHICH TRUCK ARE YOU DRIVING?",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = OceanNavyPrimary,
                                    letterSpacing = 0.5.sp
                                )
                            }
                            Text(
                                text = "${trucks.size} trucks",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(visibleTrucks) { truck ->
                                val isSelected = truck.id == selectedTruckId
                                Card(
                                    modifier = Modifier
                                        .width(155.dp)
                                        .clickable(enabled = !truck.isShiftActive) { viewModel.selectTruck(truck.id) }
                                        .testTag("select_truck_${truck.id}"),
                                    shape = RoundedCornerShape(18.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected) OceanNavyPrimary else MaterialTheme.colorScheme.surface
                                    ),
                                    border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFCBD5E1)),
                                    elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 6.dp else 2.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.LocalShipping,
                                            contentDescription = null,
                                            tint = if (isSelected) CyanWater else OceanNavyPrimary,
                                            modifier = Modifier.size(34.dp)
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = truck.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = truck.plateNumber,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isSelected) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "${truck.capacityLiters} L Tanker",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isSelected) CyanWater else OceanNavyPrimary,
                                            fontWeight = FontWeight.Bold
                                        )
                                        if (isSelected) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = CyanWater
                                            ) {
                                                Text(
                                                    text = "SELECTED",
                                                    color = OceanNavyPrimary,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontSize = 9.sp,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 3. CONFIRM & START SHIFT (CLICK SELFIE)
                    currentTruck?.let { truck ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("driver_login_card"),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(22.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = OceanNavyPrimary.copy(alpha = 0.1f),
                                    modifier = Modifier.size(68.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.CameraAlt,
                                            contentDescription = null,
                                            tint = OceanNavyPrimary,
                                            modifier = Modifier.size(34.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Text(
                                    text = "READY TO START SHIFT",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = OceanNavyPrimary,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "Driver: $effectiveDriverName",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = OceanNavyPrimary
                                        )
                                        Text(
                                            text = "Vehicle: ${truck.name} (${truck.plateNumber})",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                if (truck.isShiftActive) {
                                    Text(
                                        "This truck already has an active shift. Ask your manager to assign an available truck.",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                        textAlign = TextAlign.Center
                                    )
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                Button(
                                    onClick = { showSelfieDialog = true },
                                    enabled = !truck.isShiftActive,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(58.dp)
                                        .testTag("driver_login_selfie_button"),
                                    colors = ButtonDefaults.buttonColors(containerColor = OceanNavyPrimary),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(22.dp))
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "CLICK SELFIE & START SHIFT",
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 15.sp,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // ================= ON DUTY / ACTIVE SHIFT STATE =================
            item {
                effectiveTruck?.let { truck ->
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Spacer(modifier = Modifier.height(4.dp))

                        // Locked Session Header Banner
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = OceanNavyPrimary,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Lock, contentDescription = null, tint = CyanWater, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = "DRIVING: ${truck.name.uppercase()}",
                                            color = Color.White,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = "Driver: $effectiveDriverName • Plate: ${truck.plateNumber}",
                                            color = Color.White.copy(alpha = 0.85f),
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = CyanWater.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = "SHIFT ACTIVE",
                                        color = CyanWater,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        if (isDriverAccount) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                                    Text("Switch tanker", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text("Your current shift will be closed on this tanker and a new attendance and GPS tracking session will start on the selected available tanker.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Box {
                                        Button(onClick = { switchTruckMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                            Text("Change tanker")
                                        }
                                        DropdownMenu(
                                            expanded = switchTruckMenuExpanded,
                                            onDismissRequest = { switchTruckMenuExpanded = false }
                                        ) {
                                            trucks.filter { it.id != truck.id && !it.isShiftActive }.forEach { candidate ->
                                                DropdownMenuItem(
                                                    text = { Text("${candidate.name} (${candidate.plateNumber})") },
                                                    onClick = {
                                                        switchTruckMenuExpanded = false
                                                        viewModel.switchDriverTruck(candidate.id, context)
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Active Duty Header Pill
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = StatusActiveGreen.copy(alpha = 0.12f),
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, StatusActiveGreen.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(14.dp)
                                            .background(StatusActiveGreen, CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = "DUTY ON • GPS TRACKING ACTIVE",
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 13.sp,
                                            color = StatusActiveGreen
                                        )
                                        Text(
                                            text = "Background location active every 10s",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(OceanNavyPrimary)
                                        .border(2.dp, StatusActiveGreen, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (activeAttendance?.selfieUri != null) {
                                        AsyncImage(
                                            model = activeAttendance?.selfieUri,
                                            contentDescription = "Driver Photo",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusActiveGreen, modifier = Modifier.size(24.dp))
                                    }
                                }
                            }
                        }

                        // ================= GIANT ONE-TAP VOICE DELIVERY MIC =================
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("voice_delivery_card"),
                            shape = RoundedCornerShape(26.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(22.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "RECORD DELIVERY AT THIS STOP",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = OceanNavyPrimary,
                                    letterSpacing = 1.sp
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = if (isRecording) "Recording Voice Note..." else "Say 'We delivered water here'",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isRecording) StatusRedAlert else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (isRecording) FontWeight.Bold else FontWeight.Normal
                                )

                                Spacer(modifier = Modifier.height(18.dp))

                                // Giant Circular Microphone Button
                                Box(
                                    modifier = Modifier
                                        .size(130.dp)
                                        .scale(if (isRecording) pulseScale else 1f)
                                        .clip(CircleShape)
                                        .background(if (isRecording) StatusRedAlert else OceanNavyPrimary)
                                        .clickable {
                                            if (isRecording) {
                                                viewModel.stopAndSaveVoiceDelivery(truck.id, effectiveDriverName, context)
                                            } else {
                                                viewModel.startVoiceDeliveryRecording()
                                            }
                                        }
                                        .testTag("voice_record_button"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                                            contentDescription = "Microphone",
                                            tint = Color.White,
                                            modifier = Modifier.size(54.dp)
                                        )
                                        if (isRecording) {
                                            Text(
                                                text = "STOP",
                                                color = Color.White,
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = 12.sp
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                if (voiceUploadStatus != "Idle") {
                                    Text(
                                        text = voiceUploadStatus,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = when {
                                            voiceUploadStatus.startsWith("Uploaded") -> StatusActiveGreen
                                            voiceUploadStatus.startsWith("Upload pending") -> StatusAmberWarning
                                            else -> CyanWater
                                        },
                                        textAlign = TextAlign.Center
                                    )
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                if (isRecording) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = StatusRedAlert.copy(alpha = 0.15f)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .background(StatusRedAlert, CircleShape)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            val minutes = recordDurationSec / 60
                                            val seconds = recordDurationSec % 60
                                            Text(
                                                text = "RECORDING: ${String.format("%02d:%02d", minutes, seconds)}",
                                                fontWeight = FontWeight.ExtraBold,
                                                color = StatusRedAlert,
                                                fontSize = 14.sp
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Tap red circle to finish & send",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = OceanNavyPrimary
                                    )
                                } else {
                                    Text(
                                        text = "TAP MIC TO RECORD DELIVERY",
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 15.sp,
                                        color = OceanNavyPrimary
                                    )
                                    Text(
                                        text = "Auto-attaches current GPS location & time",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // ================= RECORDED DELIVERIES TODAY =================
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.VolumeUp, contentDescription = null, tint = CyanWater, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Recorded Deliveries (${voiceNotes.size})",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            text = "Today",
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                if (voiceNotes.isEmpty()) {
                                    Text(
                                        text = "No deliveries recorded yet. Tap the big mic button when delivering water.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        voiceNotes.forEachIndexed { index, note ->
                                            val isCurrentPlaying = (playingPath == note.audioFilePath && isPlaying)
                                            Surface(
                                                shape = RoundedCornerShape(14.dp),
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
                                                        onClick = { viewModel.playVoiceNote(note.audioFilePath) },
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
                                                                text = "Delivery Note #${voiceNotes.size - index}",
                                                                style = MaterialTheme.typography.titleSmall,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                            Spacer(modifier = Modifier.width(6.dp))
                                                            Text(
                                                                text = "(${note.durationSeconds}s)",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = CyanWater,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                        }

                                                        val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(note.timestamp))
                                                        Text(
                                                            text = "Recorded at $timeStr • ${note.locationLabel}",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            maxLines = 1
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // ================= SHIFT END / LOG OUT =================
                        if (isLogoutAllowed) {
                            // After 9:00 PM: Driver is allowed to log out directly
                            Button(
                                onClick = { viewModel.endDriverShift(truck.id, context) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .testTag("end_shift_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = StatusRedAlert),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "FINISH WORK & LOG OUT",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 14.sp
                                )
                            }
                        } else {
                            // Before 9:00 PM: Shift is locked. Only manager can authorize early logout.
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("shift_locked_card"),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                                border = androidx.compose.foundation.BorderStroke(1.5.dp, StatusAmberWarning.copy(alpha = 0.5f))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(18.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = StatusAmberWarning.copy(alpha = 0.15f),
                                        modifier = Modifier.size(46.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.Lock,
                                                contentDescription = null,
                                                tint = StatusAmberWarning,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    Text(
                                        text = "SHIFT LOCKED UNTIL ${shiftPolicy.formattedLogoutTime()}",
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 15.sp,
                                        color = OceanNavyPrimary
                                    )

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Text(
                                        text = "Logout becomes available at ${shiftPolicy.formattedLogoutTime()}. Your shift and GPS tracking will continue until you manually log out. Ask your manager to enable early logout if needed.",
                                        textAlign = TextAlign.Center,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    Text(
                                        text = "Early logout is controlled by your fleet manager.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }

    // Selfie Attendance Dialog
    val truckForSelfie = effectiveTruck ?: currentTruck
    if (showSelfieDialog && truckForSelfie != null) {
        SelfieAttendanceDialog(
            truck = truckForSelfie,
            driverName = effectiveDriverName,
            onDismiss = { showSelfieDialog = false },
            onConfirmCheckIn = { selfieUri ->
                viewModel.startDriverShift(truckForSelfie.id, effectiveDriverName, selfieUri, context)
                showSelfieDialog = false
            }
        )
    }
}
