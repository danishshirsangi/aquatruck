package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.DriverEntity
import com.example.data.model.TruckEntity

@Composable
fun ManagerDriverAssignmentsScreen(
    drivers: List<DriverEntity>,
    trucks: List<TruckEntity>,
    onAssignTruck: (Long, Int) -> Unit,
    onEarlyLogoutChanged: (Long, Boolean) -> Unit
) {
    var expandedDriverId by remember { mutableStateOf<Long?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Driver assignments", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Register each driver with the phone number they will use to join. Assign a truck and enable early logout individually when requested.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (drivers.isEmpty()) {
            item { Text("No drivers registered yet. Use Add Driver from Fleet Overview first.") }
        }
        items(drivers, key = { it.id }) { driver ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(driver.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (driver.phone.isBlank()) "Add a phone number to let this driver join."
                        else driver.phone,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (driver.userUid.isNullOrBlank()) "Invitation: waiting for driver to join"
                        else "Driver account linked",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Button(
                        onClick = { expandedDriverId = driver.id },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = trucks.isNotEmpty()
                    ) {
                        val selectedTruck = trucks.firstOrNull { it.id == driver.assignedTruckId }
                        Text(if (selectedTruck == null) "Assign a truck" else "Assigned: ${selectedTruck.name} — change")
                    }
                    DropdownMenu(
                        expanded = expandedDriverId == driver.id,
                        onDismissRequest = { expandedDriverId = null }
                    ) {
                        trucks.forEach { truck ->
                            DropdownMenuItem(
                                text = { Text("${truck.name} (${truck.plateNumber})") },
                                onClick = {
                                    onAssignTruck(driver.id, truck.id)
                                    expandedDriverId = null
                                }
                            )
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Allow early logout", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Unlock this driver's logout button before the standard time.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = driver.earlyLogoutEnabled,
                            onCheckedChange = { onEarlyLogoutChanged(driver.id, it) }
                        )
                    }
                }
            }
        }
    }
}
