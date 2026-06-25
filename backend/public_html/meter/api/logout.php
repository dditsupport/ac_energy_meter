<?php
declare(strict_types=1);
require_once __DIR__ . '/_db.php';

logout_user();

if (str_contains($_SERVER['HTTP_ACCEPT'] ?? '', 'application/json')) {
    json_response(200, ['ok' => true]);
}
header('Location: /meter/dashboard/login.php');
exit;
