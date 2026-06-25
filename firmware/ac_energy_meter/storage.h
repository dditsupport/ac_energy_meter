#pragma once

#include <Arduino.h>
#include <functional>

namespace storage {

struct BootRecord {
  uint32_t boot_id;
  uint32_t duration_sec;
};

struct RowFields {
  uint64_t seq;
  uint32_t boot_id;
  uint32_t sec_since_boot;
  float V;
  float I;
  float P;
  float Wh;
  float PF;
  float Hz;  // mains frequency; appended in the v2 row format
};

// Mount LittleFS, run crash recovery (delete /log.tmp, validate last line),
// open NVS namespaces, compute fresh boot_id and persist it.
// Returns true on success.
bool begin();

// NVS getters/setters ---------------------------------------------------------
uint32_t boot_id();
uint64_t last_seq();
void set_last_seq(uint64_t seq);  // updates RAM and writes HWM to NVS if needed
uint64_t seq_hwm();

// Append the current boot's record once duration is known (e.g. on graceful shutdown).
// Internally used by recoverOnBoot() to chain partial previous boots too.
void push_boot_record(const BootRecord &rec);
size_t get_boot_history(BootRecord *out, size_t max_out);

// Drop boot_history entries with boot_id < min_keep_boot_id. Used after a
// successful sync: once the server has acked rows up to seq N, any history
// entry whose boot_id is older than the oldest remaining row is no longer
// needed by the firmware (the server already has it and the device will not
// re-send rows from that boot).
void prune_boot_history_below(uint32_t min_keep_boot_id);

// Wipe the entire boot_history. Exposed for the CLEARBOOTS serial command.
void clear_boot_history();

// Wi-Fi credentials -----------------------------------------------------------
struct WifiCred {
  String ssid;
  String password;
};
size_t get_wifi_creds(WifiCred *out, size_t max_out);
bool add_wifi_cred(const String &ssid, const String &password);
void clear_wifi_creds();

// last_sync_at (informational)
void set_last_sync_at(uint32_t epoch);
uint32_t last_sync_at();

// Backend host (scheme + host + optional port, e.g. "https://aromen.biz").
// Empty string means "use INGEST_HOST_DEFAULT compiled into config.h".
// Updated at runtime via BLE (Server Config characteristic).
String ingest_host();
bool set_ingest_host(const String &host);

// Logging cadence in seconds — the gap between writes to /log.csv. The
// runtime value lives in NVS so it survives reboots. The server can push
// a new value via the {"log_interval_sec": N} field in each ingest.php
// response. Out-of-range values (< LOG_INTERVAL_SEC_MIN or
// > LOG_INTERVAL_SEC_MAX) are rejected.
uint32_t log_interval_sec();
bool set_log_interval_sec(uint32_t sec);

// "Today" anchor — the PZEM cumulative-Wh value captured at the start of
// today. today_kwh on the OLED is computed as (current_pzem_wh - anchor) /
// 1000 so it survives ESP32 reboots without re-integration on the MCU.
//
// today_anchor_wh() returns < 0 when no anchor has ever been written.
// today_anchor_day() is time_source::local_day_number() at the moment the
// anchor was set; if today's day differs, sampling_task re-anchors.
// today_anchor_clean() is true only when the anchor was captured at a
// midnight rollover observed during a single boot (i.e. we have the full
// day's data). False means the anchor was set mid-day (boot, wall-clock
// arrival, day jump across power-off), so the displayed value is partial.
float today_anchor_wh();
uint32_t today_anchor_day();
bool today_anchor_clean();
void set_today_anchor(float wh, uint32_t day, bool clean);

// LittleFS log file -----------------------------------------------------------
// Append a row. Returns true on success, false if buffer full or write error.
bool append_row(const RowFields &row);

// Number of rows currently in /log.csv.
uint32_t row_count();

// Iterate rows with seq <= max_seq, calling cb for each.
// If cb returns false, iteration stops early. Returns count emitted.
uint32_t stream_rows_up_to(uint64_t max_seq, std::function<bool(const RowFields &)> cb);

// Snapshot: take current last_seq from RAM as "max seq to send". This avoids
// scanning the file. Returns the snapshot.
uint64_t snapshot_max_seq();

// Rewrite /log.csv keeping only rows with seq > acked_seq.
// Blocks log appends only during the brief rename. Returns true on success.
bool truncate_up_to(uint64_t acked_seq);

// Free space in bytes on the LittleFS partition.
uint32_t free_bytes();

// Buffer-full guard: returns true if free space is below the threshold.
bool is_buffer_full();

// Update the in-memory unsynced count exposed to SharedState.
uint32_t current_unsynced_count();

// Serial helpers --------------------------------------------------------------
void dump_log_to_serial();      // for `DUMP` command
void dump_boots_to_serial();    // for `BOOTS` command
void clear_log();               // for `CLEAR` command

}  // namespace storage
