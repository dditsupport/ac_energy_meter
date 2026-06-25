#pragma once

#include <Arduino.h>
#include <time.h>

namespace time_source {

void begin();

// Monotonic microseconds since boot. Never jumps.
uint64_t monotonic_us();

// Wall-clock seconds since Unix epoch. 0 if wall clock not known.
time_t wall_time();
bool wall_clock_known();

// Set wall clock from a Unix timestamp (e.g. parsed from BLE write or NTP).
// Returns true if accepted.
bool set_wall_clock(time_t epoch);

// Parse ISO 8601 like "2026-05-19T14:32:11+05:30" or "...Z".
// Returns 0 on failure.
time_t parse_iso8601(const char *s);

// Format current wall time as ISO 8601 with offset, or empty string if unknown.
String iso8601_now();

// Local-time day number (days since epoch in TZ_INFO). Used for midnight detection.
// Returns 0 if wall clock unknown.
uint32_t local_day_number();

}  // namespace time_source
