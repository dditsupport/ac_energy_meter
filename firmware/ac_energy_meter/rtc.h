#pragma once

#include <Arduino.h>
#include <time.h>

namespace rtc {

// Initialize Wire on the configured I2C pins and probe the DS3231.
// Returns true if the chip responded; false if absent or stopped.
bool begin();

// True if a healthy DS3231 was detected at begin() and its oscillator is
// running (lost-power bit clear or already cleared by a prior write).
bool available();

// Read the current time from the DS3231 as a Unix epoch (UTC, assuming the
// RTC was previously written with UTC). Returns 0 on failure or if not
// available.
time_t read_epoch();

// Write a Unix epoch (UTC) into the DS3231. Returns true on success.
// Clears the OSF (oscillator stop flag) so future reads are considered valid.
bool write_epoch(time_t epoch);

// True if the DS3231 reports it lost power since the last write (OSF bit).
// Useful at boot to decide whether to trust read_epoch().
bool lost_power();

}  // namespace rtc
