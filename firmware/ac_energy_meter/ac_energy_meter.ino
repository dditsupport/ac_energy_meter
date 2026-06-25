// AC Energy Meter — ESP32 firmware (v1.0.0)
//
// Sketch entry point. Sets up the brownout detector, mounts storage, then
// spawns two pinned FreeRTOS tasks: SamplingTask on core 0 (PZEM + OLED) and
// ConnectivityTask on core 1 (BLE always, Wi-Fi periodic). All shared mutable
// state lives in g_state, guarded by g_state_mutex.
//
// See docs/PINOUT.md for wiring and docs/PROVISIONING.md for setup steps.

#include "config.h"
#include "shared_state.h"
#include "identity.h"
#include "time_source.h"
#include "pzem.h"
#include "display.h"
#include "storage.h"
#include "health.h"
#include "wifi_sync.h"
#include "ble_service.h"
#include "led.h"
#include "relay.h"
#include "rtc.h"

#include <esp_task_wdt.h>
#include <esp_system.h>
#include <esp_mac.h>
#include "log_serial.h"

// ---- Global shared state ----------------------------------------------------
SharedState g_state;
SemaphoreHandle_t g_state_mutex;

// ---- Forward declarations ---------------------------------------------------
static void sampling_task(void *);
static void connectivity_task(void *);
static void handle_serial_command(const String &cmd);

extern "C" void health_mark_clean_uptime();

// ---- Setup ------------------------------------------------------------------
void setup() {
  // The brownout detector is enabled by default in ESP-IDF 5.x at the chip
  // default threshold (~2.4 V). Override via menuconfig / sdkconfig if needed.

  Serial.begin(115200);
  log_serial::init();
  delay(50);
  LOG_PRINTLN();
  LOG_PRINTLN("=== AC Energy Meter boot ===");

  g_state_mutex = xSemaphoreCreateMutex();
  if (!g_state_mutex) {
    LOG_PRINTLN("[fatal] state mutex alloc failed");
    while (true) delay(1000);
  }

  display::begin();
  display::splash("AC Energy Meter", "booting...");

  time_source::begin();
  health::begin();
  if (!storage::begin()) {
    display::splash("STORAGE FAIL", "check flash");
    while (true) delay(1000);
  }
  pzem::begin();

  // Seed wall clock from the DS3231 if it's healthy. This lets the OLED show
  // "Today:" immediately at boot instead of "Session:" until NTP or BLE.
  if (rtc::begin()) {
    time_t epoch = rtc::read_epoch();
    if (epoch > 0 && time_source::set_wall_clock(epoch)) {
      struct tm lt;
      localtime_r(&epoch, &lt);
      char tbuf[40];
      strftime(tbuf, sizeof(tbuf), "%Y-%m-%d %H:%M:%S %Z", &lt);
      LOG_PRINTF("[boot] RTC time: %s  (epoch=%ld)\n", tbuf, (long)epoch);
    } else {
      LOG_PRINTLN("[boot] RTC present but returned no time");
    }
  } else {
    LOG_PRINTLN("[boot] RTC unavailable (lost power or not wired)");
  }

  // Seed shared state.
  if (state_lock(pdMS_TO_TICKS(1000))) {
    g_state.boot_id = storage::boot_id();
    g_state.last_seq = storage::last_seq();
    g_state.unsynced_count = storage::current_unsynced_count();
    g_state.buffer_full = storage::is_buffer_full();
    g_state.wifi_status = WIFI_IDLE;
    g_state.ble_status = BLE_OFF;
    g_state.wall_clock_known = time_source::wall_clock_known();
    state_unlock();
  }

  LOG_PRINTF("Device ID: %s  fw=%s  boot=%u  unsynced=%u\n",
                identity::device_id().c_str(), identity::fw_version(),
                storage::boot_id(), storage::current_unsynced_count());

  // Diagnostic: print both the factory base MAC (what esptool shows) and the
  // effective Wi-Fi STA MAC (what WiFi.macAddress() returns). They differ if
  // a Custom MAC has been burned into eFuse, which is why two ESP32s can
  // print different BLE names than their printed chip MACs would suggest.
  {
    uint8_t base[6] = {0};
    uint8_t sta[6]  = {0};
    esp_efuse_mac_get_default(base);
    esp_read_mac(sta, ESP_MAC_WIFI_STA);
    LOG_PRINTF("[boot] eFuse base MAC: %02X:%02X:%02X:%02X:%02X:%02X\n",
                  base[0], base[1], base[2], base[3], base[4], base[5]);
    LOG_PRINTF("[boot] Wi-Fi STA MAC : %02X:%02X:%02X:%02X:%02X:%02X (used for device_id)\n",
                  sta[0],  sta[1],  sta[2],  sta[3],  sta[4],  sta[5]);
  }

  // Hardcoded Wi-Fi fallback for bench testing. If WIFI_SSID is non-empty
  // and the NVS slot has no credentials yet, copy them in. Once provisioned
  // via BLE the saved value wins and this block becomes a no-op.
  if (sizeof(WIFI_SSID) > 1) {
    storage::WifiCred existing[MAX_WIFI_CREDS];
    if (storage::get_wifi_creds(existing, MAX_WIFI_CREDS) == 0) {
      if (storage::add_wifi_cred(WIFI_SSID, WIFI_PASSWORD)) {
        LOG_PRINTF("[boot] seeded Wi-Fi from config.h: %s\n", WIFI_SSID);
      }
    }
  }

  ble_service::begin();
  wifi_sync::begin();
  led::begin();
  relay::begin();

  xTaskCreatePinnedToCore(sampling_task, "sampling",
                          SAMPLING_TASK_STACK, nullptr, SAMPLING_TASK_PRIO,
                          nullptr, 0);
  xTaskCreatePinnedToCore(connectivity_task, "conn",
                          CONN_TASK_STACK, nullptr, CONN_TASK_PRIO,
                          nullptr, 1);
}

// ---- Main loop (idle / serial console / WDT feeder) ------------------------
void loop() {
  static String line;
  while (Serial.available()) {
    char c = (char)Serial.read();
    if (c == '\n' || c == '\r') {
      if (line.length()) {
        handle_serial_command(line);
        line = "";
      }
    } else if (line.length() < 64) {
      line += c;
    }
  }
  led::tick();
  relay::tick();
  delay(50);
}

static void handle_serial_command(const String &cmd) {
  String c = cmd;
  c.trim();
  c.toUpperCase();
  if (c == "DUMP") {
    storage::dump_log_to_serial();
  } else if (c == "BOOTS") {
    storage::dump_boots_to_serial();
  } else if (c == "CLEAR") {
    storage::clear_log();
  } else if (c == "WIFI") {
    storage::WifiCred creds[MAX_WIFI_CREDS];
    size_t n = storage::get_wifi_creds(creds, MAX_WIFI_CREDS);
    LOG_PRINTF("%u saved networks\n", (unsigned)n);
    for (size_t i = 0; i < n; ++i) {
      LOG_PRINTF("  %u: %s\n", (unsigned)i, creds[i].ssid.c_str());
    }
  } else if (c == "INFO") {
    LOG_PRINTF("id=%s fw=%s boot=%u last_seq=%llu unsynced=%u free=%u\n",
                  identity::device_id().c_str(), identity::fw_version(),
                  storage::boot_id(), (unsigned long long)storage::last_seq(),
                  storage::current_unsynced_count(),
                  (unsigned)storage::free_bytes());
  } else if (c == "SYNC") {
    LOG_PRINTLN("[cmd] requesting immediate Wi-Fi sync");
    wifi_sync::request_immediate_sync();
  } else if (c == "CLEARBOOTS") {
    storage::clear_boot_history();
    LOG_PRINTLN("[cmd] boot_history cleared from NVS");
  } else if (c == "LOG") {
    // Append a synthetic row using the latest sample so the next sync
    // has something to send. Useful for end-to-end testing without
    // waiting LOG_INTERVAL_SEC.
    SharedState snap;
    if (state_snapshot(snap)) {
      uint64_t seq = storage::last_seq() + 1;
      storage::RowFields rf;
      rf.seq = seq;
      rf.boot_id = storage::boot_id();
      rf.sec_since_boot = (uint32_t)(time_source::monotonic_us() / 1000000ULL);
      rf.V = snap.latest.voltage;
      rf.I = snap.latest.current;
      rf.P = snap.latest.power;
      rf.Wh = snap.latest.energy_wh;
      rf.PF = snap.latest.pf;
      if (storage::append_row(rf)) {
        storage::set_last_seq(seq);
        wifi_sync::request_immediate_sync();
        LOG_PRINTF("[cmd] synthetic row seq=%llu logged, sync requested\n",
                      (unsigned long long)seq);
      } else {
        LOG_PRINTLN("[cmd] append_row failed (buffer full?)");
      }
    }
  } else {
    LOG_PRINTF("unknown command: %s (try DUMP, BOOTS, CLEAR, CLEARBOOTS, WIFI, INFO, SYNC, LOG)\n",
                  c.c_str());
  }
}

// ---- SamplingTask (core 0) --------------------------------------------------
static void sampling_task(void *) {
  esp_task_wdt_add(nullptr);

  uint64_t last_us = time_source::monotonic_us();
  uint64_t last_log_us = last_us;
  uint32_t prev_day_observed = 0;   // last local day this boot has seen
  bool clean_uptime_marked = false;
  uint32_t boot_start_us_sec = (uint32_t)(last_us / 1000000ULL);

  // Session anchor = first valid PZEM cumulative reading after this boot.
  // RAM-only; resets on every reboot, which is exactly what "session" means.
  float session_anchor_wh = -1.0f;

  for (;;) {
    esp_task_wdt_reset();

    PzemSample sample{};
    bool ok = pzem::read(sample);
    PzemStatus st = pzem::classify(ok, sample);

    uint64_t now_us = time_source::monotonic_us();
    last_us = now_us;

    // ----- kWh display values, all derived from PZEM (no ESP32 integration) -----
    float total_kwh = 0.0f;
    float session_kwh = 0.0f;
    float today_kwh = 0.0f;
    bool today_partial = true;

    if (ok) {
      // Total: PZEM's lifetime cumulative reading, straight conversion.
      total_kwh = sample.energy_wh / 1000.0f;

      // Session: anchored at first PZEM read of this boot. Re-anchor if the
      // PZEM rolled back (someone called resetEnergy(), or hardware reset).
      if (session_anchor_wh < 0 || sample.energy_wh < session_anchor_wh) {
        session_anchor_wh = sample.energy_wh;
      }
      session_kwh = (sample.energy_wh - session_anchor_wh) / 1000.0f;

      // Today: anchored in NVS at the start of the day. Re-anchor whenever
      // the day changes, when no anchor exists yet, or when the PZEM rolled
      // back below the stored anchor.
      if (time_source::wall_clock_known()) {
        uint32_t today = time_source::local_day_number();
        float anchor_wh = storage::today_anchor_wh();
        uint32_t anchor_day = storage::today_anchor_day();
        bool anchor_clean = storage::today_anchor_clean();

        bool observed_rollover =
            (prev_day_observed != 0) && (prev_day_observed != today);
        bool need_reanchor = (anchor_wh < 0) ||
                             (anchor_day != today) ||
                             (sample.energy_wh < anchor_wh);

        if (need_reanchor) {
          // Clean iff we watched the day boundary tick over during this boot
          // (so we caught the full day's energy). Otherwise it's a mid-day
          // boot, a wall-clock-just-arrived case, or a multi-day gap.
          bool clean = observed_rollover;
          storage::set_today_anchor(sample.energy_wh, today, clean);
          anchor_wh = sample.energy_wh;
          anchor_clean = clean;
        }
        today_kwh = (sample.energy_wh - anchor_wh) / 1000.0f;
        if (today_kwh < 0) today_kwh = 0.0f;
        today_partial = !anchor_clean;
        prev_day_observed = today;
      }
    }

    if (state_lock()) {
      if (ok) {
        g_state.latest = sample;
        g_state.total_kwh = total_kwh;
        g_state.session_kwh = session_kwh;
        g_state.today_kwh = today_kwh;
        g_state.today_is_partial = today_partial;
        if (sample.power > g_state.peak_power_w) g_state.peak_power_w = sample.power;
      }
      g_state.pzem_status = st;
      g_state.wall_clock_known = time_source::wall_clock_known();
      g_state.uptime_sec = (uint32_t)(now_us / 1000000ULL);
      g_state.unsynced_count = storage::current_unsynced_count();
      g_state.buffer_full = storage::is_buffer_full();
      state_unlock();
    }

    // Render OLED from a snapshot (no I/O under lock).
    display::tick();

    // Periodic log row. Cadence is server-configurable (storage::log_interval_sec)
    // and falls back to LOG_INTERVAL_SEC_DEFAULT (config.h) on a fresh device.
    uint32_t log_period_sec = storage::log_interval_sec();
    if ((uint64_t)(now_us - last_log_us) >= (uint64_t)log_period_sec * 1000000ULL) {
      last_log_us = now_us;
      if (ok || st == PZEM_OK) {
        uint64_t seq = storage::last_seq() + 1;
        storage::RowFields rf;
        rf.seq = seq;
        rf.boot_id = storage::boot_id();
        rf.sec_since_boot = (uint32_t)(now_us / 1000000ULL);
        rf.V = sample.voltage;
        rf.I = sample.current;
        rf.P = sample.power;
        rf.Wh = sample.energy_wh;
        rf.PF = sample.pf;
        rf.Hz = sample.frequency;
        if (storage::append_row(rf)) {
          storage::set_last_seq(seq);
          if (state_lock()) {
            g_state.last_seq = seq;
            g_state.unsynced_count = storage::current_unsynced_count();
            state_unlock();
          }
          // Push to MilesWeb as soon as the next ConnectivityTask tick runs.
          // If Wi-Fi is reachable, the row ships within seconds; if not,
          // it stays in /log.csv and the periodic 2-min cycle retries.
          wifi_sync::request_immediate_sync();
        }
      }
    }

    // Mark clean uptime after passing the boot-loop window.
    if (!clean_uptime_marked &&
        (uint32_t)(now_us / 1000000ULL) - boot_start_us_sec >= BOOTLOOP_WINDOW_SEC) {
      health_mark_clean_uptime();
      clean_uptime_marked = true;
    }

    vTaskDelay(pdMS_TO_TICKS(DISPLAY_REFRESH_MS));
  }
}

// ---- ConnectivityTask (core 1) ---------------------------------------------
static void connectivity_task(void *) {
  esp_task_wdt_add(nullptr);
  uint64_t last_wifi_us     = time_source::monotonic_us();
  uint64_t last_ble_alive_us = time_source::monotonic_us();
  // Run a Wi-Fi cycle quickly on first boot too — wait one interval to let
  // the rest of the system settle.
  bool first_cycle = true;

  for (;;) {
    esp_task_wdt_reset();
    ble_service::tick();
    if (ble_service::is_alive()) {
      last_ble_alive_us = time_source::monotonic_us();
    }

    if (!health::boot_loop_tripped()) {
      // On-demand scan requested via BLE Wi-Fi Config write of {"action":"scan"}.
      if (wifi_sync::consume_scan_request()) {
        wifi_sync::run_scan();
        ble_service::tick();  // push the fresh results immediately
      }

      uint64_t now_us = time_source::monotonic_us();
      uint64_t since_us = now_us - last_wifi_us;
      uint64_t interval_us = (uint64_t)WIFI_SCAN_INTERVAL_SEC * 1000000ULL;
      bool periodic_due = first_cycle ? (since_us > 30ULL * 1000000ULL)
                                       : (since_us >= interval_us);
      bool triggered = wifi_sync::consume_immediate_sync_request();
      if (periodic_due || triggered) {
        first_cycle = false;
        last_wifi_us = now_us;
        wifi_sync::run_cycle();
      }
    }

    // ---- Stuck-watchdog soft reboots ---------------------------------------
    // Independent of the 30 s task WDT — these catch the subtler case where
    // every task is alive but the radio side is silently dead. Guarded by
    // uptime so we never reboot in the first STUCK_*_REBOOT_SEC after boot.
    uint64_t uptime_sec =
        time_source::monotonic_us() / 1000000ULL;

    if (uptime_sec > STUCK_WIFI_REBOOT_SEC) {
      uint32_t since_post = wifi_sync::seconds_since_last_successful_post();
      // UINT32_MAX = never posted -> don't reboot a brand-new / unprovisioned
      // device. Only reboot if we *had* been syncing and now can't.
      if (since_post != UINT32_MAX && since_post > STUCK_WIFI_REBOOT_SEC) {
        LOG_PRINTF("[health] stuck-wifi watchdog: %u s since last POST, restarting\n",
                   since_post);
        delay(100);
        esp_restart();
      }
    }

    uint64_t since_ble_us = time_source::monotonic_us() - last_ble_alive_us;
    if (since_ble_us / 1000000ULL > STUCK_BLE_REBOOT_SEC) {
      LOG_PRINTF("[health] stuck-ble watchdog: %llu s since BLE was alive, restarting\n",
                 (unsigned long long)(since_ble_us / 1000000ULL));
      delay(100);
      esp_restart();
    }

    vTaskDelay(pdMS_TO_TICKS(1000));
  }
}
