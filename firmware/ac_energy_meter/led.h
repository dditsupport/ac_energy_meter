#pragma once

// Wi-Fi status LED.
//   - Wi-Fi disconnected      -> slow continuous blink (searching)
//   - Wi-Fi connected, idle   -> off
//   - Data being sent (POST)  -> fast flicker for LED_TX_PULSE_MS
//
// Driven by led::tick(), called frequently (every ~50 ms) from loop().
// led::signal_tx() is called by wifi_sync around each ingest POST.

namespace led {

void begin();
void tick();
void signal_tx();   // flash to indicate a data upload

}  // namespace led
