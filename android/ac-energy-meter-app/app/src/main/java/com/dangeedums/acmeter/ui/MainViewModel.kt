package com.dangeedums.acmeter.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dangeedums.acmeter.AcMeterApp
import com.dangeedums.acmeter.data.Device
import com.dangeedums.acmeter.data.DeviceStore
import com.dangeedums.acmeter.ble.BleScanner
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ScanUiState(
    val scanning: Boolean = false,
    val nearby: List<Device> = emptyList(),
    val error: String? = null,
    val needsPermission: Boolean = false,
)

class MainViewModel(
    application: Application,
    private val store: DeviceStore,
    private val scanner: BleScanner,
) : AndroidViewModel(application) {

    val savedDevices: Flow<List<Device>> = store.devices

    private val _scanState = MutableStateFlow(ScanUiState())
    val scanState: StateFlow<ScanUiState> = _scanState.asStateFlow()

    private var scanJob: Job? = null

    fun startScan() {
        if (!scanner.hasScanPermission()) {
            _scanState.value = _scanState.value.copy(
                needsPermission = true, scanning = false, error = null,
            )
            return
        }
        if (!scanner.isBluetoothEnabled) {
            // Clear scanning so the UI shows the Bluetooth-off notice instead of
            // the "Scanning…" placeholder.
            _scanState.value = ScanUiState(
                scanning = false,
                error = "Bluetooth is turned off. Turn it on, then tap Scan.",
            )
            return
        }
        if (scanJob?.isActive == true) return
        _scanState.value = ScanUiState(scanning = true)
        scanJob = viewModelScope.launch {
            try {
                scanner.nearby().collect { list ->
                    _scanState.value = _scanState.value.copy(nearby = list, error = null)
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                // Normal: the user navigated away or tapped Stop. Not an error —
                // rethrow so structured concurrency unwinds cleanly and we don't
                // surface "StandaloneCoroutine was cancelled" to the user.
                throw ce
            } catch (t: Throwable) {
                _scanState.value = _scanState.value.copy(
                    scanning = false,
                    error = friendlyScanError(t),
                )
            }
        }
    }

    private fun friendlyScanError(t: Throwable): String {
        val msg = t.message.orEmpty()
        return when {
            msg.contains("code=2", ignoreCase = true) ->
                "Couldn't start scanning. Try turning Bluetooth off and on again."
            msg.contains("code=1", ignoreCase = true) ->
                "A scan is already running. Wait a moment and try again."
            msg.contains("permission", ignoreCase = true) ->
                "Bluetooth permission is required to scan for devices."
            msg.isBlank() ->
                "Couldn't scan for devices. Make sure Bluetooth is on and try again."
            else -> msg
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _scanState.value = _scanState.value.copy(scanning = false)
    }

    fun onPermissionGranted() {
        _scanState.value = _scanState.value.copy(needsPermission = false)
        startScan()
    }

    fun addDevice(device: Device) {
        viewModelScope.launch { store.add(device) }
    }

    fun removeDevice(address: String) {
        viewModelScope.launch { store.remove(address) }
    }

    override fun onCleared() {
        super.onCleared()
        stopScan()
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = application as AcMeterApp
                MainViewModel(application, app.deviceStore, app.bleScanner)
            }
        }
    }
}
