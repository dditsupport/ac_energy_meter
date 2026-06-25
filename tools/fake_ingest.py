#!/usr/bin/env python3
"""Bench-test stub for the Stage 6 Wi-Fi sync verification.

Mimics the MilesWeb ingest.php endpoint. Accepts POST requests on /ingest,
prints the payload, and returns {"ok": true, "acked_up_to_seq": <max_seq>}
so the firmware can exercise its truncate-on-ACK path.

Usage:
    python3 tools/fake_ingest.py            # listens on 0.0.0.0:8080
    python3 tools/fake_ingest.py --port 9000
    python3 tools/fake_ingest.py --token <token>   # require X-Device-Token

Point the firmware at this server by editing INGEST_URL in
firmware/ac_energy_meter/config.h to:
    http://<laptop-ip>:8080/ingest
(use http, not https, for the stub).
"""
import argparse
import json
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer


class IngestHandler(BaseHTTPRequestHandler):
    server_token = None
    log_interval = 0

    def _send(self, status, body):
        payload = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_POST(self):
        if self.path.rstrip("/") not in ("/ingest", "/api/meter/ingest.php"):
            self._send(404, {"ok": False, "error": "not_found"})
            return

        if self.server_token:
            sent = self.headers.get("X-Device-Token", "")
            if sent != self.server_token:
                self._send(401, {"ok": False, "error": "unauthorized"})
                return

        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length > 0 else b""
        try:
            doc = json.loads(raw.decode("utf-8"))
        except Exception as exc:
            self._send(400, {"ok": False, "error": f"invalid_json: {exc}"})
            return

        readings = doc.get("readings", [])
        max_seq = max((r.get("seq", 0) for r in readings), default=0)
        tag = "heartbeat" if not readings else f"rows={len(readings)}"
        print(
            f"[ingest] device={doc.get('device_id')} "
            f"fw={doc.get('fw_version')} "
            f"{tag} "
            f"max_seq={max_seq} "
            f"boots={len(doc.get('boot_history', []))}"
        )
        for r in readings:
            print(
                f"  seq={r['seq']} boot={r['boot_id']} sec={r['sec']} "
                f"V={r['V']} I={r['I']} P={r['P']} Wh={r['Wh']} "
                f"PF={r['PF']} Hz={r.get('Hz', 0)}"
            )
        # ISO 8601 with explicit +00:00 offset (firmware parser handles
        # both 'Z' and '+HH:MM' forms; real MilesWeb endpoint should
        # format this in APP_TIMEZONE).
        server_time = datetime.now(timezone.utc).strftime(
            "%Y-%m-%dT%H:%M:%S+00:00"
        )
        body = {
            "ok": True,
            "acked_up_to_seq": max_seq,
            "server_time": server_time,
        }
        if self.log_interval > 0:
            body["log_interval_sec"] = self.log_interval
        self._send(200, body)

    def log_message(self, fmt, *args):
        # Suppress default access log noise; our own prints are richer.
        pass


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--token", default=None,
                        help="If set, require this value in X-Device-Token.")
    parser.add_argument("--log-interval", type=int, default=0,
                        help="If >0, include log_interval_sec in each response "
                             "so the device adjusts its logging cadence. "
                             "Allowed range on the firmware side: 60..86400.")
    args = parser.parse_args()

    IngestHandler.server_token = args.token
    IngestHandler.log_interval = args.log_interval
    server = HTTPServer((args.host, args.port), IngestHandler)
    print(f"[ingest] listening on http://{args.host}:{args.port}/ingest")
    if args.token:
        print(f"[ingest] requiring X-Device-Token={args.token!r}")
    if args.log_interval > 0:
        print(f"[ingest] pushing log_interval_sec={args.log_interval} in each response")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[ingest] shutting down")


if __name__ == "__main__":
    main()
