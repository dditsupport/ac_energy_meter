#include "log_serial.h"

#include <esp_log.h>
#include <esp_rom_sys.h>   // esp_rom_install_channel_putc()
#include <rom/ets_sys.h>   // ets_install_putc1 / ets_install_putc2
#include <stdarg.h>
#include <stdio.h>

namespace log_serial {

static SemaphoreHandle_t s_mtx = nullptr;

// vprintf hook for esp_log_write(). Acquires the same mutex our Serial.*
// macros use, so IDF-internal log lines and our Arduino-side prints can no
// longer interleave on the UART.
static int locked_vprintf(const char *fmt, va_list args) {
  if (s_mtx && xSemaphoreTake(s_mtx, pdMS_TO_TICKS(50)) == pdTRUE) {
    int n = vprintf(fmt, args);
    xSemaphoreGive(s_mtx);
    return n;
  }
  return vprintf(fmt, args);
}

// ROM-level putchar sink: silently discard. ets_printf() is used by parts
// of the WiFi PHY/driver for low-level diagnostics that bypass esp_log
// entirely (and therefore our vprintf hook). They were the last source of
// '���' garbage that showed up mid-line during WiFi (re)connects.
// Trade-off: panic-time ets_printf output is also silenced. Panic dumps
// from the regular panic handler still print via their own UART path.
static void noop_putc(char c) { (void)c; }

void init() {
  if (s_mtx == nullptr) {
    s_mtx = xSemaphoreCreateMutex();
  }
  // Drop the noisy IDF tags that fire on every Wi-Fi reconnect.
  esp_log_level_set("wifi",          ESP_LOG_WARN);
  esp_log_level_set("wifi_init",     ESP_LOG_WARN);
  esp_log_level_set("phy_init",      ESP_LOG_WARN);
  esp_log_level_set("dhcpc",         ESP_LOG_WARN);
  esp_log_level_set("lwip",          ESP_LOG_WARN);
  esp_log_level_set("NimBLE",        ESP_LOG_WARN);
  esp_log_level_set("HTTPClient",    ESP_LOG_WARN);
  // Anything that still goes through esp_log gets serialised by our mutex.
  esp_log_set_vprintf(&locked_vprintf);
  // Anything that goes through ets_printf / ROM putchar gets dropped.
  ets_install_putc1(&noop_putc);
  ets_install_putc2(nullptr);
}

Lock::Lock() : taken_(false) {
  // Allow logging before init() runs (e.g. very early setup). In that case
  // we just skip the mutex — the worst that happens is a few interleaved
  // lines before init() is called.
  if (s_mtx == nullptr) return;
  // Wait up to 50 ms; longer than that and we'd rather log out-of-order
  // than risk a deadlock with a paused task.
  if (xSemaphoreTake(s_mtx, pdMS_TO_TICKS(50)) == pdTRUE) {
    taken_ = true;
  }
}

Lock::~Lock() {
  if (taken_) xSemaphoreGive(s_mtx);
}

}  // namespace log_serial
