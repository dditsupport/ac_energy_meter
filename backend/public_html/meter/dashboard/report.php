<?php
declare(strict_types=1);
require_once __DIR__ . '/../api/_db.php';
$user = require_login();
$pdo  = db();

// Devices the user can see (same visibility rule as the dashboard).
if (!empty($user['is_admin'])) {
    $dev_rows = $pdo->query(
        'SELECT d.device_id, d.friendly_name, d.location
           FROM energy_devices d
          ORDER BY d.friendly_name'
    )->fetchAll();
} else {
    $st = $pdo->prepare(
        'SELECT d.device_id, d.friendly_name, d.location
           FROM energy_devices d
          WHERE d.owner_user_id = ?
          ORDER BY d.friendly_name'
    );
    $st->execute([$user['id']]);
    $dev_rows = $st->fetchAll();
}
$selected = $_GET['device_id'] ?? ($dev_rows[0]['device_id'] ?? '');

// "Today" and the current month, computed in IST so the default ranges line
// up with how readings are bucketed server-side.
$today_ist = date('Y-m-d');
$month_ist = date('Y-m');
?>
<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>AC Energy Meter — reports</title>
<link rel="stylesheet" href="/meter/dashboard/assets/style.css?v=7">
<script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.6/dist/chart.umd.min.js"></script>
<style>
  .report-controls .month-pick { display: none; }
  .report-controls .month-pick.show { display: flex; }
  .report-controls input[type=month] {
    padding: 0.4rem 0.6rem; border: 1px solid var(--border); border-radius: 6px;
  }
  #report-empty { color: var(--muted); padding: 2rem 0; text-align: center; }
</style>
</head><body>

<header class="topbar">
  <div class="brand">AC Energy Meter — reports</div>
  <div class="user">
    <a href="/meter/dashboard/">dashboard</a>
    <?php if (!empty($user['is_admin'])): ?>
      &middot; <a href="/meter/admin/">admin</a>
    <?php endif; ?>
    &middot; <a href="/meter/api/logout.php">sign out</a>
  </div>
</header>

<?php if (!$dev_rows): ?>
<main class="container">
  <div class="card empty">
    <p>You don't have any devices bound to your account yet.</p>
  </div>
</main>
<?php else: ?>
<main class="container">
  <div class="card controls report-controls">
    <label>Device
      <select id="device-select">
        <?php foreach ($dev_rows as $d): ?>
          <option value="<?= h($d['device_id']) ?>"
            <?= $d['device_id'] === $selected ? 'selected' : '' ?>>
            <?= h($d['friendly_name']) ?>
            <?php if ($d['location']) echo ' — ' . h($d['location']); ?>
          </option>
        <?php endforeach; ?>
      </select>
    </label>
    <div class="range-buttons">
      <button type="button" data-mode="weekly" class="on">Weekly</button>
      <button type="button" data-mode="monthly">Monthly</button>
    </div>
    <label class="month-pick">Month
      <input type="month" id="month-input" value="<?= h($month_ist) ?>"
             max="<?= h($month_ist) ?>">
    </label>
  </div>

  <section class="card">
    <h2 id="report-title">Weekly — hourly kWh by day</h2>
    <p class="muted" id="report-sub">Each line is one day; compare the hour-by-hour kWh across days.</p>
    <canvas id="report-chart" height="150"></canvas>
    <div id="report-empty" hidden>No readings in this range.</div>
  </section>
</main>

<script>
let DEVICE_ID = <?= json_encode($selected) ?>;
const TODAY_IST = <?= json_encode($today_ist) ?>;   // YYYY-MM-DD (IST)

const MONTHS = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
const pad = n => String(n).padStart(2, '0');

// Plain calendar-date math (UTC-based to dodge browser-TZ/DST drift). Inputs
// and outputs are "YYYY-MM-DD" strings interpreted as IST wall dates.
function addDays(ymd, n) {
  const [y, m, d] = ymd.split('-').map(Number);
  const dt = new Date(Date.UTC(y, m - 1, d) + n * 86400000);
  return dt.getUTCFullYear() + '-' + pad(dt.getUTCMonth() + 1) + '-' + pad(dt.getUTCDate());
}
function dayLabel(ymd) {           // "2026-06-24" -> "24-Jun"
  const [y, m, d] = ymd.split('-').map(Number);
  return pad(d) + '-' + MONTHS[m - 1];
}
function colorFor(i, n) {          // evenly-spaced hues, distinct per day
  return `hsl(${Math.round((i * 360) / Math.max(1, n))}, 65%, 50%)`;
}

let mode = 'weekly';
let chart = null;

function currentRange() {
  if (mode === 'weekly') {
    const from = addDays(TODAY_IST, -6) + 'T00:00:00';   // last 7 days incl. today
    const to   = TODAY_IST + 'T23:59:59';
    return { from, to, title: 'Weekly — hourly kWh by day',
             sub: `Last 7 days (incl. today): ${dayLabel(addDays(TODAY_IST,-6))} – ${dayLabel(TODAY_IST)}` };
  }
  const month = document.getElementById('month-input').value || TODAY_IST.slice(0, 7);
  const [y, m] = month.split('-').map(Number);
  const lastDay = new Date(Date.UTC(y, m, 0)).getUTCDate();
  const from = `${month}-01T00:00:00`;
  const to   = `${month}-${pad(lastDay)}T23:59:59`;
  return { from, to, title: `Monthly — hourly kWh by day`,
           sub: `${MONTHS[m - 1]} ${y} — one line per day` };
}

async function load() {
  const R = currentRange();
  document.getElementById('report-title').textContent = R.title;
  document.getElementById('report-sub').textContent   = R.sub;

  const url = `/meter/api/readings.php?device_id=${encodeURIComponent(DEVICE_ID)}` +
              `&aggregate=hourly&from=${encodeURIComponent(R.from)}&to=${encodeURIComponent(R.to)}`;
  let j;
  try {
    j = await (await fetch(url, { credentials: 'same-origin' })).json();
  } catch (e) { j = { ok: false, error: 'network' }; }
  if (!j.ok) { alert('Error: ' + (j.error || 'failed to load')); return; }

  // Reshape hourly points into one series per IST calendar day.
  // p.t is an ISO string with the +05:30 offset, e.g. 2026-06-24T10:00:00+05:30.
  // Slice the wall-clock fields directly so bucketing is IST regardless of the
  // viewer's browser time zone.
  const byDay = new Map();                       // "YYYY-MM-DD" -> {hour: kwh}
  for (const p of j.points) {
    const day  = p.t.slice(0, 10);
    const hour = parseInt(p.t.slice(11, 13), 10);
    if (Number.isNaN(hour)) continue;
    if (!byDay.has(day)) byDay.set(day, {});
    byDay.get(day)[hour] = (byDay.get(day)[hour] || 0) + (p.kwh || 0);
  }

  const days = [...byDay.keys()].sort();
  const empty = days.length === 0;
  document.getElementById('report-empty').hidden = !empty;
  document.getElementById('report-chart').style.display = empty ? 'none' : '';

  const datasets = days.map((day, i) => {
    const hours = byDay.get(day);
    const data = Object.keys(hours)
      .map(Number).sort((a, b) => a - b)
      .map(h => ({ x: h, y: Math.round(hours[h] * 1000) / 1000 }));
    const c = colorFor(i, days.length);
    return { label: dayLabel(day), data, borderColor: c, backgroundColor: c,
             tension: 0.25, borderWidth: 2, pointRadius: 2 };
  });

  if (chart) chart.destroy();
  chart = new Chart(document.getElementById('report-chart').getContext('2d'), {
    type: 'line',
    data: { datasets },
    options: {
      responsive: true, animation: false,
      interaction: { mode: 'nearest', intersect: false },
      scales: {
        x: {
          // Span the full day 00:00–24:00 so the final hour (23:00 bucket)
          // isn't clipped against the right edge.
          type: 'linear', min: 0, max: 24,
          title: { display: true, text: 'Hour of day (IST)' },
          ticks: { stepSize: 1, callback: v => pad(v) + ':00' },
        },
        y: { beginAtZero: true, title: { display: true, text: 'kWh' } },
      },
      plugins: {
        legend: { display: true, position: 'bottom' },
        tooltip: {
          callbacks: {
            title: items => items.length ? pad(items[0].parsed.x) + ':00' : '',
            label: ctx => `${ctx.dataset.label}: ${ctx.parsed.y} kWh`,
          },
        },
      },
    },
  });
}

document.querySelectorAll('.range-buttons button').forEach(b => {
  b.addEventListener('click', () => {
    document.querySelectorAll('.range-buttons button').forEach(x => x.classList.remove('on'));
    b.classList.add('on');
    mode = b.dataset.mode;
    document.querySelector('.month-pick').classList.toggle('show', mode === 'monthly');
    load();
  });
});
document.getElementById('device-select').addEventListener('change', e => {
  DEVICE_ID = e.target.value;
  load();
});
document.getElementById('month-input').addEventListener('change', () => {
  if (mode === 'monthly') load();
});

load();
</script>
<?php endif; ?>
</body></html>
