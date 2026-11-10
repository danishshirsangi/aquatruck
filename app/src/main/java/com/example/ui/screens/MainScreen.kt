package com.example.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.FleetViewModel
import com.example.ui.UserRole
import com.example.data.tenant.TenantManager
import com.example.ui.components.ManagerPinDialog
import com.example.ui.theme.CyanWater
import com.example.ui.theme.OceanNavyPrimary
import com.google.firebase.Firebase
import com.google.firebase.auth.auth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: FleetViewModel = viewModel()
) {
    val currentRole by viewModel.currentRole.collectAsState()
    val context = LocalContext.current
    val isManagerAccount = TenantManager.role(context) == "manager"
    val isManagerUnlocked by viewModel.isManagerUnlocked.collectAsState()

    var showPinDialog by remember { mutableStateOf(false) }

    // Intercept back button when in Manager Portal to return to Driver Mode
    BackHandler(enabled = (currentRole == UserRole.MANAGER)) {
        viewModel.setRole(UserRole.DRIVER)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = CyanWater.copy(alpha = 0.2f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.WaterDrop,
                                    contentDescription = "AquaTrack Logo",
                                    tint = CyanWater,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "AquaTrack",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                            Text(
                                text = "Water Transport Services",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyanWater
                            )
                        }
                    }
                },
                actions = {
                    // Driver-only memberships never see a Manager mode switch.
                    if (isManagerAccount) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Color.White.copy(alpha = 0.12f),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Driver Option
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(
                                            if (currentRole == UserRole.DRIVER) CyanWater else Color.Transparent
                                        )
                                        .clickable { viewModel.setRole(UserRole.DRIVER) }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                        .testTag("role_driver_button"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Badge,
                                            contentDescription = null,
                                            tint = if (currentRole == UserRole.DRIVER) OceanNavyPrimary else Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Driver",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            color = if (currentRole == UserRole.DRIVER) OceanNavyPrimary else Color.White
                                        )
                                    }
                                }
    
                                // Manager Option
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(
                                            if (currentRole == UserRole.MANAGER) CyanWater else Color.Transparent
                                        )
                                        .clickable {
                                            if (isManagerUnlocked) {
                                                viewModel.setRole(UserRole.MANAGER)
                                            } else {
                                                showPinDialog = true
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                        .testTag("role_manager_button"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (isManagerUnlocked) Icons.Default.LockOpen else Icons.Default.Lock,
                                            contentDescription = null,
                                            tint = if (currentRole == UserRole.MANAGER) OceanNavyPrimary else Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Manager",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            color = if (currentRole == UserRole.MANAGER) OceanNavyPrimary else Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (currentRole == UserRole.MANAGER) {
                        IconButton(
                            onClick = {
                                viewModel.lockManager()
                                Firebase.auth.signOut()
                            },
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .size(40.dp)
                                .testTag("manager_sign_out_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Logout,
                                contentDescription = "Sign out",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = OceanNavyPrimary,
                    titleContentColor = Color.White
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentRole) {
                UserRole.DRIVER -> DriverPortalScreen(viewModel = viewModel)
                UserRole.MANAGER -> ManagerPortalScreen(viewModel = viewModel)
            }
        }
    }

    if (showPinDialog) {
        ManagerPinDialog(
            onDismiss = { showPinDialog = false },
            onVerifyPin = { pin ->
                val success = viewModel.unlockManager(pin)
                if (success) {
                    showPinDialog = false
                }
                success
            }
        )
    }
}
