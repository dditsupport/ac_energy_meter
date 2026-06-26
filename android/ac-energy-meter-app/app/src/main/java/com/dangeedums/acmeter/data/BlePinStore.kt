package com.dangeedums.acmeter.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Caches the per-device BLE access PINs the user is authorised for, downloaded
 * from the server at cloud login. The BLE access gate validates entered PINs
 * against this map locally, so it keeps working offline. Cleared on logout.
 *
 * Keyed by canonical device_id (e.g. "meter-475b78"); value is the PIN string.
 */
class BlePinStore(private val store: DataStore<Preferences>) {

    private val key = stringPreferencesKey("ble_pins_json")
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun read(): Map<String, String> =
        store.data.first()[key]
            ?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() }
            ?: emptyMap()

    /** Replace the whole map (called after a fresh device list is fetched). */
    suspend fun setAll(pins: Map<String, String>) {
        store.edit { it[key] = json.encodeToString(pins) }
    }

    /** PIN for a device_id, or null if unknown / not authorised. */
    suspend fun get(deviceId: String): String? = read()[deviceId.lowercase()]

    suspend fun clear() {
        store.edit { it.remove(key) }
    }
}
