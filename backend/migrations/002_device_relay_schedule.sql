-- Migration: per-device relay schedule.
-- Idempotent: CREATE TABLE IF NOT EXISTS.
-- Apply once via phpMyAdmin (SQL tab) or
--   mysql -u <user> -p <db> < this.sql

CREATE TABLE IF NOT EXISTS device_relay_schedule (
  device_id     VARCHAR(32)     NOT NULL PRIMARY KEY,
  -- JSON array of windows: [{days:[0..6], on:"HH:MM", off:"HH:MM"}, ...]
  -- days: 0=Sun, 1=Mon, ..., 6=Sat. Multiple windows per device allowed.
  schedule_json TEXT            NOT NULL DEFAULT ('[]'),
  -- Monotonic version. Bumped on every update. Firmware uses it to detect
  -- whether the schedule has changed since the last poll.
  version       INT UNSIGNED    NOT NULL DEFAULT 1,
  updated_at    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
                                ON UPDATE CURRENT_TIMESTAMP,
  FOREIGN KEY (device_id) REFERENCES energy_devices(device_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
