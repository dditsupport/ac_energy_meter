# Provisioning Guide

This document covers first-time flashing, Wi-Fi setup via BLE, and the
bench-test workflow for Stages 6–7.

## 1. Toolchain & libraries

Verified against:

- **Arduino IDE 2.3.10**
- **ESP32 by Espressif Systems** core **3.3.10** (Boards Manager URL:
  `https://espressif.github.io/arduino-esp32/package_esp32_index.json`).
  This series is built on ESP-IDF 5.x.
- Board: **ESP32 Dev Module**
- Partition Scheme: **No OTA (2MB APP/2MB SPIFFS)**
  *(The original spec called for "Default 4MB with spiffs (1.2MB
  APP/1.5MB SPIFFS)". The current binary — NimBLE 2.x + ArduinoJson 7 +
  RTC + Wi-Fi scan — runs ~1.5 MB and overflows the 1.2 MB APP slot.
  "No OTA 2MB/2MB" gives the app 2 MB and **increases** the LittleFS
  partition to 2 MB at the same time, so log capacity goes up too.)*
- CPU Frequency: **240 MHz (WiFi/BT)**
- Flash Size: **4 MB**

Install via Library Manager. Pin to at least the versions noted below —
NimBLE in particular has a hard 1.x → 2.x API break that this firmware
relies on.

| Library | Min version | Purpose |
|---|---|---|
| `U8g2` (olikraus) | 2.35.x | SSD1306 OLED |
| `PZEM-004T-v30` (mandulaj) | 1.1.x | PZEM read wrapper |
| `ArduinoJson` (bblanchon) | 7.x | JSON parsing/serialization |
| `NimBLE-Arduino` (h2zero) | **2.x** (required for core 3.x) | BLE GATT server |
| `RTClib` (Adafruit) | 2.1.x | DS3231 RTC |

If you previously had NimBLE-Arduino 1.x installed (paired with ESP32
core 2.x), upgrade it via Library Manager — the firmware uses the 2.x
`NimBLEConnInfo&` callback signatures and will not compile against
1.x.

LittleFS, WiFi, HTTPClient, WiFiClientSecure, and Preferences are built in.

## 2. Flash

1. Open `firmware/ac_energy_meter/ac_energy_meter.ino` in Arduino IDE.
2. Set board to **ESP32 Dev Module** and partition scheme as above.
3. Verify the `INGEST_URL` and `DEVICE_TOKEN` macros in `config.h`. The
   token committed in this repo is a placeholder generated at project
   bring-up — replace it with the production value before deploying.
4. Upload.

## 3. First boot

OLED shows "AC Energy Meter / booting...". After a few seconds you should
see live PZEM readings (zero if no load) and the device ID printed on
the serial monitor at 115200 baud.

Serial commands available at any time:

| Command | Effect |
|---|---|
| `INFO` | Print device id, firmware, last seq, unsynced row count |
| `DUMP` | Print full contents of `/log.csv` |
| `BOOTS` | Print current boot id and the boot history NVS buffer |
| `CLEAR` | Wipe `/log.csv` (does **not** clear NVS state) |
| `WIFI` | List saved Wi-Fi SSIDs |

## 4. Wi-Fi provisioning via BLE (nRF Connect)

The firmware stores **exactly one** Wi-Fi credential; writing a new one
replaces the previous one. The companion app (or nRF Connect) drives
provisioning via three characteristics:

| Operation | Characteristic | UUID | Payload |
|---|---|---|---|
| Trigger a Wi-Fi scan | Wi-Fi Config (write) | `41310027-c18e-4452-a50e-861e77cf2743` | `{"action":"scan"}` |
| Read scan results | Wi-Fi Scan (read or notify) | `d4346c1c-6e36-4a0f-a164-84cd396a4697` | JSON array `[{"s":"SSID","r":-65,"e":1}, ...]` |
| Save credential | Wi-Fi Config (write) | `41310027-c18e-4452-a50e-861e77cf2743` | `{"ssid":"...","password":"..."}` |
| Watch progress | Wi-Fi Status (read or notify) | `28c3fa43-a1b5-4e0e-a51c-a1e979609d28` | JSON `{"status":"...","ssid":"..."}` |
| Update backend host | Server Config (write) | `9478f8ff-cb2f-4447-8a2f-49791de6bc09` | `{"host":"https://aromen.biz"}` (path stays hardcoded in firmware) |
| Read current host | Device Info (read) | `56c4fe7d-1c7d-4042-9547-6170ec5c243c` | `ingest_host` and `ingest_path` fields in the JSON |

Recommended flow with nRF Connect:

1. Connect, discover services → custom 128-bit service.
2. **Subscribe** to notifications on Wi-Fi Status and Wi-Fi Scan.
3. Write `{"action":"scan"}` to Wi-Fi Config. Wi-Fi Status will push
   `{"status":"scanning"}` and Wi-Fi Scan will push the result JSON
   when the scan finishes (~3–5 s).
4. Pick an SSID from the scan list. Write the credential JSON
   (`{"ssid":"...","password":"..."}`) to Wi-Fi Config. Wi-Fi Status
   will push `{"status":"saved","ssid":"...","next":"connecting"}` and
   then progress through `connecting → connected → syncing → idle`.
5. No 2-minute wait — the firmware kicks off a Wi-Fi cycle the moment
   credentials are saved.

Scan result entries use short keys to keep notifications under the BLE
MTU: `s` = SSID, `r` = RSSI (negative dBm), `e` = 1 if encrypted else 0.
The list is capped at `MAX_SCAN_RESULTS = 12` strongest networks.

`Wi-Fi Status` strings emitted by the firmware:

| `status` | Meaning |
|---|---|
| `idle` | Wi-Fi radio is down |
| `scanning` | scan in progress (BLE-requested or periodic) |
| `connecting` | trying to join the saved SSID |
| `connected` | associated; about to NTP+POST |
| `syncing` | actively uploading buffered rows |
| `saved` | credential just written (will transition to `connecting` next) |
| `error` | with a `detail` field, e.g. `empty ssid`, `bad json`, `save failed` |

## 5. Wall-clock time sources

The firmware accepts time from four sources, in priority order:

1. **NTP** during any Wi-Fi sync cycle. The corrected time is written
   back to the DS3231 if it drifts more than `RTC_WRITEBACK_DRIFT_SEC`
   (default 2 s) from the chip's reading.
2. **DS3231** at boot. If the RTC chip is present and reports its
   oscillator is running (no lost-power flag), the firmware seeds the
   wall clock immediately so the OLED can show "Today: X kWh" from the
   first second.
3. **MilesWeb `server_time`** in the ingest response. If both the
   DS3231 and NTP failed, the device still POSTs (with empty
   `sync_wall_time`); the server's response carries a `server_time`
   ISO 8601 string that the firmware uses to seed the wall clock and
   write back to the RTC. Falls back to this automatically.
4. **BLE Set Wall Time** characteristic
   (`b90e068f-8856-4cba-a043-841081fbd1a1`). Accepts ISO 8601 strings
   like `2026-05-19T14:32:11+05:30` or `2026-05-19T09:02:11Z`. If the
   DS3231 is absent or reports lost-power, the phone time also seeds
   the RTC.

The Device Info characteristic exposes `rtc_ok` (true if the DS3231 is
present and healthy) and `wall_clock_known` (true after any source has
landed). On a fresh DS3231 (CR2032 battery just installed), the chip
will report lost-power until the first NTP or BLE writeback clears the
OSF bit — after that, power-cycling the ESP32 still preserves time.

## 6. Bench-testing the sync path (Stage 6)

1. On your laptop:

   ```bash
   python3 tools/fake_ingest.py --port 8080
   ```

   Note the laptop's IP on the same network the ESP32 will join.

2. Edit `INGEST_URL` in `config.h` to:

   ```
   http://<laptop-ip>:8080/ingest
   ```

   (Plain http while bench-testing — the stub doesn't speak TLS.) Reflash.

3. Provision the ESP32 with your laptop's hotspot SSID via BLE (step 4
   above). Within ~2 minutes the device should connect, NTP-sync, and
   POST any buffered rows to the laptop. The stub prints each row and
   acknowledges with the highest seq. Re-run `INFO` over serial — the
   unsynced count should drop to zero.

## 7. BLE data dump (Stage 7)

In nRF Connect:

1. Read **Device Info** to see `unsynced_count`.
2. Subscribe to notifications on **Data Stream**
   (`1199716e-692b-4d47-bd00-72792988364d`). You'll receive one or more
   notifications, each containing one or more CSV rows separated by `\n`,
   ending with the literal `"END\n"` terminator.
3. After your client (or hand-rolled app) has forwarded the rows to your
   server, write the highest acknowledged `seq` as a decimal-string ASCII
   value to **Sync ACK** (`a4b32253-c2e3-42e8-93c3-a008325540b6`). The
   firmware truncates `/log.csv` up to and including that seq.

## Open security TODOs

These are documented now and will be addressed in a later session:

- **No BLE bonding/pairing** in v1 — anyone in range can read buffered
  readings and write Wi-Fi credentials. Acceptable for a single-user
  bench device; not acceptable for any deployment.
- **`setInsecure()` on HTTPS** — the firmware accepts any server certificate
  presented for `INGEST_URL`. The `X-Device-Token` header is the only
  authentication. Cert pinning (PROGMEM root CA) is a TODO in
  `wifi_sync.cpp`.
- **No HMAC payload signing** — a hostile Wi-Fi network can capture the
  token and replay POSTs. The server's `(device_id, seq)` upsert key
  bounds the damage but doesn't prevent it.
