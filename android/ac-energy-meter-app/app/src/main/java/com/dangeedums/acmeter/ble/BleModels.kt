package com.dangeedums.acmeter.ble

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * JSON shape exposed by the firmware on the Device Info characteristic.
 * Keep in sync with build_device_info_json() in firmware/ble_service.cpp.
 */
@Serializable
data class DeviceInfoBle(
    @SerialName("device_id")          val deviceId: String,
    @SerialName("fw")                 val fw: String,
    @SerialName("unsynced_count")     val unsyncedCount: Int = 0,
    @SerialName("current_boot_id")    val currentBootId: Int = 0,
    @SerialName("uptime_sec")         val uptimeSec: Long = 0,
    @SerialName("last_seq")           val lastSeq: Long = 0,
    @SerialName("expected_row_count") val expectedRowCount: Int = 0,
    @SerialName("wall_clock_known")   val wallClockKnown: Boolean = false,
    @SerialName("rtc_ok")             val rtcOk: Boolean = false,
    @SerialName("ingest_host")        val ingestHost: String = "",
    @SerialName("ingest_path")        val ingestPath: String = "",
    @SerialName("log_interval_sec")   val logIntervalSec: Int = 900,
)

@Serializable
data class BootRecord(
    @SerialName("boot_id")      val bootId: Int,
    @SerialName("duration_sec") val durationSec: Int,
)

/** Wi-Fi Scan characteristic shape: [{"s":"SSID","r":-65,"e":1}, ...] */
@Serializable
data class WifiScanResult(
    @SerialName("s") val ssid: String,
    @SerialName("r") val rssi: Int,
    @SerialName("e") val encrypted: Int = 0,
) {
    val isEncrypted: Boolean get() = encrypted != 0
}

/**
 * Relay characteristic shape: {"mode":"auto","on":false,"sched_version":3}.
 * `mode` is "auto" | "on" | "off". Keep in sync with relay::status_json().
 */
@Serializable
data class RelayState(
    val mode: String = "auto",
    val on: Boolean = false,
    @SerialName("sched_version") val schedVersion: Int = 0,
)

/** Wi-Fi Status characteristic shape: {"status":"connected","ssid":"..."} */
@Serializable
data class WifiStatus(
    val status: String,
    val ssid: String? = null,
    val ip: String? = null,
    val detail: String? = null,
    val next: String? = null,
)
