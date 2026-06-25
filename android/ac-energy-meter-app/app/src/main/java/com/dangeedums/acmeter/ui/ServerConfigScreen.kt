package com.dangeedums.acmeter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dangeedums.acmeter.ble.MeterGatt
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@Composable
fun ServerConfigScreen(
    gatt: MeterGatt,
    vm: DeviceDetailViewModel,
    onBack: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var host by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(ui.info?.ingestHost) {
        ui.info?.ingestHost?.let { if (host.isBlank()) host = it }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp),
           verticalArrangement = Arrangement.spacedBy(12.dp)) {

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Backend host", style = MaterialTheme.typography.titleLarge)
        }

        Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
             modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Currently configured on device", fontWeight = FontWeight.Medium)
                Text(ui.info?.let { it.ingestHost + it.ingestPath } ?: "—",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        OutlinedTextField(
            value = host,
            onValueChange = { host = it; saved = null; error = null },
            label = { Text("Host (e.g. https://aromen.biz)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Path is firmware-hardcoded: ${ui.info?.ingestPath ?: "/meter/api/ingest.php"}",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)

        Button(
            enabled = host.isNotBlank(),
            onClick = {
                scope.launch {
                    runCatching {
                        // JSON body: {"host":"<value>"}  — quote() returns the value already wrapped in quotes.
                        val body = "{\"host\":" + quote(host.trim()) + "}"
                        gatt.writeServerConfig(body)
                        vm.refreshInfo()
                        saved = host.trim()
                    }.onFailure { error = it.message }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save to device") }

        saved?.let {
            Text("Saved: $it", color = MaterialTheme.colorScheme.primary)
        }
        error?.let {
            Text("Error: $it", color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun quote(s: String): String {
    val sb = StringBuilder("\"")
    s.forEach { c ->
        when (c) {
            '"', '\\' -> sb.append('\\').append(c)
            else      -> sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}
