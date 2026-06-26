package com.dangeedums.acmeter.cloud

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Talks to the /meter/api endpoints. One instance per app process; cookies
 * persist for the lifetime of this object (cleared on logout). The base URL
 * is user-settable from the Cloud Login screen so the same APK works against
 * staging/prod/self-hosted MilesWeb installs.
 */
class CloudClient(
    /** Persistent cookie store. Survives app restarts so the user stays signed in. */
    private val cookieStorage: PersistentCookieStorage,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Volatile var baseUrl: String = "https://aromen.biz"
        private set

    /** CSRF token returned by /api/login.php. Sent as X-CSRF on state-changing calls. */
    @Volatile var csrf: String = ""
        private set

    fun setBaseUrl(url: String) {
        baseUrl = url.trimEnd('/')
    }

    fun setCsrf(token: String) { csrf = token }

    private val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpCookies) { storage = cookieStorage }
        install(HttpTimeout) {
            requestTimeoutMillis  = 15_000
            connectTimeoutMillis  = 10_000
            socketTimeoutMillis   = 15_000
        }
        defaultRequest {
            header(HttpHeaders.Accept, "application/json")
        }
        expectSuccess = false
    }

    suspend fun login(username: String, password: String): LoginResponse {
        val resp: HttpResponse = http.submitForm(
            url = "$baseUrl/meter/api/login.php",
            formParameters = Parameters.build {
                append("username", username)
                append("password", password)
            },
        )
        val parsed: LoginResponse = resp.body()
        parsed.csrf?.let { csrf = it }
        return parsed
    }

    /** Ensure we have a CSRF token loaded. Fetches one via /csrf.php if the
     *  session cookie is still valid but we haven't asked yet (e.g. cold-start
     *  with persisted cookies). No-op if CSRF is already cached. */
    private suspend fun ensureCsrf() {
        if (csrf.isBlank()) refreshCsrf()
    }

    /** Register/claim a device for the current logged-in user. */
    suspend fun claimDevice(
        deviceId: String,
        friendlyName: String,
        location: String? = null,
        capacityKw: Double? = null,
        notes: String? = null,
    ): ClaimDeviceResponse {
        ensureCsrf()
        val resp: HttpResponse = http.submitForm(
            url = "$baseUrl/meter/api/claim_device.php",
            formParameters = Parameters.build {
                append("device_id",     deviceId)
                append("friendly_name", friendlyName)
                if (location != null)   append("location",    location)
                if (capacityKw != null) append("capacity_kw", capacityKw.toString())
                if (notes != null)      append("notes",       notes)
            },
        ) {
            header("X-CSRF", csrf)
        }
        return resp.body()
    }

    suspend fun logout(): Boolean {
        val resp: HttpResponse = http.post("$baseUrl/meter/api/logout.php") {
            header("Accept", "application/json")
        }
        // Clear the local cookie storage and CSRF token even if the server
        // request failed (offline logout). Otherwise the dead session cookie
        // keeps getting sent on every subsequent call.
        cookieStorage.clear()
        csrf = ""
        return resp.status.value in 200..299
    }

    suspend fun devices(): DevicesResponse =
        http.get("$baseUrl/meter/api/devices.php").body()

    /**
     * Public, no-auth check used by the BLE access gate: is this device_id
     * registered on the server? Unregistered devices stay open over BLE so new
     * units can be provisioned.
     */
    suspend fun bleRegistered(deviceId: String): BleRegisteredResponse =
        http.get("$baseUrl/meter/api/ble_registered.php") {
            parameter("device_id", deviceId)
        }.body()

    /** Refresh the CSRF token using the existing session cookie. No-op if not logged in. */
    suspend fun refreshCsrf(): String? {
        val resp: CsrfResponse = runCatching {
            http.get("$baseUrl/meter/api/csrf.php").body<CsrfResponse>()
        }.getOrElse { return null }
        if (resp.ok && !resp.csrf.isNullOrBlank()) {
            csrf = resp.csrf
            return resp.csrf
        }
        return null
    }

    /**
     * @param aggregate one of "raw", "hourly", "daily", "monthly"
     * @param fromIso optional ISO-8601 local time (omit for endpoint default)
     */
    suspend fun readings(
        deviceId: String,
        aggregate: String,
        fromIso: String? = null,
        toIso: String? = null,
    ): ReadingsResponse = http.get("$baseUrl/meter/api/readings.php") {
        parameter("device_id", deviceId)
        parameter("aggregate", aggregate)
        if (fromIso != null) parameter("from", fromIso)
        if (toIso   != null) parameter("to", toIso)
    }.body()

    /**
     * Used by the BLE-relay path: forwards rows received over BLE to the
     * same endpoint the firmware would have used directly over Wi-Fi.
     *
     * Two auth modes are supported by /meter/api/ingest.php:
     *   1. X-Device-Token header — the firmware path. Only set if [token]
     *      is non-blank (which it usually isn't on the phone).
     *   2. Session cookie + X-CSRF header — what the Android app uses
     *      after signing into the Cloud tab. We always send the CSRF so
     *      the server can fall back to session auth if the device token
     *      is empty or wrong.
     */
    suspend fun ingest(token: String, payload: IngestPayload): IngestResponse {
        // Use session auth when the caller didn't pass a device token (the
        // Android relay path). Make sure CSRF is loaded — it might not be on
        // a cold start with persisted cookies.
        if (token.isBlank()) ensureCsrf()
        val resp: HttpResponse = http.post("$baseUrl/meter/api/ingest.php") {
            contentType(ContentType.Application.Json)
            if (token.isNotBlank()) header("X-Device-Token", token)
            if (csrf.isNotBlank())  header("X-CSRF", csrf)
            setBody(payload)
        }
        return resp.body()
    }

    /** Drop the persistent cookie store + in-memory CSRF. Used on logout. */
    suspend fun clearCookies() {
        cookieStorage.clear()
        csrf = ""
    }
}
