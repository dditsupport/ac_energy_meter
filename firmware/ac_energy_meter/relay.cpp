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
static Mode     s_mode          = Mode::AUTO;  // RAM-only manual override

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
Mode     mode()    { return s_mode; }

const char *mode_str() {
  switch (s_mode) {
    case Mode::FORCE_ON:  return "on";
    case Mode::FORCE_OFF: return "off";
    default:              return "auto";
  }
}

void set_mode(Mode m) {
  if (m == s_mode) return;
  s_mode = m;
  LOG_PRINTF("[relay] mode -> %s\n", mode_str());
  // Apply immediately so the toggle is felt without waiting for the next tick.
  tick();
}

String status_json() {
  StaticJsonDocument<96> doc;
  doc["mode"]          = mode_str();
  doc["on"]            = s_state;
  doc["sched_version"] = s_version;
  String out;
  serializeJson(doc, out);
  return out;
}

// Parse "HH:MM" into minutes-of-day (0..1439). Returns -1 on malformed.
static int parse_hm(const char *s) {
  if (!s || strlen(s) != 5 || s[2] != ':') return -1;
  int h = (s[0] - '0') * 10 + (s[1] - '0');
  int m = (s[3] - '0') * 10 + (s[4] - '0');
  if (h < 0 || h > 23 || m < 0 || m > 59) return -1;
  return h * 60 + m;
}

// True if `dow` (0..6) is listed in the window's days array.
static bool day_in(JsonArray days, int dow) {
  for (JsonVariant d : days) {
    if (d.as<int>() == dow) return true;
  }
  return false;
}

// Returns the desired state at (dow, minute) given the cached schedule.
// dow: 0..6 (Sun..Sat). minute: minute-of-day 0..1439.
//
// Window semantics: on at `on`, off at `off`, on the selected weekdays.
//   - Same-day window (on < off): active [on, off) on each selected day.
//   - Overnight window (off < on): runs past midnight into the NEXT day.
//     The selected days are the START days. e.g. on=08:00 off=02:00 with
//     Mon selected => relay ON Mon 08:00 through Tue 02:00. Tuesday's
//     00:00-02:00 is ON because Monday (the previous day) is selected, NOT
//     because Tuesday is.
// Multiple windows OR together.
static bool desired_state(int dow, int minute) {
  if (!s_initialised || s_schedule_json.length() < 2) return false;
  StaticJsonDocument<1024> doc;
  if (deserializeJson(doc, s_schedule_json)) return false;
  JsonArray arr = doc.as<JsonArray>();
  if (arr.isNull() || arr.size() == 0) return false;

  int prev = (dow + 6) % 7;  // yesterday, for overnight tails
  for (JsonObject w : arr) {
    JsonArray days = w["days"].as<JsonArray>();
    int on  = parse_hm(w["on"]  | (const char *)nullptr);
    int off = parse_hm(w["off"] | (const char *)nullptr);
    if (on < 0 || off < 0 || on == off) continue;

    if (on < off) {
      // Same-day window.
      if (day_in(days, dow) && minute >= on && minute < off) return true;
    } else {
      // Overnight window: [on, 24:00) on the start day, then [00:00, off)
      // on the following day.
      if (day_in(days, dow)  && minute >= on)  return true;  // evening, start day
      if (day_in(days, prev) && minute <  off) return true;  // morning, next day
    }
  }
  return false;
}

void tick() {
  if (!s_initialised) return;

  // Manual override from the app takes precedence over the schedule and
  // needs no wall clock.
  if (s_mode == Mode::FORCE_ON || s_mode == Mode::FORCE_OFF) {
    bool want = (s_mode == Mode::FORCE_ON);
    if (want != s_state) {
      write_pin(want);
      LOG_PRINTF("[relay] %s (manual override)\n", want ? "ON" : "OFF");
    }
    return;
  }

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
