package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.tenant.DriverShiftPolicy
import com.example.ui.FleetViewModel

@Composable
fun ManagerShiftSettingsScreen(viewModel: FleetViewModel) {
    val policy by viewModel.shiftPolicy.collectAsState()
    var timeText by remember(policy.logoutTimeMinutes) { mutableStateOf(policy.formattedLogoutTime()) }
    var earlyLogout by remember(policy.allowEarlyLogout) { mutableStateOf(policy.allowEarlyLogout) }
    var error by remember { mutableStateOf<String?>(null) }
    var savedMessage by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Driver shift settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "The configured time only unlocks the driver's logout button. Drivers are never automatically logged out and GPS tracking does not stop until they manually end the shift.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedTextField(
            value = timeText,
            onValueChange = { timeText = it.take(5); error = null; savedMessage = false },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Default logout-allowed time (HH:mm)") },
            placeholder = { Text("21:00") },
            singleLine = true
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Allow early logout", fontWeight = FontWeight.SemiBold)
                Text("Enable the driver's logout button before the configured time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = earlyLogout, onCheckedChange = { earlyLogout = it; savedMessage = false })
        }
        Button(
            onClick = {
                val parts = timeText.trim().split(":")
                val hour = parts.getOrNull(0)?.toIntOrNull()
                val minute = parts.getOrNull(1)?.toIntOrNull()
                if (parts.size != 2 || hour == null || minute == null || hour !in 0..23 || minute !in 0..59) {
                    error = "Enter time in 24-hour HH:mm format, for example 21:00."
                    return@Button
                }
                error = null
                viewModel.saveShiftPolicy(DriverShiftPolicy(hour * 60 + minute, earlyLogout, policy.timezone))
                savedMessage = true
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save shift settings") }
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
        if (savedMessage) Text("Settings saved. Connected driver devices will receive the update.", color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Text("Timezone: \${policy.timezone}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
