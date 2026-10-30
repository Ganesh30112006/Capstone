    """Asynchronous AI worker.

Consumes `sensor.reading.created` from the internal bus, scores each reading,
and publishes `anomaly.detected` for the alert service.

This runs entirely off the telemetry write path. Sensor-service persists a
reading and publishes the event; whether this worker is fast, slow, or down
makes no difference to storage, analytics, or alerting on deterministic rules.
That is the whole point of the split — AI is never a hard dependency.
"""

import json
import logging
import math
import threading
import time
import uuid
from collections import OrderedDict, deque
from datetime import datetime, timezone
from typing import Deque, Optional

import pika

import config
import metrics
from broker import connection_parameters
from chronos_forecaster import chronos_forecaster
from forecast_cache import ForecastCache
from anomaly_announcer import anomaly_announcer
from ml_service import orchestrator
from security import scrub, valid_device_uid
from timestamps import to_utc_iso

logger = logging.getLogger(__name__)

QUEUE_NAME = "ai.processing.queue"
DLQ_NAME = "ai.processing.queue.dlq"
DLX_NAME = "weather.events.dlx"
SOURCE_ROUTING_KEY = "sensor.reading.created"
PUBLISH_ROUTING_KEY = "anomaly.detected"

MODEL_NAME = "IsolationForest + Chronos-2 residual"
MODEL_VERSION = "1.0"

# How many past readings per device to keep for forecast context.
# Chronos needs a window to forecast against; without one the residual detector
# contributes nothing and the score collapses to rules + isolation forest.
HISTORY_WINDOW = int(getattr(config, "AI_HISTORY_WINDOW", 48))

# Bound on tracked devices so a large or churning fleet cannot grow the buffer
# without limit inside a long-lived worker.
MAX_TRACKED_DEVICES = 500


class DeviceHistoryBuffer:
    """Rolling per-device window of recent readings, used as forecast context.

    Bounded, evicting whichever device has gone longest without reporting.

    The bound used to be unreachable. `_buffers` was a defaultdict and
    `history()` indexed it, so a lookup *created* the entry; by the time
    `record()` asked whether the device was new, it never was, and the eviction
    branch was dead code. The worker calls `history()` then `record()` for every
    message, so in production this table grew with every distinct identifier it
    had ever been shown — measured, 1,500 identifiers were all retained against
    a stated cap of 500, each holding a window of up to HISTORY_WINDOW readings.

    Two rules keep that from coming back: reads never create state, and the
    single place that does create it is the same place that enforces the bound.
    """

    def __init__(self, window: int = HISTORY_WINDOW, max_devices: int = MAX_TRACKED_DEVICES):
        self._window = window
        self._max_devices = max(1, max_devices)
        self._buffers: "OrderedDict[str, Deque[dict]]" = OrderedDict()
        self._lock = threading.Lock()

    def history(self, device_uid: str) -> list:
        """The device's recent readings. Never creates an entry for an unknown
        device — a query is not evidence that a device exists."""
        with self._lock:
            buffer = self._buffers.get(device_uid)
            if buffer is None:
                return []
            self._buffers.move_to_end(device_uid)
            return list(buffer)

    def record(self, device_uid: str, reading: dict) -> None:
        with self._lock:
            buffer = self._buffers.get(device_uid)
            if buffer is None:
                if len(self._buffers) >= self._max_devices:
                    # The device that has been silent longest, not the one that
                    # has been reporting longest.
                    oldest, _ = self._buffers.popitem(last=False)
                    logger.warning("History buffer full; evicted device %s", scrub(oldest))
                buffer = deque(maxlen=self._window)
                self._buffers[device_uid] = buffer
            else:
                self._buffers.move_to_end(device_uid)
            buffer.append(reading)

    def tracked_devices(self) -> int:
        with self._lock:
            return len(self._buffers)

    def forget(self, device_uid: str) -> None:
        with self._lock:
            self._buffers.pop(device_uid, None)


history_buffer = DeviceHistoryBuffer()

# One batched Chronos forecast covers the next N readings for a device; each of
# those is then scored against the matching step instead of triggering its own
# forward pass. See forecast_cache for why the horizon is a hard boundary.
FORECAST_HORIZON = int(getattr(config, "AI_STREAM_FORECAST_HORIZON", 6))
forecast_cache = ForecastCache(refresh_every=FORECAST_HORIZON)


def _to_reading(message: dict) -> Optional[dict]:
    """Extracts the numeric reading, or None if the payload is unusable.

    Temperature, humidity and pressure are required by the model; a message
    missing them cannot be scored and is not worth retrying.

    Non-finite values are rejected here rather than scored. `float()` accepts
    the strings "NaN" and "Infinity", nothing validates this path the way
    Pydantic validates the HTTP path, and a NaN passes every comparison in the
    rule engine — so it would be reported as a normal reading.
    """
    try:
        reading = {
            "temperature": float(message["temperature"]),
            "humidity": float(message["humidity"]),
            "pressure": float(message["pressure"]),
            "airQuality": float(message.get("airQuality") or 50.0),
            "oxygen": float(message.get("oxygen") or 20.9),
        }
    except (KeyError, TypeError, ValueError):
        return None

    if not all(math.isfinite(value) for value in reading.values()):
        metrics.INVALID_READINGS.labels("non_finite").inc()
        return None
    return reading


def _parse_timestamp(raw: object) -> str:
    """Normalises the reading's timestamp to a UTC ISO-8601 string.

    Accepts the Java services' array encoding as well as ISO strings. Calling
    .replace() on the array form is what used to raise AttributeError here and
    send every scored reading to the dead-letter queue.
    """
    normalised = to_utc_iso(raw)
    if normalised is not None:
        return normalised
    if raw not in (None, ""):
        logger.warning("Unparseable timestamp %r; stamping detection time instead", raw)
    return datetime.now(timezone.utc).isoformat()


def build_anomaly_event(message: dict, reading: dict, result) -> dict:
    """Builds the `anomaly.detected` payload the alert service consumes.

    Field names match alert-service's AnomalyDetectedEvent exactly. Note
    `score`, not `confidence`: this is a distance-from-expectation measure, not
    a calibrated probability, and naming it confidence would overstate it.
    """
    sensor = result.anomaly.sensor or "environment"

    expected_value = None
    if result.forecast and isinstance(result.forecast, dict):
        expected_value = result.forecast.get(sensor)

    return {
        "eventId": str(uuid.uuid4()),
        "deviceUid": message.get("deviceUid"),
        "sensor": sensor,
        "value": reading.get(sensor),
        "expectedValue": expected_value,
        "score": result.anomaly.score,
        "severity": result.anomaly.severity,
        "reason": "; ".join(result.reasons) if result.reasons else "Anomalous reading detected",
        "modelName": MODEL_NAME,
        "modelVersion": MODEL_VERSION,
        "detectedAt": _parse_timestamp(message.get("recordedAt")),
    }


# The worker that used to live here consumed ``sensor.reading.created`` and
# published anomalies from it, while a second worker scored the broadcast
# branch. Every reading was therefore scored twice. The full engine — anomalies,
# forecasts and reports — now runs once, in ``stream_consumer``, on this same
# backend branch. What remains in this module is the payload handling both
# workers shared and the tests still cover.
