package com.dangeedums.acmeter.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists the user's added device list in a single JSON blob inside
 * Preferences DataStore. The list is small (one or a few devices), so a
 * dedicated database would be overkill. Reads and writes are atomic.
 */
class DeviceStore(private val store: DataStore<Preferences>) {

    private val key = stringPreferencesKey("devices_json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val devices: Flow<List<Device>> = store.data.map { prefs ->
        prefs[key]?.let { runCatching { json.decodeFromString<List<Device>>(it) }.getOrNull() }
            ?: emptyList()
    }

    suspend fun add(device: Device) {
        store.edit { prefs ->
            val current = prefs[key]
                ?.let { runCatching { json.decodeFromString<List<Device>>(it) }.getOrNull() }
                ?: emptyList()
            // De-dup by MAC address.
            val replaced = current.filter { it.address != device.address } + device
            prefs[key] = json.encodeToString(replaced)
        }
    }

    suspend fun remove(address: String) {
        store.edit { prefs ->
            val current = prefs[key]
                ?.let { runCatching { json.decodeFromString<List<Device>>(it) }.getOrNull() }
                ?: emptyList()
            prefs[key] = json.encodeToString(current.filter { it.address != address })
        }
    }
}
