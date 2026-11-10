package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.tenant.TenantManager
import kotlinx.coroutines.launch

@Composable
fun BusinessOnboardingScreen(onComplete: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var managerMode by remember { mutableStateOf(true) }
    var businessName by remember { mutableStateOf("") }
    var businessType by remember { mutableStateOf("Water tanker / transport") }
    var ownerName by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("") }
    var gstin by remember { mutableStateOf("") }
    var managerPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf("") }
    var driverName by remember { mutableStateOf("") }
    var driverPhone by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var generatedCode by remember { mutableStateOf<String?>(null) }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Welcome to AquaTruck", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
            Text("Set up your business workspace or join a fleet using an invitation code.", color = MaterialTheme.colorScheme.onSurfaceVariant)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { managerMode = true; error = null; generatedCode = null },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = if (managerMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer)
                ) { Text("Register as Manager") }
                Button(
                    onClick = { managerMode = false; error = null; generatedCode = null },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = if (!managerMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer)
                ) { Text("Join as Driver") }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (managerMode) {
                        Text("Business information", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        OutlinedTextField(businessName, { businessName = it }, label = { Text("Business name *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(businessType, { businessType = it }, label = { Text("Business type") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(ownerName, { ownerName = it }, label = { Text("Manager / owner name *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(phone, { phone = it }, label = { Text("Contact number *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(email, { email = it }, label = { Text("Business email") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(address, { address = it }, label = { Text("Business address") }, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(city, { city = it }, label = { Text("City") }, modifier = Modifier.weight(1f), singleLine = true)
                            OutlinedTextField(state, { state = it }, label = { Text("State") }, modifier = Modifier.weight(1f), singleLine = true)
                        }
                        OutlinedTextField(gstin, { gstin = it }, label = { Text("GSTIN (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Spacer(Modifier.height(4.dp))
                        Text("Manager security PIN", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        OutlinedTextField(managerPin, { managerPin = it.filter(Char::isDigit).take(8) }, label = { Text("Create 4–8 digit PIN") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(confirmPin, { confirmPin = it.filter(Char::isDigit).take(8) }, label = { Text("Confirm PIN") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                    } else {
                        Text("Join an existing business", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        OutlinedTextField(inviteCode, { inviteCode = it.uppercase().take(12) }, label = { Text("One-time driver invitation code *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(driverName, { driverName = it }, label = { Text("Your full name *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(driverPhone, { driverPhone = it }, label = { Text("Phone number") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Text("Enter the individual one-time invitation code and phone number registered by your manager. The invitation determines your business and assigned truck.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }

                    if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (generatedCode != null) {
                        Text("Business created successfully.", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Business created. Add driver profiles from Fleet Overview and share their individual one-time codes from Drivers & Assignments.")
                        Text("Business reference: " + generatedCode!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Button(
                        onClick = {
                            error = null
                            isLoading = true
                            scope.launch {
                                try {
                                    if (managerMode) {
                                        if (managerPin != confirmPin) throw IllegalArgumentException("Manager PIN entries do not match.")
                                        generatedCode = TenantManager.registerBusiness(
                                            context, businessName, businessType, ownerName, phone, email,
                                            address, city, state, gstin, managerPin
                                        )
                                    } else {
                                        TenantManager.joinBusiness(context, inviteCode, driverName, driverPhone)
                                        onComplete()
                                    }
                                } catch (e: Exception) {
                                    error = e.localizedMessage ?: "Unable to complete registration."
                                } finally {
                                    isLoading = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading && generatedCode == null
                    ) {
                        Text(if (isLoading) "Please wait…" else if (managerMode) "Create Business" else "Join Business")
                    }
                    if (generatedCode != null) {
                        Button(onClick = onComplete, modifier = Modifier.fillMaxWidth()) { Text("Continue to Manager Portal") }
                    }
                }
            }
        }
    }
}
