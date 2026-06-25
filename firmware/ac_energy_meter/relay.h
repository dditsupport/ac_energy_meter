#pragma once

#include <Arduino.h>

// Server-controlled relay output. The server pushes a schedule in each
// ingest.php response:
//   { "relay_version": <int>, "relay_schedule": [ {days:[0..6], on:"HH:MM", off:"HH:MM"}, ... ] }
// The schedule is cached in NVS so the relay keeps switching during a
// Wi-Fi outage. evaluate() is called every loop tick (~50 ms) and drives
// the GPIO based on the current local time + cached schedule. With no
// schedule cached the relay stays off.
//
// The Android app can also take manual control over BLE (see Mode below):
// FORCE_ON / FORCE_OFF override the schedule until set back to AUTO.

namespace relay {

// Manual override, set by the Android app over the BLE Relay characteristic.
//   AUTO      - follow the server schedule (default).
//   FORCE_ON  - hold the relay ON  regardless of schedule.
//   FORCE_OFF - hold the relay OFF regardless of schedule.
// The override lives in RAM only, so a reboot returns to AUTO (schedule
// driven). A manual toggle is a live action while the phone is connected,
// not a persistent configuration change.
enum class Mode : uint8_t { AUTO = 0, FORCE_ON = 1, FORCE_OFF = 2 };

void begin();

// Apply a freshly-fetched schedule. Caller passes the parsed JSON array
// as a string. Compares against the stored version; persists + reapplies
// on change. Pass empty array to clear.
void apply(uint32_t version, const String &schedule_json_array);

// Last version we accepted. Firmware sends this back so the server can
// short-circuit on no-change later if it wants to. Currently informational.
uint32_t version();

// Called every loop tick from ac_energy_meter.ino. Recomputes the
// desired on/off state from the cached schedule + local time, drives GPIO.
void tick();

// True if the relay is currently energised.
bool is_on();

// Manual override control. set_mode() drives the GPIO immediately.
void set_mode(Mode m);
Mode mode();
const char *mode_str();   // "auto" | "on" | "off"

// Compact status for the BLE Relay characteristic, e.g.
//   {"mode":"auto","on":false,"sched_version":3}
String status_json();

}  // namespace relay
