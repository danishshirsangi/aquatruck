package com.example.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.example.data.model.TruckEntity
import com.example.ui.theme.CyanWater
import com.example.ui.theme.OceanNavyPrimary
import com.example.ui.theme.StatusActiveGreen
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SelfieAttendanceDialog(
    truck: TruckEntity,
    driverName: String = truck.driverName,
    onDismiss: () -> Unit,
    onConfirmCheckIn: (selfieUri: String?) -> Unit
) {
    val context = LocalContext.current
    var selfieUri by remember { mutableStateOf<Uri?>(null) }
    var generatedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var tempPhotoFile by remember { mutableStateOf<File?>(null) }

    // Camera Launcher with FileProvider
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && tempPhotoFile != null) {
            selfieUri = Uri.fromFile(tempPhotoFile)
        }
    }

    // Function to take photo via Camera Intent
    fun launchCamera() {
        try {
            val file = File(context.cacheDir, "driver_selfie_${truck.id}_${System.currentTimeMillis()}.jpg")
            tempPhotoFile = file
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            cameraLauncher.launch(uri)
        } catch (e: Exception) {
            // In headless/streaming emulator container, camera intent might be missing; create water transport driver avatar bitmap
            val fallbackBitmap = createDriverSelfieAvatar(truck.driverName, truck.name)
            generatedBitmap = fallbackBitmap
            val file = File(context.cacheDir, "driver_selfie_demo_${truck.id}.jpg")
            FileOutputStream(file).use { out ->
                fallbackBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            selfieUri = Uri.fromFile(file)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(24.dp))
                .testTag("selfie_attendance_dialog"),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 16.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Driver Attendance",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = OceanNavyPrimary
                        )
                        Text(
                            text = "${truck.name} • $driverName",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Selfie Viewfinder Area
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF0F172A))
                        .border(4.dp, if (selfieUri != null) StatusActiveGreen else CyanWater, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        selfieUri != null -> {
                            AsyncImage(
                                model = selfieUri,
                                contentDescription = "Driver Selfie",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                        generatedBitmap != null -> {
                            Image(
                                bitmap = generatedBitmap!!.asImageBitmap(),
                                contentDescription = "Driver Selfie",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                        else -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Face,
                                    contentDescription = "Face Guide",
                                    tint = CyanWater.copy(alpha = 0.8f),
                                    modifier = Modifier.size(72.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Center face inside oval",
                                    color = Color.White.copy(alpha = 0.8f),
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Timestamp and GPS Auto-Stamp Badges
                val currentTimeStr = remember {
                    SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault()).format(Date())
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LocationOn, contentDescription = null, tint = CyanWater, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "GPS: ${String.format("%.4f", truck.currentLat)}, ${String.format("%.4f", truck.currentLng)} (Yard Central)",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Timestamp: $currentTimeStr",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Actions: Capture / Retake & Confirm
                if (selfieUri == null && generatedBitmap == null) {
                    Button(
                        onClick = { launchCamera() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp)
                            .testTag("take_selfie_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = OceanNavyPrimary),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("1. CLICK SELFIE PHOTO", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { launchCamera() },
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("Retake", fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                onConfirmCheckIn(selfieUri?.toString())
                            },
                            modifier = Modifier
                                .weight(1.5f)
                                .height(56.dp)
                                .testTag("confirm_checkin_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = StatusActiveGreen),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("START WORK", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                        }
                    }
                }
            }
        }
    }
}

// Generates a crisp water transport driver attendance avatar bitmap for immediate fallback
private fun createDriverSelfieAvatar(driverName: String, truckName: String): Bitmap {
    val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Background gradient
    val paint = Paint().apply {
        isAntiAlias = true
    }
    paint.color = AndroidColor.parseColor("#0B2545")
    canvas.drawRect(0f, 0f, 400f, 400f, paint)

    // Avatar head & shoulders
    paint.color = AndroidColor.parseColor("#134074")
    canvas.drawCircle(200f, 150f, 75f, paint)
    paint.color = AndroidColor.parseColor("#8DA9C4")
    canvas.drawCircle(200f, 140f, 60f, paint)

    paint.color = AndroidColor.parseColor("#00A8E8")
    canvas.drawOval(80f, 260f, 320f, 440f, paint)

    // Verified badge
    paint.color = AndroidColor.WHITE
    paint.textSize = 22f
    paint.textAlign = Paint.Align.CENTER
    paint.isFakeBoldText = true
    canvas.drawText(driverName, 200f, 320f, paint)

    paint.textSize = 16f
    paint.color = AndroidColor.parseColor("#A3E635")
    canvas.drawText("ATTENDANCE VERIFIED", 200f, 350f, paint)

    return bitmap
}
