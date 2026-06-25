<?php
// GET /meter/api/readings.php?device_id=X&from=ISO&to=ISO&aggregate=raw|hourly|daily|monthly
// Auth: session (browser/app). Returns JSON.
//
// aggregate=daily / monthly compute generated kWh as MAX(energy_wh)-MIN(energy_wh)
// per bucket — works because the PZEM Wh counter is monotonically increasing
// across resets (and the firmware logs PZEM resets if they happen).

declare(strict_types=1);
require_once __DIR__ . '/_db.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    json_response(405, ['ok' => false, 'error' => 'method_not_allowed']);
}
$user = require_login();

$device_id = (string)($_GET['device_id'] ?? '');
$aggregate = (string)($_GET['aggregate'] ?? 'raw');
$from      = (string)($_GET['from'] ?? '');
$to        = (string)($_GET['to']   ?? '');

if ($device_id === '' || !user_can_see_device($user, $device_id)) {
    json_response(403, ['ok' => false, 'error' => 'no_such_device']);
}

if (!in_array($aggregate, ['raw', 'hourly', 'daily', 'monthly'], true)) {
    json_response(400, ['ok' => false, 'error' => 'bad_aggregate']);
}

[$from_str, $to_str] = resolve_range($aggregate, $from, $to);

$pdo  = db();
$meta = $pdo->prepare('SELECT friendly_name, location, capacity_kw FROM energy_devices WHERE device_id = ?');
$meta->execute([$device_id]);
$dev  = $meta->fetch() ?: [];

$points = match ($aggregate) {
    'raw'     => fetch_raw($device_id, $from_str, $to_str),
    'hourly'  => fetch_bucketed($device_id, $from_str, $to_str, '%Y-%m-%d %H:00:00'),
    'daily'   => fetch_bucketed($device_id, $from_str, $to_str, '%Y-%m-%d 00:00:00'),
    'monthly' => fetch_bucketed($device_id, $from_str, $to_str, '%Y-%m-01 00:00:00'),
};

json_response(200, [
    'ok'            => true,
    'device_id'     => $device_id,
    'friendly_name' => $dev['friendly_name'] ?? $device_id,
    'capacity_kw'   => $dev['capacity_kw'] ?? null,
    'from'          => $from_str,
    'to'            => $to_str,
    'aggregate'     => $aggregate,
    'points'        => $points,
]);


/* ---------- helpers ---------- */
function resolve_range(string $agg, string $from, string $to): array {
    $tz = new DateTimeZone(APP_TIMEZONE);
    $now = new DateTimeImmutable('now', $tz);
    $to_dt   = $to   !== '' ? new DateTimeImmutable($to,   $tz) : $now;
    $from_dt = $from !== '' ? new DateTimeImmutable($from, $tz) : $to_dt->modify('-1 day');

    // Sensible defaults per aggregate
    if ($from === '') {
        $from_dt = match ($agg) {
            'raw'     => $to_dt->modify('-1 day'),
            'hourly'  => $to_dt->modify('-7 days'),
            'daily'   => $to_dt->modify('-30 days'),
            'monthly' => $to_dt->modify('-12 months'),
        };
    }
    return [
        $from_dt->format('Y-m-d H:i:s'),
        $to_dt->format('Y-m-d H:i:s'),
    ];
}

function fetch_raw(string $device, string $from, string $to): array {
    $st = db()->prepare(
        'SELECT wall_time, voltage, current_a, power_w, energy_wh, power_factor,
                frequency_hz, time_confidence
           FROM energy_readings
          WHERE device_id = ? AND wall_time BETWEEN ? AND ?
          ORDER BY wall_time ASC
          LIMIT 5000'
    );
    $st->execute([$device, $from, $to]);
    return array_map(fn($r) => [
        't'    => format_iso($r['wall_time']),
        'V'    => (float)$r['voltage'],
        'I'    => (float)$r['current_a'],
        'P'    => (float)$r['power_w'],
        'Wh'   => (float)$r['energy_wh'],
        'PF'   => (float)$r['power_factor'],
        'Hz'   => $r['frequency_hz'] !== null ? (float)$r['frequency_hz'] : null,
        'conf' => $r['time_confidence'],
    ], $st->fetchAll());
}

function fetch_bucketed(string $device, string $from, string $to, string $fmt): array {
    // Per-bucket: max-min of PZEM cumulative Wh = energy generated in bucket.
    // Plus avg/peak power for context.
    $st = db()->prepare(
        "SELECT DATE_FORMAT(wall_time, ?) AS bucket,
                MIN(wall_time)      AS bucket_start,
                MAX(wall_time)      AS bucket_end,
                MIN(energy_wh)      AS wh_min,
                MAX(energy_wh)      AS wh_max,
                AVG(power_w)        AS p_avg,
                MAX(power_w)        AS p_peak,
                AVG(voltage)        AS v_avg,
                COUNT(*)            AS samples,
                SUM(time_confidence='approx') AS approx_count
           FROM energy_readings
          WHERE device_id = ? AND wall_time BETWEEN ? AND ?
          GROUP BY bucket
          ORDER BY bucket ASC
          LIMIT 5000"
    );
    $st->execute([$fmt, $device, $from, $to]);
    return array_map(function ($r) {
        $kwh = max(0.0, ((float)$r['wh_max'] - (float)$r['wh_min']) / 1000.0);
        return [
            't'        => format_iso($r['bucket']),
            't_end'    => format_iso($r['bucket_end']),
            'kwh'      => round($kwh, 3),
            'P_avg'    => round((float)$r['p_avg'], 1),
            'P_peak'   => round((float)$r['p_peak'], 1),
            'V_avg'    => round((float)$r['v_avg'], 1),
            'samples'  => (int)$r['samples'],
            'approx'   => (int)$r['approx_count'] > 0,
        ];
    }, $st->fetchAll());
}

function format_iso(string $datetime): string {
    return (new DateTimeImmutable($datetime, new DateTimeZone(APP_TIMEZONE)))
        ->format('c');
}
