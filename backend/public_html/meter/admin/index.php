<?php
declare(strict_types=1);
require_once __DIR__ . '/../api/_db.php';
$user = require_admin();
$pdo  = db();

$stats = $pdo->query(
    "SELECT
       (SELECT COUNT(*) FROM users)            AS user_count,
       (SELECT COUNT(*) FROM energy_devices)          AS device_count,
       (SELECT COUNT(*) FROM energy_devices WHERE owner_user_id IS NULL) AS unbound,
       (SELECT COUNT(*) FROM energy_readings)   AS row_count,
       (SELECT MAX(received_at) FROM ingest_log WHERE status='ok') AS last_ingest"
)->fetch();
$recent = $pdo->query(
    "SELECT received_at, device_id, rows_in_payload, rows_inserted, status, notes
       FROM ingest_log ORDER BY received_at DESC LIMIT 25"
)->fetchAll();
?>
<!doctype html><html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>AC Energy Meter — admin</title>
<link rel="stylesheet" href="/meter/dashboard/assets/style.css?v=7">
</head><body>
<header class="topbar">
  <div class="brand">AC Energy Meter — admin</div>
  <div class="user">
    <a href="/meter/dashboard/">dashboard</a>
    &middot; <a href="/meter/admin/users.php">users</a>
    &middot; <a href="/meter/admin/devices.php">devices</a>
    &middot; <a href="/meter/api/logout.php">sign out</a>
  </div>
</header>
<main class="container">
  <section class="cards stats">
    <div class="stat"><span>Users</span><b><?= (int)$stats['user_count'] ?></b></div>
    <div class="stat"><span>Devices</span><b><?= (int)$stats['device_count'] ?></b></div>
    <div class="stat"><span>Unbound devices</span><b><?= (int)$stats['unbound'] ?></b></div>
    <div class="stat"><span>Total readings</span><b><?= number_format((int)$stats['row_count']) ?></b></div>
  </section>
  <section class="card">
    <h2>Recent ingest activity</h2>
    <table class="grid">
      <thead><tr><th>When</th><th>Device</th><th>Payload</th><th>Inserted</th><th>Status</th><th>Notes</th></tr></thead>
      <tbody>
      <?php foreach ($recent as $r): ?>
        <tr>
          <td><?= h($r['received_at']) ?></td>
          <td><?= h($r['device_id'] ?? '—') ?></td>
          <td><?= h((string)($r['rows_in_payload'] ?? '')) ?></td>
          <td><?= h((string)($r['rows_inserted'] ?? '')) ?></td>
          <td class="status status-<?= h($r['status']) ?>"><?= h($r['status']) ?></td>
          <td><?= h((string)($r['notes'] ?? '')) ?></td>
        </tr>
      <?php endforeach; ?>
      </tbody>
    </table>
  </section>
</main>
</body></html>
