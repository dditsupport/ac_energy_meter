# Backend (MilesWeb PHP 8.4 + MySQL)

Server-side counterpart to the ESP32 firmware. Multi-device, multi-user
with admin role.

## Host requirements

- **PHP 8.4** (8.2 minimum — `_db.php` exits early below that). Verified
  on 8.4; uses `Random\Randomizer`, `#[\SensitiveParameter]`,
  `JSON_THROW_ON_ERROR`, `match`, `?type`, `never` return, strict types.
- **MySQL 5.7+ or MariaDB 10.3+** (needs `JSON`-free schema and
  `ON DUPLICATE KEY UPDATE` — both old enough).
- **PDO_MYSQL** extension (default on MilesWeb / any cPanel host).
- **`mod_authz_core`** (for the `Require all denied` in `_config/.htaccess`).
  Standard on every Apache 2.4 install.
- **Let's Encrypt / Cloudflare TLS** for the public hostname so the
  ESP32's `WiFiClientSecure::setInsecure()` POST works (we accept any
  cert but still need *some* cert).

No Composer dependencies. No build step. No PHP frameworks.

## Layout

```
backend/
├── schema.sql                       ← one-time SQL setup
└── public_html/
    ├── _config/
    │   ├── .htaccess                ← Require all denied
    │   ├── secrets.php.example      ← copy to secrets.php, fill in
    │   └── secrets.php              ← gitignored; real creds live here
    └── meter/
        ├── api/
        │   ├── _db.php              ← PDO + auth + helpers
        │   ├── ingest.php           ← device POST endpoint (X-Device-Token)
        │   ├── readings.php         ← GET data for dashboard (session auth)
        │   ├── devices.php          ← list user's devices
        │   ├── login.php            ← POST login (form or JSON)
        │   ├── logout.php
        │   ├── admin_users.php      ← admin user CRUD
        │   └── admin_devices.php    ← admin device CRUD + binding
        ├── dashboard/
        │   ├── login.php            ← sign-in page
        │   ├── index.php            ← user dashboard with charts
        │   └── assets/style.css
        └── admin/
            ├── index.php            ← admin overview + recent ingest log
            ├── users.php            ← create / edit / delete users
            └── devices.php          ← assign devices to users
```

## Deploy

1. **Database** — create a MySQL DB and user in cPanel:
   ```text
   db name:  <prefix>_acmeter
   db user:  <prefix>_acmeterapp     (grant ALL on the db)
   ```
   Then in phpMyAdmin → SQL tab, paste the contents of `schema.sql` and run.

2. **Secrets** — copy `public_html/_config/secrets.php.example` to
   `public_html/_config/secrets.php` and fill in DB creds + token.
   The `DEVICE_TOKEN` value must match `firmware/ac_energy_meter/config.h`.

3. **Upload** — drop the contents of `public_html/` into your hosting's
   `public_html/` (so the URLs end up at e.g. `https://aromen.biz/meter/...`).

4. **Verify .htaccess works** — visit
   `https://aromen.biz/_config/secrets.php` in a browser. Should return
   403. If it serves the file, your host doesn't honor `Require all denied` —
   move `_config/` outside `public_html/` and adjust `require_once` paths
   in `meter/api/_db.php`.

5. **First admin login** — visit
   `https://aromen.biz/meter/bootstrap.php`. The page creates the
   first admin account from a form, then locks itself the moment any
   admin row exists. Sign in at `/meter/dashboard/login.php` afterwards
   and (optionally) delete `bootstrap.php` from the server.

6. **Point a device** — confirm `INGEST_HOST_DEFAULT` (firmware
   `config.h`) is `https://aromen.biz` or push a different value via
   BLE. Power on the ESP32; within ~30 s its heartbeat POST lands and
   the device auto-registers. Then Admin → Devices to bind it to a user.

## Routes

### Device-facing

| Method | URL | Auth | Purpose |
|---|---|---|---|
| POST | `/meter/api/ingest.php` | `X-Device-Token` | data ingest |

Request shape: per spec §3.5, plus `Hz` per reading. Response:

```json
{
  "ok": true,
  "acked_up_to_seq": 1248,
  "server_time": "2026-06-20T17:24:32+05:30",
  "log_interval_sec": 900
}
```

`log_interval_sec` is the effective cadence for the device — taken from
`device_meta.log_interval_sec` (per-device override), falling back to
`DEFAULT_LOG_INTERVAL_SEC` from `secrets.php`.

### Browser / Android app

All require a session cookie (`meter_sess`) from POST `/meter/api/login.php`.

| Method | URL | Auth | Purpose |
|---|---|---|---|
| POST | `/meter/api/login.php` | none | `{username,password}` → session |
| GET/POST | `/meter/api/logout.php` | any | clear session |
| GET | `/meter/api/devices.php` | session | list devices user can see |
| GET | `/meter/api/readings.php` | session | data points; query params: `device_id`, `from`, `to`, `aggregate=raw\|hourly\|daily\|monthly` |
| POST | `/meter/api/admin_users.php` | admin | `action=list\|create\|set_password\|set_admin\|delete` (CSRF) |
| POST | `/meter/api/admin_devices.php` | admin | `action=list\|bind\|rename\|set_interval\|delete` (CSRF) |
| POST | `/meter/api/admin_relay.php` | admin | `action=get\|set\|clear` per-device relay schedule (CSRF) |

### Pages

| URL | Auth | Purpose |
|---|---|---|
| `/meter/dashboard/login.php` | none | sign-in form |
| `/meter/dashboard/` | session | charts: Today / 24 h / 7 d / 30 d / 12 mo |
| `/meter/dashboard/report.php` | session | day-vs-day comparison: hourly kWh line per day, Weekly (last 7 days incl. today) or Monthly (pick a month) |
| `/meter/admin/` | admin | overview + recent ingest activity |
| `/meter/admin/users.php` | admin | user CRUD |
| `/meter/admin/devices.php` | admin | device binding, per-device interval override + relay schedule |

## Aggregations

`readings.php?aggregate=hourly|daily|monthly` groups rows by bucket and
computes energy generated in the bucket as
`(MAX(energy_wh) - MIN(energy_wh)) / 1000` — works because the PZEM's
`Wh` counter is monotonically increasing. Also returns `P_avg`,
`P_peak`, `V_avg`, `samples`, and `approx` (true if any rows in the
bucket had `time_confidence='approx'`).

## Time zone (IST)

Everything runs in `APP_TIMEZONE` (default `Asia/Kolkata`, IST/+05:30):

- PHP's default zone is set from it, so device `wall_time` and `server_time`
  (`date('c')`) are formatted in IST.
- On every connection, `_db.php` runs `SET time_zone` to the matching numeric
  offset, so MySQL `NOW()` / `CURRENT_TIMESTAMP` columns (`last_sync_at`,
  `ingested_at`, `received_at`, relay `updated_at`, …) are IST too — not the
  shared host's default (often UTC). The numeric offset is used so it works
  without the named-time-zone tables many hosts omit.
- Relay schedule times (HH:MM) are interpreted by the firmware in its own
  `TZ_INFO` (`IST-5:30`), which matches. Keep the two in sync if you change
  region.

To run in a different zone, change `APP_TIMEZONE` in `secrets.php` **and**
`TZ_INFO` in `firmware/ac_energy_meter/config.h`.

## Heartbeat POSTs

Devices POST every ~hour even with no readings, so the server can push
fresh `log_interval_sec` / `server_time` to idle devices. Heartbeat
requests look identical to data requests but have an empty
`readings: []` array. The endpoint treats them as ordinary POSTs;
`acked_up_to_seq` will be 0.

## Security TODO list

- [ ] Replace `setInsecure()` cert pinning on the firmware side
- [ ] HMAC-sign device payloads to prevent token replay over hostile Wi-Fi
- [ ] Rate-limit `/api/login.php` per source IP
- [ ] HTTPS everywhere (rely on host-supplied Let's Encrypt cert)
- [ ] Bonding on BLE before opening Wi-Fi credential characteristic
