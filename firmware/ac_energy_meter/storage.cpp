#include "storage.h"
#include "config.h"

#include <LittleFS.h>
#include <Preferences.h>
#include <ArduinoJson.h>
#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>
#include "log_serial.h"

namespace storage {

static Preferences s_cfg;
static Preferences s_state;
static SemaphoreHandle_t s_log_mutex = nullptr;

static uint32_t s_boot_id = 0;
static uint64_t s_last_seq = 0;
static uint64_t s_seq_hwm = 0;
static uint32_t s_unsynced_count = 0;
static bool s_buffer_full = false;
static uint32_t s_partition_total = 0;

// ---- Helpers ----------------------------------------------------------------

static bool lock_log(TickType_t ticks = pdMS_TO_TICKS(2000)) {
  return xSemaphoreTake(s_log_mutex, ticks) == pdTRUE;
}
static void unlock_log() { xSemaphoreGive(s_log_mutex); }

static bool parse_row(const String &line, RowFields &out) {
  // v2 expected: "seq,boot_id,sec,V,I,P,Wh,PF,Hz"
  // v1 (legacy): "seq,boot_id,sec,V,I,P,Wh,PF"   <- Hz defaults to 0
  int parts = 0;
  const char *s = line.c_str();
  char *end;
  uint64_t v_u64;
  uint32_t v_u32;
  float v_f;
  out.Hz = 0.0f;  // default for legacy rows

  v_u64 = strtoull(s, &end, 10);
  if (end == s || *end != ',') return false;
  out.seq = v_u64;
  s = end + 1; parts++;

  v_u32 = strtoul(s, &end, 10);
  if (end == s || *end != ',') return false;
  out.boot_id = v_u32;
  s = end + 1; parts++;

  v_u32 = strtoul(s, &end, 10);
  if (end == s || *end != ',') return false;
  out.sec_since_boot = v_u32;
  s = end + 1; parts++;

  float *fields[] = {&out.V, &out.I, &out.P, &out.Wh, &out.PF};
  for (int i = 0; i < 5; ++i) {
    v_f = strtof(s, &end);
    if (end == s) return false;
    *fields[i] = v_f;
    if (i < 4) {
      if (*end != ',') return false;
      s = end + 1;
    }
    parts++;
  }
  // Optional Hz field (v2). If present, *end == ','; otherwise it's '\n', '\r' or '\0'.
  if (*end == ',') {
    s = end + 1;
    v_f = strtof(s, &end);
    if (end != s) {
      out.Hz = v_f;
      parts++;
    }
  }
  return parts == 8 || parts == 9;
}

// Strip a trailing partial line from /log.csv if it lacks newline or fails to parse.
static void repair_tail() {
  File f = LittleFS.open(LOG_PATH, "r");
  if (!f) return;
  size_t size = f.size();
  if (size == 0) { f.close(); return; }

  // Read up to last 256 bytes.
  size_t scan = size > 256 ? 256 : size;
  f.seek(size - scan, SeekSet);
  String tail;
  while (f.available()) tail += (char)f.read();
  f.close();

  // Find last full line.
  int last_nl = tail.lastIndexOf('\n');
  if (last_nl < 0) {
    // No newline at all in tail; if file <= 256B, the whole thing is garbage.
    if (size <= 256) {
      LittleFS.remove(LOG_PATH);
    } else {
      // Otherwise, leave it; tail was inside a long line. Defensive: truncate to size - scan.
      // (Should not happen in practice — rows are ~50 bytes.)
    }
    return;
  }
  // Validate the last complete line.
  String last_line = tail.substring(tail.lastIndexOf('\n', last_nl - 1) + 1, last_nl);
  RowFields rf;
  if (!parse_row(last_line, rf)) {
    // Truncate to before that bad line.
    size_t cut_at = size - (tail.length() - tail.lastIndexOf('\n', last_nl - 1) - 1);
    File w = LittleFS.open(LOG_PATH, "r+");
    if (w) {
      // ESP32 LittleFS lacks fs::truncate; rewrite without the bad line.
      w.close();
      File r = LittleFS.open(LOG_PATH, "r");
      File t = LittleFS.open(LOG_TMP_PATH, "w");
      if (r && t) {
        size_t copied = 0;
        while (r.available() && copied < cut_at) {
          int b = r.read();
          if (b < 0) break;
          t.write((uint8_t)b);
          copied++;
        }
        t.close();
        r.close();
        LittleFS.remove(LOG_PATH);
        LittleFS.rename(LOG_TMP_PATH, LOG_PATH);
      }
    }
  }
  // Also: ensure file ends with newline. If after repair last char isn't '\n', append one.
  File chk = LittleFS.open(LOG_PATH, "r");
  if (chk) {
    size_t sz = chk.size();
    if (sz > 0) {
      chk.seek(sz - 1, SeekSet);
      int last = chk.read();
      chk.close();
      if (last != '\n') {
        File ap = LittleFS.open(LOG_PATH, "a");
        if (ap) { ap.write((uint8_t)'\n'); ap.close(); }
      }
    } else chk.close();
  }
}

static uint32_t count_rows() {
  File f = LittleFS.open(LOG_PATH, "r");
  if (!f) return 0;
  uint32_t n = 0;
  while (f.available()) {
    int b = f.read();
    if (b == '\n') n++;
  }
  f.close();
  return n;
}

static void scan_prev_boot_duration(uint32_t prev_boot_id, uint32_t &max_sec) {
  max_sec = 0;
  File f = LittleFS.open(LOG_PATH, "r");
  if (!f) return;
  String line;
  while (f.available()) {
    char c = (char)f.read();
    if (c == '\n') {
      RowFields rf;
      if (parse_row(line, rf) && rf.boot_id == prev_boot_id) {
        if (rf.sec_since_boot > max_sec) max_sec = rf.sec_since_boot;
      }
      line = "";
    } else if (c != '\r') {
      line += c;
    }
  }
  f.close();
}

// ---- Public API -------------------------------------------------------------

bool begin() {
  s_log_mutex = xSemaphoreCreateMutex();
  if (!s_log_mutex) return false;

  if (!LittleFS.begin(true)) {
    LOG_PRINTLN("[storage] LittleFS mount failed");
    return false;
  }
  s_partition_total = LittleFS.totalBytes();

  // Recovery step 1: delete leftover /log.tmp.
  if (LittleFS.exists(LOG_TMP_PATH)) {
    LOG_PRINTLN("[storage] cleanup leftover /log.tmp");
    LittleFS.remove(LOG_TMP_PATH);
  }

  // Recovery step 2: repair tail of /log.csv.
  if (LittleFS.exists(LOG_PATH)) {
    repair_tail();
  }

  // Open NVS namespaces.
  s_cfg.begin("cfg", false);
  s_state.begin("state", false);

  // Pull previous boot identity & seq HWM.
  uint32_t prev_boot_id = s_state.getUInt("boot_id", 0);
  s_seq_hwm = s_state.getULong64("seq_hwm", 0);

  // Restore last_seq from HWM (never reuse seqs).
  s_last_seq = s_seq_hwm;

  // If previous boot exists AND it ran long enough to log at least one row,
  // append a boot record. Zero-duration boots (dev reflashes, brief power
  // glitches) carry no readings and would just clutter boot_history with
  // entries the server doesn't need.
  if (prev_boot_id > 0) {
    uint32_t max_sec = 0;
    scan_prev_boot_duration(prev_boot_id, max_sec);
    if (max_sec > 0) {
      BootRecord rec = {prev_boot_id, max_sec};
      push_boot_record(rec);
    }
  }

  // Bump and persist new boot_id.
  s_boot_id = prev_boot_id + 1;
  s_state.putUInt("boot_id", s_boot_id);

  s_unsynced_count = count_rows();
  s_buffer_full = (LittleFS.totalBytes() - LittleFS.usedBytes()) <
                  max((uint32_t)BUFFER_FREE_MIN_BYTES,
                      (uint32_t)(s_partition_total * BUFFER_FREE_MIN_PCT / 100));

  LOG_PRINTF("[storage] boot_id=%u last_seq=%llu unsynced=%u free=%u\n",
                s_boot_id, (unsigned long long)s_last_seq, s_unsynced_count,
                (unsigned)(LittleFS.totalBytes() - LittleFS.usedBytes()));
  return true;
}

uint32_t boot_id() { return s_boot_id; }
uint64_t last_seq() { return s_last_seq; }
uint64_t seq_hwm() { return s_seq_hwm; }

void set_last_seq(uint64_t seq) {
  s_last_seq = seq;
  // Advance HWM only when we cross it.
  if (seq >= s_seq_hwm) {
    s_seq_hwm = seq + SEQ_HWM_STRIDE;
    s_state.putULong64("seq_hwm", s_seq_hwm);
  }
}

// ---- Boot history (circular buffer in NVS) ----------------------------------

void push_boot_record(const BootRecord &rec) {
  // Stored as a JSON array string for simplicity (max 32 entries x ~30 bytes ~ <1 KB).
  String json = s_state.getString("boots", "[]");
  StaticJsonDocument<1500> doc;
  if (deserializeJson(doc, json)) {
    doc.clear();
    doc.to<JsonArray>();
  }
  JsonArray arr = doc.as<JsonArray>();
  JsonObject obj = arr.createNestedObject();
  obj["b"] = rec.boot_id;
  obj["d"] = rec.duration_sec;
  while (arr.size() > MAX_BOOT_HISTORY) arr.remove(0);
  String out;
  serializeJson(doc, out);
  s_state.putString("boots", out);
}

void prune_boot_history_below(uint32_t min_keep_boot_id) {
  String json = s_state.getString("boots", "[]");
  StaticJsonDocument<1500> doc;
  if (deserializeJson(doc, json)) return;
  JsonArray arr = doc.as<JsonArray>();
  bool changed = false;
  for (int i = (int)arr.size() - 1; i >= 0; --i) {
    uint32_t bid = arr[i]["b"] | 0;
    if (bid < min_keep_boot_id) {
      arr.remove(i);
      changed = true;
    }
  }
  if (changed) {
    String out;
    serializeJson(doc, out);
    s_state.putString("boots", out);
  }
}

void clear_boot_history() {
  s_state.putString("boots", "[]");
}

size_t get_boot_history(BootRecord *out, size_t max_out) {
  String json = s_state.getString("boots", "[]");
  StaticJsonDocument<1500> doc;
  if (deserializeJson(doc, json)) return 0;
  JsonArray arr = doc.as<JsonArray>();
  size_t n = 0;
  for (JsonObject o : arr) {
    if (n >= max_out) break;
    out[n].boot_id = o["b"] | 0;
    out[n].duration_sec = o["d"] | 0;
    n++;
  }
  return n;
}

// ---- Wi-Fi creds ------------------------------------------------------------

size_t get_wifi_creds(WifiCred *out, size_t max_out) {
  String json = s_cfg.getString("wifi", "[]");
  StaticJsonDocument<1024> doc;
  if (deserializeJson(doc, json)) return 0;
  JsonArray arr = doc.as<JsonArray>();
  size_t n = 0;
  for (JsonObject o : arr) {
    if (n >= max_out) break;
    out[n].ssid = (const char *)(o["s"] | "");
    out[n].password = (const char *)(o["p"] | "");
    if (out[n].ssid.length()) n++;
  }
  return n;
}

bool add_wifi_cred(const String &ssid, const String &password) {
  if (ssid.isEmpty()) return false;
  // Single-credential model: replace any prior entry with this one.
  StaticJsonDocument<512> doc;
  JsonArray arr = doc.to<JsonArray>();
  JsonObject o = arr.createNestedObject();
  o["s"] = ssid;
  o["p"] = password;
  String out;
  serializeJson(doc, out);
  s_cfg.putString("wifi", out);
  return true;
}

void clear_wifi_creds() {
  s_cfg.putString("wifi", "[]");
}

void set_last_sync_at(uint32_t epoch) {
  s_state.putUInt("sync_at", epoch);
}
uint32_t last_sync_at() {
  return s_state.getUInt("sync_at", 0);
}

String ingest_host() {
  return s_cfg.getString("host", "");
}
bool set_ingest_host(const String &host) {
  if (host.length() > 128) return false;  // sanity cap
  s_cfg.putString("host", host);
  return true;
}

uint32_t log_interval_sec() {
  return s_cfg.getUInt("log_int", LOG_INTERVAL_SEC_DEFAULT);
}
bool set_log_interval_sec(uint32_t sec) {
  if (sec < LOG_INTERVAL_SEC_MIN || sec > LOG_INTERVAL_SEC_MAX) return false;
  // Avoid an NVS write if the value didn't change — limits flash wear on
  // chatty servers that include the field in every response.
  if (s_cfg.getUInt("log_int", 0) == sec) return true;
  s_cfg.putUInt("log_int", sec);
  return true;
}

float today_anchor_wh() {
  return s_state.getFloat("tdy_wh", -1.0f);
}
uint32_t today_anchor_day() {
  return s_state.getUInt("tdy_day", 0);
}
bool today_anchor_clean() {
  return s_state.getBool("tdy_cln", false);
}
void set_today_anchor(float wh, uint32_t day, bool clean) {
  s_state.putFloat("tdy_wh", wh);
  s_state.putUInt("tdy_day", day);
  s_state.putBool("tdy_cln", clean);
}

// ---- Log file ---------------------------------------------------------------

uint32_t free_bytes() {
  return LittleFS.totalBytes() - LittleFS.usedBytes();
}

bool is_buffer_full() {
  uint32_t free_b = free_bytes();
  uint32_t threshold = max((uint32_t)BUFFER_FREE_MIN_BYTES,
                           (uint32_t)(s_partition_total * BUFFER_FREE_MIN_PCT / 100));
  s_buffer_full = (free_b < threshold);
  return s_buffer_full;
}

bool append_row(const RowFields &row) {
  if (is_buffer_full()) return false;
  if (!lock_log()) return false;
  bool ok = false;
  File f = LittleFS.open(LOG_PATH, "a");
  if (f) {
    char line[128];
    int n = snprintf(line, sizeof(line),
                     "%llu,%u,%u,%.2f,%.3f,%.2f,%.2f,%.3f,%.2f\n",
                     (unsigned long long)row.seq, row.boot_id, row.sec_since_boot,
                     row.V, row.I, row.P, row.Wh, row.PF, row.Hz);
    if (n > 0 && n < (int)sizeof(line)) {
      size_t w = f.write((const uint8_t *)line, n);
      f.flush();
      f.close();
      if ((int)w == n) {
        s_unsynced_count++;
        ok = true;
      }
    } else {
      f.close();
    }
  }
  unlock_log();
  return ok;
}

uint32_t row_count() {
  return s_unsynced_count;
}

uint32_t current_unsynced_count() {
  return s_unsynced_count;
}

uint64_t snapshot_max_seq() {
  return s_last_seq;
}

uint32_t stream_rows_up_to(uint64_t max_seq, std::function<bool(const RowFields &)> cb) {
  uint32_t emitted = 0;
  File f = LittleFS.open(LOG_PATH, "r");
  if (!f) return 0;
  String line;
  while (f.available()) {
    char c = (char)f.read();
    if (c == '\n') {
      RowFields rf;
      if (parse_row(line, rf) && rf.seq <= max_seq) {
        if (!cb(rf)) {
          f.close();
          return emitted;
        }
        emitted++;
      }
      line = "";
    } else if (c != '\r') {
      line += c;
    }
  }
  f.close();
  return emitted;
}

bool truncate_up_to(uint64_t acked_seq) {
  if (!lock_log()) return false;
  bool ok = false;
  File r = LittleFS.open(LOG_PATH, "r");
  File w = LittleFS.open(LOG_TMP_PATH, "w");
  if (r && w) {
    String line;
    uint32_t kept = 0;
    while (r.available()) {
      char c = (char)r.read();
      if (c == '\n') {
        RowFields rf;
        if (parse_row(line, rf) && rf.seq > acked_seq) {
          line += '\n';
          w.write((const uint8_t *)line.c_str(), line.length());
          kept++;
        }
        line = "";
      } else if (c != '\r') {
        line += c;
      }
    }
    w.flush();
    w.close();
    r.close();
    LittleFS.remove(LOG_PATH);
    LittleFS.rename(LOG_TMP_PATH, LOG_PATH);
    s_unsynced_count = kept;
    ok = true;
  } else {
    if (r) r.close();
    if (w) w.close();
  }
  unlock_log();
  return ok;
}

// ---- Serial helpers ---------------------------------------------------------

void dump_log_to_serial() {
  File f = LittleFS.open(LOG_PATH, "r");
  if (!f) {
    LOG_PRINTLN("[storage] no log file");
    return;
  }
  LOG_PRINTLN("---BEGIN LOG---");
  while (f.available()) LOG_WRITE(f.read());
  LOG_PRINTLN("---END LOG---");
  f.close();
}

void dump_boots_to_serial() {
  BootRecord recs[MAX_BOOT_HISTORY];
  size_t n = get_boot_history(recs, MAX_BOOT_HISTORY);
  LOG_PRINTF("[storage] current boot_id=%u, history has %u records\n",
                s_boot_id, (unsigned)n);
  for (size_t i = 0; i < n; ++i) {
    LOG_PRINTF("  boot %u: %u sec\n", recs[i].boot_id, recs[i].duration_sec);
  }
}

void clear_log() {
  if (!lock_log()) return;
  LittleFS.remove(LOG_PATH);
  s_unsynced_count = 0;
  unlock_log();
  LOG_PRINTLN("[storage] log cleared");
}

}  // namespace storage
