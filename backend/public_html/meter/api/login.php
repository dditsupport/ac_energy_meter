<?php
// POST /meter/api/login.php { username, password } -> session cookie.
// Form-encoded or JSON both accepted.

declare(strict_types=1);
require_once __DIR__ . '/_db.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    json_response(405, ['ok' => false, 'error' => 'method_not_allowed']);
}

$body = $_POST;
if (!$body) $body = json_body();
$username = trim((string)($body['username'] ?? ''));

if ($username === '' || ($body['password'] ?? '') === '') {
    json_response(400, ['ok' => false, 'error' => 'missing_fields']);
}

attempt_login($username, (string)($body['password'] ?? ''));


function attempt_login(string $username, #[\SensitiveParameter] string $password): never {
    // Tiny rate-limit hook (no per-user yet; in-process throttle for brute force)
    usleep(200_000); // 200 ms

    $st = db()->prepare('SELECT id, password_hash, is_admin FROM users WHERE username = ?');
    $st->execute([$username]);
    $u = $st->fetch();

    if (!$u || !password_verify($password, $u['password_hash'])) {
        json_response(401, ['ok' => false, 'error' => 'invalid_credentials']);
    }

    if (password_needs_rehash($u['password_hash'], PASSWORD_DEFAULT)) {
        db()->prepare('UPDATE users SET password_hash = ? WHERE id = ?')
            ->execute([password_hash($password, PASSWORD_DEFAULT), $u['id']]);
    }

    login_user((int)$u['id']);
    json_response(200, [
        'ok'       => true,
        'username' => $username,
        'is_admin' => (bool)$u['is_admin'],
        // CSRF token for state-changing API calls from the Android app
        // (the dashboard pages get theirs via csrf_token() during render).
        'csrf'     => csrf_token(),
    ]);
}
