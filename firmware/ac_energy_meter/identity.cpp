#include "identity.h"
#include "config.h"
#include <esp_mac.h>

namespace identity {

static String s_device_id;
static String s_ble_name;

static void compute() {
  if (!s_device_id.isEmpty()) return;
  // Read the Wi-Fi STA MAC directly from eFuse instead of going through
  // WiFi.macAddress(). The Arduino-ESP32 3.x wrapper returns the buffer
  // unmodified if it's called before WiFi has ever been initialized,
  // leaving stack garbage that produces nonsense device IDs like
  // 'meter-001e00'.
  uint8_t mac[6] = {0};
  esp_read_mac(mac, ESP_MAC_WIFI_STA);
  char lower[16];
  snprintf(lower, sizeof(lower), "meter-%02x%02x%02x", mac[3], mac[4], mac[5]);
  char upper[16];
  snprintf(upper, sizeof(upper), "Meter-%02X%02X%02X", mac[3], mac[4], mac[5]);
  s_device_id = lower;
  s_ble_name = upper;
}

const String &device_id() {
  compute();
  return s_device_id;
}

const String &ble_name() {
  compute();
  return s_ble_name;
}

const char *fw_version() {
  return FW_VERSION;
}

}  // namespace identity
