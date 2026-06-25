<?php
declare(strict_types=1);
require_once __DIR__ . '/../api/_db.php';
require_admin();
$pdo = db();

// The relay_* columns arrive with migration 003; fall back gracefully if the
// DB hasn't been migrated yet so the page still renders.
$base_cols  = 'd.device_id, d.friendly_name, d.location, d.capacity_kw,
               d.owner_user_id, u.username AS owner_username, d.first_seen_at,
               m.fw_version, m.last_sync_at, m.last_seq, m.last_boot_id,
               m.total_readings, m.log_interval_sec';
$relay_cols = ', m.relay_on, m.relay_mode, m.relay_reported_at';
$from       = ' FROM energy_devices d
                LEFT JOIN users        u ON u.id = d.owner_user_id
                LEFT JOIN device_meta  m ON m.device_id = d.device_id
               ORDER BY d.friendly_name';
try {
    $devices = $pdo->query("SELECT $base_cols $relay_cols $from")->fetchAll();
} catch (Throwable $e) {
    $devices = $pdo->query("SELECT $base_cols $from")->fetchAll();
}

$users = $pdo->query('SELECT id, username FROM users ORDER BY username')->fetchAll();
?>
<!doctype html><html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>AC Energy Meter — devices</title>
<link rel="stylesheet" href="/meter/dashboard/assets/style.css?v=7">
<style>
  /* Per-column sizing for the devices admin grid. The table can be wider than
     the viewport — parent .card.scroll-x handles horizontal overflow. */
  table.devices            { table-layout: auto; min-width: 920px; }
  table.devices td input,
  table.devices td select  { width: 100%; box-sizing: border-box; }
  table.devices .col-id    { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
                             font-size: 0.82rem; white-space: nowrap; }
  /* Min-widths so user-typed text isn't truncated mid-word. */
  table.devices input.name     { min-width: 9rem; }
  table.devices input.location { min-width: 8rem; }
  table.devices select.owner   { min-width: 8rem; }
  table.devices .col-cap   input { width: 5rem; min-width: 5rem; }
  table.devices .col-int   { white-space: nowrap; }
  table.devices .col-int   input  { width: 5.5rem; min-width: 5.5rem; display: inline-block; }
  table.devices .col-int   button { margin-left: 0.35rem; }
  table.devices .col-meta  { white-space: nowrap; color: var(--muted); font-size: 0.82rem; }
  table.devices .col-rows  { text-align: right; font-variant-numeric: tabular-nums; }
  table.devices .col-relay { white-space: nowrap; font-size: 0.85rem; }
  .relay-dot { display: inline-block; width: 0.62rem; height: 0.62rem; border-radius: 50%;
               margin-right: 0.4rem; vertical-align: middle; background: #c8ccc4; }
  .relay-dot.on      { background: #1f9d3a; box-shadow: 0 0 0 3px rgba(31,157,58,0.18); }
  .relay-dot.off     { background: #98a09a; }
  .relay-dot.stale   { background: #d8a200; }
  .relay-dot.unknown { background: #c8ccc4; }
  table.devices .col-relay .relay-label { color: var(--muted); vertical-align: middle; }
  table.devices .actions   { white-space: nowrap; display: flex; gap: 0.5rem; align-items: center; }
  table.devices .actions a { font-size: 0.85rem; }
  /* Visual grouping: zebra stripe + breathing room */
  table.devices tbody tr:nth-child(odd) td { background: #fafbf8; }
  table.devices td, table.devices th { padding: 0.55rem 0.6rem; vertical-align: middle; }
</style>
</head><body>
<header class="topbar">
  <div class="brand">AC Energy Meter — admin</div>
  <div class="user">
    <a href="/meter/admin/">overview</a>
    &middot; <a href="/meter/admin/users.php">users</a>
    &middot; <a href="/meter/api/logout.php">sign out</a>
  </div>
</header>
<main class="container">
  <section class="card scroll-x">
    <h2>Devices</h2>
    <p class="muted">Devices auto-register on first ingest POST. Assign each one to a user below.</p>
    <table class="grid devices">
      <thead><tr>
        <th>Device ID</th>
        <th>Friendly name</th>
        <th>Location</th>
        <th>kW</th>
        <th>Owner</th>
        <th>Interval&nbsp;(s)</th>
        <th>Last sync</th>
        <th>FW</th>
        <th class="col-rows">Rows</th>
        <th>Relay</th>
        <th></th>
      </tr></thead>
      <tbody>
      <?php foreach ($devices as $d): ?>
        <tr data-id="<?= h($d['device_id']) ?>">
          <td class="col-id"><?= h($d['device_id']) ?></td>
          <td><input class="name"     value="<?= h($d['friendly_name']) ?>"></td>
          <td><input class="location" placeholder="—"
                     value="<?= h((string)($d['location'] ?? '')) ?>"></td>
          <td class="col-cap">
            <input class="capacity" type="number" step="0.01" min="0" placeholder="—"
                   value="<?= h((string)($d['capacity_kw'] ?? '')) ?>">
          </td>
          <td>
            <select class="owner">
              <option value="">— unassigned —</option>
              <?php foreach ($users as $u): ?>
                <option value="<?= (int)$u['id'] ?>"
                  <?= $u['id'] == ($d['owner_user_id'] ?? -1) ? 'selected' : '' ?>>
                  <?= h($u['username']) ?>
                </option>
              <?php endforeach; ?>
            </select>
          </td>
          <td class="col-int">
            <input class="interval" type="number" min="60" max="86400" step="1"
                   value="<?= (int)($d['log_interval_sec'] ?? 900) ?>">
            <button class="set-interval">Set</button>
          </td>
          <td class="col-meta"><?= h((string)($d['last_sync_at'] ?? '—')) ?></td>
          <td class="col-meta"><?= h((string)($d['fw_version'] ?? '—')) ?></td>
          <td class="col-rows"><?= number_format((int)($d['total_readings'] ?? 0)) ?></td>
          <td class="col-relay"
              data-on="<?= array_key_exists('relay_on', $d) && $d['relay_on'] !== null ? (int)$d['relay_on'] : '' ?>"
              data-mode="<?= h((string)($d['relay_mode'] ?? '')) ?>"
              data-at="<?= h((string)($d['relay_reported_at'] ?? '')) ?>"
              data-int="<?= (int)($d['log_interval_sec'] ?? 900) ?>">
            <span class="relay-dot unknown"></span><span class="relay-label">—</span>
          </td>
          <td class="actions">
            <button class="rename">Save</button>
            <button class="relay">Relay</button>
            <a href="/meter/dashboard/?device_id=<?= urlencode($d['device_id']) ?>">view</a>
            <button class="danger delete-device">Delete</button>
          </td>
        </tr>
      <?php endforeach; ?>
      </tbody>
    </table>
  </section>
</main>

<!-- Relay-schedule editor dialog. -->
<dialog id="relay-dialog" class="relay-dialog">
  <form method="dialog">
    <h3 style="margin:0 0 0.5rem">Relay schedule for <span id="relay-dev"></span></h3>
    <p class="muted" style="margin:0 0 0.75rem">
      Windows turn the relay <b>on</b> at the start time and <b>off</b> at the end time, only on the
      selected weekdays. No windows = relay always off.
      <br>If <b>Off</b> is earlier than <b>On</b>, the window runs <b>overnight</b> into the next day
      — e.g. On 08:00, Off 02:00 means on at 8 AM and off at 2 AM the next morning. The <b>+1 day</b>
      tick lights up and the selected days are the days the window <i>starts</i>.
    </p>
    <table class="grid relay-grid">
      <thead><tr>
        <th>Days</th><th>On</th><th>Off</th><th>+1&nbsp;day</th><th></th>
      </tr></thead>
      <tbody id="relay-rows"></tbody>
    </table>
    <div style="margin-top:0.75rem; display:flex; gap:0.5rem;">
      <button type="button" id="relay-add">+ Add window</button>
      <span style="flex:1"></span>
      <button type="button" id="relay-cancel">Cancel</button>
      <button type="button" id="relay-save">Save</button>
    </div>
  </form>
</dialog>

<style>
.relay-dialog       { border:1px solid var(--border); border-radius:8px; padding:1rem 1.25rem; max-width:600px; width:90%; }
.relay-dialog::backdrop { background: rgba(0,0,0,0.35); }
.relay-grid td      { padding:0.4rem 0.4rem; vertical-align:middle; }
.relay-grid .dow    { display:flex; gap:0.15rem; flex-wrap:wrap; }
.relay-grid .dow label { font-size:0.78rem; border:1px solid var(--border); border-radius:4px; padding:1px 5px; cursor:pointer; }
.relay-grid .dow input { display:none; }
.relay-grid .dow input:checked + span { background:var(--primary); color:#fff; padding:1px 5px; border-radius:3px; margin:-1px -5px; }
.relay-grid input[type=time] { width:6.5rem; }
.relay-grid .overnight { display:inline-flex; align-items:center; gap:0.3rem; font-size:0.76rem; color:var(--muted); white-space:nowrap; }
.relay-grid .overnight input { margin:0; }
.relay-grid .overnight.on { color:var(--primary); font-weight:600; }
</style>

<script>
const CSRF = <?= json_encode(csrf_token()) ?>;
// Server timestamps are IST; anchor parsing to that offset for staleness math.
const APP_TZ_OFFSET = <?= json_encode(app_tz_offset()) ?>;

async function post(action, fields){
  const fd = new FormData();
  fd.append('action', action);
  fd.append('csrf', CSRF);
  for (const k in fields) fd.append(k, fields[k]);
  const res = await fetch('/meter/api/admin_devices.php', { method: 'POST', body: fd, credentials: 'same-origin' });
  return res.json();
}

document.querySelectorAll('select.owner').forEach(sel => sel.addEventListener('change', async () => {
  const tr = sel.closest('tr');
  const r  = await post('bind', { device_id: tr.dataset.id, user_id: sel.value || '' });
  if (!r.ok) alert('Error: ' + r.error);
}));

document.querySelectorAll('button.rename').forEach(btn => btn.addEventListener('click', async () => {
  const tr = btn.closest('tr');
  const r  = await post('rename', {
    device_id:     tr.dataset.id,
    friendly_name: tr.querySelector('.name').value,
    location:      tr.querySelector('.location').value,
    capacity_kw:   tr.querySelector('.capacity').value,
  });
  alert(r.ok ? 'Saved.' : 'Error: ' + r.error);
}));

document.querySelectorAll('button.set-interval').forEach(btn => btn.addEventListener('click', async () => {
  const tr = btn.closest('tr');
  const r  = await post('set_interval', {
    device_id: tr.dataset.id,
    log_interval_sec: tr.querySelector('.interval').value,
  });
  alert(r.ok ? 'Saved. Takes effect on the device\'s next sync.' : 'Error: ' + r.error);
}));

document.querySelectorAll('button.delete-device').forEach(btn => btn.addEventListener('click', async () => {
  const tr = btn.closest('tr');
  if (!confirm('Delete this device and ALL its readings? This cannot be undone.')) return;
  const r = await post('delete', { device_id: tr.dataset.id });
  if (!r.ok) { alert('Error: ' + r.error); return; }
  tr.remove();
}));

/* ---------- Relay schedule editor ---------- */
const DOW_NAMES = ['Sun','Mon','Tue','Wed','Thu','Fri','Sat'];
const dlg       = document.getElementById('relay-dialog');
const dlgDevEl  = document.getElementById('relay-dev');
const rowsEl    = document.getElementById('relay-rows');
let currentDev  = null;

async function postRelay(action, fields) {
  const fd = new FormData();
  fd.append('action', action);
  fd.append('csrf', CSRF);
  for (const k in fields) fd.append(k, fields[k]);
  const res = await fetch('/meter/api/admin_relay.php',
                          { method:'POST', body:fd, credentials:'same-origin' });
  return res.json();
}

function renderRow(win) {
  const tr = document.createElement('tr');
  const tdDays = document.createElement('td');
  tdDays.className = 'dow-cell';
  const dowWrap = document.createElement('div');
  dowWrap.className = 'dow';
  DOW_NAMES.forEach((name, i) => {
    const lbl = document.createElement('label');
    const cb = document.createElement('input');
    cb.type = 'checkbox'; cb.value = i;
    if (win.days && win.days.includes(i)) cb.checked = true;
    const sp = document.createElement('span'); sp.textContent = name;
    lbl.appendChild(cb); lbl.appendChild(sp);
    dowWrap.appendChild(lbl);
  });
  tdDays.appendChild(dowWrap);
  tr.appendChild(tdDays);

  const tdOn = document.createElement('td');
  const onIn = document.createElement('input'); onIn.type='time'; onIn.value = win.on || '06:00';
  tdOn.appendChild(onIn); tr.appendChild(tdOn);

  const tdOff = document.createElement('td');
  const offIn = document.createElement('input'); offIn.type='time'; offIn.value = win.off || '18:00';
  tdOff.appendChild(offIn); tr.appendChild(tdOff);

  // Overnight (+1 day) tick. Auto-driven: lights up when Off is earlier than
  // On, i.e. the window runs past midnight into the next day. Read-only — the
  // configuration is the On/Off times themselves.
  const tdNext = document.createElement('td');
  const ovWrap = document.createElement('label'); ovWrap.className = 'overnight';
  const ov = document.createElement('input');
  ov.type = 'checkbox'; ov.disabled = true; ov.tabIndex = -1;
  ov.title = 'Window runs into the next day (Off earlier than On)';
  const ovTxt = document.createElement('span'); ovTxt.textContent = 'next day';
  ovWrap.appendChild(ov); ovWrap.appendChild(ovTxt);
  tdNext.appendChild(ovWrap); tr.appendChild(tdNext);

  const toMin = v => { const m = /^(\d\d):(\d\d)$/.exec(v || ''); return m ? (+m[1] * 60 + +m[2]) : null; };
  const refreshOvernight = () => {
    const a = toMin(onIn.value), b = toMin(offIn.value);
    const overnight = (a !== null && b !== null && b < a);
    ov.checked = overnight;
    ovWrap.classList.toggle('on', overnight);
  };
  onIn.addEventListener('input', refreshOvernight);
  offIn.addEventListener('input', refreshOvernight);
  refreshOvernight();

  const tdRm = document.createElement('td');
  const rm = document.createElement('button'); rm.type='button'; rm.className='danger'; rm.textContent='×';
  rm.addEventListener('click', () => tr.remove());
  tdRm.appendChild(rm); tr.appendChild(tdRm);

  rowsEl.appendChild(tr);
}

function collectWindows() {
  const out = [];
  rowsEl.querySelectorAll('tr').forEach(tr => {
    const days = [];
    // Scope to the day cell so the overnight indicator checkbox isn't counted.
    tr.querySelectorAll('.dow input[type=checkbox]').forEach(cb => { if (cb.checked) days.push(+cb.value); });
    const times = tr.querySelectorAll('input[type=time]');
    if (!days.length || !times[0].value || !times[1].value) return;
    out.push({ days, on: times[0].value, off: times[1].value });
  });
  return out;
}

document.querySelectorAll('button.relay').forEach(btn => btn.addEventListener('click', async () => {
  currentDev = btn.closest('tr').dataset.id;
  dlgDevEl.textContent = currentDev;
  rowsEl.innerHTML = '';
  const r = await postRelay('get', { device_id: currentDev });
  if (!r.ok) { alert('Error: ' + r.error); return; }
  const schedule = r.schedule || [];
  if (schedule.length === 0) renderRow({ days:[1,2,3,4,5], on:'06:00', off:'18:00' });
  else schedule.forEach(renderRow);
  dlg.showModal();
}));

document.getElementById('relay-add'   ).addEventListener('click', () => renderRow({ days:[], on:'06:00', off:'18:00' }));
document.getElementById('relay-cancel').addEventListener('click', () => dlg.close());
document.getElementById('relay-save'  ).addEventListener('click', async () => {
  const windows = collectWindows();
  const r = await postRelay('set', {
    device_id: currentDev,
    schedule_json: JSON.stringify(windows),
  });
  if (!r.ok) { alert('Error: ' + (r.detail || r.error)); return; }
  alert('Saved (version ' + r.version + '). Takes effect on the device\'s next sync.');
  dlg.close();
});

/* ---------- Live relay-state indicator ----------
   Each device reports its relay state (relay_on / relay_mode) on every ingest
   POST; the server stores it on device_meta. We render the last-known state
   here and refresh it every 20 s so the dot tracks the device's sync cadence. */
function renderRelayCell(cell, st) {
  const dot = cell.querySelector('.relay-dot');
  const lbl = cell.querySelector('.relay-label');
  const on  = st && st.on;
  const at  = st && st.at;
  if (st == null || on == null || !at) {
    dot.className = 'relay-dot unknown';
    lbl.textContent = '—';
    cell.title = 'No state reported yet';
    return;
  }
  const ageSec = (Date.now() - new Date(at.replace(' ', 'T') + APP_TZ_OFFSET).getTime()) / 1000;
  const stale  = !isFinite(ageSec) || ageSec > Math.max(2.5 * (st.interval || 900), 900);
  dot.className = 'relay-dot ' + (stale ? 'stale' : (on ? 'on' : 'off'));
  let text = on ? 'ON' : 'OFF';
  if (st.mode && st.mode !== 'auto') text += ' · forced ' + st.mode;
  if (stale) text += ' · stale';
  lbl.textContent = text;
  cell.title = 'Reported ' + at + ' IST';
}

// Initial paint from the server-rendered data-* attributes.
function stateFromCell(cell) {
  const onAttr = cell.dataset.on;
  return {
    on:       onAttr === '' ? null : onAttr === '1',
    mode:     cell.dataset.mode || null,
    at:       cell.dataset.at || null,
    interval: parseInt(cell.dataset.int || '900', 10),
  };
}
document.querySelectorAll('td.col-relay').forEach(cell => renderRelayCell(cell, stateFromCell(cell)));

async function refreshRelayStates() {
  let j;
  try { j = await postRelay('states', {}); } catch (e) { return; }
  if (!j || !j.ok) return;
  const byId = {};
  for (const s of j.states) {
    byId[s.device_id] = { on: s.relay_on, mode: s.relay_mode, at: s.reported_at, interval: s.interval };
  }
  document.querySelectorAll('tr[data-id]').forEach(tr => {
    const cell = tr.querySelector('td.col-relay');
    if (cell) renderRelayCell(cell, byId[tr.dataset.id] ?? null);
  });
}
refreshRelayStates();
setInterval(refreshRelayStates, 20000);
</script>
</body></html>
