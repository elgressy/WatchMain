package com.pulsewave.relax.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private var isMeasuring by mutableStateOf(false)
    private var permissionDenied by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.BODY_SENSORS] == true) {
            startMeasuring()
        } else {
            permissionDenied = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WearApp() }
    }

    @Composable
    private fun WearApp() {
        val bpm by HeartRateBus.bpm.collectAsState()
        val connected by HeartRateBus.phoneConnected.collectAsState()

        MaterialTheme {
            Box(modifier = Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(text = if (isMeasuring) (bpm?.roundToInt()?.toString() ?: "--") else "--")
                    Text(text = stringResource(R.string.bpm_unit))
                    Text(
                        text = stringResource(
                            if (connected) R.string.phone_connected else R.string.phone_disconnected
                        )
                    )
                    if (permissionDenied) {
                        Text(text = stringResource(R.string.permission_explainer))
                    }
                    Button(onClick = { toggleMeasuring() }) {
                        Text(text = stringResource(if (isMeasuring) R.string.stop else R.string.start))
                    }
                }
            }
        }
    }

    private fun toggleMeasuring() {
        if (isMeasuring) stopMeasuring() else requestPermissionAndStart()
    }

    private fun requestPermissionAndStart() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.BODY_SENSORS
        ) == PackageManager.PERMISSION_GRANTED

        when {
            granted -> startMeasuring()
            permissionDenied && !ActivityCompat.shouldShowRequestPermissionRationale(
                this, Manifest.permission.BODY_SENSORS
            ) -> openAppSettings()
            else -> {
                val perms = mutableListOf(Manifest.permission.BODY_SENSORS)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    perms += Manifest.permission.POST_NOTIFICATIONS
                }
                permissionLauncher.launch(perms.toTypedArray())
            }
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
        )
    }

    private fun startMeasuring() {
        permissionDenied = false
        isMeasuring = true
        ContextCompat.startForegroundService(this, Intent(this, HeartRateForegroundService::class.java))
    }

    private fun stopMeasuring() {
        isMeasuring = false
        stopService(Intent(this, HeartRateForegroundService::class.java))
        HeartRateBus.clearBpm()
    }
}
