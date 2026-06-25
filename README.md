# AC Energy Meter

Single-phase AC energy metering for any 230 V circuit or load. The device
measures voltage, current, real power, energy, power factor, and frequency
with a PZEM-004T v3.0, displays live metrics on a local OLED, buffers
15-minute readings to internal flash, and mirrors them to a MilesWeb-hosted
PHP/MySQL backend via two store-and-forward paths:

- **Wi-Fi** — when a known hotspot is in range, the ESP32 connects, NTP-syncs,
  POSTs buffered rows, and clears them on server ACK.
- **BLE** — a companion Android app pulls buffered rows over GATT and
  forwards them to the same MilesWeb endpoint over the phone's cellular
  data.

The ESP32 is the source of truth. Cloud and app are catch-up mirrors.

## Scheduled relay control

Each meter carries a server-controlled relay output (default GPIO 26). An
admin sets a weekly on/off **schedule** per device from the backend's device
settings (Admin → Devices → **Relay**). The schedule is a list of windows —
weekdays plus an on time and an off time — and is delivered to the firmware
in every `ingest.php` response. The firmware caches it in NVS and drives the
GPIO from its local clock, so the relay keeps switching on schedule even
during a Wi-Fi outage. No windows = relay stays off. See
[`docs/PINOUT.md`](docs/PINOUT.md) for wiring.

The **Android app** can also toggle the relay directly over BLE from the
device detail screen: **On** / **Off** force the relay regardless of the
schedule, and **Auto** returns it to schedule control. The override lives in
RAM, so a power-cycle returns the meter to its server schedule.

## Repository layout

```
firmware/ac_energy_meter/   ESP32 firmware (Arduino IDE sketch folder)
backend/                  MilesWeb PHP + MySQL (planned, not yet built)
android/                  Companion app (planned, not yet built)
docs/                     Wiring, provisioning, future hardware notes
tools/                    Bench-test helpers (fake_ingest.py)
```

## How fast does data reach the cloud?

The firmware aims for **seconds, not minutes**, when Wi-Fi is reachable.
Once a 15-minute row is appended to flash, the sampling task immediately
asks the connectivity task to run a Wi-Fi cycle; the next 1-second tick
picks it up, connects, NTP-syncs, POSTs, and (on ACK) truncates the row
from flash. The 2-minute periodic scan still runs as a fallback for when
the AP is out of range.

If neither the DS3231 nor NTP gave the device a wall clock, the firmware
still POSTs (`sync_wall_time` is empty); MilesWeb is expected to return a
`server_time` ISO 8601 string in the response. The firmware uses that to
seed its clock and the RTC, so subsequent rows carry real timestamps.

## Status

This branch delivers **firmware Stages 1–7** per the project spec:

| Stage | Scope | Status |
|---|---|---|
| 1 | OLED bring-up | ✅ |
| 2 | PZEM bring-up | ✅ |
| 3 | PZEM + OLED integration | ✅ |
| 4 | LittleFS + NVS + boot history | ✅ |
| 5 | Backend (MilesWeb) | ⏳ next session |
| 6 | Wi-Fi sync end-to-end | ✅ (testable against `tools/fake_ingest.py`) |
| 7 | BLE GATT service | ✅ (testable with nRF Connect) |
| 8 | Android companion app | ⏳ next session |
| 9 | Backend dashboard | ⏳ next session |
| 10 | AC install | hardware, out of code scope |

## Building the firmware

See [`docs/PROVISIONING.md`](docs/PROVISIONING.md) for the full step-by-step.
Quick path:

1. Arduino IDE 2.x with libraries: U8g2, PZEM-004T-v30 (mandulaj),
   ArduinoJson, NimBLE-Arduino.
2. Board: **ESP32 Dev Module**, partition scheme:
   **Default 4MB with spiffs (1.2MB APP/1.5MB SPIFFS)**.
3. Open `firmware/ac_energy_meter/ac_energy_meter.ino` and Upload.

## Bench-testing without a backend

Run the stub:

```bash
python3 tools/fake_ingest.py --port 8080
```

Point `INGEST_URL` in `firmware/ac_energy_meter/config.h` at
`http://<laptop-ip>:8080/ingest`, reflash, and the device will exercise its
full sync path against the stub.

## Architecture quick reference

- **Two FreeRTOS tasks** pinned to separate cores:
  - Core 0 — sampling: 1 Hz PZEM read + OLED render. Every 15 min appends
    one row to `/log.csv` and advances `last_seq`.
  - Core 1 — connectivity: BLE GATT always advertising. Every 2 min
    attempts the Wi-Fi cycle (scan → connect → NTP → POST → ACK truncate).
- **Single mutex** protects the small shared-state POD; readers snapshot
  a value copy and release before doing any I/O.
- **Monotonic-µs clock** for energy integration (`esp_timer_get_time()`);
  wall-clock is only used for log timestamps and midnight rollover.
- **DS3231 RTC** on I²C (GPIO 21/22) seeds wall-clock at boot so the OLED
  shows "Today: X kWh" immediately. NTP corrections are written back to
  the chip so it stays accurate across power loss. If the chip is absent
  or reports lost-power, NTP and BLE Set-Wall-Time still work as before.
- **NVS** uses two namespaces (`cfg` for Wi-Fi credentials, `state` for
  boot id / seq HWM / boot history) and a high-water-mark scheme that
  persists every 10 seqs to limit flash wear at the cost of small
  monotonic gaps across crashes.
- **`/log.csv`** is append-only with crash recovery on boot (deletes any
  leftover `/log.tmp`, validates and trims the last line). Sync uses
  snapshot-and-rewrite: capture max seq from RAM, send rows ≤ that, on
  ACK rewrite the file keeping only rows > that.
- **Buffer full**: when LittleFS free space drops below max(150 KB, 10 %),
  the firmware stops logging and surfaces "BUFFER FULL" on the OLED.

See [`docs/PROVISIONING.md`](docs/PROVISIONING.md) for security TODOs
(no BLE bonding, no cert pinning, no HMAC) deferred to future hardening.
