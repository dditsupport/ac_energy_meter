package com.dangeedums.acmeter.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.dangeedums.acmeter.AcMeterApp
import com.dangeedums.acmeter.cloud.CloudClient
import com.dangeedums.acmeter.cloud.CloudDevice
import com.dangeedums.acmeter.cloud.ReadingPoint
import com.dangeedums.acmeter.data.BlePinStore
import com.dangeedums.acmeter.data.BleUnlockRegistry
import com.dangeedums.acmeter.data.CloudSessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class Range(val label: String, val aggregate: String) {
    Today  ("Today",   "hourly"),
    Last24h("24 h",    "hourly"),
    Last7d ("7 days",  "daily"),
    Last30d("30 days", "daily"),
    Last12m("12 mo",   "monthly"),
}

data class CloudUi(
    val loggedIn: Boolean = false,
    val username: String = "",
    val baseUrl: String = "https://aromen.biz",
    val devices: List<CloudDevice> = emptyList(),
    val selectedDeviceId: String? = null,
    val range: Range = Range.Today,
    val points: List<ReadingPoint> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

class CloudViewModel(
    application: Application,
    private val client: CloudClient,
    private val session: CloudSessionStore,
    private val blePinStore: BlePinStore,
    private val unlockRegistry: BleUnlockRegistry,
) : AndroidViewModel(application) {

    private val _ui = MutableStateFlow(CloudUi())
    val ui: StateFlow<CloudUi> = _ui.asStateFlow()

    /** Cache the authorised BLE PINs locally so the BLE access gate can
     *  validate entered PINs offline. Called whenever we fetch the device list
     *  while logged in. */
    private suspend fun cachePins(devices: List<CloudDevice>) {
        val pins = devices.mapNotNull { d ->
            d.ble_pin?.takeIf { it.isNotBlank() }?.let { d.device_id.lowercase() to it }
        }.toMap()
        blePinStore.setAll(pins)
        session.setLoggedIn(true)
    }

    init {
        viewModelScope.launch {
            val s = session.settings.first()
            client.setBaseUrl(s.baseUrl)
            _ui.value = _ui.value.copy(baseUrl = s.baseUrl, username = s.username)
            // Try a fetch to detect a still-valid session cookie. If it fails
            // with 401 the user lands on the login screen.
            tryAutoFetch()
        }
    }

    private suspend fun tryAutoFetch() {
        runCatching { client.devices() }
            .onSuccess { resp ->
                if (resp.ok) {
                    // Session is alive but CSRF was wiped at process restart — refresh it.
                    client.refreshCsrf()
                    cachePins(resp.devices)
                    _ui.value = _ui.value.copy(
                        loggedIn = true,
                        devices = resp.devices,
                        selectedDeviceId = resp.devices.firstOrNull()?.device_id,
                    )
                    refreshChart()
                }
            }
    }

    fun setBaseUrl(url: String) {
        _ui.value = _ui.value.copy(baseUrl = url)
        client.setBaseUrl(url)
        viewModelScope.launch { session.update(baseUrl = url) }
    }

    fun login(username: String, password: String) {
        _ui.value = _ui.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { client.login(username, password) }
                .onSuccess { resp ->
                    if (resp.ok) {
                        session.update(username = username)
                        _ui.value = _ui.value.copy(loggedIn = true, username = username, loading = false)
                        refreshDevices()
                    } else {
                        _ui.value = _ui.value.copy(loading = false, error = resp.error ?: "login failed")
                    }
                }
                .onFailure { _ui.value = _ui.value.copy(loading = false, error = it.message ?: "network error") }
        }
    }

    fun logout() {
        viewModelScope.launch {
            runCatching { client.logout() }
            // Drop cached BLE PINs + unlocks so registered devices re-lock.
            runCatching { blePinStore.clear() }
            unlockRegistry.clear()
            session.setLoggedIn(false)
            _ui.value = CloudUi(baseUrl = _ui.value.baseUrl)
        }
    }

    fun refreshDevices() {
        _ui.value = _ui.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { client.devices() }
                .onSuccess { resp ->
                    cachePins(resp.devices)
                    val selected = _ui.value.selectedDeviceId
                        ?: resp.devices.firstOrNull()?.device_id
                    _ui.value = _ui.value.copy(
                        devices = resp.devices,
                        selectedDeviceId = selected,
                        loading = false,
                    )
                    refreshChart()
                }
                .onFailure { _ui.value = _ui.value.copy(loading = false, error = it.message ?: "fetch failed") }
        }
    }

    fun selectDevice(id: String) {
        _ui.value = _ui.value.copy(selectedDeviceId = id)
        refreshChart()
    }

    fun selectRange(r: Range) {
        _ui.value = _ui.value.copy(range = r)
        refreshChart()
    }

    fun refreshChart() {
        val dev = _ui.value.selectedDeviceId ?: return
        val r   = _ui.value.range
        val fromIso = when (r) {
            Range.Today   -> LocalDate.now().atStartOfDay().format(LocalIso)
            Range.Last24h -> LocalDateTime.now().minusHours(24).format(LocalIso)
            Range.Last7d  -> LocalDateTime.now().minusDays(7).format(LocalIso)
            Range.Last30d -> LocalDateTime.now().minusDays(30).format(LocalIso)
            Range.Last12m -> LocalDateTime.now().minusMonths(12).format(LocalIso)
        }
        _ui.value = _ui.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { client.readings(dev, r.aggregate, fromIso = fromIso) }
                .onSuccess { resp ->
                    _ui.value = _ui.value.copy(
                        loading = false,
                        points = resp.points,
                    )
                }
                .onFailure { _ui.value = _ui.value.copy(loading = false, error = it.message ?: "fetch failed") }
        }
    }

    companion object {
        private val LocalIso: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

        fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = application as AcMeterApp
                CloudViewModel(
                    application, app.cloudClient, app.cloudSessionStore,
                    app.blePinStore, app.bleUnlockRegistry,
                )
            }
        }
    }
}
