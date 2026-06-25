#include "led.h"
#include "config.h"

#include <Arduino.h>
#include <WiFi.h>

namespace led {

static volatile uint32_t s_tx_until_ms = 0;   // TX flicker active while millis() < this
static bool s_level = false;                  // current logical on/off
static uint32_t s_last_toggle_ms = 0;

static inline void write(bool on) {
  s_level = on;
#if LED_ACTIVE_HIGH
  digitalWrite(PIN_STATUS_LED, on ? HIGH : LOW);
#else
  digitalWrite(PIN_STATUS_LED, on ? LOW : HIGH);
#endif
}

void begin() {
  pinMode(PIN_STATUS_LED, OUTPUT);
  write(false);
  s_last_toggle_ms = millis();
}

void signal_tx() {
  s_tx_until_ms = millis() + LED_TX_PULSE_MS;
}

void tick() {
  const uint32_t now = millis();

  // Decide the blink period for the current mode. 0 == steady (no blink).
  uint32_t period;
  if ((int32_t)(s_tx_until_ms - now) > 0) {
    // A POST is in flight (or just finished) -> fast flicker.
    period = LED_BLINK_TX_MS;
  } else if (WiFi.isConnected()) {
    // Connected and idle -> LED off, steady.
    if (s_level) write(false);
    return;
  } else {
    // Disconnected -> slow searching blink.
    period = LED_BLINK_SEARCH_MS;
  }

  if (now - s_last_toggle_ms >= period) {
    s_last_toggle_ms = now;
    write(!s_level);
  }
}

}  // namespace led
