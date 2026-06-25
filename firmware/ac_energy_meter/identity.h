#pragma once

#include <Arduino.h>

namespace identity {

// "meter-a3f29c" (last 6 of MAC, lowercase). Cached after first call.
const String &device_id();

// "Meter-A3F29C" (uppercase). Used for BLE advertising name.
const String &ble_name();

// FW_VERSION from config.h
const char *fw_version();

}  // namespace identity
