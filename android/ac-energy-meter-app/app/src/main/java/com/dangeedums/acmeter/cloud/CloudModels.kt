package com.dangeedums.acmeter.cloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/* ---------- login.php ---------- */

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class LoginResponse(
    val ok: Boolean,
    val username: String? = null,
    val is_admin: Boolean = false,
    val csrf: String? = null,
    val error: String? = null,
)

/* ---------- csrf.php ---------- */

@Serializable
data class CsrfResponse(val ok: Boolean, val csrf: String? = null, val error: String? = null)

/* ---------- claim_device.php ---------- */

@Serializable
data class ClaimDeviceResponse(
    val ok: Boolean,
    val device_id: String? = null,
    val friendly_name: String? = null,
    val created: Boolean = false,
    val owner_user_id: Int? = null,
    val error: String? = null,
)

/* ---------- devices.php ---------- */

@Serializable
data class DevicesResponse(
    val ok: Boolean,
    val devices: List<CloudDevice> = emptyList(),
    val error: String? = null,
)

@Serializable
data class CloudDevice(
    val device_id: String,
    val friendly_name: String,
    val location: String? = null,
    val capacity_kw: Double? = null,
    val owner_user_id: Int? = null,
    val owner_username: String? = null,
    val fw_version: String? = null,
    val last_sync_at: String? = null,
    val last_seq: Long? = null,
    val last_boot_id: Int? = null,
    val total_readings: Long? = null,
    val log_interval_sec: Int? = null,
    /** BLE access PIN for this device. Cached locally to gate BLE access. */
    val ble_pin: String? = null,
)

/* ---------- ble_registered.php ---------- */

@Serializable
data class BleRegisteredResponse(
    val ok: Boolean,
    val registered: Boolean = false,
    val error: String? = null,
)

/* ---------- readings.php ---------- */

@Serializable
data class ReadingsResponse(
    val ok: Boolean,
    val device_id: String? = null,
    val friendly_name: String? = null,
    val capacity_kw: Double? = null,
    val from: String? = null,
    val to: String? = null,
    val aggregate: String? = null,
    val points: List<ReadingPoint> = emptyList(),
    val error: String? = null,
)

/**
 * One union model that handles both raw and bucketed points. Fields are
 * mutually exclusive: raw rows carry V/I/P/Wh/PF/Hz; bucketed rows carry
 * kwh/P_avg/P_peak/V_avg/samples.
 */
@Serializable
data class ReadingPoint(
    val t: String,
    val t_end: String? = null,
    // raw
    val V: Double? = null,
    val I: Double? = null,
    val P: Double? = null,
    val Wh: Double? = null,
    val PF: Double? = null,
    val Hz: Double? = null,
    val conf: String? = null,
    // bucketed
    val kwh: Double? = null,
    val P_avg: Double? = null,
    val P_peak: Double? = null,
    val V_avg: Double? = null,
    val samples: Int? = null,
    val approx: Boolean? = null,
)

/* ---------- ingest.php (used by BLE relay) ---------- */

@Serializable
data class IngestPayload(
    val device_id: String,
    val fw_version: String,
    val sync_wall_time: String,
    val current_boot_id: Int,
    val current_boot_uptime_sec: Long,
    val boot_history: List<IngestBoot>,
    val readings: List<IngestReading>,
)

@Serializable
data class IngestBoot(val boot_id: Int, val duration_sec: Int)

@Serializable
data class IngestReading(
    val seq: Long,
    val boot_id: Int,
    val sec: Long,
    val V: Double,
    val I: Double,
    val P: Double,
    val Wh: Double,
    val PF: Double,
    val Hz: Double? = null,
)

@Serializable
data class IngestResponse(
    val ok: Boolean,
    val acked_up_to_seq: Long = 0,
    val server_time: String? = null,
    val log_interval_sec: Int? = null,
    val error: String? = null,
)
