<?php
declare(strict_types=1);
require_once __DIR__ . '/../api/_db.php';
$user = require_login();
$pdo  = db();

// Devices the user can see
if (!empty($user['is_admin'])) {
    $dev_rows = $pdo->query(
        'SELECT d.device_id, d.friendly_name, d.location, d.capacity_kw,
                m.last_sync_at
           FROM energy_devices d
           LEFT JOIN device_meta m ON m.device_id = d.device_id
          ORDER BY d.friendly_name'
    )->fetchAll();
} else {
    $st = $pdo->prepare(
        'SELECT d.device_id, d.friendly_name, d.location, d.capacity_kw,
                m.last_sync_at
           FROM energy_devices d
           LEFT JOIN device_meta m ON m.device_id = d.device_id
          WHERE d.owner_user_id = ?
          ORDER BY d.friendly_name'
    );
    $st->execute([$user['id']]);
    $dev_rows = $st->fetchAll();
}
$selected = $_GET['device_id'] ?? ($dev_rows[0]['device_id'] ?? '');
$selected_meta = null;
foreach ($dev_rows as $d) {
    if ($d['device_id'] === $selected) { $selected_meta = $d; break; }
}
?>
<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>AC Energy Meter — dashboard</title>
<link rel="stylesheet" href="/meter/dashboard/assets/style.css?v=7">
<script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.6/dist/chart.umd.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/chartjs-adapter-date-fns@3.0.0/dist/chartjs-adapter-date-fns.bundle.min.js"></script>
</head><body>

<header class="topbar">
  <div class="brand">AC Energy Meter</div>
  <div class="user">
    Signed in as <b><?= h($user['username']) ?></b>
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
    <?php if (!empty($user['is_admin'])): ?>
      <p>Go to <a href="/meter/admin/">Admin</a> &rarr; Devices to bind one.</p>
    <?php else: ?>
      <p>Ask an administrator to bind your device to this account.</p>
    <?php endif; ?>
  </div>
</main>
<?php else: ?>
<main class="container">
  <form class="card controls" method="get">
    <label>Device
      <select name="device_id" onchange="this.form.submit()">
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
      <button type="button" data-range="today">Today</button>
      <button type="button" data-range="24h">24 h</button>
      <button type="button" data-range="7d">7 days</button>
      <button type="button" data-range="30d">30 days</button>
      <button type="button" data-range="12m">12 months</button>
    </div>
    <?php if ($selected_meta): ?>
      <span class="last-sync">
        Last sync:
        <?php if (!empty($selected_meta['last_sync_at'])): ?>
          <b><?= h($selected_meta['last_sync_at']) ?></b>
          <span class="rel" data-ts="<?= h($selected_meta['last_sync_at']) ?>"></span>
        <?php else: ?>
          <b>never</b>
        <?php endif; ?>
      </span>
    <?php endif; ?>
  </form>

  <section class="cards stats">
    <div class="stat"><span>Current</span>     <div class="stat-val"><b id="stat-now">—</b><i>W</i></div></div>
    <div class="stat"><span>Today</span>       <div class="stat-val"><b id="stat-today">—</b><i>kWh</i></div></div>
    <div class="stat"><span>Peak</span>        <div class="stat-val"><b id="stat-peak">—</b><i>W</i></div></div>
    <div class="stat"><span>Period total</span><div class="stat-val"><b id="stat-total">—</b><i>kWh</i></div></div>
  </section>

  <section class="card">
    <h2 id="chart-title">Energy</h2>
    <canvas id="chart-energy" height="120"></canvas>
  </section>

  <section class="card">
    <h2>Power</h2>
    <canvas id="chart-power" height="120"></canvas>
  </section>
</main>

<script>
const DEVICE_ID = <?= json_encode($selected) ?>;
// Server timestamps are in APP_TIMEZONE (IST). Anchor parsing to that offset
// so "X ago" is correct regardless of the viewer's browser time zone.
const APP_TZ_OFFSET = <?= json_encode(app_tz_offset()) ?>;

const RANGES = {
  today: {
    aggregate: 'hourly', from: () => startOfToday(),
    label: "Today's energy (per hour)", energyLabel: 'kWh / hour',
    // Clamp the X axis to the daylight window so the shape of the day
    // is consistent and "nothing yet" is obvious.
    xMin: () => hourOfToday(7), xMax: () => hourOfToday(19),
    xUnit: 'hour',
  },
  '24h': { aggregate: 'hourly', from: () => hoursAgo(24), label: 'Last 24 hours',           energyLabel: 'kWh / hour', xUnit: 'hour'  },
  '7d':  { aggregate: 'daily',  from: () => daysAgo(7),   label: 'Last 7 days',              energyLabel: 'kWh / day',  xUnit: 'day'   },
  '30d': { aggregate: 'daily',  from: () => daysAgo(30),  label: 'Last 30 days',             energyLabel: 'kWh / day',  xUnit: 'day'   },
  '12m': { aggregate: 'monthly',from: () => monthsAgo(12),label: 'Last 12 months',           energyLabel: 'kWh / month',xUnit: 'month' },
};

function startOfToday(){ const d=new Date(); d.setHours(0,0,0,0); return d; }
function hourOfToday(h){ const d=new Date(); d.setHours(h,0,0,0); return d; }
function hoursAgo(h){ return new Date(Date.now() - h*3600e3); }
function daysAgo(d){ return new Date(Date.now() - d*86400e3); }
function monthsAgo(m){ const d=new Date(); d.setMonth(d.getMonth()-m); return d; }
function isoLocal(d){
  const pad=n=>String(n).padStart(2,'0');
  return d.getFullYear()+'-'+pad(d.getMonth()+1)+'-'+pad(d.getDate())+'T'+
         pad(d.getHours())+':'+pad(d.getMinutes())+':'+pad(d.getSeconds());
}

let energyChart, powerChart;
function makeChart(canvasId, type, datasets, yLabel, xOpts){
  const ctx = document.getElementById(canvasId).getContext('2d');
  const x = {
    type: 'time',
    time: { tooltipFormat: 'PPp', unit: xOpts.unit || undefined },
  };
  if (xOpts.min) x.min = xOpts.min.getTime();
  if (xOpts.max) x.max = xOpts.max.getTime();
  return new Chart(ctx, {
    type, data: { datasets },
    options: {
      responsive: true, animation: false,
      parsing: { xAxisKey: 't', yAxisKey: 'y' },
      scales: {
        x,
        y: { beginAtZero: true, title: { display: true, text: yLabel } },
      },
      plugins: { legend: { display: false } },
    },
  });
}

async function loadRange(rangeKey){
  const R = RANGES[rangeKey];
  document.getElementById('chart-title').textContent = R.label;
  const from = isoLocal(R.from());
  const url = `/meter/api/readings.php?device_id=${encodeURIComponent(DEVICE_ID)}` +
              `&aggregate=${R.aggregate}&from=${encodeURIComponent(from)}`;
  const res = await fetch(url, { credentials: 'same-origin' });
  const j   = await res.json();
  if (!j.ok) { alert('Error: ' + j.error); return; }

  const energyPoints = j.points.map(p => ({ t: p.t, y: p.kwh }));
  const powerPoints  = j.points.map(p => ({ t: p.t, y: p.P_avg }));

  if (energyChart) energyChart.destroy();
  if (powerChart)  powerChart.destroy();
  const xOpts = {
    unit: R.xUnit,
    min:  R.xMin ? R.xMin() : null,
    max:  R.xMax ? R.xMax() : null,
  };
  energyChart = makeChart('chart-energy', 'bar', [{
    label: R.energyLabel, data: energyPoints, backgroundColor: 'rgba(31,110,42,0.7)',
  }], R.energyLabel, xOpts);
  powerChart = makeChart('chart-power', 'line', [{
    label: 'Avg power (W)', data: powerPoints, borderColor: '#c97a1a', tension: 0.25,
  }], 'W', xOpts);

  // Stats
  const periodTotal = energyPoints.reduce((a, p) => a + (p.y || 0), 0);
  const peakP = powerPoints.reduce((m, p) => Math.max(m, p.y || 0), 0);
  document.getElementById('stat-total').textContent = periodTotal.toFixed(2);
  document.getElementById('stat-peak').textContent  = peakP.toFixed(0);

  // "Today" + "Current" come from a raw query of the last hour
  loadLive();
}

async function loadLive(){
  const from = isoLocal(hoursAgo(1));
  const url = `/meter/api/readings.php?device_id=${encodeURIComponent(DEVICE_ID)}&aggregate=raw&from=${encodeURIComponent(from)}`;
  let now = null;
  try {
    const res = await fetch(url, { credentials: 'same-origin' });
    const j   = await res.json();
    if (j.ok && j.points.length) {
      const p = j.points[j.points.length-1];
      if (typeof p.P === 'number') now = p.P;
    }
  } catch (e) { /* network/parse error — fall through to dash */ }
  document.getElementById('stat-now').textContent =
    now === null ? '—' : now.toFixed(0);

  // Today kWh via the daily bucket
  let today_kwh = null;
  try {
    const today = isoLocal(startOfToday());
    const url2 = `/meter/api/readings.php?device_id=${encodeURIComponent(DEVICE_ID)}&aggregate=daily&from=${encodeURIComponent(today)}`;
    const r2 = await (await fetch(url2, { credentials: 'same-origin' })).json();
    if (r2.ok && r2.points.length && typeof r2.points[0].kwh === 'number') {
      today_kwh = r2.points[0].kwh;
    } else if (r2.ok) {
      today_kwh = 0;
    }
  } catch (e) { /* fall through */ }
  document.getElementById('stat-today').textContent =
    today_kwh === null ? '—' : today_kwh.toFixed(2);
}

document.querySelectorAll('.range-buttons button').forEach(b => {
  b.addEventListener('click', () => {
    document.querySelectorAll('.range-buttons button').forEach(x => x.classList.remove('on'));
    b.classList.add('on');
    loadRange(b.dataset.range);
  });
});
// initial load: today
document.querySelector('.range-buttons button[data-range="today"]').click();

// "Last sync" relative time. Server timestamp is in APP_TIMEZONE (IST);
// append that offset so the instant is correct in any viewer's browser.
(function annotateLastSync(){
  const el = document.querySelector('.last-sync .rel');
  if (!el) return;
  const ts = el.dataset.ts;
  if (!ts) return;
  const d = new Date(ts.replace(' ', 'T') + APP_TZ_OFFSET);
  if (isNaN(d.getTime())) return;
  const tick = () => {
    const secs = Math.max(0, Math.round((Date.now() - d.getTime()) / 1000));
    let s;
    if      (secs < 60)        s = `${secs}s ago`;
    else if (secs < 3600)      s = `${Math.round(secs/60)} min ago`;
    else if (secs < 86400)     s = `${Math.round(secs/3600)} h ago`;
    else                       s = `${Math.round(secs/86400)} d ago`;
    el.textContent = ` (${s})`;
  };
  tick();
  setInterval(tick, 30_000);
})();
</script>
<?php endif; ?>

</body></html>
