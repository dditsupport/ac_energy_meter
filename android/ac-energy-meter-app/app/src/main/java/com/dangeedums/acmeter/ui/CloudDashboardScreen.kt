package com.dangeedums.acmeter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
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
import com.dangeedums.acmeter.cloud.ReadingPoint
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudDashboardScreen(vm: CloudViewModel, onSignOut: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Cloud", style = MaterialTheme.typography.titleLarge,
                 modifier = Modifier.weight(1f))
            Text(ui.username, style = MaterialTheme.typography.bodyMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant,
                 modifier = Modifier.padding(end = 8.dp))
            OutlinedButton(onClick = onSignOut) { Text("Sign out") }
        }

        // Device picker
        var expanded by remember { mutableStateOf(false) }
        val current = ui.devices.firstOrNull { it.device_id == ui.selectedDeviceId }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            OutlinedTextField(
                value = current?.friendly_name ?: (ui.selectedDeviceId ?: "— pick a device —"),
                onValueChange = {},
                readOnly = true,
                label = { Text("Device") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = expanded, onDismissRequest = { expanded = false }) {
                ui.devices.forEach { d ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(d.friendly_name, fontWeight = FontWeight.Medium)
                                Text(d.device_id, style = MaterialTheme.typography.bodySmall,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        onClick = { vm.selectDevice(d.device_id); expanded = false },
                    )
                }
                if (ui.devices.isEmpty()) {
                    DropdownMenuItem(text = { Text("No devices bound to you") }, onClick = { expanded = false })
                }
            }
        }

        // Range chips. Five chips don't fit on narrow phones, so the row
        // scrolls horizontally instead of clipping the last ("12 mo") chip.
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            Range.entries.forEach { r ->
                FilterChip(
                    selected = ui.range == r,
                    onClick = { vm.selectRange(r) },
                    label = { Text(r.label) },
                )
            }
        }

        // Stats
        val periodKwh = ui.points.sumOf { it.kwh ?: 0.0 }
        val peakW     = ui.points.maxOfOrNull { it.P_peak ?: it.P ?: 0.0 } ?: 0.0
        val nowW      = ui.points.lastOrNull()?.P ?: ui.points.lastOrNull()?.P_avg ?: 0.0
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard("Period kWh", "%.2f".format(periodKwh), modifier = Modifier.weight(1f))
            StatCard("Peak W",     "%.0f".format(peakW),     modifier = Modifier.weight(1f))
            StatCard("Last W",     "%.0f".format(nowW),      modifier = Modifier.weight(1f))
        }

        // Energy column chart (kWh per bucket)
        Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Text("Energy (${ui.range.label})", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                EnergyChart(ui.points)
            }
        }

        // Power line chart (W avg per bucket, or raw)
        Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Text("Power", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                PowerChart(ui.points)
            }
        }

        ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall,
                 color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun EnergyChart(points: List<ReadingPoint>) {
    if (points.isEmpty()) {
        Text("No data in range.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val values = points.map { (it.kwh ?: 0.0).toFloat() }
    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(values) {
        modelProducer.runTransaction {
            columnSeries { series(values) }
        }
    }
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberColumnCartesianLayer(),
            startAxis  = VerticalAxis.rememberStart(),
            bottomAxis = HorizontalAxis.rememberBottom(),
        ),
        modelProducer = modelProducer,
        scrollState   = rememberVicoScrollState(scrollEnabled = true),
        modifier      = Modifier.fillMaxWidth().height(180.dp),
    )
}

@Composable
private fun PowerChart(points: List<ReadingPoint>) {
    if (points.isEmpty()) {
        Text("No data in range.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val values = points.map { (it.P_avg ?: it.P ?: 0.0).toFloat() }
    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(values) {
        modelProducer.runTransaction {
            lineSeries { series(values) }
        }
    }
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(),
            startAxis  = VerticalAxis.rememberStart(),
            bottomAxis = HorizontalAxis.rememberBottom(),
        ),
        modelProducer = modelProducer,
        scrollState   = rememberVicoScrollState(scrollEnabled = true),
        modifier      = Modifier.fillMaxWidth().height(180.dp),
    )
}
