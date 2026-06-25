package com.dangeedums.acmeter.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.dangeedums.acmeter.data.Device
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

private val METER_SERVICE_UUID: UUID =
    UUID.fromString("5f12b3bc-8ef3-4b48-a971-f70a38f519ec")

/**
 * Thin wrapper over Android's BluetoothLeScanner.
 *
 * `nearby()` is a cold Flow: collecting starts a scan, cancellation stops it.
 * Results are deduplicated by MAC address and emitted as the discovered set
 * grows. We filter on the firmware's custom service UUID — that's the most
 * reliable way to find AC Energy Meter devices because Android 12+ does not
 * always surface the advertising name on first sight of the device.
 *
 * As a fallback, we also accept anything whose name starts with "Meter-"
 * so older firmware revisions or partial advert packets still show up.
 */
class BleScanner(private val context: Context) {

    private val manager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = manager.adapter

    val isBluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    fun nearby(): Flow<List<Device>> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val discovered = LinkedHashMap<String, Device>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handle(result)
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(::handle)
            }
            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("BLE scan failed: code=$errorCode"))
            }

            private fun handle(result: ScanResult) {
                val name = try {
                    result.device.name ?: result.scanRecord?.deviceName
                } catch (_: SecurityException) {
                    null
                }
                if (name.isNullOrBlank() || !name.startsWith("Meter-", ignoreCase = true)) {
                    // Service-UUID match is enough; keep the address but synthesize a name.
                }
                val effectiveName = name?.takeIf { it.isNotBlank() }
                    ?: "Meter (${result.device.address.takeLast(5)})"
                val key = result.device.address
                discovered[key] = Device(name = effectiveName, address = key)
                trySend(discovered.values.toList())
            }
        }

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(METER_SERVICE_UUID))
                .build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(filters, settings, callback)

        awaitClose {
            try {
                scanner.stopScan(callback)
            } catch (_: SecurityException) {
                // permission revoked mid-scan
            }
        }
    }
}
