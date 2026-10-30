"""
A stand-in fleet of ESP32 weather stations, for running the platform in real
time before the hardware exists.

It publishes to the same LocalStack SQS queues that AWS IoT Core's rule action
writes to, in the same JSON envelope an ESP32 publishes to
``weather/devices/{deviceUid}/telemetry``. Nothing downstream is aware of it:
iot-ingestion-service validates, de-duplicates and forwards these exactly as it
will forward real readings, and every service after that sees only RabbitMQ
events. When the hardware arrives it attaches at this same seam and this script
is simply switched off.

What it deliberately does NOT do is bypass the queue. There is no HTTP write
path into sensor-service -- that absence is a security property, so telemetry
cannot be forged by a caller -- and a simulator that posted readings directly
would be exercising a path that does not exist in production.

Readings follow a diurnal curve with per-station offsets and bounded random
walk, so the series look like weather rather than noise: the anomaly detector
and the daily aggregates are being fed something with real structure. Faults
are injectable so the alerting and AI paths can be seen working.

    usage:
      python scripts/simulate_fleet.py                     # run until Ctrl-C
      python scripts/simulate_fleet.py --interval 5        # seconds per round
      python scripts/simulate_fleet.py --once              # one round, exit
      python scripts/simulate_fleet.py --anomaly ESP32-CHN-002
      python scripts/simulate_fleet.py --flatline ESP32-CHN-003
"""
from __future__ import annotations

import argparse
import json
import math
import random
import signal
import sys
import time
import uuid
from datetime import datetime, timezone

try:
    import boto3
except ImportError:
    sys.exit("boto3 is required: pip install boto3")

SQS_ENDPOINT = "http://localhost:4566"
REGION = "us-east-1"
TELEMETRY_QUEUE = "iot-telemetry-queue"
HEARTBEAT_QUEUE = "iot-heartbeat-queue"

# Four stations around Hyderabad, spread widely enough that a radius query over
# them has something to include and something to exclude.
STATIONS = [
    # Andhra Pradesh, spread across the state rather than clustered in one city,
    # so a map framed on AP shows a fleet that plausibly covers it: the northern
    # coast, the Krishna delta, Rayalaseema inland, and the southern tip.
    #
    # These sat in Hyderabad until now -- Secunderabad, Hussain Sagar,
    # Shamshabad, Patancheru -- which is Telangana, a different state from the
    # one this deployment is for. The console framed its map on Andhra Pradesh
    # while every station in it reported from outside.
    {"uid": "ESP32-CHN-001", "name": "Visakhapatnam Coastal", "location": "Visakhapatnam",  "lat": 17.6868, "lon": 83.2185, "temp_base": 29.0},
    {"uid": "ESP32-CHN-002", "name": "Amaravati Riverfront",  "location": "Amaravati",      "lat": 16.5062, "lon": 80.5180, "temp_base": 28.0},
    {"uid": "ESP32-CHN-003", "name": "Kurnool Inland Post",   "location": "Kurnool",        "lat": 15.8281, "lon": 78.0373, "temp_base": 30.5},
    {"uid": "ESP32-CHN-004", "name": "Tirupati Hill Station", "location": "Tirupati",       "lat": 13.6288, "lon": 79.4192, "temp_base": 31.0},
    # Added to broaden fleet coverage across the state; registered in the
    # device registry via the real API, not fabricated in the frontend.
    {"uid": "ESP32-CHN-005", "name": "Guntur Market Post",    "location": "Guntur",         "lat": 16.3067, "lon": 80.4365, "temp_base": 30.0},
    {"uid": "ESP32-CHN-006", "name": "Nellore Coastal",       "location": "Nellore",        "lat": 14.4426, "lon": 79.9865, "temp_base": 29.5},
    {"uid": "ESP32-CHN-007", "name": "Srikakulam North",      "location": "Srikakulam",     "lat": 18.2949, "lon": 83.8938, "temp_base": 28.5},
]


class Station:
    """Holds each station's drifting state between rounds."""

    def __init__(self, spec: dict) -> None:
        self.spec = spec
        self.uid = spec["uid"]
        self.humidity = random.uniform(55, 70)
        self.pressure = random.uniform(1006, 1014)
        self.air_quality = random.randint(60, 110)
        self.oxygen = random.uniform(20.6, 21.0)
        self.battery = random.randint(82, 100)
        self.wifi = random.randint(-72, -48)
        # Fault injection, toggled from the command line.
        self.anomaly = False
        self.flatline = False
        self._frozen: dict | None = None

    def _diurnal(self) -> float:
        """Temperature follows the time of day, peaking mid-afternoon."""
        now = datetime.now(timezone.utc).astimezone()
        hours = now.hour + now.minute / 60.0
        # Trough at ~05:00, peak at ~17:00.
        return self.spec["temp_base"] + 4.5 * math.sin((hours - 11) / 24 * 2 * math.pi)

    @staticmethod
    def _walk(value: float, step: float, low: float, high: float) -> float:
        return max(low, min(high, value + random.uniform(-step, step)))

    def reading(self) -> dict:
        if self.flatline and self._frozen is not None:
            # A stuck sensor repeats its last value exactly. The detector looks
            # for precisely this, so it has to be byte-identical, not merely
            # close.
            payload = dict(self._frozen)
            payload["messageId"] = str(uuid.uuid4())
            payload["timestamp"] = datetime.now(timezone.utc).isoformat()
            return payload

        self.humidity = self._walk(self.humidity, 1.2, 25, 96)
        self.pressure = self._walk(self.pressure, 0.4, 985, 1035)
        self.air_quality = int(self._walk(self.air_quality, 6, 15, 260))
        self.oxygen = self._walk(self.oxygen, 0.05, 19.8, 21.4)
        self.battery = max(5, self.battery - (1 if random.random() < 0.04 else 0))
        self.wifi = int(self._walk(self.wifi, 3, -92, -38))

        temperature = self._diurnal() + random.uniform(-0.4, 0.4)
        if self.anomaly:
            # Well outside the diurnal envelope but still physically possible,
            # so it tests the detector rather than the range validator.
            temperature += random.uniform(14, 19)
            self.air_quality = random.randint(280, 420)

        payload = {
            "messageId": str(uuid.uuid4()),
            "deviceUid": self.uid,
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "temperature": round(temperature, 2),
            "humidity": round(self.humidity, 2),
            "airQuality": self.air_quality,
            "pressure": round(self.pressure, 2),
            "rain": self.humidity > 88 and random.random() < 0.5,
            "oxygen": round(self.oxygen, 2),
            "latitude": self.spec["lat"],
            "longitude": self.spec["lon"],
        }
        if self.flatline:
            self._frozen = payload
        return payload

    def heartbeat(self) -> dict:
        return {
            "messageId": str(uuid.uuid4()),
            "deviceUid": self.uid,
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "batteryLevel": self.battery,
            "wifiSignalStrength": self.wifi,
            "firmwareVersion": "1.4.2",
        }


def queue_urls(sqs):
    try:
        return (
            sqs.get_queue_url(QueueName=TELEMETRY_QUEUE)["QueueUrl"],
            sqs.get_queue_url(QueueName=HEARTBEAT_QUEUE)["QueueUrl"],
        )
    except Exception as exc:  # noqa: BLE001
        sys.exit(
            "Could not reach the SQS queues at %s (%s).\n"
            "Start the stack first:  docker compose up -d localstack" % (SQS_ENDPOINT, exc)
        )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--interval", type=float, default=10.0, help="seconds between rounds (default 10)")
    parser.add_argument("--once", action="store_true", help="publish a single round and exit")
    parser.add_argument("--anomaly", action="append", default=[], metavar="UID",
                        help="station that should report implausible values")
    parser.add_argument("--flatline", action="append", default=[], metavar="UID",
                        help="station whose sensor is stuck repeating one reading")
    parser.add_argument("--heartbeat-every", type=int, default=3,
                        help="send a heartbeat every N rounds (default 3)")
    args = parser.parse_args()

    sqs = boto3.client("sqs", region_name=REGION, endpoint_url=SQS_ENDPOINT,
                       aws_access_key_id="test", aws_secret_access_key="test")
    telemetry_url, heartbeat_url = queue_urls(sqs)

    stations = [Station(spec) for spec in STATIONS]
    for station in stations:
        station.anomaly = station.uid in args.anomaly
        station.flatline = station.uid in args.flatline

    running = True

    def stop(_signum, _frame):
        nonlocal running
        running = False
        print("\nstopping after this round")

    signal.signal(signal.SIGINT, stop)

    print("publishing to %s" % telemetry_url)
    print("stations: %s" % ", ".join(s.uid for s in stations))
    if args.anomaly:
        print("anomalous: %s" % ", ".join(args.anomaly))
    if args.flatline:
        print("flatlined: %s" % ", ".join(args.flatline))
    print()

    rounds = 0
    sent = 0
    while running:
        rounds += 1
        for station in stations:
            payload = station.reading()
            sqs.send_message(QueueUrl=telemetry_url, MessageBody=json.dumps(payload))
            sent += 1
            print("  %s  %s  %5.1fC  %4.1f%%RH  AQI %3d  %.1fhPa"
                  % (datetime.now().strftime("%H:%M:%S"), station.uid,
                     payload["temperature"], payload["humidity"],
                     payload["airQuality"], payload["pressure"]))

        if rounds % max(1, args.heartbeat_every) == 0:
            for station in stations:
                sqs.send_message(QueueUrl=heartbeat_url, MessageBody=json.dumps(station.heartbeat()))
            print("  -- heartbeats sent for %d stations" % len(stations))

        if args.once:
            break
        # Interruptible sleep, so Ctrl-C does not wait out a long interval.
        for _ in range(int(args.interval * 10)):
            if not running:
                break
            time.sleep(0.1)

    print("\n%d readings published over %d rounds" % (sent, rounds))


if __name__ == "__main__":
    main()
