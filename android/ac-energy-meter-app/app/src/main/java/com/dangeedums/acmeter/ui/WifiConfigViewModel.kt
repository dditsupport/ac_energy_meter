package com.dangeedums.acmeter.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dangeedums.acmeter.ble.MeterGatt
import com.dangeedums.acmeter.ble.WifiScanResult
import com.dangeedums.acmeter.ble.WifiStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

data class WifiConfigUi(
    val scanning: Boolean = false,
    val networks: List<WifiScanResult> = emptyList(),
    val selected: String = "",
    val password: String = "",
    val status: WifiStatus? = null,
    val saving: Boolean = false,
    val message: String = "",
    val log: List<String> = emptyList(),
)

/**
 * Reuses the parent DeviceDetailViewModel's MeterGatt instance — passed in so
 * we don't open a second BLE connection. Caller is responsible for keeping
 * the parent's connection alive while this screen is on-screen.
 *
 * Scan results and Wi-Fi status both arrive via BLE NOTIFY, but notifications
 * are racy: the firmware may push a result in the gap before Kable finishes
 * enabling the CCCD, or push only once. So in addition to subscribing we
 * actively READ-poll both characteristics after every request.
 */
class WifiConfigViewModel(private val gatt: MeterGatt) : ViewModel() {

    private val _ui = MutableStateFlow(WifiConfigUi())
    val ui: StateFlow<WifiConfigUi> = _ui.asStateFlow()

    init {
        // Live status updates from the device (Wi-Fi Status NOTIFY).
        gatt.observeWifiStatus()
            .onEach { st -> applyStatus(st) }
            .catch { /* swallow; user can re-open if connection drops */ }
            .launchIn(viewModelScope)

        // Live scan results from the device (Wi-Fi Scan NOTIFY).
        gatt.observeWifiScan()
            .onEach { list -> if (list.isNotEmpty()) applyNetworks(list) }
            .catch { /* swallow */ }
            .launchIn(viewModelScope)

        // Read whatever the device already has cached, then kick a fresh scan.
        viewModelScope.launch {
            runCatching { gatt.readWifiStatus() }.getOrNull()?.let { applyStatus(it) }
            runCatching { gatt.readWifiScan() }.getOrNull()?.let {
                if (it.isNotEmpty()) applyNetworks(it)
            }
        }
        requestScan()
    }

    private fun applyStatus(st: WifiStatus) {
        _ui.value = _ui.value.copy(
            status = st,
            log = (_ui.value.log + "status: ${st.status}").takeLast(20),
        )
        // If we were waiting on a save, turn the device's status into feedback.
        if (_ui.value.saving) {
            val msg = when (st.status.lowercase()) {
                "connected"  -> "Connected to ${st.ssid ?: _ui.value.selected}."
                "connecting" -> "Connecting to ${st.ssid ?: _ui.value.selected}…"
                "failed", "disconnected" ->
                    "Couldn't connect${st.detail?.let { " — $it" } ?: ""}. Check the password."
                else -> "Device status: ${st.status}"
            }
            val done = st.status.equals("connected", true) ||
                       st.status.equals("failed", true) ||
                       st.status.equals("disconnected", true)
            _ui.value = _ui.value.copy(message = msg, saving = !done && _ui.value.saving)
        }
    }

    private fun applyNetworks(list: List<WifiScanResult>) {
        // Keep the strongest entry per SSID, sorted by signal.
        val best = list.groupBy { it.ssid }
            .map { (_, dupes) -> dupes.maxBy { it.rssi } }
            .sortedByDescending { it.rssi }
        _ui.value = _ui.value.copy(networks = best, scanning = false)
    }

    fun requestScan() {
        _ui.value = _ui.value.copy(scanning = true)
        viewModelScope.launch {
            runCatching { gatt.writeWifiConfig("""{"action":"scan"}""") }
                .onFailure {
                    _ui.value = _ui.value.copy(scanning = false,
                        message = "Couldn't ask the device to scan: ${it.message}")
                    return@launch
                }
            // Poll the scan characteristic — the firmware fills it a few
            // seconds after we request, and the NOTIFY may not reach us.
            repeat(8) {
                delay(2000)
                val list = runCatching { gatt.readWifiScan() }.getOrNull()
                if (!list.isNullOrEmpty()) {
                    applyNetworks(list)
                    return@launch
                }
            }
            // Timed out — stop the spinner; manual entry still works.
            if (_ui.value.networks.isEmpty()) {
                _ui.value = _ui.value.copy(scanning = false)
            } else {
                _ui.value = _ui.value.copy(scanning = false)
            }
        }
    }

    fun selectSsid(ssid: String) {
        _ui.value = _ui.value.copy(selected = ssid, message = "")
    }

    fun setPassword(pw: String) {
        _ui.value = _ui.value.copy(password = pw)
    }

    fun saveCredentials() {
        val ssid = _ui.value.selected.trim()
        val pw   = _ui.value.password
        if (ssid.isBlank()) {
            _ui.value = _ui.value.copy(message = "Enter a network name (SSID) first.")
            return
        }
        _ui.value = _ui.value.copy(saving = true, message = "Sending credentials to device…")
        viewModelScope.launch {
            val json = buildString {
                append("""{"ssid":"""); append(quote(ssid))
                append(""","password":"""); append(quote(pw)); append("""}""")
            }
            runCatching { gatt.writeWifiConfig(json) }
                .onSuccess {
                    _ui.value = _ui.value.copy(
                        message = "Credentials saved. Asking the device to connect…",
                    )
                    // Poll status as a fallback in case the NOTIFY is missed.
                    repeat(10) {
                        delay(1500)
                        val st = runCatching { gatt.readWifiStatus() }.getOrNull()
                        if (st != null) {
                            applyStatus(st)
                            if (!_ui.value.saving) return@launch  // reached a terminal state
                        }
                    }
                    // Stop the spinner even if the device never reported back.
                    if (_ui.value.saving) {
                        _ui.value = _ui.value.copy(
                            saving = false,
                            message = "Credentials saved. The device will keep trying to connect.",
                        )
                    }
                }
                .onFailure {
                    _ui.value = _ui.value.copy(
                        saving = false,
                        message = "Couldn't save credentials: ${it.message}",
                    )
                }
        }
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        s.forEach { c ->
            when (c) {
                '"', '\\' -> sb.append('\\').append(c)
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
