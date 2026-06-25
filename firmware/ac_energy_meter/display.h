#pragma once

#include "shared_state.h"

namespace display {

void begin();

// Renders one frame using a snapshot of shared state.
// Caller is responsible for taking the snapshot under lock.
void render(const SharedState &s);

// Convenience: snapshot + render. Returns false if state lock could not be taken.
bool tick();

// Show a static boot splash before tasks start.
void splash(const char *line1, const char *line2);

}  // namespace display
