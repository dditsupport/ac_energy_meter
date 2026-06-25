#pragma once

#include <Arduino.h>

namespace health {

void begin();

// Register the calling task with the Task WDT.
void register_task();

// Feed the WDT for the current task.
void feed();

// Called early in setup. Returns true if the boot-loop guard tripped.
// When true, the firmware should skip Wi-Fi scanning and only run BLE
// diagnostic mode until the user power-cycles cleanly.
bool boot_loop_tripped();

}  // namespace health
