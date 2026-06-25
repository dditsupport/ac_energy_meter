package com.dangeedums.acmeter.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dangeedums.acmeter.R
import com.dangeedums.acmeter.data.Device

@Composable
fun ScanScreen(
    state: ScanUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onAdd: (Device) -> Unit,
    onBack: () -> Unit,
) {
    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.all { it }) onStart()
    }

    LaunchedEffect(Unit) {
        if (!state.scanning) onStart()
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = stringResource(R.string.title_scan),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            if (state.scanning) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                OutlinedButton(onClick = onStop) { Text(stringResource(R.string.action_stop)) }
            } else {
                Button(onClick = onStart) { Text(stringResource(R.string.action_scan)) }
            }
        }

        if (state.needsPermission) {
            Spacer(modifier = Modifier.padding(top = 12.dp))
            PermissionPrompt(onGrant = {
                permLauncher.launch(requiredPermissions())
            })
        }

        state.error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Spacer(modifier = Modifier.padding(top = 8.dp))

        if (state.nearby.isEmpty()) {
            // Only describe the empty state when there isn't already an error
            // banner above (e.g. "Bluetooth is off") — otherwise we'd claim to
            // be scanning while we're actually stopped.
            if (state.error == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (state.scanning) {
                            stringResource(R.string.empty_scan)
                        } else {
                            stringResource(R.string.empty_scan_idle)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.nearby, key = { it.address }) { device ->
                    DiscoveredDeviceCard(device = device, onAdd = onAdd)
                }
            }
        }
    }
}

@Composable
private fun PermissionPrompt(onGrant: () -> Unit) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = stringResource(R.string.permission_needed))
            Spacer(modifier = Modifier.padding(top = 8.dp))
            Button(onClick = onGrant) { Text(stringResource(R.string.action_grant)) }
        }
    }
}

@Composable
private fun DiscoveredDeviceCard(device: Device, onAdd: (Device) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = device.name, fontWeight = FontWeight.SemiBold)
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { onAdd(device) }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.action_add))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.action_add))
            }
        }
    }
}

private fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )
} else {
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}
