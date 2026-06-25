<?php
// GET /meter/api/relay_state.php?device_id=X
// Session-authed. Returns the device's last-reported relay state for the
// live dashboard indicator. Respects per-user device visibility. The state
// is whatever the firmware last reported on an ingest POST.

declare(strict_types=1);
require_once __DIR__ . '/_db.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    json_response(405, ['ok' => false, 'error' => 'method_not_allowed']);
}
$user = require_login();

$device_id = (string)($_GET['device_id'] ?? '');
if ($device_id === '' || !user_can_see_device($user, $device_id)) {
    json_response(403, ['ok' => false, 'error' => 'no_such_device']);
}

$pdo = db();
// Guarded: a DB without migration 003 (relay_* columns) returns "unknown".
try {
    $st = $pdo->prepare(
        'SELECT relay_on, relay_mode, relay_reported_at, log_interval_sec
           FROM device_meta WHERE device_id = ?'
    );
    $st->execute([$device_id]);
    $r = $st->fetch() ?: null;
} catch (Throwable $e) {
    $r = null;
}

json_response(200, [
    'ok'          => true,
    'on'          => ($r && $r['relay_on'] !== null) ? (bool)(int)$r['relay_on'] : null,
    'mode'        => $r['relay_mode'] ?? null,
    'reported_at' => $r['relay_reported_at'] ?? null,
    'interval'    => (int)($r['log_interval_sec'] ?? 900),
]);
