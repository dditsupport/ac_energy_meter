<?php
// One-shot installer. Creates the first admin user, then refuses to run
// again as long as ANY admin user exists in the database. Delete this
// file from the server after setup if you want to be extra paranoid.

declare(strict_types=1);
require_once __DIR__ . '/api/_db.php';

$pdo = db();
$has_admin = (bool)$pdo->query('SELECT 1 FROM users WHERE is_admin = 1 LIMIT 1')->fetchColumn();

if ($has_admin) {
    http_response_code(409);
    header('Content-Type: text/html; charset=utf-8');
    echo '<!doctype html><meta charset=utf-8><title>Already initialized</title>';
    echo '<h1>Already initialized</h1>';
    echo '<p>An admin user exists. This page is now locked.</p>';
    echo '<p><a href="/meter/dashboard/login.php">Sign in</a> with that admin to manage users.</p>';
    echo '<p>If you locked yourself out, delete the row from the <code>users</code> table and reload this page.</p>';
    exit;
}

$err = null;
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $err = create_first_admin(
        $pdo,
        trim((string)($_POST['username'] ?? '')),
        (string)($_POST['password'] ?? ''),
    );
}

function create_first_admin(PDO $pdo, string $u, #[\SensitiveParameter] string $p): ?string {
    if (!preg_match('/^[a-zA-Z0-9._-]{3,32}$/', $u)) {
        return 'Username must be 3–32 characters: letters, digits, dot, underscore, hyphen.';
    }
    if (strlen($p) < 8) {
        return 'Password must be at least 8 characters.';
    }
    $pdo->prepare(
        'INSERT INTO users (username, password_hash, is_admin) VALUES (?, ?, 1)'
    )->execute([$u, password_hash($p, PASSWORD_DEFAULT)]);
    header('Location: /meter/dashboard/login.php');
    exit;
}
?>
<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>AC Energy Meter — first-run setup</title>
<link rel="stylesheet" href="/meter/dashboard/assets/style.css?v=7">
</head><body class="auth">
<form method="post" class="card login">
  <h1>First-run setup</h1>
  <p class="muted">Create the initial admin account. This page locks itself after the first user is created.</p>
  <?php if ($err): ?><p class="error"><?= h($err) ?></p><?php endif; ?>
  <label>Admin username
    <input name="username" autocomplete="username" required pattern="[a-zA-Z0-9._\-]{3,32}" autofocus>
  </label>
  <label>Password (8+ chars)
    <input name="password" type="password" autocomplete="new-password" required minlength="8">
  </label>
  <button type="submit">Create admin</button>
</form>
</body></html>
