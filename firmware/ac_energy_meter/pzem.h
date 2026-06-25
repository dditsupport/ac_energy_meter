#pragma once

#include "shared_state.h"

namespace pzem {

void begin();

// Reads one sample. On success returns true and fills `out`.
// On Modbus failure, returns false; caller should not use `out`.
bool read(PzemSample &out);

// Classify a stream of samples and return the right PzemStatus.
// Pass `ok = true` on a successful read, `ok = false` on a failed read.
// Internally counts consecutive failures and tracks low-voltage duration.
PzemStatus classify(bool ok, const PzemSample &sample);

// Reset the cumulative energy counter on the PZEM (not used in normal operation).
bool reset_energy();

}  // namespace pzem
