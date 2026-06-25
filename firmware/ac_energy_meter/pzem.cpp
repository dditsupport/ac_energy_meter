#include "pzem.h"
#include "config.h"
#include "time_source.h"

#include <PZEM004Tv30.h>
#include <math.h>
#include "log_serial.h"

namespace pzem {

static PZEM004Tv30 *s_pzem = nullptr;
static uint8_t s_fail_streak = 0;
static uint64_t s_low_v_start_us = 0;
static bool s_low_v_active = false;

#if PZEM_DEMO_MODE
static float s_demo_energy_wh = 0.0f;
#endif

void begin() {
#if PZEM_DEMO_MODE
  LOG_PRINTLN("[pzem] DEMO MODE: synthetic readings, hardware not queried");
  return;
#else
  // PZEM-004T-v30 (mandulaj) takes (HardwareSerial&, rxPin, txPin) and runs
  // Serial2.begin() internally — no need for an external begin() call.
  // rxPin = ESP32's RX (GPIO 16, connected to PZEM TX);
  // txPin = ESP32's TX (GPIO 17, connected to PZEM RX).
  s_pzem = new PZEM004Tv30(Serial2, PIN_PZEM_RX, PIN_PZEM_TX);
#endif
}

bool read(PzemSample &out) {
#if PZEM_DEMO_MODE
  // Simple synthetic generator: 230 V mains, current slowly rising/falling
  // with a sine wave, power = V * I, PF ~1, 50 Hz, energy integrates over time.
  float t = (float)(time_source::monotonic_us() / 1000000ULL);
  float i = 4.0f + 3.5f * sinf(t * 0.05f);   // 0.5 .. 7.5 A
  out.voltage   = 230.0f + 1.5f * sinf(t * 0.13f);
  out.current   = i < 0 ? 0 : i;
  out.power     = out.voltage * out.current;
  s_demo_energy_wh += out.power / 3600.0f;   // ~1 sample/sec assumed
  out.energy_wh = s_demo_energy_wh;
  out.pf        = 0.98f;
  out.frequency = 50.0f;
  return true;
#else
  if (!s_pzem) return false;
  float v = s_pzem->voltage();
  float i = s_pzem->current();
  float p = s_pzem->power();
  float e = s_pzem->energy();
  float pf = s_pzem->pf();
  float f = s_pzem->frequency();
  if (isnan(v) || isnan(i) || isnan(p) || isnan(e) || isnan(pf) || isnan(f)) {
    return false;
  }
  out.voltage = v;
  out.current = i;
  out.power = p;
  out.energy_wh = e * 1000.0f;  // library returns kWh
  out.pf = pf;
  out.frequency = f;
  return true;
#endif
}

PzemStatus classify(bool ok, const PzemSample &sample) {
  if (!ok) {
    if (s_fail_streak < 255) s_fail_streak++;
    if (s_fail_streak >= PZEM_FAIL_THRESHOLD) return PZEM_STALE;
    // Below threshold, hold previous classification by returning PZEM_OK
    // (caller keeps last-known sample on screen).
    return PZEM_OK;
  }
  s_fail_streak = 0;

  // Voltage-based sensor-fault detection (distinguish broken vs night).
  uint64_t now = time_source::monotonic_us();
  if (sample.voltage < SENSOR_LOW_V_THRESHOLD) {
    if (!s_low_v_active) {
      s_low_v_active = true;
      s_low_v_start_us = now;
    }
    uint64_t dur_us = now - s_low_v_start_us;
    if (dur_us >= (uint64_t)SENSOR_FAULT_WINDOW_SEC * 1000000ULL) {
      return PZEM_SENSOR_FAULT;
    }
  } else {
    s_low_v_active = false;
  }
  return PZEM_OK;
}

bool reset_energy() {
  if (!s_pzem) return false;
  return s_pzem->resetEnergy();
}

}  // namespace pzem
