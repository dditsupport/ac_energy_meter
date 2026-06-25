package com.dangeedums.acmeter.cloud

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.Cookie
import io.ktor.http.Url
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Ktor [CookiesStorage] backed by a DataStore preference. Replaces the
 * in-memory AcceptAllCookiesStorage so the user's PHP session cookie
 * survives across app restarts — they don't have to sign back into the
 * Cloud tab every time they reopen the app.
 *
 * Persists every cookie, including session cookies that a browser would
 * normally discard on close, so the meter_sess cookie stays alive until
 * the server-side session actually expires.
 */
class PersistentCookieStorage(
    private val store: DataStore<Preferences>,
) : CookiesStorage {

    private val json   = Json { ignoreUnknownKeys = true }
    private val prefKey = stringPreferencesKey("cloud_cookies")
    private val mtx    = Mutex()
    private val scope  = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val cookies: MutableList<Cookie> = mutableListOf()
    private var loaded = false

    private suspend fun ensureLoaded() {
        if (loaded) return
        mtx.withLock {
            if (loaded) return@withLock
            val raw = store.data.first()[prefKey].orEmpty()
            if (raw.isNotBlank()) {
                runCatching {
                    val list = json.decodeFromString<List<StoredCookie>>(raw)
                    cookies.addAll(list.map { it.toCookie() })
                }
            }
            loaded = true
        }
    }

    override suspend fun get(requestUrl: Url): List<Cookie> {
        ensureLoaded()
        val now = GMTDate()
        return mtx.withLock {
            // Evict anything that has expired since we last loaded.
            cookies.removeAll { c -> c.expires?.let { it < now } == true }
            cookies.filter { matches(it, requestUrl) }
        }
    }

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) {
        if (cookie.name.isBlank()) return
        ensureLoaded()
        mtx.withLock {
            val effective = cookie.copy(
                domain = cookie.domain ?: requestUrl.host,
                path   = cookie.path   ?: "/",
            )
            // Replace any cookie with the same (name, domain, path) identity.
            cookies.removeAll {
                it.name == effective.name &&
                    (it.domain ?: "") == (effective.domain ?: "") &&
                    (it.path   ?: "/") == (effective.path  ?: "/")
            }
            // Set-Cookie with empty value + past expiry is the server telling
            // us to delete the cookie — don't re-add it.
            val expired = effective.expires?.let { it < GMTDate() } == true
            if (effective.value.isNotEmpty() && !expired) {
                cookies.add(effective)
            }
            persist()
        }
    }

    override fun close() {
        scope.cancel()
    }

    /** Wipe everything. Used by logout(). */
    suspend fun clear() {
        ensureLoaded()
        mtx.withLock {
            cookies.clear()
            persist()
        }
    }

    private fun persist() {
        val snapshot = cookies.map { StoredCookie.from(it) }
        val raw = json.encodeToString(snapshot)
        scope.launch { store.edit { it[prefKey] = raw } }
    }

    private fun matches(cookie: Cookie, url: Url): Boolean {
        val host = url.host.lowercase()
        val cookieDomain = cookie.domain?.trimStart('.')?.lowercase()
        if (cookieDomain != null && cookieDomain.isNotBlank()) {
            if (host != cookieDomain && !host.endsWith(".$cookieDomain")) return false
        }
        val cookiePath = cookie.path ?: "/"
        return url.encodedPath.startsWith(cookiePath)
    }

    @Serializable
    private data class StoredCookie(
        val name: String,
        val value: String,
        val domain: String? = null,
        val path: String?   = null,
        val expiresMs: Long? = null,
        val maxAge: Int     = 0,
        val secure: Boolean = false,
        val httpOnly: Boolean = false,
    ) {
        fun toCookie() = Cookie(
            name     = name,
            value    = value,
            domain   = domain,
            path     = path,
            expires  = expiresMs?.let { GMTDate(it) },
            maxAge   = maxAge,
            secure   = secure,
            httpOnly = httpOnly,
        )

        companion object {
            fun from(c: Cookie) = StoredCookie(
                name      = c.name,
                value     = c.value,
                domain    = c.domain,
                path      = c.path,
                expiresMs = c.expires?.timestamp,
                maxAge    = c.maxAge ?: 0,
                secure    = c.secure,
                httpOnly  = c.httpOnly,
            )
        }
    }
}
