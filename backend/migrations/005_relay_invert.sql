-- Migration: per-device relay "invert" flag (load wired to the NC contact).
-- When set, the schedule and manual control are expressed in LOAD terms and
-- the firmware inverts the coil (coil de-energized = load on). Pushed to the
-- firmware as `relay_invert` in the ingest.php response.
--
-- Idempotent: guarded ADD COLUMN. Apply once via phpMyAdmin (SQL tab) or
--   mysql -u <user> -p <db> < this.sql

SET @c := (SELECT COUNT(*) FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'device_relay_schedule'
              AND column_name = 'invert');
SET @sql := IF(@c = 0,
    'ALTER TABLE device_relay_schedule ADD COLUMN invert TINYINT(1) NOT NULL DEFAULT 0 AFTER version',
    'DO 0');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
