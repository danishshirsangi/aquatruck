package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.BusinessOnboardingScreen
import com.example.data.tenant.TenantManager
import com.example.ui.screens.MainScreen
import com.example.ui.theme.AquaTrackTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val fleetViewModel: com.example.ui.FleetViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
            val isDarkTheme by fleetViewModel.isDarkTheme.collectAsState()
            val darkTheme = isDarkTheme ?: androidx.compose.foundation.isSystemInDarkTheme()

            AquaTrackTheme(darkTheme = darkTheme) {
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { /* Permissions result handled */ }

                LaunchedEffect(Unit) {
                    val permissionsToRequest = mutableListOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.CAMERA,
                        Manifest.permission.RECORD_AUDIO
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                    }

                    val needed = permissionsToRequest.filter { perm ->
                        ContextCompat.checkSelfPermission(this@MainActivity, perm) != PackageManager.PERMISSION_GRANTED
                    }
                    if (needed.isNotEmpty()) {
                        permissionLauncher.launch(needed.toTypedArray())
                    }
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    var currentUser by remember { mutableStateOf(Firebase.auth.currentUser) }

                    DisposableEffect(Unit) {
                        val listener = FirebaseAuth.AuthStateListener { auth ->
                            currentUser = auth.currentUser
                        }
                        Firebase.auth.addAuthStateListener(listener)
                        onDispose {
                            Firebase.auth.removeAuthStateListener(listener)
                        }
                    }

                    var tenantReady by remember { mutableStateOf(false) }
                    var tenantCheckComplete by remember { mutableStateOf(false) }

                    LaunchedEffect(currentUser?.uid) {
                        tenantReady = false
                        tenantCheckComplete = currentUser == null
                        if (currentUser != null) {
                            tenantReady = TenantManager.restoreMembership(this@MainActivity)
                            tenantCheckComplete = true
                        }
                    }

                    LaunchedEffect(tenantReady) {
                        if (tenantReady) fleetViewModel.refreshTenantContext()
                    }

                    LaunchedEffect(tenantReady) {
                        if (tenantReady) fleetViewModel.refreshTenantContext()
                    }

                    if (currentUser == null) {
                        AuthScreen(
                            onAuthSuccess = {
                                currentUser = Firebase.auth.currentUser
                            }
                        )
                    } else if (!tenantCheckComplete) {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = androidx.compose.ui.Alignment.Center
                        ) { androidx.compose.material3.CircularProgressIndicator() }
                    } else if (!tenantReady) {
                        BusinessOnboardingScreen(onComplete = { tenantReady = true })
                    } else {
                        MainScreen(viewModel = fleetViewModel)
                    }
                }
            }
        }
    }
}
