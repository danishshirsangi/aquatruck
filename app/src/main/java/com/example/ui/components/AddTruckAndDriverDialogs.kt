package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.ui.theme.OceanNavyPrimary

@Composable
fun AddTruckDialog(
    nextTruckNumber: Int,
    onDismiss: () -> Unit,
    onAddTruck: (name: String, plate: String, driver: String, capacity: Int) -> Unit
) {
    var truckName by remember { mutableStateOf("Hydro Tanker $nextTruckNumber") }
    var plateNumber by remember { mutableStateOf("") }
    var driverName by remember { mutableStateOf("") }
    var capacityText by remember { mutableStateOf("10000") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .clip(RoundedCornerShape(24.dp))
                .testTag("add_truck_dialog"),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 16.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Add Water Tanker Truck",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = OceanNavyPrimary
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = truckName,
                    onValueChange = { truckName = it },
                    label = { Text("Truck Name / ID") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = plateNumber,
                    onValueChange = { plateNumber = it.uppercase() },
                    label = { Text("License Plate Number") },
                    placeholder = { Text("e.g. MH-12-AB-5678") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = driverName,
                    onValueChange = { driverName = it },
                    label = { Text("Assigned Driver Name") },
                    placeholder = { Text("e.g. Rajesh Kumar") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = capacityText,
                    onValueChange = { capacityText = it.filter { char -> char.isDigit() } },
                    label = { Text("Tank Capacity (Liters)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(18.dp))

                Button(
                    onClick = {
                        val cap = capacityText.toIntOrNull() ?: 10000
                        onAddTruck(
                            truckName.ifBlank { "Water Tanker $nextTruckNumber" },
                            plateNumber.ifBlank { "WT-0$nextTruckNumber-AUTO" },
                            driverName.ifBlank { "Driver $nextTruckNumber" },
                            cap
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("save_truck_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = OceanNavyPrimary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Register Truck in Fleet", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun AddDriverDialog(
    onDismiss: () -> Unit,
    onAddDriver: (name: String, phone: String) -> Unit
) {
    var driverName by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(24.dp))
                .testTag("add_driver_dialog"),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 16.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Add Driver",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = OceanNavyPrimary
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = driverName,
                    onValueChange = { driverName = it },
                    label = { Text("Driver Full Name") },
                    placeholder = { Text("e.g. David Miller") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Phone Number (required for invitation)") },
                    placeholder = { Text("e.g. +1 555-0199") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(18.dp))

                Button(
                    onClick = {
                        if (driverName.isNotBlank() && phone.filter { it.isDigit() }.length >= 8) {
                            onAddDriver(driverName, phone)
                        }
                    },
                    enabled = driverName.isNotBlank() && phone.filter { it.isDigit() }.length >= 8,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("save_driver_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = OceanNavyPrimary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Save Driver", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
