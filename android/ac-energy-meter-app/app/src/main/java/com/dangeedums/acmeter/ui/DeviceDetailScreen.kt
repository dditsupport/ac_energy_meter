package com.dangeedums.acmeter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun DeviceDetailScreen(
    deviceName: String,
    vm: DeviceDetailViewModel,
    onBack: () -> Unit,
    onConfigureWifi: () -> Unit,
    onConfigureServer: () -> Unit,
    onGoToCloud: () -> Unit = {},
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var showClaim by remember { mutableStateOf(false) }

    if (showClaim && ui.info != null) {
        ClaimDeviceDialog(
            defaultName = ui.info!!.deviceId,
            onDismiss = { showClaim = false; vm.resetClaimState() },
            onConfirm = { name, loc, kw ->
                vm.claimToCloud(name, loc, kw)
            },
            stage = ui.claimStage,
            message = ui.claimMessage,
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(deviceName, style = MaterialTheme.typography.titleLarge)
                Text(if (ui.access == AccessState.Unlocked) connStateLabel(ui.connState) else "Locked",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (ui.access == AccessState.Unlocked && ui.connState == ConnState.Connecting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            }
        }

        ui.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        if (ui.access != AccessState.Unlocked) {
            Spacer(Modifier.height(16.dp))
            AccessGateCard(
                ui          = ui,
                onSubmitPin = { vm.submitPin(it) },
                onRetry     = { vm.runAccessGate() },
                onGoToCloud = onGoToCloud,
            )
            return@Column
        }

        if (ui.connState == ConnState.Failed || ui.connState == ConnState.Disconnected) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = { vm.connect() }) { Text("Retry connect") }
        }

        ui.info?.let { info ->
            Spacer(Modifier.height(12.dp))
            InfoCard(info, ui.wifi)

            Spacer(Modifier.height(12.dp))
            RelayCard(relay = ui.relay, onMode = { vm.setRelayMode(it) })

            Spacer(Modifier.height(12.dp))
            ActionsCard(
                unsyncedCount     = info.unsyncedCount,
                onSync            = { vm.syncNow() },
                onConfigureWifi   = onConfigureWifi,
                onConfigureServer = onConfigureServer,
                onRefresh         = { vm.refreshInfo() },
                onClaim           = { showClaim = true },
                syncing           = ui.syncStage != SyncStage.Idle
                                     && ui.syncStage != SyncStage.Done
                                     && ui.syncStage != SyncStage.Failed,
            )

            if (ui.syncStage != SyncStage.Idle) {
                Spacer(Modifier.height(12.dp))
                SyncProgressCard(ui)
            }
        }
    }
}

@Composable
private fun AccessGateCard(
    ui: DeviceDetailUi,
    onSubmitPin: (String) -> Unit,
    onRetry: () -> Unit,
    onGoToCloud: () -> Unit,
) {
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (ui.access) {
                AccessState.Checking -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(Modifier.size(12.dp))
                        Text("Checking device access…")
                    }
                }
                AccessState.NeedPin -> {
                    var pin by remember { mutableStateOf("") }
                    Text("Enter BLE PIN", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "This meter is locked. Enter its PIN to connect over Bluetooth.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                        label = { Text("PIN") },
                        singleLine = true,
                        isError = ui.pinError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (ui.pinError) {
                        Text("Incorrect PIN. Try again.",
                             color = MaterialTheme.colorScheme.error,
                             style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = { onSubmitPin(pin) },
                        enabled = pin.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Unlock") }
                }
                AccessState.NeedLogin -> {
                    Text("Sign in required", style = MaterialTheme.typography.titleMedium)
                    Text(ui.accessMessage,
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onGoToCloud, modifier = Modifier.fillMaxWidth()) {
                        Text("Go to Cloud sign-in")
                    }
                }
                AccessState.Denied -> {
                    Text("Access denied", style = MaterialTheme.typography.titleMedium)
                    Text(ui.accessMessage,
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Text("Retry")
                    }
                }
                AccessState.Unlocked -> { /* gate not shown */ }
            }
        }
    }
}

@Composable
private fun InfoCard(
    info: com.dangeedums.acmeter.ble.DeviceInfoBle,
    wifi: com.dangeedums.acmeter.ble.WifiStatus?,
) {
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Device", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Field("Device ID", info.deviceId)
            Field("Firmware",  info.fw)
            WifiField(wifi)
            Field("Boot ID",   "${info.currentBootId}  (uptime ${info.uptimeSec}s)")
            Field("Last seq",  info.lastSeq.toString())
            Field("Unsynced",  info.unsyncedCount.toString())
            Field("RTC",       if (info.rtcOk) "ok" else "missing / lost power")
            Field("Wall clock",if (info.wallClockKnown) "known" else "unknown")
            Field("Log interval", "${info.logIntervalSec}s")
            if (info.ingestHost.isNotBlank()) {
                Field("Backend", "${info.ingestHost}${info.ingestPath}")
            }
        }
    }
}

@Composable
private fun WifiField(wifi: com.dangeedums.acmeter.ble.WifiStatus?) {
    val connected = wifi?.status.equals("connected", ignoreCase = true)
    val value = when {
        wifi == null -> "—"
        connected    -> buildString {
            append("Connected")
            wifi.ssid?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
            wifi.ip?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        }
        else -> wifi.status.replaceFirstChar { it.uppercase() } +
            (wifi.detail?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
    }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("Wi-Fi", modifier = Modifier.weight(0.4f),
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(0.6f),
            color = when {
                wifi == null -> MaterialTheme.colorScheme.onSurface
                connected    -> MaterialTheme.colorScheme.primary
                else         -> MaterialTheme.colorScheme.error
            },
        )
    }
}

@Composable
private fun ActionsCard(
    unsyncedCount: Int,
    onSync: () -> Unit,
    onConfigureWifi: () -> Unit,
    onConfigureServer: () -> Unit,
    onRefresh: () -> Unit,
    onClaim: () -> Unit,
    syncing: Boolean,
) {
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Actions", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Refresh device info")
            }
            Button(
                onClick = onSync,
                enabled = !syncing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Sync, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(if (unsyncedCount > 0) "Sync $unsyncedCount row(s) now" else "Sync (heartbeat)")
            }
            OutlinedButton(onClick = onConfigureWifi, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Wifi, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Configure Wi-Fi")
            }
            OutlinedButton(onClick = onConfigureServer, modifier = Modifier.fillMaxWidth()) {
                Text("Configure backend host")
            }
            OutlinedButton(onClick = onClaim, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.CloudUpload, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Register with cloud")
            }
        }
    }
}

@Composable
private fun RelayCard(
    relay: com.dangeedums.acmeter.ble.RelayState?,
    onMode: (String) -> Unit,
) {
    val mode = relay?.mode ?: "auto"
    val on   = relay?.on ?: false
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Relay", style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text("State", modifier = Modifier.weight(0.4f),
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (on) "ON" else "OFF",
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(0.6f),
                    color = if (on) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                when (mode) {
                    "on"  -> "Manual override: forced ON (ignores schedule)."
                    "off" -> "Manual override: forced OFF (ignores schedule)."
                    else  -> "Auto: following the server schedule."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RelayModeButton("On",   active = mode == "on",   modifier = Modifier.weight(1f)) { onMode("on") }
                RelayModeButton("Off",  active = mode == "off",  modifier = Modifier.weight(1f)) { onMode("off") }
                RelayModeButton("Auto", active = mode == "auto", modifier = Modifier.weight(1f)) { onMode("auto") }
            }
        }
    }
}

@Composable
private fun RelayModeButton(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    if (active) Button(onClick = onClick, modifier = modifier) { Text(label) }
    else        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
}

@Composable
private fun ClaimDeviceDialog(
    defaultName: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, location: String?, capacityKw: Double?) -> Unit,
    stage: ClaimStage,
    message: String,
) {
    var name        by remember { mutableStateOf(defaultName) }
    var location    by remember { mutableStateOf("") }
    var capacityStr by remember { mutableStateOf("") }

    val submitting = stage == ClaimStage.Submitting
    val finished   = stage == ClaimStage.Done

    LaunchedEffect(stage) {
        // Auto-close on success after the user has seen the confirmation.
        if (stage == ClaimStage.Done) {
            kotlinx.coroutines.delay(1200)
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("Register device with cloud") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Device ID: $defaultName",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Friendly name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = location, onValueChange = { location = it },
                    label = { Text("Location (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = capacityStr, onValueChange = { capacityStr = it },
                    label = { Text("Capacity in kW (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (message.isNotBlank()) {
                    val color = when (stage) {
                        ClaimStage.Done                       -> MaterialTheme.colorScheme.primary
                        ClaimStage.Failed, ClaimStage.Conflict-> MaterialTheme.colorScheme.error
                        else                                  -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(message, color = color, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !submitting && !finished && name.isNotBlank(),
                onClick = {
                    onConfirm(
                        name.trim(),
                        location.trim().ifBlank { null },
                        capacityStr.trim().toDoubleOrNull(),
                    )
                },
            ) {
                if (submitting) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                else            Text(if (finished) "Done" else "Register")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) { Text("Cancel") }
        },
    )
}

@Composable
private fun SyncProgressCard(ui: DeviceDetailUi) {
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Sync", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(syncStageLabel(ui.syncStage))
            if (ui.syncMessage.isNotBlank()) {
                Text(ui.syncMessage,
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (ui.syncStage in setOf(SyncStage.Reading, SyncStage.Forwarding, SyncStage.Acking)) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, modifier = Modifier.weight(0.4f),
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.6f))
    }
}

private fun connStateLabel(s: ConnState): String = when (s) {
    ConnState.Idle         -> "Idle"
    ConnState.Connecting   -> "Connecting…"
    ConnState.Connected    -> "Connected"
    ConnState.Disconnected -> "Disconnected"
    ConnState.Failed       -> "Connect failed"
}

private fun syncStageLabel(s: SyncStage): String = when (s) {
    SyncStage.Idle       -> ""
    SyncStage.Reading    -> "Reading from device"
    SyncStage.Forwarding -> "Forwarding to server"
    SyncStage.Acking     -> "Acknowledging device"
    SyncStage.Done       -> "Done"
    SyncStage.Failed     -> "Failed"
}
