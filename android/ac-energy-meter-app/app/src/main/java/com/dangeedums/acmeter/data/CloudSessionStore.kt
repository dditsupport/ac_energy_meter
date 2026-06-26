package com.dangeedums.acmeter.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persists cloud login choices across launches: base URL, last-used username,
 * device token (for BLE-relayed ingest POSTs). Passwords are not stored —
 * sessions are cookie-based, and re-login is required after expiry.
 */
class CloudSessionStore(private val store: DataStore<Preferences>) {
    private val keyBaseUrl   = stringPreferencesKey("cloud_base_url")
    private val keyUsername  = stringPreferencesKey("cloud_username")
    private val keyToken     = stringPreferencesKey("device_token")
    private val keyLoggedIn  = booleanPreferencesKey("cloud_logged_in")

    data class Settings(
        val baseUrl: String,
        val username: String,
        val deviceToken: String,
    )

    val settings: Flow<Settings> = store.data.map { prefs ->
        Settings(
            baseUrl     = prefs[keyBaseUrl]  ?: "https://aromen.biz",
            username    = prefs[keyUsername] ?: "",
            deviceToken = prefs[keyToken]    ?: "",
        )
    }

    suspend fun update(
        baseUrl: String? = null,
        username: String? = null,
        deviceToken: String? = null,
    ) {
        store.edit { prefs ->
            if (baseUrl     != null) prefs[keyBaseUrl]  = baseUrl.trimEnd('/')
            if (username    != null) prefs[keyUsername] = username
            if (deviceToken != null) prefs[keyToken]    = deviceToken
        }
    }

    /** Whether a cloud session is currently considered active. Used by the BLE
     *  access gate to distinguish "log in first" from "not your device". */
    suspend fun isLoggedIn(): Boolean = store.data.first()[keyLoggedIn] ?: false

    suspend fun setLoggedIn(value: Boolean) {
        store.edit { it[keyLoggedIn] = value }
    }
}
