-- Migration: store the device-reported relay state on device_meta so the
-- admin devices table can show a live ON/OFF indicator. The firmware reports
-- relay_on / relay_mode on every ingest POST; ingest.php writes them here.
--
-- Idempotent: each ADD COLUMN is guarded so re-running is a no-op.
-- Apply once via phpMyAdmin (SQL tab) or
--   mysql -u <user> -p <db> < this.sql

-- relay_on
SET @c := (SELECT COUNT(*) FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'device_meta'
              AND column_name = 'relay_on');
SET @sql := IF(@c = 0,
    'ALTER TABLE device_meta ADD COLUMN relay_on TINYINT(1) NULL AFTER log_interval_sec',
    'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- relay_mode
SET @c := (SELECT COUNT(*) FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'device_meta'
              AND column_name = 'relay_mode');
SET @sql := IF(@c = 0,
    "ALTER TABLE device_meta ADD COLUMN relay_mode VARCHAR(8) NULL AFTER relay_on",
    'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- relay_reported_at
SET @c := (SELECT COUNT(*) FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'device_meta'
              AND column_name = 'relay_reported_at');
SET @sql := IF(@c = 0,
    'ALTER TABLE device_meta ADD COLUMN relay_reported_at TIMESTAMP NULL AFTER relay_mode',
    'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
