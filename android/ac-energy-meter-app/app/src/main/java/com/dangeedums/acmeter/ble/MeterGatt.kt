package com.dangeedums.acmeter.ble

import com.juul.kable.AndroidPeripheral
import com.juul.kable.Peripheral
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val SERVICE = BleUuids.SERVICE.toString()

private val DEVICE_INFO_CHAR    = characteristicOf(SERVICE, BleUuids.DEVICE_INFO.toString())
private val SET_WALL_TIME_CHAR  = characteristicOf(SERVICE, BleUuids.SET_WALL_TIME.toString())
private val BOOT_HISTORY_CHAR   = characteristicOf(SERVICE, BleUuids.BOOT_HISTORY.toString())
private val DATA_STREAM_CHAR    = characteristicOf(SERVICE, BleUuids.DATA_STREAM.toString())
private val SYNC_ACK_CHAR       = characteristicOf(SERVICE, BleUuids.SYNC_ACK.toString())
private val WIFI_CONFIG_CHAR    = characteristicOf(SERVICE, BleUuids.WIFI_CONFIG.toString())
private val WIFI_STATUS_CHAR    = characteristicOf(SERVICE, BleUuids.WIFI_STATUS.toString())
private val WIFI_SCAN_CHAR      = characteristicOf(SERVICE, BleUuids.WIFI_SCAN.toString())
private val SERVER_CONFIG_CHAR  = characteristicOf(SERVICE, BleUuids.SERVER_CONFIG.toString())
private val RELAY_CHAR          = characteristicOf(SERVICE, BleUuids.RELAY.toString())

/**
 * Higher-level operations on a AC Energy Meter peripheral. One instance per
 * connection. Caller is responsible for calling [connect] before any other
 * method and [close] when done.
 */
class MeterGatt(
    private val peripheral: Peripheral,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    val state: Flow<State> = peripheral.state

    suspend fun connect() {
        peripheral.connect()
        // Request a larger MTU so the data-stream chunks fit. Negotiated value
        // ends up being min(requested, server-supported). 247 matches what the
        // firmware sets via NimBLEDevice::setMTU.
        (peripheral as? AndroidPeripheral)?.requestMtu(247)
    }

    suspend fun disconnect() = peripheral.disconnect()

    suspend fun readDeviceInfo(): DeviceInfoBle {
        val bytes = peripheral.read(DEVICE_INFO_CHAR)
        return json.decodeFromString(DeviceInfoBle.serializer(), bytes.decodeToString())
    }

    suspend fun readBootHistory(): List<BootRecord> {
        val bytes = peripheral.read(BOOT_HISTORY_CHAR)
        val text = bytes.decodeToString()
        if (text.isBlank() || text == "[]") return emptyList()
        return json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(BootRecord.serializer()), text)
    }

    /** ISO 8601 string, e.g. "2026-06-20T17:24:32+05:30". */
    suspend fun setWallTime(iso8601: String) {
        peripheral.write(SET_WALL_TIME_CHAR, iso8601.toByteArray(), WriteType.WithResponse)
    }

    /** {"ssid":"...","password":"..."} or {"action":"scan"} */
    suspend fun writeWifiConfig(json: String) {
        peripheral.write(WIFI_CONFIG_CHAR, json.toByteArray(), WriteType.WithResponse)
    }

    /** {"host":"https://aromen.biz"} */
    suspend fun writeServerConfig(json: String) {
        peripheral.write(SERVER_CONFIG_CHAR, json.toByteArray(), WriteType.WithResponse)
    }

    /** Highest seq the server has acknowledged. ESP32 truncates /log.csv up to it. */
    suspend fun writeSyncAck(seq: Long) {
        peripheral.write(SYNC_ACK_CHAR, seq.toString().toByteArray(), WriteType.WithResponse)
    }

    /** Last-known Wi-Fi status. Returns null if char is empty / unparseable. */
    suspend fun readWifiStatus(): WifiStatus? = runCatching {
        val text = peripheral.read(WIFI_STATUS_CHAR).decodeToString()
        if (text.isBlank()) null
        else json.decodeFromString(WifiStatus.serializer(), text)
    }.getOrNull()

    /** Current relay state, or null if the char is empty / unparseable. */
    suspend fun readRelay(): RelayState? = runCatching {
        val text = peripheral.read(RELAY_CHAR).decodeToString()
        if (text.isBlank()) null
        else json.decodeFromString(RelayState.serializer(), text)
    }.getOrNull()

    /** Live relay-state pushes (schedule- or override-driven) from the device. */
    fun observeRelay(): Flow<RelayState> = peripheral.observe(RELAY_CHAR).map {
        json.decodeFromString(RelayState.serializer(), it.decodeToString())
    }

    /** Manual relay control. mode = "on" | "off" | "auto" (follow schedule). */
    suspend fun writeRelayMode(mode: String) {
        peripheral.write(RELAY_CHAR, """{"mode":"$mode"}""".toByteArray(), WriteType.WithResponse)
    }

    /** Live Wi-Fi status pushes from the device. */
    fun observeWifiStatus(): Flow<WifiStatus> = peripheral.observe(WIFI_STATUS_CHAR).map {
        json.decodeFromString(WifiStatus.serializer(), it.decodeToString())
    }

    /** Live Wi-Fi scan results — emits whenever the firmware completes a scan. */
    fun observeWifiScan(): Flow<List<WifiScanResult>> = peripheral.observe(WIFI_SCAN_CHAR).map { bytes ->
        val text = bytes.decodeToString()
        if (text.isBlank() || text == "[]") emptyList()
        else json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(WifiScanResult.serializer()),
            text,
        )
    }

    suspend fun readWifiScan(): List<WifiScanResult> {
        val text = peripheral.read(WIFI_SCAN_CHAR).decodeToString()
        return if (text.isBlank() || text == "[]") emptyList()
        else json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(WifiScanResult.serializer()),
            text,
        )
    }

    /**
     * Subscribes to the Data Stream characteristic and emits each chunk as
     * a string. Terminator chunk "END\n" is included so the caller can
     * detect end-of-stream and stop accumulating.
     */
    fun observeDataStream(): Flow<String> =
        peripheral.observe(DATA_STREAM_CHAR).map { it.decodeToString() }
}

/**
 * Build a Peripheral from a MAC address. Kable 0.35+ owns the internal
 * coroutine scope; lifecycle is driven by explicit connect()/disconnect().
 */
fun peripheralForAddress(address: String): Peripheral = Peripheral(address)
