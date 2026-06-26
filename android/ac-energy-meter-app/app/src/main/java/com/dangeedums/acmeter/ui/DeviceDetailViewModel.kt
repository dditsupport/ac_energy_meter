package com.dangeedums.acmeter.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.dangeedums.acmeter.AcMeterApp
import com.dangeedums.acmeter.ble.DeviceInfoBle
import com.dangeedums.acmeter.ble.MeterGatt
import com.dangeedums.acmeter.ble.peripheralForAddress
import com.dangeedums.acmeter.cloud.CloudClient
import com.dangeedums.acmeter.cloud.IngestBoot
import com.dangeedums.acmeter.cloud.IngestPayload
import com.dangeedums.acmeter.cloud.IngestReading
import com.dangeedums.acmeter.data.BlePinStore
import com.dangeedums.acmeter.data.BleUnlockRegistry
import com.dangeedums.acmeter.data.CloudSessionStore
import com.juul.kable.NotConnectedException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

enum class ConnState  { Idle, Connecting, Connected, Disconnected, Failed }
enum class SyncStage  { Idle, Reading, Forwarding, Acking, Done, Failed }
enum class ClaimStage { Idle, Submitting, Done, Conflict, Failed }

/**
 * BLE access gate (server-issued PIN). Until the device is [Unlocked], the
 * detail screen does not connect over BLE.
 *   Checking  - deciding whether a PIN is needed
 *   NeedPin   - registered + we hold the PIN: prompt for it
 *   NeedLogin - registered but not signed in: ask the user to log in
 *   Denied    - registered to someone else / can't verify
 *   Unlocked  - open (unregistered) or PIN accepted -> connect proceeds
 */
enum class AccessState { Checking, NeedPin, NeedLogin, Denied, Unlocked }

data class DeviceDetailUi(
    val connState: ConnState = ConnState.Idle,
    val info: DeviceInfoBle? = null,
    val wifi: com.dangeedums.acmeter.ble.WifiStatus? = null,
    val relay: com.dangeedums.acmeter.ble.RelayState? = null,
    val error: String? = null,
    val syncStage: SyncStage = SyncStage.Idle,
    val syncRows: Int = 0,
    val syncMessage: String = "",
    val claimStage: ClaimStage = ClaimStage.Idle,
    val claimMessage: String = "",
    val access: AccessState = AccessState.Checking,
    val accessMessage: String = "",
    val pinError: Boolean = false,
)

class DeviceDetailViewModel(
    application: Application,
    private val address: String,
    private val cloud: CloudClient,
    private val session: CloudSessionStore,
    private val blePinStore: BlePinStore,
    private val unlockRegistry: BleUnlockRegistry,
) : AndroidViewModel(application) {

    private val peripheral = peripheralForAddress(address)
    val gatt = MeterGatt(peripheral)

    private val _ui = MutableStateFlow(DeviceDetailUi())
    val ui: StateFlow<DeviceDetailUi> = _ui.asStateFlow()

    // The canonical device_id, read from the device's Device Info after a brief
    // connect. The BLE advertising name is unreliable on Android 12+ (the
    // scanner finds meters by service UUID and may synthesize a placeholder
    // name), so we never gate on the name — only on this device-reported id.
    private var pendingId: String? = null
    private var pendingInfo: DeviceInfoBle? = null

    init {
        connect()
    }

    /** Re-run the access gate (used by the lock screen's Retry). */
    fun runAccessGate() = connect()

    /**
     * Connect, read the real device_id from Device Info, then gate BEFORE
     * exposing anything. The brief connect leaks nothing (the data stream is
     * pull-only); if the gate fails we disconnect immediately. See [AccessState].
     */
    fun connect() {
        if (_ui.value.connState == ConnState.Connecting) return
        _ui.value = _ui.value.copy(
            access = AccessState.Checking, accessMessage = "", pinError = false,
            connState = ConnState.Connecting, error = null,
        )
        viewModelScope.launch {
            val info = try {
                withTimeout(20_000) { gatt.connect() }
                gatt.readDeviceInfo()
            } catch (t: Throwable) {
                _ui.value = _ui.value.copy(
                    connState = ConnState.Failed,
                    access = AccessState.Denied,
                    accessMessage = "Couldn't connect to the device. Move closer and retry.",
                )
                return@launch
            }

            val id = info.deviceId.trim().lowercase()
            pendingId = id
            pendingInfo = info
            _ui.value = _ui.value.copy(connState = ConnState.Connected)

            when {
                unlockRegistry.isUnlocked(id) -> proceedUnlocked(id, info)
                blePinStore.get(id) != null ->
                    _ui.value = _ui.value.copy(access = AccessState.NeedPin)
                else -> {
                    val registered = runCatching { cloud.bleRegistered(id) }.getOrNull()
                    when {
                        registered == null ->
                            lockAndDisconnect(AccessState.Denied,
                                "Couldn't verify this device. Check your connection and retry.")
                        !registered.registered ->
                            proceedUnlocked(id, info)   // unregistered -> open (provisioning)
                        !session.isLoggedIn() ->
                            lockAndDisconnect(AccessState.NeedLogin,
                                "This meter is registered. Sign in on the Cloud tab to access it over Bluetooth.")
                        else ->
                            lockAndDisconnect(AccessState.Denied,
                                "This meter is registered to another account. Ask an admin for access.")
                    }
                }
            }
        }
    }

    /** Validate a PIN entered by the user against the cached server PIN. */
    fun submitPin(entered: String) {
        val id   = pendingId ?: return
        val info = pendingInfo ?: return
        viewModelScope.launch {
            val expected = blePinStore.get(id)
            if (expected != null && entered.trim() == expected) {
                proceedUnlocked(id, info)
            } else {
                _ui.value = _ui.value.copy(pinError = true)
            }
        }
    }

    /** Gate passed: surface device info and start the normal live observers. */
    private suspend fun proceedUnlocked(id: String, info: DeviceInfoBle) {
        unlockRegistry.unlock(id)
        _ui.value = _ui.value.copy(
            access = AccessState.Unlocked, connState = ConnState.Connected,
            info = info, pinError = false, error = null,
        )
        // Set wall time from the phone — best-effort, helps the device if its
        // RTC is missing/dead.
        runCatching { gatt.setWallTime(nowIso()) }
        runCatching { gatt.readWifiStatus() }.getOrNull()?.let {
            _ui.value = _ui.value.copy(wifi = it)
        }
        gatt.observeWifiStatus()
            .onEach { _ui.value = _ui.value.copy(wifi = it) }
            .catch { /* connection ended; ignore */ }
            .launchIn(viewModelScope)
        runCatching { gatt.readRelay() }.getOrNull()?.let {
            _ui.value = _ui.value.copy(relay = it)
        }
        gatt.observeRelay()
            .onEach { _ui.value = _ui.value.copy(relay = it) }
            .catch { /* connection ended; ignore */ }
            .launchIn(viewModelScope)
    }

    private fun lockAndDisconnect(state: AccessState, message: String) {
        _ui.value = _ui.value.copy(access = state, accessMessage = message,
                                    connState = ConnState.Disconnected)
        viewModelScope.launch { runCatching { gatt.disconnect() } }
    }

    fun disconnect() {
        viewModelScope.launch {
            runCatching { gatt.disconnect() }
            _ui.value = _ui.value.copy(connState = ConnState.Disconnected)
        }
    }

    fun refreshInfo() {
        viewModelScope.launch { readInfoNow() }
    }

    /** Manual relay control over BLE. mode = "on" | "off" | "auto". */
    fun setRelayMode(mode: String) {
        viewModelScope.launch {
            runCatching { gatt.writeRelayMode(mode) }
                .onFailure { _ui.value = _ui.value.copy(error = "relay: ${it.message}") }
            // The firmware notifies on change, but read back too in case the
            // notification was missed (e.g. mode unchanged).
            kotlinx.coroutines.delay(250)
            runCatching { gatt.readRelay() }.getOrNull()?.let {
                _ui.value = _ui.value.copy(relay = it)
            }
        }
    }

    /** Suspending device-info read so callers can await it (e.g. after a sync). */
    private suspend fun readInfoNow() {
        runCatching { gatt.readDeviceInfo() }
            .onSuccess { _ui.value = _ui.value.copy(info = it, error = null) }
            .onFailure { _ui.value = _ui.value.copy(error = "read info: ${it.message}") }
        // Best-effort Wi-Fi status so the Device Info card can show
        // connected / disconnected without the user opening Configure Wi-Fi.
        runCatching { gatt.readWifiStatus() }.getOrNull()?.let {
            _ui.value = _ui.value.copy(wifi = it)
        }
    }

    /**
     * BLE-relay sync: pull every buffered row off the device, build the
     * ingest payload, POST it to MilesWeb, then ACK the highest seq back
     * to the device so it truncates /log.csv.
     */
    fun syncNow() {
        viewModelScope.launch {
            try {
                _ui.value = _ui.value.copy(syncStage = SyncStage.Reading, syncRows = 0,
                                            syncMessage = "Subscribing to data stream…")
                val info  = gatt.readDeviceInfo()
                val boots = gatt.readBootHistory()

                // Accumulate stream until "END\n" arrives.
                val acc = StringBuilder()
                withTimeout(60_000) {
                    gatt.observeDataStream().takeWhile { chunk ->
                        acc.append(chunk)
                        !chunk.contains("END\n") && !chunk.endsWith("END")
                    }.collect { /* accumulating */ }
                }

                val rows = parseCsvChunks(acc.toString())
                _ui.value = _ui.value.copy(syncRows = rows.size,
                                            syncStage = SyncStage.Forwarding,
                                            syncMessage = "Forwarding ${rows.size} row(s)…")

                if (rows.isEmpty()) {
                    _ui.value = _ui.value.copy(syncStage = SyncStage.Done,
                                                syncMessage = "Nothing to sync.")
                    return@launch
                }

                val s = session.settings.first()
                val payload = IngestPayload(
                    device_id              = info.deviceId,
                    fw_version             = info.fw,
                    sync_wall_time         = nowIso(),
                    current_boot_id        = info.currentBootId,
                    current_boot_uptime_sec= info.uptimeSec,
                    boot_history           = boots.map { IngestBoot(it.bootId, it.durationSec) },
                    readings               = rows,
                )
                val resp = cloud.ingest(s.deviceToken, payload)

                if (!resp.ok) {
                    val msg = when (resp.error) {
                        "unauthorized"               -> "Sign in on the Cloud tab first, then try again."
                        "bad_csrf"                   -> "Session expired. Sign out & in on the Cloud tab, then retry."
                        "device_owned_by_other_user" -> "This device is bound to a different user. Ask an admin to re-bind it."
                        "missing_fields", "invalid_json" -> "Sync payload was rejected by the server (${resp.error})."
                        null                          -> "Server rejected the upload."
                        else                          -> "Server: ${resp.error}"
                    }
                    _ui.value = _ui.value.copy(syncStage = SyncStage.Failed, syncMessage = msg)
                    return@launch
                }

                _ui.value = _ui.value.copy(syncStage = SyncStage.Acking,
                                            syncMessage = "Acking seq ${resp.acked_up_to_seq}…")
                val acked = if (resp.acked_up_to_seq > 0) resp.acked_up_to_seq
                            else rows.maxOf { it.seq }
                gatt.writeSyncAck(acked)
                // Give the firmware a moment to process the ACK: truncate
                // /log.csv and recompute unsynced_count. Reading device info
                // immediately would race and still report the old count.
                kotlinx.coroutines.delay(1200)
                readInfoNow()
                _ui.value = _ui.value.copy(syncStage = SyncStage.Done,
                                            syncMessage = "Synced ${rows.size} row(s).")
            } catch (t: NotConnectedException) {
                _ui.value = _ui.value.copy(syncStage = SyncStage.Failed,
                                            syncMessage = "Connection lost.",
                                            connState = ConnState.Disconnected)
            } catch (t: Throwable) {
                _ui.value = _ui.value.copy(syncStage = SyncStage.Failed,
                                            syncMessage = t.message ?: "sync failed")
            }
        }
    }

    /**
     * Register/claim this device with the cloud server under the currently-
     * logged-in user. Requires the user to have already signed in on the
     * Cloud tab (otherwise the server returns 401 / no CSRF token).
     */
    fun claimToCloud(friendlyName: String, location: String?, capacityKw: Double?) {
        val deviceId = _ui.value.info?.deviceId
        if (deviceId.isNullOrBlank()) {
            _ui.value = _ui.value.copy(
                claimStage = ClaimStage.Failed,
                claimMessage = "Read device info first.",
            )
            return
        }
        // No CSRF check here — claimDevice() lazy-refreshes the token if the
        // session cookie is still alive. If the session is genuinely dead the
        // server will respond 401 and we surface that as a friendly message
        // in the onSuccess block below.
        _ui.value = _ui.value.copy(
            claimStage = ClaimStage.Submitting,
            claimMessage = "Registering $deviceId…",
        )
        viewModelScope.launch {
            runCatching {
                cloud.claimDevice(
                    deviceId     = deviceId,
                    friendlyName = friendlyName.ifBlank { deviceId },
                    location     = location?.ifBlank { null },
                    capacityKw   = capacityKw,
                )
            }.onSuccess { resp ->
                _ui.value = when {
                    resp.ok -> _ui.value.copy(
                        claimStage = ClaimStage.Done,
                        claimMessage = if (resp.created) "Registered & bound to your account."
                                       else "Updated & bound to your account.",
                    )
                    resp.error == "owned_by_other_user" -> _ui.value.copy(
                        claimStage = ClaimStage.Conflict,
                        claimMessage = "This device is owned by another user. Ask an admin to re-bind it.",
                    )
                    resp.error == "login_required" || resp.error == "unauthorized" ->
                        _ui.value.copy(
                            claimStage = ClaimStage.Failed,
                            claimMessage = "Sign in on the Cloud tab first, then try again.",
                        )
                    resp.error == "bad_csrf" ->
                        _ui.value.copy(
                            claimStage = ClaimStage.Failed,
                            claimMessage = "Session expired. Sign out & in on the Cloud tab, then retry.",
                        )
                    else -> _ui.value.copy(
                        claimStage = ClaimStage.Failed,
                        claimMessage = resp.error ?: "claim failed",
                    )
                }
                // A device the user just registered is theirs — keep it unlocked
                // for this session and refresh the cached PIN map so it stays
                // unlockable next time.
                if (resp.ok) {
                    pendingId?.let { unlockRegistry.unlock(it) }
                    runCatching { cloud.devices() }.getOrNull()?.takeIf { it.ok }?.let { dr ->
                        val pins = dr.devices.mapNotNull { d ->
                            d.ble_pin?.takeIf { p -> p.isNotBlank() }?.let { d.device_id.lowercase() to it }
                        }.toMap()
                        runCatching { blePinStore.setAll(pins) }
                    }
                }
            }.onFailure {
                _ui.value = _ui.value.copy(
                    claimStage = ClaimStage.Failed,
                    claimMessage = it.message ?: "network error",
                )
            }
        }
    }

    fun resetClaimState() {
        _ui.value = _ui.value.copy(claimStage = ClaimStage.Idle, claimMessage = "")
    }

    private fun parseCsvChunks(text: String): List<IngestReading> {
        val out = ArrayList<IngestReading>()
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed == "END") return@forEach
            val parts = trimmed.split(',')
            if (parts.size < 8) return@forEach
            runCatching {
                out += IngestReading(
                    seq     = parts[0].toLong(),
                    boot_id = parts[1].toInt(),
                    sec     = parts[2].toLong(),
                    V  = parts[3].toDouble(),
                    I  = parts[4].toDouble(),
                    P  = parts[5].toDouble(),
                    Wh = parts[6].toDouble(),
                    PF = parts[7].toDouble(),
                    Hz = parts.getOrNull(8)?.toDoubleOrNull(),
                )
            }
        }
        return out
    }

    private fun nowIso(): String =
        OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch { runCatching { gatt.disconnect() } }
    }

    companion object {
        fun factory(application: Application, address: String) = viewModelFactory {
            initializer {
                val app = application as AcMeterApp
                DeviceDetailViewModel(
                    application, address,
                    app.cloudClient, app.cloudSessionStore,
                    app.blePinStore, app.bleUnlockRegistry,
                )
            }
        }
    }
}
