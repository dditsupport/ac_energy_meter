# Pinout — ESP32-WROOM-32D DevKit V1 (38-pin)

Orientation: USB connector at the **bottom**, ESP32 module facing you.

## Confirmed hardware

| Property | Value |
|---|---|
| Module | ESP-WROOM-32D (38-pin DevKit V1) |
| Chip | ESP32-D0WDQ5 |
| Silicon revision | 3 (latest stable) |
| Cores | 2 (Xtensa LX6) |
| CPU clock | 240 MHz |
| Flash | 4 MB @ 80 MHz |
| Free heap at boot | ~337 KB |

The MAC address (and therefore the firmware-derived `device_id` and BLE
advertising name `Meter-XXXXXX`) is per-board. Read it from the serial
boot output: the firmware prints `Device ID: meter-xxxxxx` right after
storage init. Alternatively, `esptool.py chip_id` reads it too.

## Definitive pin map

The **Silkscreen** column is the label printed on the DevKit board next
to the pin — that's what you actually look at when soldering. Labels
below match the 38-pin ESP-WROOM-32D DevKit V1.

| Peripheral pin | ESP32 GPIO | Silkscreen | Side |
|---|---|---|---|
| **PZEM-004T v3.0** | | | |
| TX | GPIO 16 | **16** | Right |
| RX | GPIO 17 | **17** | Right |
| 5V | 5V rail | **VIN** (or **5V**) | Bottom-left |
| GND | GND | **GND** | several positions |
| **SSD1306 OLED (SPI 7-pin)** | | | |
| VCC | 3V3 rail | **3V3** | Top-left |
| GND | GND | **GND** | several positions |
| D0 / SCK / CLK | GPIO 18 | **18** | Right |
| D1 / MOSI / SDA | GPIO 21 | **21** | Right |
| RES / RST | GPIO 19 | **19** | Right |
| DC | GPIO 4 | **4** | Right |
| CS | GPIO 5 | **5** | Right |
| **DS3231 RTC (I²C)** | | | |
| VCC | 3V3 rail | **3V3** | Top-left (share with OLED) |
| GND | GND | **GND** | several positions |
| SDA | GPIO 22 | **22** | Right |
| SCL | GPIO 23 | **23** | Right (near top) |
| SQW, 32K | — | — | leave disconnected |
| **Relay module (server-scheduled)** | | | |
| IN (control) | GPIO 26 | **26** | Left |
| VCC | 5V rail | **VIN** (or **5V**) | Bottom-left |
| GND | GND | **GND** | several positions |

All signal pins live on the **right column** of the board, so wiring stays
clean.

## Visual pin reference (board orientation: USB at bottom)

Layout matches the 38-pin ESP-WROOM-32D DevKit V1. Trust the silkscreen
label, not the position number — variants exist.

```
              Left column                   Right column
              ───────────                   ────────────
          1   3V3   ← OLED & DS3231 VCC     GND
          2   EN                            23    ← DS3231 SCL
          3   VP   (GPIO 36)                22    ← DS3231 SDA
          4   VN   (GPIO 39)                TX    (USB serial, GPIO 1)
          5   34                            RX    (USB serial, GPIO 3)
          6   35                            21    ← OLED MOSI / D1
          7   32                            GND
          8   33                            19    ← OLED RST
          9   25                            18    ← OLED SCK / D0
         10   26                            5     ← OLED CS
         11   27                            17    ← PZEM RX
         12   14                            16    ← PZEM TX
         13   12                            4     ← OLED DC
         14   GND                           0     (BOOT button — free)
         15   13                            2     (on-board LED — free)
         16   D2    (SD flash, unusable)    15
         17   D3    (SD flash, unusable)    D1    (SD flash, unusable)
         18   CMD   (SD flash, unusable)    D0    (SD flash, unusable)
         19   5V / VIN  ← HLK-PM01 input    CLK   (SD flash, unusable)
                              ┌─────────┐
                              │  USB-B  │
                              └─────────┘
```

Pins not labeled with `←` are unused by this project and free for future
expansion. The six `SD flash` pins along the bottom are connected to the
on-module SPI flash and cannot be used for general I/O.

## Power & ground

- The HLK-PM01 (mains → 5 V) feeds the ESP32 `VIN`.
- The PZEM logic side runs from the same 5 V rail (PZEM does NOT accept 3.3 V).
- The OLED VCC and DS3231 VCC both run from **3V3** (top-left pin).
- **Common ground is mandatory**: PZEM 5 V GND, OLED GND, DS3231 GND, and
  ESP32 GND must share a single rail. Without it, UART and I²C traffic is
  unreliable.

## UART / SPI / I²C assignments

- `UART2` (default ESP32 pins, GPIO 16/17) → PZEM.
- `VSPI` (default HW SPI: GPIO 18 SCK, 23 MOSI, 5 CS) → OLED. DC on GPIO 4,
  RST on GPIO 19.
- `Wire` (default ESP32 I²C: GPIO 21 SDA, GPIO 22 SCL) → DS3231 at 400 kHz.
  Most DS3231 breakout boards include on-board 4.7 kΩ pull-ups on SDA/SCL;
  add external pull-ups (4.7 kΩ to 3V3) if your module doesn't.

## Notes & strapping-pin safety

- **GPIO 2** is intentionally **not used** — it's a boot strapping pin (must
  be LOW or floating at power-on) and is wired to the on-board LED on most
  DevKit boards. Leaving it free preserves both the strap behavior and the
  LED for future status indication.
- **GPIO 0** (BOOT button) and **GPIO 12** are also not used (both are
  strapping pins with boot constraints).
- **GPIO 5** (OLED CS) is a strapping pin that must be HIGH at boot — SPI CS
  idles HIGH naturally, so this is safe.
- **GPIO 15** is reserved/unused (strapping pin).

## Free pins for future expansion

If you add a push-button, status LED, second sensor, etc., these are clean
choices that don't conflict with anything above:

- Outputs / general I/O: **GPIO 13, 14, 25, 27, 32, 33** (GPIO 26 drives the
  scheduled relay — see the pin map above)
- Input-only (sensors only, no output drive): **GPIO 34, 35, 36 (VP), 39 (VN)**
- On-board LED for status: **GPIO 2** (now free after RST move)
- BOOT button (already debounced on board): **GPIO 0**
