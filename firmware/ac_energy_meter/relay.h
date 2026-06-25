#pragma once

#include <Arduino.h>

// Server-controlled relay output. The server pushes a schedule in each
// ingest.php response:
//   { "relay_version": <int>, "relay_schedule": [ {days:[0..6], on:"HH:MM", off:"HH:MM"}, ... ] }
// The schedule is cached in NVS so the relay keeps switching during a
// Wi-Fi outage. evaluate() is called every loop tick (~50 ms) and drives
// the GPIO based on the current local time + cached schedule. With no
// schedule cached the relay stays off.

namespace relay {

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

}  // namespace relay
