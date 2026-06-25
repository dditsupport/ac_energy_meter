#pragma once

#include <Arduino.h>

namespace wifi_sync {

void begin();

// Called periodically from ConnectivityTask. Returns true if a sync cycle ran
// to completion (regardless of how many rows transferred).
bool run_cycle();

// Pause notifications during HTTPS POST to ease 2.4 GHz coexistence with BLE.
// Set/cleared internally; ble_service.cpp consults this via is_radio_busy().
bool is_radio_busy();

// Ask the connectivity task to attempt a Wi-Fi cycle right away instead of
// waiting for the next periodic tick. Called by ble_service::WifiCfgCallbacks
// after a successful credential write.
void request_immediate_sync();

// Returns true (and clears) if an immediate-sync request is pending.
bool consume_immediate_sync_request();

// Ask the connectivity task to run a Wi-Fi scan and update the cached results
// JSON. Triggered by a BLE write of {"action":"scan"} to Wi-Fi Config.
void request_scan();
bool consume_scan_request();

// Run a synchronous Wi-Fi scan and update the cached JSON. Should only be
// called from the ConnectivityTask context (it briefly switches the Wi-Fi mode).
void run_scan();

// Returns the most recent scan result as a JSON string (array). Empty array
// "[]" if no scan has completed yet.
String get_scan_results_json();

// Monotonic counter, incremented every time the scan results are refreshed.
// ble_service uses this to decide when to push a NOTIFY.
uint32_t scan_results_version();

// Seconds since the last successful ingest POST. UINT32_MAX if there has
// never been a successful POST since boot. Used by the stuck-Wi-Fi watchdog
// in the connectivity task.
uint32_t seconds_since_last_successful_post();

}  // namespace wifi_sync
