package com.dangeedums.acmeter.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dangeedums.acmeter.R
import com.dangeedums.acmeter.data.Device

@Composable
fun SavedDevicesScreen(
    devices: List<Device>,
    onRemove: (String) -> Unit,
    onOpen:   (Device) -> Unit = {},
) {
    if (devices.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.empty_saved),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.title_devices),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        items(devices, key = { it.address }) { device ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onOpen(device) },
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = device.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = device.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        device.id?.let { canonical ->
                            Text(text = canonical, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    IconButton(onClick = { onRemove(device.address) }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_remove))
                    }
                }
            }
        }
    }
}
