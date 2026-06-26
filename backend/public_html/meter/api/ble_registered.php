<?php
// GET /meter/api/ble_registered.php?device_id=X
// PUBLIC (no auth). Returns whether a device_id is registered on the server.
//
// The Android app uses this to gate BLE access: an unregistered device is
// open (so new units can be provisioned), while a registered device requires
// the user to be logged in and to enter the correct PIN. Only a boolean is
// exposed — no PIN, owner, or readings.

declare(strict_types=1);
require_once __DIR__ . '/_db.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    json_response(405, ['ok' => false, 'error' => 'method_not_allowed']);
}

$device_id = (string)($_GET['device_id'] ?? '');
// Mirror the firmware/claim device_id charset; reject anything else.
if ($device_id === '' || !preg_match('/^[A-Za-z0-9_\-:.]{1,32}$/', $device_id)) {
    json_response(400, ['ok' => false, 'error' => 'bad_device_id']);
}

$st = db()->prepare('SELECT 1 FROM energy_devices WHERE device_id = ?');
$st->execute([$device_id]);
json_response(200, ['ok' => true, 'registered' => (bool)$st->fetchColumn()]);
