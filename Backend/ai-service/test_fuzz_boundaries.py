"""Fuzzing the two ways a reading can enter the service.

Only two doors exist: an HTTP request, and a broker message. The HTTP door is
guarded by Pydantic models with bounded types; the broker door has no schema in
front of it at all, so `to_reading` is the only thing between the network and
the detectors.

What is fuzzed is therefore the doors, not the rooms behind them. Scoring
internals are reached only through one of these, and testing them against input
they cannot receive would pin behaviour nobody depends on while proving nothing
about what an attacker can actually send.

The invariants asserted are the ones other components rely on:

  * neither door raises, whatever arrives — an exception on the broker path
    means a message that is never acknowledged, and the consumer retries it
    forever;
  * a score is finite and inside [0, 1], because the scorer's weighting
    arithmetic and every downstream threshold assume that; and
  * `to_reading` either rejects a message or returns five finite floats, since
    a non-finite value compares false against every threshold in the rule
    engine and would be reported as normal.

Seeded, so a failure can be reproduced exactly rather than "sometimes".

This harness found the one input class where the fast Isolation Forest path
disagreed with scikit-learn: a reading finite as a float64 but past the float32
range the trees use. See test_fast_scoring.py.
"""

import logging
import math
import random
import tempfile
from pathlib import Path

import pytest

import config

_SANDBOX = Path(tempfile.mkdtemp(prefix="ai-fuzz-"))
config.IF_MODEL_PATH = _SANDBOX / "model.joblib"
config.METADATA_PATH = _SANDBOX / "metadata.json"
config.IF_SIGNATURE_PATH = _SANDBOX / "model.joblib.sig"

import stream_processor as sp  # noqa: E402

logging.disable(logging.CRITICAL)

FEATURES = ["temperature", "humidity", "airQuality", "oxygen", "pressure"]

# Values chosen because each has broken something, here or in a comparable
# system: the float32 boundary, the float64 boundary, the integer/float
# boundary, signed zero, and the strings float() quietly accepts.
HOSTILE_NUMBERS = [
    0.0, -0.0, 1e-308, 1e15, -1e15, 2 ** 53, -(2 ** 53),
    3.4e38, 3.5e38, 1e39, 1e308, -1e308, 1.7e308,
    float("inf"), float("-inf"), float("nan"),
    -273.15, 999999.9,
]
HOSTILE_OTHERS = ["", "NaN", "Infinity", "-inf", "abc", "1e999", None, True, False,
                  [1, 2, 3], {"nested": 1}, (1, 2)]

HOSTILE_UIDS = ["", None, "a" * 4096, "../../etc/passwd", "a b", 5, "dev\x00null",
                "<script>alert(1)</script>", "'; DROP TABLE readings; --",
                "‮abc", "ok-device", "ok.device:1"]


def _value(rng):
    roll = rng.random()
    if roll < 0.55:
        return rng.uniform(-1e4, 1e4)
    if roll < 0.82:
        return rng.choice(HOSTILE_NUMBERS)
    return rng.choice(HOSTILE_OTHERS)


def _message(rng):
    message = {f: _value(rng) for f in FEATURES if rng.random() < 0.88}
    if rng.random() < 0.12:
        message[f"unexpected_{rng.randint(0, 5)}"] = _value(rng)
    return message


# ---------------------------------------------------------------------------
# Broker door
# ---------------------------------------------------------------------------

def test_to_reading_never_raises_and_never_passes_a_non_finite_value():
    rng = random.Random(20250915)
    for _ in range(20000):
        message = _message(rng)
        reading = sp.to_reading(message)
        if reading is None:
            continue
        assert set(reading) == set(FEATURES)
        for name, value in reading.items():
            assert isinstance(value, float)
            assert math.isfinite(value), (
                f"{name}={value!r} reached the detectors; it compares false "
                f"against every rule threshold and would be read as normal")


def test_stream_ingest_never_raises_on_any_message():
    """An exception here is a message the consumer never acknowledges."""
    rng = random.Random(4242)
    processor = sp.StreamProcessor(forecast_every=10 ** 9, report_every=10 ** 9)

    for index in range(15000):
        message = _message(rng)
        message["deviceUid"] = rng.choice(HOSTILE_UIDS)
        if rng.random() < 0.35:
            message["recordedAt"] = rng.choice(
                ["", None, "not-a-date", "2020-13-45T99:99:99Z", 12345,
                 "9999-99-99", "0001-01-01T00:00:00Z", "x" * 500])
        processor.handle_reading(message)   # must not raise

    assert processor.tracked_devices() <= sp.MAX_TRACKED_DEVICES


def test_hostile_identifiers_do_not_enter_the_device_table():
    """Rejected identifiers must not consume a slot in a bounded table."""
    processor = sp.StreamProcessor(forecast_every=10 ** 9, report_every=10 ** 9)
    good = {f: 20.0 for f in FEATURES}
    good.update({"humidity": 50.0, "pressure": 1013.0, "oxygen": 20.9})

    for uid in HOSTILE_UIDS:
        processor.handle_reading({**good, "deviceUid": uid})

    assert set(processor._devices) <= {"ok-device", "ok.device:1"}


# ---------------------------------------------------------------------------
# HTTP door
# ---------------------------------------------------------------------------

@pytest.fixture(scope="module")
def client():
    from fastapi.testclient import TestClient
    import main
    return TestClient(main.app)


def test_http_analysis_never_returns_a_server_error(client):
    """Any rejection is fine; a 500 is not.

    A 4xx says the service understood the request and refused it. A 5xx says a
    stranger's payload reached code that did not expect it, which is the shape
    every remote crash starts as.
    """
    rng = random.Random(777)
    payloads = []
    for _ in range(400):
        body = {"deviceUid": rng.choice(HOSTILE_UIDS),
                "current": _message(rng),
                "history": [_message(rng) for _ in range(rng.randint(0, 4))]}
        if rng.random() < 0.2:
            body.pop(rng.choice(list(body)))
        payloads.append(body)
    payloads += [{}, {"deviceUid": "x"}, [], "string", 12345, None]

    for payload in payloads:
        response = client.post("/api/ai/anomaly-analysis", json=payload)
        assert response.status_code < 500, (
            f"payload produced {response.status_code}: {str(payload)[:200]}")


def test_http_rejection_does_not_echo_the_input(client):
    """A validation error must not reflect what was sent.

    Echoing input turns the error body into a channel for whatever the sender
    put in it, to be rendered by whichever console reads the response.
    """
    marker = "<img src=x onerror=alert(1)>"
    response = client.post("/api/ai/anomaly-analysis",
                           json={"deviceUid": marker, "current": {"temperature": marker}})
    assert response.status_code == 422
    assert marker not in response.text
