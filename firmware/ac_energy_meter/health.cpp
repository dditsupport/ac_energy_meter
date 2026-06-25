#include "health.h"
#include "config.h"

#include <esp_task_wdt.h>
#include <Preferences.h>
#include "log_serial.h"

namespace health {

static bool s_tripped = false;

void begin() {
  // Initialize a 30-second task WDT. Arduino core 3.x (IDF 5.x) replaced the
  // (timeout_sec, panic) signature with a config struct. Older 2.x code is
  // intentionally not supported in this branch.
  esp_task_wdt_config_t wdt_cfg = {
      .timeout_ms = 30000,
      .idle_core_mask = 0,
      .trigger_panic = true,
  };
  esp_task_wdt_reconfigure(&wdt_cfg);

  // Boot-loop guard: stash recent boot epochs in NVS namespace "health".
  Preferences p;
  p.begin("health", false);
  uint32_t now = millis() / 1000;  // monotonic-ish at boot
  // Track up to 8 recent boots' uptime-at-record markers via a counter; simpler:
  // we record a rolling array of last 8 millis values, then count how many fall
  // inside the BOOTLOOP_WINDOW_SEC interval ending now.
  String hist = p.getString("recent", "");
  // hist is comma-separated uint32 seconds-since-some-epoch values; we use a
  // synthetic epoch via getUInt("clk", 0) + a small advance each boot.
  uint32_t clk = p.getUInt("clk", 0);
  clk += 1;  // each boot advances by 1 "second" min; real time isn't required here
  // Actually use a coarse monotonic across boots: keep a counter.

  // Re-derive: store an array of the last BOOTLOOP_THRESHOLD boot "tick" values.
  // We just count boots in the last BOOTLOOP_WINDOW_SEC seconds *of wall-clock* —
  // but we don't have wall clock yet at this point. So fall back to: count fast
  // boots by recording millis-at-record from RTC slow memory if available. Since
  // ESP32 keeps RTC across deep sleep but not power loss, we cannot rely on it.
  //
  // Practical approach: maintain a counter `consec`; bump on every boot, decay it
  // if last_uptime >= BOOTLOOP_WINDOW_SEC was achieved before reboot. We don't
  // know last uptime cleanly, so we use a "last clean uptime" marker that
  // SamplingTask writes once it has run for >= BOOTLOOP_WINDOW_SEC.
  uint32_t consec = p.getUInt("consec", 0);
  uint32_t last_clean = p.getUInt("last_clean", 0);
  if (last_clean != 0) {
    // previous boot did reach clean uptime
    consec = 0;
  }
  consec += 1;
  p.putUInt("consec", consec);
  p.putUInt("last_clean", 0);

  if (consec >= BOOTLOOP_THRESHOLD) {
    s_tripped = true;
    LOG_PRINTF("[health] BOOT-LOOP TRIPPED (consec=%u). BLE-only mode.\n", consec);
  } else {
    LOG_PRINTF("[health] consecutive fast boots: %u\n", consec);
  }
  p.end();
}

void register_task() {
  esp_task_wdt_add(nullptr);
}

void feed() {
  esp_task_wdt_reset();
}

bool boot_loop_tripped() {
  return s_tripped;
}

// Called by SamplingTask once it has run > BOOTLOOP_WINDOW_SEC.
// Defined here so the symbol is co-located with the boot-loop bookkeeping.
extern "C" void health_mark_clean_uptime() {
  Preferences p;
  p.begin("health", false);
  p.putUInt("last_clean", 1);
  p.putUInt("consec", 0);
  p.end();
}

}  // namespace health
