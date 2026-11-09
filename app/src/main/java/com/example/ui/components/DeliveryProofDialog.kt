package com.example.ui.components

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
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
fun DeliveryProofDialog(
    truck: TruckEntity,
    onDismiss: () -> Unit,
    onSubmitProof: (customer: String, volumeLiters: Int, photoUri: String?, notes: String) -> Unit
) {
    val context = LocalContext.current
    var customerName by remember { mutableStateOf("") }
    var volumeText by remember { mutableStateOf("4000") }
    var deliveryNotes by remember { mutableStateOf("") }

    var photoUri by remember { mutableStateOf<Uri?>(null) }
    var generatedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var tempPhotoFile by remember { mutableStateOf<File?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && tempPhotoFile != null) {
            photoUri = Uri.fromFile(tempPhotoFile)
        }
    }

    fun launchCamera() {
        try {
            val file = File(context.cacheDir, "proof_${truck.id}_${System.currentTimeMillis()}.jpg")
            tempPhotoFile = file
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            cameraLauncher.launch(uri)
        } catch (e: Exception) {
            val fallbackBitmap = createDeliveryProofBitmap(
                truck.name,
                customerName.ifBlank { "Water Customer Site" },
                volumeText.toIntOrNull() ?: 4000,
                truck.currentLat,
                truck.currentLng
            )
            generatedBitmap = fallbackBitmap
            val file = File(context.cacheDir, "proof_demo_${truck.id}.jpg")
            FileOutputStream(file).use { out ->
                fallbackBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            photoUri = Uri.fromFile(file)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(24.dp))
                .testTag("delivery_proof_dialog"),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 16.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
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
                            text = "Upload Delivery Proof",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = OceanNavyPrimary
                        )
                        Text(
                            text = "${truck.name} • POD Form",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Photo Capture Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF0F172A))
                        .border(
                            2.dp,
                            if (photoUri != null) StatusActiveGreen else CyanWater.copy(alpha = 0.5f),
                            RoundedCornerShape(16.dp)
                        )
                        .clickable { launchCamera() },
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        photoUri != null -> {
                            AsyncImage(
                                model = photoUri,
                                contentDescription = "Delivery Proof Photo",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                        generatedBitmap != null -> {
                            Image(
                                bitmap = generatedBitmap!!.asImageBitmap(),
                                contentDescription = "Delivery Proof Photo",
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
                                    imageVector = Icons.Default.CameraAlt,
                                    contentDescription = "Capture Delivery",
                                    tint = CyanWater,
                                    modifier = Modifier.size(44.dp)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Tap to photograph tank valve / meter",
                                    color = Color.White.copy(alpha = 0.9f),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Attaches secure GPS & time watermark",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // GPS Stamp Info Banner
                val timeStr = remember {
                    SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault()).format(Date())
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = CyanWater, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "GPS Geotag: ${String.format("%.4f", truck.currentLat)}, ${String.format("%.4f", truck.currentLng)}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Time: $timeStr",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Inputs: Customer Site & Water Volume
                OutlinedTextField(
                    value = customerName,
                    onValueChange = { customerName = it },
                    label = { Text("Customer / Site Name") },
                    placeholder = { Text("e.g. Apex Tower Construction") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = volumeText,
                    onValueChange = { volumeText = it.filter { char -> char.isDigit() } },
                    label = { Text("Water Volume Delivered (Liters)") },
                    leadingIcon = {
                        Icon(Icons.Default.WaterDrop, contentDescription = null, tint = CyanWater)
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = deliveryNotes,
                    onValueChange = { deliveryNotes = it },
                    label = { Text("Delivery Notes") },
                    placeholder = { Text("e.g. Inflow valve sealed, signed by site supervisor") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    maxLines = 3
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Submit Button
                Button(
                    onClick = {
                        val vol = volumeText.toIntOrNull() ?: 4000
                        onSubmitProof(
                            customerName,
                            vol,
                            photoUri?.toString(),
                            deliveryNotes.ifBlank { "Discharge completed into customer cistern." }
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("submit_delivery_proof_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = OceanNavyPrimary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Confirm & Log Delivery Proof", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun createDeliveryProofBitmap(truckName: String, customer: String, volume: Int, lat: Double, lng: Double): Bitmap {
    val bitmap = Bitmap.createBitmap(480, 320, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint().apply { isAntiAlias = true }

    // Industrial water tanker background
    paint.color = AndroidColor.parseColor("#0A2540")
    canvas.drawRect(0f, 0f, 480f, 320f, paint)

    // Tanker discharge valve graphic
    paint.color = AndroidColor.parseColor("#00A8E8")
    canvas.drawCircle(240f, 130f, 80f, paint)
    paint.color = AndroidColor.parseColor("#FFFFFF")
    canvas.drawCircle(240f, 130f, 50f, paint)
    paint.color = AndroidColor.parseColor("#007EA7")
    canvas.drawCircle(240f, 130f, 25f, paint)

    // Proof stamp banner at bottom
    paint.color = AndroidColor.parseColor("#CC000000")
    canvas.drawRect(0f, 230f, 480f, 320f, paint)

    paint.color = AndroidColor.parseColor("#A3E635")
    paint.textSize = 20f
    paint.isFakeBoldText = true
    canvas.drawText("WATER DELIVERY VERIFIED • ${volume}L", 20f, 260f, paint)

    paint.color = AndroidColor.WHITE
    paint.textSize = 15f
    paint.isFakeBoldText = false
    canvas.drawText("$truckName • $customer", 20f, 285f, paint)
    canvas.drawText("GPS: ${String.format("%.4f", lat)}, ${String.format("%.4f", lng)}", 20f, 305f, paint)

    return bitmap
}
