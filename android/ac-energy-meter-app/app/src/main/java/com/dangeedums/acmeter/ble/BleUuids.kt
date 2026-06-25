package com.dangeedums.acmeter.ble

import java.util.UUID

/**
 * BLE GATT layout for the AC Energy Meter firmware. Must stay in sync with
 * firmware/ac_energy_meter/config.h. Generated UUIDs are kept opaque — they have
 * no semantic meaning beyond uniqueness.
 */
object BleUuids {
    val SERVICE         : UUID = UUID.fromString("5f12b3bc-8ef3-4b48-a971-f70a38f519ec")

    val DEVICE_INFO     : UUID = UUID.fromString("56c4fe7d-1c7d-4042-9547-6170ec5c243c")
    val SET_WALL_TIME   : UUID = UUID.fromString("b90e068f-8856-4cba-a043-841081fbd1a1")
    val BOOT_HISTORY    : UUID = UUID.fromString("d155756b-566e-4aa3-9fe5-c898f78fda8b")
    val DATA_STREAM     : UUID = UUID.fromString("1199716e-692b-4d47-bd00-72792988364d")
    val SYNC_ACK        : UUID = UUID.fromString("a4b32253-c2e3-42e8-93c3-a008325540b6")
    val WIFI_CONFIG     : UUID = UUID.fromString("41310027-c18e-4452-a50e-861e77cf2743")
    val WIFI_STATUS     : UUID = UUID.fromString("28c3fa43-a1b5-4e0e-a51c-a1e979609d28")
    val WIFI_SCAN       : UUID = UUID.fromString("d4346c1c-6e36-4a0f-a164-84cd396a4697")
    val SERVER_CONFIG   : UUID = UUID.fromString("9478f8ff-cb2f-4447-8a2f-49791de6bc09")
    val RELAY           : UUID = UUID.fromString("8c5a2e91-6f3d-4b27-9a1c-0e7d3f8b6a52")
}
