#include "relay.h"
#include "config.h"
#include "log_serial.h"

#include <ArduinoJson.h>
#include <Preferences.h>
#include <time.h>

namespace relay {

// Cached schedule. Parsed lazily on apply() / tick(); we keep the raw
// string in NVS so we can survive reboots even before the first sync.
static String   s_schedule_json = "[]";
static uint32_t s_version       = 0;
static bool     s_state         = false;
static bool     s_initialised   = false;

static inline void write_pin(bool on) {
#if RELAY_ACTIVE_HIGH
  digitalWrite(PIN_RELAY, on ? HIGH : LOW);
#else
  digitalWrite(PIN_RELAY, on ? LOW : HIGH);
#endif
  s_state = on;
}

void begin() {
  pinMode(PIN_RELAY, OUTPUT);
  write_pin(false);  // fail-safe off at boot

  Preferences p;
  p.begin("relay", true);  // read-only first
  s_schedule_json = p.getString("sched", "[]");
  s_version       = p.getUInt("ver",   0);
  p.end();
  s_initialised = true;
  LOG_PRINTF("[relay] boot schedule v=%u json=%s\n",
                (unsigned)s_version, s_schedule_json.c_str());
}

void apply(uint32_t version, const String &schedule_json_array) {
  // Skip if neither version nor content changed.
  if (version == s_version && schedule_json_array == s_schedule_json) return;

  s_schedule_json = schedule_json_array.length() ? schedule_json_array : "[]";
  s_version       = version;
  Preferences p;
  p.begin("relay", false);
  p.putString("sched", s_schedule_json);
  p.putUInt("ver",   s_version);
  p.end();
  LOG_PRINTF("[relay] schedule updated v=%u: %s\n",
                (unsigned)s_version, s_schedule_json.c_str());
  // Re-evaluate immediately so a fresh push takes effect without waiting
  // for the next loop tick.
  tick();
}

uint32_t version() { return s_version; }
bool     is_on()   { return s_state; }

// Parse "HH:MM" into minutes-of-day (0..1439). Returns -1 on malformed.
static int parse_hm(const char *s) {
  if (!s || strlen(s) != 5 || s[2] != ':') return -1;
  int h = (s[0] - '0') * 10 + (s[1] - '0');
  int m = (s[3] - '0') * 10 + (s[4] - '0');
  if (h < 0 || h > 23 || m < 0 || m > 59) return -1;
  return h * 60 + m;
}

// Returns the desired state at (dow, minute) given the cached schedule.
// dow: 0..6 (Sun..Sat). minute: minute-of-day 0..1439.
// Window semantics: on at `on`, off at `off`. If off <= on, the window
// wraps midnight (e.g. on=20:00, off=06:00 -> active 20:00-23:59 AND
// 00:00-06:00 on the same calendar day). Multiple windows OR together.
static bool desired_state(int dow, int minute) {
  if (!s_initialised || s_schedule_json.length() < 2) return false;
  StaticJsonDocument<1024> doc;
  if (deserializeJson(doc, s_schedule_json)) return false;
  JsonArray arr = doc.as<JsonArray>();
  if (arr.isNull() || arr.size() == 0) return false;

  for (JsonObject w : arr) {
    JsonArray days = w["days"].as<JsonArray>();
    bool day_match = false;
    for (JsonVariant d : days) {
      if (d.as<int>() == dow) { day_match = true; break; }
    }
    if (!day_match) continue;
    int on  = parse_hm(w["on"]  | (const char *)nullptr);
    int off = parse_hm(w["off"] | (const char *)nullptr);
    if (on < 0 || off < 0 || on == off) continue;
    bool inside = (on < off)
        ? (minute >= on && minute < off)
        : (minute >= on || minute < off);  // wraps midnight
    if (inside) return true;
  }
  return false;
}

void tick() {
  if (!s_initialised) return;

  time_t now = time(nullptr);
  if (now < 1700000000) {
    // Wall clock not yet known — leave the relay in its current state
    // rather than guessing. Default at boot was OFF.
    return;
  }
  struct tm lt;
  localtime_r(&now, &lt);
  int dow    = lt.tm_wday;                   // 0=Sun..6=Sat
  int minute = lt.tm_hour * 60 + lt.tm_min;

  bool want = desired_state(dow, minute);
  if (want != s_state) {
    write_pin(want);
    LOG_PRINTF("[relay] %s at %02d:%02d (dow=%d)\n",
                  want ? "ON" : "OFF", lt.tm_hour, lt.tm_min, dow);
  }
}

}  // namespace relay
