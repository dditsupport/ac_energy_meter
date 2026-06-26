package com.dangeedums.acmeter

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.dangeedums.acmeter.ble.BleScanner
import com.dangeedums.acmeter.cloud.CloudClient
import com.dangeedums.acmeter.cloud.PersistentCookieStorage
import com.dangeedums.acmeter.data.BlePinStore
import com.dangeedums.acmeter.data.BleUnlockRegistry
import com.dangeedums.acmeter.data.CloudSessionStore
import com.dangeedums.acmeter.data.DeviceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

val android.content.Context.savedDevicesDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "saved_devices")

val android.content.Context.cloudSessionDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "cloud_session")

val android.content.Context.blePinDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "ble_pins")

class AcMeterApp : Application() {
    lateinit var deviceStore: DeviceStore
        private set
    lateinit var bleScanner: BleScanner
        private set
    lateinit var cloudClient: CloudClient
        private set
    lateinit var cloudSessionStore: CloudSessionStore
        private set
    lateinit var blePinStore: BlePinStore
        private set

    /** Process-scoped set of BLE devices unlocked with their PIN this run. */
    val bleUnlockRegistry = BleUnlockRegistry()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        deviceStore       = DeviceStore(savedDevicesDataStore)
        bleScanner        = BleScanner(this)
        cloudClient       = CloudClient(PersistentCookieStorage(cloudSessionDataStore))
        cloudSessionStore = CloudSessionStore(cloudSessionDataStore)
        blePinStore       = BlePinStore(blePinDataStore)

        // Pull persisted base URL into the client as early as possible so
        // subsequent calls hit the right host.
        appScope.launch {
            val s = cloudSessionStore.settings.first()
            cloudClient.setBaseUrl(s.baseUrl)
        }
    }
}
