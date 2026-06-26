-- Migration: per-device BLE access PIN on energy_devices.
-- The Android app caches authorised PINs at login and requires the correct
-- PIN before opening a registered device over BLE. The ESP32 never sees it.
--
-- Idempotent: the ADD COLUMN is guarded; the backfill only touches NULLs.
-- Apply once via phpMyAdmin (SQL tab) or
--   mysql -u <user> -p <db> < this.sql

-- ble_pin column
SET @c := (SELECT COUNT(*) FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'energy_devices'
              AND column_name = 'ble_pin');
SET @sql := IF(@c = 0,
    'ALTER TABLE energy_devices ADD COLUMN ble_pin VARCHAR(12) NULL AFTER owner_user_id',
    'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- Backfill a random 6-digit PIN for any device that doesn't have one yet.
UPDATE energy_devices
   SET ble_pin = LPAD(FLOOR(RAND() * 1000000), 6, '0')
 WHERE ble_pin IS NULL OR ble_pin = '';
