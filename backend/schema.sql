-- AC Energy Meter — MySQL schema.
-- Apply once via phpMyAdmin (or `mysql -u <user> -p <db> < schema.sql`).
-- Charset: utf8mb4. Engine: InnoDB (for FK + transactions).

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- Drop in dependency-safe order if re-running. Comment these out for first install.
-- DROP TABLE IF EXISTS energy_readings;
-- DROP TABLE IF EXISTS ingest_log;
-- DROP TABLE IF EXISTS device_meta;
-- DROP TABLE IF EXISTS energy_devices;
-- DROP TABLE IF EXISTS users;

SET FOREIGN_KEY_CHECKS = 1;

-- ---------------- users ----------------
CREATE TABLE IF NOT EXISTS users (
  id              INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  username        VARCHAR(32)  NOT NULL UNIQUE,
  password_hash   VARCHAR(255) NOT NULL,
  email           VARCHAR(128) NULL,
  is_admin        TINYINT(1)   NOT NULL DEFAULT 0,
  created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_login_at   TIMESTAMP    NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- energy_devices ----------------
-- Auto-registered the first time an ingest POST arrives.
-- owner_user_id is set by an admin via the admin panel.
CREATE TABLE IF NOT EXISTS energy_devices (
  device_id       VARCHAR(32)  PRIMARY KEY,
  friendly_name   VARCHAR(64)  NOT NULL,
  location        VARCHAR(128) NULL,
  installed_at    DATE         NULL,
  capacity_kw     DECIMAL(5,2) NULL,
  notes           TEXT         NULL,
  owner_user_id   INT UNSIGNED NULL,
  first_seen_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (owner_user_id) REFERENCES users(id) ON DELETE SET NULL,
  KEY idx_owner   (owner_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- device_meta ----------------
CREATE TABLE IF NOT EXISTS device_meta (
  device_id        VARCHAR(32)  PRIMARY KEY,
  fw_version       VARCHAR(16)  NULL,
  last_sync_at     TIMESTAMP    NULL,
  last_seq         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  last_boot_id     INT UNSIGNED  NOT NULL DEFAULT 0,
  total_readings   BIGINT UNSIGNED NOT NULL DEFAULT 0,
  log_interval_sec INT UNSIGNED  NOT NULL DEFAULT 900,
  -- Last relay state the device reported on an ingest POST (live indicator).
  relay_on          TINYINT(1)  NULL,
  relay_mode        VARCHAR(8)  NULL,   -- 'auto' | 'on' | 'off'
  relay_reported_at TIMESTAMP   NULL,
  FOREIGN KEY (device_id) REFERENCES energy_devices(device_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- energy_readings ----------------
-- (device_id, seq) UNIQUE makes duplicate POSTs (firmware retry) a no-op.
CREATE TABLE IF NOT EXISTS energy_readings (
  id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  device_id       VARCHAR(32)   NOT NULL,
  seq             BIGINT UNSIGNED NOT NULL,
  wall_time       DATETIME       NOT NULL,
  time_confidence ENUM('exact','approx') NOT NULL DEFAULT 'exact',
  boot_id         INT UNSIGNED   NOT NULL,
  sec_since_boot  INT UNSIGNED   NOT NULL,
  voltage         DECIMAL(6,2)   NOT NULL,
  current_a       DECIMAL(8,3)   NOT NULL,
  power_w         DECIMAL(10,2)  NOT NULL,
  energy_wh       DECIMAL(14,2)  NOT NULL,
  power_factor    DECIMAL(4,3)   NOT NULL,
  frequency_hz    DECIMAL(5,2)   NULL,
  ingested_at     TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_device_seq    (device_id, seq),
  KEY idx_device_time          (device_id, wall_time),
  KEY idx_device_date_energy   (device_id, wall_time, energy_wh),
  FOREIGN KEY (device_id) REFERENCES energy_devices(device_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- ingest_log ----------------
-- Lightweight audit of every POST attempt. Trim manually if it grows.
CREATE TABLE IF NOT EXISTS ingest_log (
  id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  device_id       VARCHAR(32)  NULL,
  received_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  rows_in_payload INT          NULL,
  rows_inserted   INT          NULL,
  status          VARCHAR(32)  NOT NULL,
  client_ip       VARCHAR(45)  NULL,
  notes           TEXT         NULL,
  KEY idx_device_time (device_id, received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- device_relay_schedule ----------------
-- Optional per-device relay schedule. The firmware fetches this in every
-- ingest.php response and switches GPIO accordingly.
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

-- ---------------- First admin user ----------------
-- Do NOT create the admin user via schema.sql — bcrypt hashes need to be
-- generated with PHP's password_hash() on the host. After running this
-- schema, visit
--   https://<your-domain>/meter/bootstrap.php
-- in a browser. It runs ONLY if no admin user exists; you'll set the
-- initial admin username and password from there, then the script
-- refuses to do anything on subsequent visits.
