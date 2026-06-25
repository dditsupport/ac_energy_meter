<?php
// GET /meter/api/csrf.php
// Returns a CSRF token for the current logged-in session. Used by the
// Android app to refresh its X-CSRF header after a cold start when the
// session cookie is still valid but the in-memory token was lost.

declare(strict_types=1);
require_once __DIR__ . '/_db.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    json_response(405, ['ok' => false, 'error' => 'method_not_allowed']);
}
require_login();
json_response(200, ['ok' => true, 'csrf' => csrf_token()]);
