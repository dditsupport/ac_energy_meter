<?php
declare(strict_types=1);
require_once __DIR__ . '/../api/_db.php';
require_admin();
$pdo = db();

$devices = $pdo->query(
    'SELECT d.device_id, d.friendly_name, d.location, d.capacity_kw,
            d.owner_user_id, u.username AS owner_username, d.first_seen_at,
            m.fw_version, m.last_sync_at, m.last_seq, m.last_boot_id,
            m.total_readings, m.log_interval_sec
       FROM energy_devices d
       LEFT JOIN users        u ON u.id = d.owner_user_id
       LEFT JOIN device_meta  m ON m.device_id = d.device_id
      ORDER BY d.friendly_name'
)->fetchAll();

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
    </p>
    <table class="grid relay-grid">
      <thead><tr>
        <th>Days</th><th>On</th><th>Off</th><th></th>
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
.relay-dialog       { border:1px solid var(--border); border-radius:8px; padding:1rem 1.25rem; max-width:560px; width:90%; }
.relay-dialog::backdrop { background: rgba(0,0,0,0.35); }
.relay-grid td      { padding:0.4rem 0.4rem; vertical-align:middle; }
.relay-grid .dow    { display:flex; gap:0.15rem; flex-wrap:wrap; }
.relay-grid .dow label { font-size:0.78rem; border:1px solid var(--border); border-radius:4px; padding:1px 5px; cursor:pointer; }
.relay-grid .dow input { display:none; }
.relay-grid .dow input:checked + span { background:var(--primary); color:#fff; padding:1px 5px; border-radius:3px; margin:-1px -5px; }
.relay-grid input[type=time] { width:6.5rem; }
</style>

<script>
const CSRF = <?= json_encode(csrf_token()) ?>;

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
    tr.querySelectorAll('input[type=checkbox]').forEach(cb => { if (cb.checked) days.push(+cb.value); });
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
</script>
</body></html>
