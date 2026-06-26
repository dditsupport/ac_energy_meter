package com.dangeedums.acmeter.data

import java.util.Collections

/**
 * In-memory set of device_ids unlocked with their BLE PIN during this app run.
 * Process-scoped (held by the Application), so an unlock survives navigating
 * between screens but is wiped on app restart — and explicitly on logout.
 */
class BleUnlockRegistry {
    private val unlocked: MutableSet<String> = Collections.synchronizedSet(HashSet())

    fun isUnlocked(deviceId: String): Boolean = unlocked.contains(deviceId.lowercase())

    fun unlock(deviceId: String) { unlocked.add(deviceId.lowercase()) }

    fun clear() { unlocked.clear() }
}
