<?php
declare(strict_types=1);
require_once __DIR__ . '/../api/_db.php';
$me  = require_admin();
$pdo = db();

$rows = $pdo->query(
    'SELECT u.id, u.username, u.email, u.is_admin, u.created_at, u.last_login_at,
            (SELECT COUNT(*) FROM energy_devices d WHERE d.owner_user_id = u.id) AS device_count
       FROM users u ORDER BY u.username'
)->fetchAll();
?>
<!doctype html><html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>AC Energy Meter — users</title>
<link rel="stylesheet" href="/meter/dashboard/assets/style.css?v=7">
</head><body>
<header class="topbar">
  <div class="brand">AC Energy Meter — admin</div>
  <div class="user">
    <a href="/meter/admin/">overview</a>
    &middot; <a href="/meter/admin/devices.php">devices</a>
    &middot; <a href="/meter/api/logout.php">sign out</a>
  </div>
</header>
<main class="container">

  <section class="card">
    <h2>Create user</h2>
    <form id="create-form" class="inline-form">
      <input name="username" placeholder="username" required pattern="[a-zA-Z0-9._\-]{3,32}">
      <input name="email"    placeholder="email (optional)">
      <input name="password" type="password" placeholder="password (8+ chars)" required minlength="8">
      <label class="check"><input type="checkbox" name="is_admin" value="1"> admin</label>
      <button type="submit">Create</button>
    </form>
  </section>

  <section class="card">
    <h2>Users</h2>
    <table class="grid">
      <thead><tr><th>Username</th><th>Email</th><th>Admin</th><th>Devices</th><th>Last login</th><th>Created</th><th></th></tr></thead>
      <tbody>
      <?php foreach ($rows as $u): ?>
        <tr data-id="<?= (int)$u['id'] ?>">
          <td><b><?= h($u['username']) ?></b><?= $u['id'] == $me['id'] ? ' (you)' : '' ?></td>
          <td><?= h((string)($u['email'] ?? '')) ?></td>
          <td>
            <input type="checkbox" class="admin-toggle"
              <?= $u['is_admin'] ? 'checked' : '' ?>
              <?= $u['id'] == $me['id'] ? 'disabled' : '' ?>>
          </td>
          <td><?= (int)$u['device_count'] ?></td>
          <td><?= h((string)($u['last_login_at'] ?? '—')) ?></td>
          <td><?= h($u['created_at']) ?></td>
          <td class="actions">
            <button class="reset-pwd">Reset password</button>
            <?php if ($u['id'] != $me['id']): ?>
              <button class="danger delete-user">Delete</button>
            <?php endif; ?>
          </td>
        </tr>
      <?php endforeach; ?>
      </tbody>
    </table>
  </section>
</main>

<script>
const CSRF = <?= json_encode(csrf_token()) ?>;

async function post(action, fields){
  const fd = new FormData();
  fd.append('action', action);
  fd.append('csrf', CSRF);
  for (const k in fields) fd.append(k, fields[k]);
  const res = await fetch('/meter/api/admin_users.php', { method: 'POST', body: fd, credentials: 'same-origin' });
  return res.json();
}

document.getElementById('create-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const fd = new FormData(e.target);
  const r = await post('create', Object.fromEntries(fd.entries()));
  if (!r.ok) { alert('Error: ' + r.error); return; }
  location.reload();
});

document.querySelectorAll('.admin-toggle').forEach(cb => cb.addEventListener('change', async () => {
  const id = cb.closest('tr').dataset.id;
  const r  = await post('set_admin', { user_id: id, is_admin: cb.checked ? 1 : 0 });
  if (!r.ok) { alert('Error: ' + r.error); cb.checked = !cb.checked; }
}));

document.querySelectorAll('.reset-pwd').forEach(btn => btn.addEventListener('click', async () => {
  const id = btn.closest('tr').dataset.id;
  const p  = prompt('New password (8+ chars):');
  if (!p || p.length < 8) return;
  const r  = await post('set_password', { user_id: id, password: p });
  alert(r.ok ? 'Password updated.' : 'Error: ' + r.error);
}));

document.querySelectorAll('.delete-user').forEach(btn => btn.addEventListener('click', async () => {
  const id = btn.closest('tr').dataset.id;
  if (!confirm('Delete this user? Their devices stay but become unbound.')) return;
  const r  = await post('delete', { user_id: id });
  if (!r.ok) { alert('Error: ' + r.error); return; }
  btn.closest('tr').remove();
}));
</script>
</body></html>
