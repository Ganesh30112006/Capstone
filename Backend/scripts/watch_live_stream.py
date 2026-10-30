"""
Connect to the live telemetry WebSocket the way the admin console does, and
print what arrives.

This is the last hop of the pipeline -- SQS, ingestion, RabbitMQ, fan-out,
browser -- and the only one a database query cannot confirm. It authenticates
with a real JWT through the gateway, exactly as the React client does, so a
frame printed here is a frame the console would have rendered.

    usage:
      python scripts/watch_live_stream.py              # follow until Ctrl-C
      python scripts/watch_live_stream.py --frames 5   # stop after N frames
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
import urllib.request
from datetime import datetime

try:
    import websockets
except ImportError:
    sys.exit("websockets is required: pip install websockets")

GATEWAY = os.environ.get("GATEWAY_URL", "http://localhost:8443")


def login() -> str:
    email = os.environ.get("AUTH_BOOTSTRAP_ADMIN_EMAIL")
    password = os.environ.get("AUTH_BOOTSTRAP_ADMIN_PASSWORD")
    if not email or not password:
        sys.exit("set AUTH_BOOTSTRAP_ADMIN_EMAIL and AUTH_BOOTSTRAP_ADMIN_PASSWORD (they are in Backend/.env)")
    body = json.dumps({"email": email, "password": password}).encode()
    req = urllib.request.Request(GATEWAY + "/api/auth/login", data=body, method="POST",
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=20) as response:
        return json.load(response)["data"]["token"]


def summarise(frame: dict) -> str:
    """One line per frame, naming what actually arrived rather than guessing."""
    kind = frame.get("type") or frame.get("eventType") or "frame"
    # StreamEnvelope carries the reading under "data".
    payload = frame.get("data") if isinstance(frame.get("data"), dict) else frame
    uid = frame.get("deviceUid") or payload.get("deviceUid") or payload.get("device_uid") or "-"
    bits = []
    for key, fmt in (("temperature", "%.1fC"), ("humidity", "%.0f%%RH"),
                     ("airQuality", "AQI %.0f"), ("pressure", "%.0fhPa")):
        value = payload.get(key)
        if isinstance(value, (int, float)):
            bits.append(fmt % value)
    # A frame with no measurements is reported as such, not as zeroes.
    return "%-22s %-16s %s" % (kind, uid, "  ".join(bits) if bits else "(no measurements in frame)")


async def run(limit: int | None) -> None:
    token = login()
    url = GATEWAY.replace("http", "ws", 1) + "/ws/live?access_token=" + token
    print("connecting to %s/ws/live" % GATEWAY)

    async with websockets.connect(url, open_timeout=20) as socket:
        print("connected -- waiting for frames (Ctrl-C to stop)\n")
        seen = 0
        while limit is None or seen < limit:
            raw = await socket.recv()
            seen += 1
            stamp = datetime.now().strftime("%H:%M:%S")
            try:
                print("  %s  %s" % (stamp, summarise(json.loads(raw))))
            except json.JSONDecodeError:
                print("  %s  non-JSON frame: %s" % (stamp, str(raw)[:120]))
        print("\n%d frames received" % seen)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--frames", type=int, default=None, help="stop after N frames")
    args = parser.parse_args()
    try:
        asyncio.run(run(args.frames))
    except KeyboardInterrupt:
        print("\nstopped")


if __name__ == "__main__":
    main()
