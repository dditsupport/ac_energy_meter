-- Migration: rename the `devices` table to `energy_devices`.
-- Safe on InnoDB: foreign keys in device_meta and energy_readings that
-- reference the old name are auto-updated to point at the new name.
-- Apply once via phpMyAdmin (SQL tab) or `mysql -u <user> -p <db> < this.sql`.
--
-- Idempotent: skips the rename if `devices` is already gone.

SET @must_rename := (
  SELECT COUNT(*) FROM information_schema.tables
   WHERE table_schema = DATABASE() AND table_name = 'devices'
);
SET @sql := IF(@must_rename = 1,
               'RENAME TABLE devices TO energy_devices',
               'DO 0 /* already renamed */');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
