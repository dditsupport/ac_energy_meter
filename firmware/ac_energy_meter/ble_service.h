#pragma once

#include <Arduino.h>

namespace ble_service {

void begin();

// Called periodically (e.g. 1 Hz) so the service can refresh dynamic values
// like the Device Info JSON, drive the data-stream pump, and update SharedState
// with current connection status.
void tick();

// True if the radio is currently streaming notifications (informational).
bool is_streaming();

// True if BLE is in a healthy state: either advertising or a client is
// currently connected. The stuck-BLE watchdog in the connectivity task
// reboots the chip if this stays false for too long.
bool is_alive();

}  // namespace ble_service
