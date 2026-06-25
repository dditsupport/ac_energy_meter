<?php
// Device-facing ingest endpoint. Accepts two auth modes:
//   1. X-Device-Token header (the shared DEVICE_TOKEN constant). The firmware
//      uses this — it's the only secret a headless ESP32 can hold.
//   2. Cookie session + X-CSRF header. The Android app uses this when
//      relaying rows it pulled off a device over BLE, so the phone never
//      needs to know the global device token.
// Idempotent on (device_id, seq).

declare(strict_types=1);
require_once __DIR__ . '/_db.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    json_response(405, ['ok' => false, 'error' => 'method_not_allowed']);
}

// Try device-token first (firmware path); fall back to session+CSRF (app
// relay path). Either is sufficient; rejecting only if both fail.
$device_token_ok = hash_equals(DEVICE_TOKEN, (string)($_SERVER['HTTP_X_DEVICE_TOKEN'] ?? ''));
$session_user    = $device_token_ok ? null : current_user();
if (!$device_token_ok) {
    if (!$session_user) {
        json_response(401, ['ok' => false, 'error' => 'unauthorized']);
    }
    // CSRF token must match for state-changing session-authenticated calls.
    $sent_csrf = $_SERVER['HTTP_X_CSRF'] ?? '';
    if (!hash_equals($_SESSION['csrf'] ?? '', $sent_csrf)) {
        json_response(403, ['ok' => false, 'error' => 'bad_csrf']);
    }
}

$body = json_body();
if (!$body) {
    log_ingest(null, 0, 0, 'invalid_json', 'empty or unparseable body');
    json_response(400, ['ok' => false, 'error' => 'invalid_json']);
}

$device_id   = (string)($body['device_id']  ?? '');
$fw_version  = (string)($body['fw_version'] ?? '');
$sync_wall   = (string)($body['sync_wall_time'] ?? '');
$current_bid = (int)   ($body['current_boot_id'] ?? 0);
$current_up  = (int)   ($body['current_boot_uptime_sec'] ?? 0);
$boot_hist   =          $body['boot_history']  ?? [];
$readings    =          $body['readings']      ?? [];

// Device-reported relay state (optional) for the live admin indicator.
$relay_on    = array_key_exists('relay_on', $body) ? (int)(bool)$body['relay_on'] : null;
$relay_mode  = (string)($body['relay_mode'] ?? '');
$relay_mode  = in_array($relay_mode, ['auto', 'on', 'off'], true) ? $relay_mode : null;

if ($device_id === '' || $current_bid <= 0) {
    log_ingest($device_id, 0, 0, 'missing_fields', null);
    json_response(400, ['ok' => false, 'error' => 'missing_fields']);
}

$pdo = db();

// Session-authed ingest is allowed only for devices the user already owns
// or for unowned devices (first-time provisioning). Token-authed ingest
// (the firmware path) skips this check because the shared DEVICE_TOKEN
// implies physical possession.
if (!$device_token_ok && $session_user) {
    $st = $pdo->prepare('SELECT owner_user_id FROM energy_devices WHERE device_id = ?');
    $st->execute([$device_id]);
    $owner = $st->fetchColumn();
    if ($owner !== false && $owner !== null && (int)$owner !== (int)$session_user['id']) {
        log_ingest($device_id, count($readings), 0, 'forbidden_owner', null);
        json_response(403, ['ok' => false, 'error' => 'device_owned_by_other_user']);
    }
}

// Auto-register the device. owner_user_id stays NULL until an admin binds it.
$pdo->prepare(
    'INSERT IGNORE INTO energy_devices (device_id, friendly_name) VALUES (?, ?)'
)->execute([$device_id, $device_id]);

$pdo->prepare(
    'INSERT INTO device_meta (device_id, fw_version, last_sync_at)
     VALUES (?, ?, NOW())
     ON DUPLICATE KEY UPDATE fw_version = VALUES(fw_version), last_sync_at = NOW()'
)->execute([$device_id, $fw_version]);

// Best-effort: record the device-reported relay state for the live admin
// indicator. Guarded so a DB that hasn't run migration 003 (relay_* columns)
// still ingests readings normally.
if ($relay_on !== null || $relay_mode !== null) {
    try {
        $pdo->prepare(
            'UPDATE device_meta
                SET relay_on = ?, relay_mode = ?, relay_reported_at = NOW()
              WHERE device_id = ?'
        )->execute([$relay_on, $relay_mode, $device_id]);
    } catch (Throwable $e) {
        // relay_* columns not present yet — ignore.
    }
}

// ---------- Reconstruct wall times via the boot-chain algorithm ----------
// boot_start_offset_sec[B] = seconds before sync_wall_time at which boot B began
$offsets = [$current_bid => (float)$current_up];
$prev    = (float)$current_up;
// boot_history is ordered oldest-first; walk newest-first to chain backwards.
$hist_by_bid = [];
foreach ($boot_hist as $h) {
    $bid = (int)($h['boot_id'] ?? 0);
    $dur = (int)($h['duration_sec'] ?? 0);
    if ($bid > 0) $hist_by_bid[$bid] = $dur;
}
// Walk from (current_bid - 1) downwards through hist_by_bid keys present.
$keys = array_keys($hist_by_bid);
rsort($keys);
foreach ($keys as $bid) {
    if ($bid >= $current_bid) continue;
    $prev += (float)$hist_by_bid[$bid];
    $offsets[$bid] = $prev;
}

$sync_epoch = parse_iso8601_to_epoch($sync_wall);
if ($sync_epoch === null) {
    // Server-side fallback: use NOW(). Mark all rows from this batch as approx.
    $sync_epoch = time();
}

$inserted = 0;
$max_seq  = 0;
$pdo->beginTransaction();
try {
    $ins = $pdo->prepare(
        'INSERT INTO energy_readings
           (device_id, seq, wall_time, time_confidence, boot_id, sec_since_boot,
            voltage, current_a, power_w, energy_wh, power_factor, frequency_hz)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON DUPLICATE KEY UPDATE id = id'
    );
    foreach ($readings as $r) {
        $seq = (int)($r['seq'] ?? 0);
        $bid = (int)($r['boot_id'] ?? 0);
        $sec = (int)($r['sec'] ?? 0);
        if ($seq <= 0 || $bid <= 0 || !isset($offsets[$bid])) continue;

        $wt_epoch = $sync_epoch - (int)round($offsets[$bid]) + $sec;
        $wt_str   = date('Y-m-d H:i:s', $wt_epoch);
        $conf     = ($bid === $current_bid) ? 'exact' : 'approx';

        $ins->execute([
            $device_id,
            $seq,
            $wt_str,
            $conf,
            $bid,
            $sec,
            (float)($r['V']  ?? 0),
            (float)($r['I']  ?? 0),
            (float)($r['P']  ?? 0),
            (float)($r['Wh'] ?? 0),
            (float)($r['PF'] ?? 0),
            isset($r['Hz']) ? (float)$r['Hz'] : null,
        ]);
        if ($ins->rowCount() > 0) $inserted++;
        if ($seq > $max_seq) $max_seq = $seq;
    }

    $pdo->prepare(
        'UPDATE device_meta
            SET last_seq        = GREATEST(last_seq, ?),
                last_boot_id    = ?,
                total_readings  = total_readings + ?
          WHERE device_id = ?'
    )->execute([$max_seq, $current_bid, $inserted, $device_id]);

    $pdo->commit();
} catch (Throwable $e) {
    $pdo->rollBack();
    log_ingest($device_id, count($readings), 0, 'db_error', $e->getMessage());
    json_response(500, ['ok' => false, 'error' => 'server_error']);
}

log_ingest($device_id, count($readings), $inserted, 'ok', null);

// Effective log_interval_sec: device override -> global default -> 0 (= omit field)
$st = $pdo->prepare('SELECT log_interval_sec FROM device_meta WHERE device_id = ?');
$st->execute([$device_id]);
$dev_interval = (int)($st->fetchColumn() ?: 0);
$effective_interval = $dev_interval > 0 ? $dev_interval : DEFAULT_LOG_INTERVAL_SEC;

$resp = [
    'ok'              => true,
    'acked_up_to_seq' => $max_seq > 0 ? $max_seq : (int)($body['readings'] ? 0 : 0),
    'server_time'     => date('c'),
];
if ($effective_interval > 0) {
    $resp['log_interval_sec'] = $effective_interval;
}

// Attach the relay schedule (if any). Firmware uses 'relay_version' to skip
// reapplying when nothing has changed.
$st = $pdo->prepare(
    'SELECT schedule_json, version FROM device_relay_schedule WHERE device_id = ?'
);
$st->execute([$device_id]);
if ($srow = $st->fetch()) {
    $resp['relay_version']  = (int)$srow['version'];
    $resp['relay_schedule'] = json_decode($srow['schedule_json'], true) ?: [];
}

json_response(200, $resp);


/* ---------- helpers ---------- */
function parse_iso8601_to_epoch(string $s): ?int {
    if ($s === '') return null;
    try { return (new DateTimeImmutable($s))->getTimestamp(); }
    catch (Throwable $e) { return null; }
}

function log_ingest(?string $dev, int $in_count, int $inserted, string $status, ?string $notes): void {
    try {
        db()->prepare(
            'INSERT INTO ingest_log (device_id, rows_in_payload, rows_inserted, status, client_ip, notes)
             VALUES (?, ?, ?, ?, ?, ?)'
        )->execute([$dev, $in_count, $inserted, $status, client_ip(), $notes]);
    } catch (Throwable $e) { /* best-effort */ }
}
