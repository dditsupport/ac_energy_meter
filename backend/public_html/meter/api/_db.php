<?php
// Shared bootstrap: DB connection, session, auth helpers, JSON responses.
// Included by every endpoint and page.

declare(strict_types=1);

// Target: PHP 8.4+. The codebase uses:
//   - Random\Randomizer       (PHP 8.2+)
//   - JSON_THROW_ON_ERROR     (PHP 7.3+)
//   - readonly + strict types (PHP 8.2+ readonly classes)
//   - match expressions, str_*_with, named args (PHP 8.0+)
//   - never / ?type / ?:    everywhere
// Soft-fail on older PHP so the cause is obvious rather than a cryptic
// syntax error somewhere deep in an admin page.
if (PHP_VERSION_ID < 80200) {
    http_response_code(500);
    header('Content-Type: text/plain');
    exit("AC Energy Meter backend requires PHP 8.2+; this host runs " . PHP_VERSION . ".\n");
}

// Locate secrets.php. Preferred location is outside the document root
// (e.g. /home/<cpaneluser>/meter_secrets/secrets.php) so the file is
// unreachable over HTTP even if the web server's deny-rules are removed.
// Falls back to the in-tree _config/ directory if the env var or the
// out-of-tree path aren't present (handy for local dev).
(function (): void {
    $candidates = array_filter([
        getenv('METER_SECRETS_PATH') ?: null,
        dirname(__DIR__, 3) . '/meter_secrets/secrets.php', // /home/<cpaneluser>/meter_secrets/secrets.php
        __DIR__ . '/../../_config/secrets.php',             // legacy in-tree
    ]);
    foreach ($candidates as $p) {
        if (is_file($p)) { require_once $p; return; }
    }
    http_response_code(500);
    header('Content-Type: text/plain');
    exit("AC Energy Meter: secrets.php not found. Tried: " . implode(', ', $candidates) . "\n");
})();

// APP_TIMEZONE drives BOTH PHP's default zone and MySQL's session zone, so
// PHP-written wall-clock values and server-generated NOW()/CURRENT_TIMESTAMP
// columns agree. Default to IST if an older secrets.php predates this setting.
if (!defined('APP_TIMEZONE')) {
    define('APP_TIMEZONE', 'Asia/Kolkata');
}
date_default_timezone_set(APP_TIMEZONE);

/**
 * APP_TIMEZONE as a fixed "+HH:MM" / "-HH:MM" UTC offset. MySQL's session
 * time zone is set from this numeric offset, which works even on shared hosts
 * that don't load the named-time-zone tables. (IST = +05:30, and has no DST.)
 */
function app_tz_offset(): string {
    $sec  = (new DateTimeZone(APP_TIMEZONE))
                ->getOffset(new DateTimeImmutable('now', new DateTimeZone('UTC')));
    $sign = $sec < 0 ? '-' : '+';
    $sec  = abs($sec);
    return sprintf('%s%02d:%02d', $sign, intdiv($sec, 3600), intdiv($sec % 3600, 60));
}

/* ---------- PDO singleton ---------- */
function db(): PDO {
    static $pdo = null;
    if ($pdo !== null) return $pdo;
    $dsn = sprintf('mysql:host=%s;dbname=%s;charset=utf8mb4', DB_HOST, DB_NAME);
    $pdo = new PDO($dsn, DB_USER, DB_PASS, [
        PDO::ATTR_ERRMODE            => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
        PDO::ATTR_EMULATE_PREPARES   => false,
    ]);
    // Pin the DB session clock to APP_TIMEZONE so NOW()/CURRENT_TIMESTAMP
    // columns (last_sync_at, ingested_at, received_at, relay updated_at, …)
    // are stored/read in IST, consistent with the wall_time PHP writes.
    $pdo->exec("SET time_zone = '" . app_tz_offset() . "'");
    return $pdo;
}

/* ---------- JSON helpers ---------- */
function json_response(int $code, array $data): never {
    http_response_code($code);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    echo json_encode($data, JSON_UNESCAPED_SLASHES | JSON_THROW_ON_ERROR);
    exit;
}

function json_body(): array {
    $raw = file_get_contents('php://input');
    if ($raw === false || $raw === '') return [];
    try {
        $doc = json_decode($raw, true, 512, JSON_THROW_ON_ERROR);
    } catch (JsonException) {
        return [];
    }
    return is_array($doc) ? $doc : [];
}

/* ---------- Device auth (X-Device-Token) ---------- */
function require_device_auth(): void {
    $sent = $_SERVER['HTTP_X_DEVICE_TOKEN'] ?? '';
    if (!hash_equals(DEVICE_TOKEN, $sent)) {
        json_response(401, ['ok' => false, 'error' => 'unauthorized']);
    }
}

/* ---------- Browser sessions ---------- */
function start_user_session(): void {
    if (session_status() === PHP_SESSION_ACTIVE) return;
    session_set_cookie_params([
        'lifetime' => SESSION_LIFETIME,
        'path'     => '/',
        'secure'   => !empty($_SERVER['HTTPS']),
        'httponly' => true,
        'samesite' => 'Lax',
    ]);
    session_name('meter_sess');
    session_start();
    // Idle timeout
    if (isset($_SESSION['last_activity']) &&
        time() - $_SESSION['last_activity'] > SESSION_LIFETIME) {
        session_unset();
        session_destroy();
    }
    $_SESSION['last_activity'] = time();
}

function current_user(): ?array {
    start_user_session();
    if (empty($_SESSION['user_id'])) return null;
    $st = db()->prepare('SELECT id, username, email, is_admin FROM users WHERE id = ?');
    $st->execute([$_SESSION['user_id']]);
    $u = $st->fetch();
    return $u ?: null;
}

function require_login(): array {
    $u = current_user();
    if (!$u) {
        if (str_starts_with($_SERVER['REQUEST_URI'] ?? '', '/meter/api/')) {
            json_response(401, ['ok' => false, 'error' => 'login_required']);
        }
        header('Location: /meter/dashboard/login.php');
        exit;
    }
    return $u;
}

function require_admin(): array {
    $u = require_login();
    if (empty($u['is_admin'])) {
        json_response(403, ['ok' => false, 'error' => 'admin_only']);
    }
    return $u;
}

function login_user(int $user_id): void {
    start_user_session();
    session_regenerate_id(true);
    $_SESSION['user_id'] = $user_id;
    $_SESSION['last_activity'] = time();
    db()->prepare('UPDATE users SET last_login_at = NOW() WHERE id = ?')
        ->execute([$user_id]);
}

function logout_user(): void {
    start_user_session();
    $_SESSION = [];
    if (ini_get('session.use_cookies')) {
        $p = session_get_cookie_params();
        setcookie(session_name(), '', time() - 42000,
            $p['path'], $p['domain'], $p['secure'], $p['httponly']);
    }
    session_destroy();
}

/* ---------- CSRF ---------- */
function csrf_token(): string {
    start_user_session();
    if (empty($_SESSION['csrf'])) {
        // Random\Randomizer (PHP 8.2+) over bare random_bytes — same security,
        // explicit about which engine and reusable across the request.
        static $rand = null;
        $rand ??= new Random\Randomizer();
        $_SESSION['csrf'] = bin2hex($rand->getBytes(16));
    }
    return $_SESSION['csrf'];
}

function check_csrf(): void {
    start_user_session();
    $sent = $_POST['csrf'] ?? $_SERVER['HTTP_X_CSRF'] ?? '';
    if (!hash_equals($_SESSION['csrf'] ?? '', $sent)) {
        json_response(403, ['ok' => false, 'error' => 'bad_csrf']);
    }
}

/* ---------- Convenience ---------- */
function client_ip(): string {
    return $_SERVER['REMOTE_ADDR'] ?? '';
}

function h(?string $s): string {
    return htmlspecialchars((string)$s, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
}

/* ---------- Device access control ---------- */
// Returns the list of device_ids the user is allowed to query.
// Admins see all devices; others only their owned ones.
function visible_device_ids(array $user): array {
    if (!empty($user['is_admin'])) {
        $st = db()->query('SELECT device_id FROM energy_devices ORDER BY device_id');
    } else {
        $st = db()->prepare('SELECT device_id FROM energy_devices WHERE owner_user_id = ?');
        $st->execute([$user['id']]);
    }
    return array_column($st->fetchAll(), 'device_id');
}

function user_can_see_device(array $user, string $device_id): bool {
    if (!empty($user['is_admin'])) return true;
    $st = db()->prepare('SELECT 1 FROM energy_devices WHERE device_id = ? AND owner_user_id = ?');
    $st->execute([$device_id, $user['id']]);
    return (bool)$st->fetchColumn();
}
