#pragma once

#include <Arduino.h>
#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>

enum PzemStatus : uint8_t {
  PZEM_OK = 0,
  PZEM_STALE = 1,        // 3+ consecutive Modbus failures
  PZEM_SENSOR_FAULT = 2  // V near zero during expected operation
};

enum WifiStatus : uint8_t {
  WIFI_IDLE = 0,
  WIFI_SCANNING = 1,
  WIFI_CONNECTING = 2,
  WIFI_CONNECTED = 3,
  WIFI_SYNCING = 4
};

enum BleStatus : uint8_t {
  BLE_OFF = 0,
  BLE_ADVERTISING = 1,
  BLE_CLIENT_CONNECTED = 2
};

struct PzemSample {
  float voltage;       // V
  float current;       // A
  float power;         // W
  float energy_wh;     // cumulative Wh (PZEM internal counter)
  float pf;            // 0..1
  float frequency;     // Hz
};

struct SharedState {
  PzemSample latest{};
  PzemStatus pzem_status = PZEM_OK;

  // All three kWh fields are PZEM-derived (no ESP32 integration):
  //   total_kwh   = PZEM cumulative reading                        (lifetime)
  //   today_kwh   = current PZEM kWh - today_anchor (stored in NVS) (today)
  //   session_kwh = current PZEM kWh - session_anchor (RAM, boot)   (this boot)
  float session_kwh = 0.0f;
  float today_kwh = 0.0f;
  float total_kwh = 0.0f;
  float peak_power_w = 0.0f;  // session peak (live, RAM only)

  uint64_t last_seq = 0;
  uint32_t boot_id = 0;
  uint32_t uptime_sec = 0;
  uint32_t unsynced_count = 0;

  WifiStatus wifi_status = WIFI_IDLE;
  BleStatus ble_status = BLE_OFF;

  bool wall_clock_known = false;
  bool today_is_partial = true;  // true unless anchor was captured at a midnight rollover
  bool buffer_full = false;
};

// Global state and mutex (defined in ac_energy_meter.ino).
extern SharedState g_state;
extern SemaphoreHandle_t g_state_mutex;

inline bool state_lock(TickType_t ticks = pdMS_TO_TICKS(200)) {
  return xSemaphoreTake(g_state_mutex, ticks) == pdTRUE;
}
inline void state_unlock() {
  xSemaphoreGive(g_state_mutex);
}

// Snapshot a value-copy of the global state. Returns true on success.
inline bool state_snapshot(SharedState &out) {
  if (!state_lock()) return false;
  out = g_state;
  state_unlock();
  return true;
}
