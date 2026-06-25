<?php
declare(strict_types=1);
require_once __DIR__ . '/../api/_db.php';

$err = null;
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $err = try_password_login(
        trim((string)($_POST['username'] ?? '')),
        (string)($_POST['password'] ?? ''),
    );
}

function try_password_login(string $u, #[\SensitiveParameter] string $p): ?string {
    if ($u === '' || $p === '') return 'Invalid username or password.';
    $st = db()->prepare('SELECT id, password_hash, is_admin FROM users WHERE username = ?');
    $st->execute([$u]);
    $row = $st->fetch();
    if ($row && password_verify($p, $row['password_hash'])) {
        login_user((int)$row['id']);
        header('Location: ' . (!empty($row['is_admin']) ? '/meter/admin/' : '/meter/dashboard/'));
        exit;
    }
    usleep(200_000);
    return 'Invalid username or password.';
}
?>
<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>AC Energy Meter — sign in</title>
<link rel="stylesheet" href="/meter/dashboard/assets/style.css?v=7">
</head><body class="auth">
<form method="post" class="card login">
  <h1>AC Energy Meter</h1>
  <?php if ($err): ?><p class="error"><?= h($err) ?></p><?php endif; ?>
  <label>Username
    <input name="username" autocomplete="username" autofocus required>
  </label>
  <label>Password
    <input name="password" type="password" autocomplete="current-password" required>
  </label>
  <button type="submit">Sign in</button>
</form>
</body></html>
