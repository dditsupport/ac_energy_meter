<?php
// GET /meter/api/devices.php
// Returns the devices the current user is allowed to see, plus their latest meta.
// Admins see every device with its owner.

declare(strict_types=1);
require_once __DIR__ . '/_db.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    json_response(405, ['ok' => false, 'error' => 'method_not_allowed']);
}
$user = require_login();
$pdo  = db();

// ble_pin arrives with migration 004; `$pin_col` degrades to NULL if absent so
// the endpoint keeps working on a pre-migration DB.
$admin = !empty($user['is_admin']);
$run = function (string $pin_col) use ($pdo, $admin, $user) {
    if ($admin) {
        $st = $pdo->query(
            "SELECT d.device_id, d.friendly_name, d.location, d.capacity_kw,
                    d.owner_user_id, u.username AS owner_username, $pin_col,
                    m.fw_version, m.last_sync_at, m.last_seq, m.last_boot_id,
                    m.total_readings, m.log_interval_sec
               FROM energy_devices d
               LEFT JOIN users        u ON u.id = d.owner_user_id
               LEFT JOIN device_meta  m ON m.device_id = d.device_id
              ORDER BY d.friendly_name"
        );
    } else {
        $st = $pdo->prepare(
            "SELECT d.device_id, d.friendly_name, d.location, d.capacity_kw, $pin_col,
                    m.fw_version, m.last_sync_at, m.last_seq, m.last_boot_id,
                    m.total_readings, m.log_interval_sec
               FROM energy_devices d
               LEFT JOIN device_meta m ON m.device_id = d.device_id
              WHERE d.owner_user_id = ?
              ORDER BY d.friendly_name"
        );
        $st->execute([$user['id']]);
    }
    return $st->fetchAll();
};
try {
    $devices = $run('d.ble_pin');
} catch (Throwable $e) {
    $devices = $run('NULL AS ble_pin');
}
json_response(200, ['ok' => true, 'devices' => $devices]);
