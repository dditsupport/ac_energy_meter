<?php
// Admin-only device management.
//   action=list           -> all devices + owner + meta
//   action=bind           -> assign owner_user_id to a device (or null to unbind)
//   action=rename         -> set friendly_name / location / capacity_kw / notes
//   action=set_interval   -> override device_meta.log_interval_sec (0 = use default)
//   action=delete         -> delete device + all its readings (cascades)

declare(strict_types=1);
require_once __DIR__ . '/_db.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    json_response(405, ['ok' => false, 'error' => 'method_not_allowed']);
}
require_admin();
check_csrf();

$pdo    = db();
$action = (string)($_POST['action'] ?? '');

switch ($action) {

case 'list':
    $rows = $pdo->query(
        'SELECT d.device_id, d.friendly_name, d.location, d.capacity_kw, d.notes,
                d.owner_user_id, u.username AS owner_username, d.first_seen_at,
                m.fw_version, m.last_sync_at, m.last_seq, m.last_boot_id,
                m.total_readings, m.log_interval_sec
           FROM energy_devices d
           LEFT JOIN users        u ON u.id = d.owner_user_id
           LEFT JOIN device_meta  m ON m.device_id = d.device_id
          ORDER BY d.friendly_name'
    )->fetchAll();
    json_response(200, ['ok' => true, 'devices' => $rows]);

case 'bind':
    $device_id = (string)($_POST['device_id'] ?? '');
    $user_id   = $_POST['user_id'] ?? '';
    $user_id   = ($user_id === '' || $user_id === '0') ? null : (int)$user_id;
    if ($device_id === '') json_response(400, ['ok' => false, 'error' => 'bad_input']);
    if ($user_id !== null) {
        $st = $pdo->prepare('SELECT 1 FROM users WHERE id = ?');
        $st->execute([$user_id]);
        if (!$st->fetchColumn()) json_response(404, ['ok' => false, 'error' => 'no_such_user']);
    }
    $pdo->prepare('UPDATE energy_devices SET owner_user_id = ? WHERE device_id = ?')
        ->execute([$user_id, $device_id]);
    json_response(200, ['ok' => true]);

case 'rename':
    $device_id    = (string)($_POST['device_id'] ?? '');
    $friendly     = trim((string)($_POST['friendly_name'] ?? ''));
    $location     = trim((string)($_POST['location'] ?? '')) ?: null;
    $capacity_kw  = $_POST['capacity_kw'] === '' || !isset($_POST['capacity_kw'])
                        ? null : (float)$_POST['capacity_kw'];
    $notes        = trim((string)($_POST['notes'] ?? '')) ?: null;
    if ($device_id === '' || $friendly === '') {
        json_response(400, ['ok' => false, 'error' => 'bad_input']);
    }
    $pdo->prepare(
        'UPDATE energy_devices SET friendly_name = ?, location = ?, capacity_kw = ?, notes = ? WHERE device_id = ?'
    )->execute([$friendly, $location, $capacity_kw, $notes, $device_id]);
    json_response(200, ['ok' => true]);

case 'set_interval':
    $device_id = (string)($_POST['device_id'] ?? '');
    $sec       = (int)($_POST['log_interval_sec'] ?? 0);
    if ($device_id === '') json_response(400, ['ok' => false, 'error' => 'bad_input']);
    if ($sec !== 0 && ($sec < 60 || $sec > 86400)) {
        json_response(400, ['ok' => false, 'error' => 'interval_out_of_range']);
    }
    $pdo->prepare(
        'INSERT INTO device_meta (device_id, log_interval_sec) VALUES (?, ?)
         ON DUPLICATE KEY UPDATE log_interval_sec = VALUES(log_interval_sec)'
    )->execute([$device_id, $sec ?: 900]);
    json_response(200, ['ok' => true]);

case 'delete':
    $device_id = (string)($_POST['device_id'] ?? '');
    if ($device_id === '') json_response(400, ['ok' => false, 'error' => 'bad_input']);
    $pdo->prepare('DELETE FROM energy_devices WHERE device_id = ?')->execute([$device_id]);
    json_response(200, ['ok' => true]);

default:
    json_response(400, ['ok' => false, 'error' => 'unknown_action']);
}
