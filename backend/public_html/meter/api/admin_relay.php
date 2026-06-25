<?php
// Admin endpoint for per-device relay schedules.
//   action=get   { device_id }                     -> {ok, schedule, version}
//   action=set   { device_id, schedule_json }      -> {ok, version}
//   action=clear { device_id }                     -> {ok}
//
// Validation: schedule_json must be a JSON array; each element must have
//   days   : array of ints 0..6
//   on/off : strings matching ^[0-2][0-9]:[0-5][0-9]$  (HH:MM, 24h)

declare(strict_types=1);
require_once __DIR__ . '/_db.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    json_response(405, ['ok' => false, 'error' => 'method_not_allowed']);
}
require_admin();
check_csrf();

$pdo    = db();
$action = (string)($_POST['action'] ?? '');
$dev    = (string)($_POST['device_id'] ?? '');

// Live relay-state poll for the admin devices table. No device_id needed —
// returns the last-reported state of every device. Guarded so a DB without
// migration 003 still responds (with empty states).
if ($action === 'states') {
    try {
        $rows = $pdo->query(
            'SELECT device_id, relay_on, relay_mode, relay_reported_at, log_interval_sec
               FROM device_meta'
        )->fetchAll();
    } catch (Throwable $e) {
        $rows = [];
    }
    $states = array_map(fn($r) => [
        'device_id'   => $r['device_id'],
        'relay_on'    => $r['relay_on'] === null ? null : (bool)(int)$r['relay_on'],
        'relay_mode'  => $r['relay_mode'],
        'reported_at' => $r['relay_reported_at'],
        'interval'    => (int)($r['log_interval_sec'] ?? 900),
    ], $rows);
    json_response(200, ['ok' => true, 'states' => $states]);
}

if ($dev === '') json_response(400, ['ok' => false, 'error' => 'bad_input']);

switch ($action) {

case 'get':
    $st = $pdo->prepare(
        'SELECT schedule_json, version FROM device_relay_schedule WHERE device_id = ?'
    );
    $st->execute([$dev]);
    $row = $st->fetch();
    json_response(200, [
        'ok'       => true,
        'schedule' => $row ? json_decode($row['schedule_json'], true) : [],
        'version'  => $row ? (int)$row['version'] : 0,
    ]);

case 'set':
    $json = (string)($_POST['schedule_json'] ?? '');
    $parsed = validate_schedule($json);  // throws on bad input
    $norm   = json_encode($parsed, JSON_UNESCAPED_SLASHES | JSON_THROW_ON_ERROR);

    // Ensure the device row exists (FK constraint).
    $exists = $pdo->prepare('SELECT 1 FROM energy_devices WHERE device_id = ?');
    $exists->execute([$dev]);
    if (!$exists->fetchColumn()) {
        json_response(404, ['ok' => false, 'error' => 'no_such_device']);
    }
    $pdo->prepare(
        'INSERT INTO device_relay_schedule (device_id, schedule_json, version)
              VALUES (?, ?, 1)
         ON DUPLICATE KEY UPDATE
              schedule_json = VALUES(schedule_json),
              version       = version + 1'
    )->execute([$dev, $norm]);

    $st = $pdo->prepare('SELECT version FROM device_relay_schedule WHERE device_id = ?');
    $st->execute([$dev]);
    json_response(200, ['ok' => true, 'version' => (int)$st->fetchColumn()]);

case 'clear':
    $pdo->prepare('DELETE FROM device_relay_schedule WHERE device_id = ?')->execute([$dev]);
    json_response(200, ['ok' => true]);

default:
    json_response(400, ['ok' => false, 'error' => 'unknown_action']);
}


/** Validate + normalise a schedule JSON string. */
function validate_schedule(string $raw): array {
    if ($raw === '') return [];
    try {
        $doc = json_decode($raw, true, 8, JSON_THROW_ON_ERROR);
    } catch (JsonException) {
        json_response(400, ['ok' => false, 'error' => 'bad_json']);
    }
    if (!is_array($doc)) {
        json_response(400, ['ok' => false, 'error' => 'schedule_not_array']);
    }
    if (count($doc) > 16) {
        json_response(400, ['ok' => false, 'error' => 'too_many_windows']);
    }
    $out = [];
    foreach ($doc as $i => $entry) {
        if (!is_array($entry)) bail("entry $i not an object");
        $days = $entry['days'] ?? null;
        $on   = $entry['on']   ?? null;
        $off  = $entry['off']  ?? null;
        if (!is_array($days) || !$days) bail("entry $i: days must be a non-empty array");
        $cleanDays = [];
        foreach ($days as $d) {
            if (!is_int($d) && !ctype_digit((string)$d)) bail("entry $i: day not int");
            $di = (int)$d;
            if ($di < 0 || $di > 6) bail("entry $i: day out of range");
            $cleanDays[$di] = true;
        }
        ksort($cleanDays);
        if (!is_string($on)  || !preg_match('/^([01]\d|2[0-3]):[0-5]\d$/', $on))
            bail("entry $i: bad 'on' time (HH:MM 24h)");
        if (!is_string($off) || !preg_match('/^([01]\d|2[0-3]):[0-5]\d$/', $off))
            bail("entry $i: bad 'off' time (HH:MM 24h)");
        $out[] = [
            'days' => array_keys($cleanDays),
            'on'   => $on,
            'off'  => $off,
        ];
    }
    return $out;
}

function bail(string $msg): never {
    json_response(400, ['ok' => false, 'error' => 'bad_schedule', 'detail' => $msg]);
}
