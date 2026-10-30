"""Seed demo calibration records through the real API, for review.

Every record goes through POST /api/sensors/{uid}/calibrations as an admin
would send it -- reference value and sensor value side by side -- so the
offsets, validity dates and drift estimates the console shows are computed by
the service, not written into the database or the UI.

Between them the records cover every calibration state the console can show:

  ESP32-CHN-006 temperature  two calibrations 103 days apart -> VALID, with a drift estimate
  ESP32-CHN-002 humidity     two calibrations 33 days apart  -> VALID, drifting low
  ESP32-CHN-005 airQuality   one, 82 days old on a 90-day policy -> DUE_SOON
  ESP32-CHN-004 pressure     one, past its 365-day validity -> EXPIRED (still applied)

Every other sensor stays NOT_CALIBRATED, which is a state worth seeing too.

The dates are fixed rather than relative to today, so re-running the script
against a database that already has them creates duplicates; it refuses unless
the device has no calibrations yet.

    usage: python scripts/seed_calibrations.py [--gateway http://localhost:8443]
"""
import argparse
import json
import sys
import urllib.error
import urllib.request

ADMIN_EMAIL = "ganesh@weatherplatform.local"
ADMIN_PASSWORD = "Admin"

# (device, sensorType, calibratedAt, referenceValue, sensorValue, notes)
CALIBRATIONS = [
    ("ESP32-CHN-006", "temperature", "2026-06-01T09:00:00", 24.80, 24.50,
     "Bench check against a Fluke 971 before deployment"),
    ("ESP32-CHN-006", "temperature", "2026-09-12T08:30:00", 25.30, 25.18,
     "Field check, reference thermometer beside the enclosure"),

    ("ESP32-CHN-002", "humidity", "2026-08-10T07:45:00", 62.00, 61.00,
     "Salt-solution check, 75% RH chamber reading scaled to site"),
    ("ESP32-CHN-002", "humidity", "2026-09-12T07:50:00", 58.60, 57.00,
     "Field check against a Vaisala HM40"),

    ("ESP32-CHN-005", "airQuality", "2026-07-20T10:15:00", 64, 58,
     "Co-located with the municipal monitor for one hour"),

    ("ESP32-CHN-004", "pressure", "2025-09-01T06:00:00", 1009.40, 1008.90,
     "Annual barometer check against the airport METAR"),
]


def call(method, url, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return resp.status, json.loads(resp.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--gateway", default="http://localhost:8443")
    args = parser.parse_args()
    api = args.gateway.rstrip("/") + "/api"

    status, body = call("POST", f"{api}/auth/login", body={"email": ADMIN_EMAIL, "password": ADMIN_PASSWORD})
    if status != 200:
        sys.exit(f"login failed ({status}): {body.get('message')}")
    token = body["data"]["token"]

    devices = sorted({c[0] for c in CALIBRATIONS})
    for device in devices:
        status, body = call("GET", f"{api}/sensors/{device}/calibrations", token)
        if status != 200:
            sys.exit(f"could not read {device}'s calibrations ({status}): {body.get('message')}")
        if body["data"]:
            sys.exit(f"{device} already has calibrations; refusing to seed duplicates.")

    failures = 0
    for device, sensor, at, reference, sensor_value, notes in CALIBRATIONS:
        status, body = call("POST", f"{api}/sensors/{device}/calibrations", token, {
            "sensorType": sensor,
            "referenceValue": reference,
            "sensorValue": sensor_value,
            "calibratedAt": at,
            "notes": notes,
        })
        if status == 201:
            d = body["data"]
            print(f"  {device}  {sensor:<12} {at[:10]}  offset {d['offset']:>+8}  {d['status']}")
        else:
            failures += 1
            print(f"  {device}  {sensor:<12} {at[:10]}  FAILED {status}: {body.get('message')}")

    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
