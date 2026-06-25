<?php
// POST /meter/api/claim_device.php
// Lets a logged-in user register or claim a device by its device_id.
//
// Required:
//   device_id        e.g. "esp32-aabb-1234"
// Optional:
//   friendly_name    defaults to device_id on first claim
//   location, capacity_kw, notes
//
// Behaviour:
//   - If the device row doesn't exist, INSERT it with owner_user_id = current user.
//   - If the device exists and owner_user_id is NULL, claim it (set owner_user_id).
//   - If the device exists and owner_user_id == current user, update metadata only.
//   - If the device exists and owner_user_id is some OTHER user, refuse (409).
//     An admin can still re-bind via the admin panel.
//
// Auth: cookie session + X-CSRF header (token returned by /api/login.php).

declare(strict_types=1);
require_once __DIR__ . '/_db.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    json_response(405, ['ok' => false, 'error' => 'method_not_allowed']);
}

$user = require_login();
check_csrf();

$body = $_POST;
if (!$body) $body = json_body();

$device_id    = trim((string)($body['device_id'] ?? ''));
$friendly     = trim((string)($body['friendly_name'] ?? ''));
$location     = trim((string)($body['location'] ?? '')) ?: null;
$capacity_raw = $body['capacity_kw'] ?? '';
$capacity_kw  = ($capacity_raw === '' || $capacity_raw === null) ? null : (float)$capacity_raw;
$notes        = trim((string)($body['notes'] ?? '')) ?: null;

if ($device_id === '') {
    json_response(400, ['ok' => false, 'error' => 'missing_device_id']);
}
if (!preg_match('/^[A-Za-z0-9_\-:.]{1,32}$/', $device_id)) {
    // Mirror the firmware's device_id charset; refuse anything weird.
    json_response(400, ['ok' => false, 'error' => 'bad_device_id']);
}

$pdo = db();

// Lock the device row (if it exists) to avoid two concurrent claims racing.
$pdo->beginTransaction();
try {
    $st = $pdo->prepare(
        'SELECT device_id, friendly_name, owner_user_id
           FROM energy_devices
          WHERE device_id = ?
          FOR UPDATE'
    );
    $st->execute([$device_id]);
    $existing = $st->fetch();

    if (!$existing) {
        // Brand new device — register and claim in one shot.
        $effective_name = $friendly !== '' ? $friendly : $device_id;
        $pdo->prepare(
            'INSERT INTO energy_devices
                 (device_id, friendly_name, location, capacity_kw, notes, owner_user_id)
             VALUES (?, ?, ?, ?, ?, ?)'
        )->execute([$device_id, $effective_name, $location, $capacity_kw, $notes, (int)$user['id']]);
        $created = true;
    } else {
        $owner = $existing['owner_user_id'];
        if ($owner !== null && (int)$owner !== (int)$user['id']) {
            $pdo->rollBack();
            json_response(409, ['ok' => false, 'error' => 'owned_by_other_user']);
        }
        $effective_name = $friendly !== '' ? $friendly : $existing['friendly_name'];
        $pdo->prepare(
            'UPDATE energy_devices
                SET friendly_name = ?,
                    location      = COALESCE(?, location),
                    capacity_kw   = COALESCE(?, capacity_kw),
                    notes         = COALESCE(?, notes),
                    owner_user_id = ?
              WHERE device_id = ?'
        )->execute([$effective_name, $location, $capacity_kw, $notes, (int)$user['id'], $device_id]);
        $created = false;
    }

    $pdo->commit();
} catch (Throwable $e) {
    $pdo->rollBack();
    json_response(500, ['ok' => false, 'error' => 'server_error']);
}

json_response(200, [
    'ok'            => true,
    'device_id'     => $device_id,
    'friendly_name' => $effective_name,
    'created'       => $created,
    'owner_user_id' => (int)$user['id'],
]);
