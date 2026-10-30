"""Turns the live IoT stream into forecasts and rolling reports.

This is the compute half of the platform's parallel AI branch:

    AWS IoT Core --(MQTT/WSS)--> iot-stream-service --(iot.telemetry.stream)--> here

It runs entirely off the storage path. Every reading it sees has already been
handed to SQS for durable persistence by the time it arrives here, so this
module is free to be slow, skip work under load, or fail a forecast without any
consequence for stored data or deterministic alerting.

Three things happen per reading:

  1. Hybrid anomaly scoring (shared with the batch worker), which may emit
     ``anomaly.detected``.
  2. Every ``forecast_every`` readings, once there is enough history, a
     multi-step Chronos forecast, emitted as ``prediction.generated`` and
     cached for the HTTP surface.
  3. Every ``report_every`` readings, a deterministic rolling report over the
     retained window, emitted as ``report.generated`` and cached.

Forecasting degrades cleanly: with no Chronos pipeline available the prediction
still publishes, with ``method="unavailable"`` and an explanation, rather than
failing.
"""

from __future__ import annotations

import logging
import math
import threading
import uuid
from collections import OrderedDict, deque
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Deque, Dict, List, Optional, Tuple

import config
import metrics
from chronos_forecaster import chronos_forecaster
from flatline_detector import flatline_detector
from forecast_cache import forecast_step as _forecast_step
from forecast_ledger import forecast_ledger, regime_of, trend_slope
from ml_service import orchestrator
from security import scrub, valid_device_uid
from timestamps import to_utc_iso

logger = logging.getLogger(__name__)

from anomaly_announcer import anomaly_announcer

RK_ANOMALY = "anomaly.detected"
RK_PREDICTION = "prediction.generated"
RK_REPORT = "report.generated"

MODEL_NAME = "IsolationForest + Chronos-2 residual"
MODEL_VERSION = "1.0"

FEATURES = ["temperature", "humidity", "airQuality", "oxygen", "pressure"]

# Bound tracked devices so a churning fleet cannot grow memory without limit.
MAX_TRACKED_DEVICES = int(getattr(config, "AI_STREAM_MAX_DEVICES", 500))


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _parse_timestamp(raw: object) -> str:
    """The reading's own time, falling back to arrival time only if unknowable.

    This used to stringify whatever it was given and, on failure, silently
    return now() -- so a Java-encoded timestamp array became a reading that
    looked freshly taken no matter how old it was. The fallback is kept because
    a stream frame still has to be placed on the timeline somewhere, but it is
    now logged rather than invisible.
    """
    normalised = to_utc_iso(raw)
    if normalised is not None:
        return normalised
    if raw not in (None, ""):
        logger.warning("Unparseable timestamp %r; using arrival time instead", raw)
    return _now_iso()


def to_reading(message: dict) -> Optional[dict]:
    """Extracts the numeric reading, or None if it cannot be scored.

    `float()` accepts the strings "NaN" and "Infinity", and a non-finite value
    passes every threshold comparison in the rule engine. The broker path has no
    schema validation in front of it, so the check has to happen here.
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


@dataclass
class DeviceState:
    history: Deque[dict] = field(
        default_factory=lambda: deque(maxlen=int(getattr(config, "AI_HISTORY_WINDOW", 48)))
    )
    timestamps: Deque[str] = field(
        default_factory=lambda: deque(maxlen=int(getattr(config, "AI_HISTORY_WINDOW", 48)))
    )
    readings_seen: int = 0
    anomalies_seen: int = 0
    latest_prediction: Optional[dict] = None
    latest_report: Optional[dict] = None
    # The most recent multi-step forecast, reused to score the readings that
    # follow it instead of running the transformer again for each one.
    forecast_series: Dict[str, List[float]] = field(default_factory=dict)
    # Readings scored since that forecast was made; indexes into the horizon.
    forecast_age: int = 0
    # What the forecast's receipts are scored against (forecast_ledger.py):
    # which method made it, the last reading it was issued from, and the
    # regime each metric was in at that moment -- all fixed before any
    # outcome it predicts has arrived.
    forecast_method: str = ""
    forecast_origin: Dict[str, float] = field(default_factory=dict)
    forecast_regimes: Dict[str, str] = field(default_factory=dict)
    forecast_trends: Dict[str, float] = field(default_factory=dict)
    forecast_issued_at: str = ""


class StreamProcessor:
    """Per-device rolling state plus the forecast and report builders."""

    def __init__(
        self,
        forecast_every: int = int(getattr(config, "AI_STREAM_FORECAST_EVERY", 10)),
        report_every: int = int(getattr(config, "AI_STREAM_REPORT_EVERY", 30)),
        min_history_for_forecast: int = int(getattr(config, "AI_STREAM_MIN_HISTORY", 8)),
        forecast_horizon: int = int(getattr(config, "AI_STREAM_FORECAST_HORIZON", 6)),
    ):
        self.forecast_every = max(1, forecast_every)
        self.report_every = max(1, report_every)
        self.min_history_for_forecast = max(2, min_history_for_forecast)
        self.forecast_horizon = max(1, forecast_horizon)
        # Ordered by last report so eviction sheds silent devices, not busy
        # ones; see _evict_if_needed.
        self._devices: "OrderedDict[str, DeviceState]" = OrderedDict()
        self._lock = threading.Lock()

    def _touch(self, device_uid: str) -> DeviceState:
        """The device's state, created if new, marked as most recently seen.

        Callers must hold the lock.
        """
        state = self._devices.get(device_uid)
        if state is None:
            state = DeviceState()
            self._devices[device_uid] = state
        else:
            self._devices.move_to_end(device_uid)
        return state

    # ------------------------------------------------------------------
    # Ingest
    # ------------------------------------------------------------------

    def handle_reading(self, message: dict) -> List[Tuple[str, dict]]:
        """Processes one live reading. Returns (routing_key, payload) pairs to publish."""
        device_uid = message.get("deviceUid")
        reading = to_reading(message)
        if not valid_device_uid(device_uid) or reading is None:
            return []

        events: List[Tuple[str, dict]] = []
        recorded_at = _parse_timestamp(message.get("recordedAt") or message.get("timestamp"))

        with self._lock:
            self._evict_if_needed(device_uid)
            state = self._touch(device_uid)
            history_snapshot = list(state.history)
            # The forecast made `forecast_age` readings ago predicted this one at
            # that offset into its horizon. Past the horizon it says nothing
            # about the present, so it is dropped rather than stretched.
            expected_now = _forecast_step(state.forecast_series, state.forecast_age)
            receipt_step = state.forecast_age
            receipt_method = state.forecast_method
            receipt_origin = dict(state.forecast_origin)
            receipt_regimes = dict(state.forecast_regimes)
            receipt_issued_at = state.forecast_issued_at
            receipt_trends = dict(state.forecast_trends)

        # Scored against the history captured *before* this reading, so the
        # reading is never part of the baseline it is compared with.
        try:
            analysis = orchestrator.analyze_with_forecast(
                device_uid, reading, history_snapshot, expected_now,
            )
        except Exception:
            logger.exception("Stream anomaly scoring failed for %s", scrub(device_uid))
            analysis = None

        metrics.STREAM_READINGS.inc()
        if expected_now:
            metrics.STREAM_FORECAST_REUSE.inc()
            # The receipt: this forecast step against the reading it predicted.
            # Bookkeeping must never cost the stream a reading, so it cannot raise.
            if receipt_origin and history_snapshot:
                try:
                    forecast_ledger.record(
                        device_uid=device_uid,
                        method=receipt_method or "unknown",
                        step=receipt_step,
                        actual=reading,
                        expected=expected_now,
                        issue_values=receipt_origin,
                        latest_values=history_snapshot[-1],
                        regimes=receipt_regimes,
                        recorded_at=recorded_at,
                        issued_at=receipt_issued_at,
                        trend_slopes=receipt_trends,
                    )
                except Exception:
                    logger.exception("Forecast receipt failed for %s", scrub(device_uid))

        is_anomaly = bool(analysis and analysis.anomaly.detected)
        if is_anomaly:
            metrics.ANOMALIES_DETECTED.inc()
            event = self._anomaly_event(device_uid, reading, analysis, recorded_at)
            # Every detection is counted above. What goes on the bus is the
            # condition, not one message per reading that still satisfies it:
            # a station reporting every second that drifts for an hour detects
            # 3,600 times and has one thing to say.
            if anomaly_announcer.should_announce(device_uid, event["sensor"], event["severity"]):
                metrics.STREAM_EVENTS.labels("anomaly").inc()
                events.append((RK_ANOMALY, event))
            else:
                metrics.ANOMALIES_SUPPRESSED.inc()
        elif analysis is not None:
            # A clean reading ends the standing condition, so the next anomaly
            # after a recovery is announced at once rather than waiting out the
            # re-assert interval.
            anomaly_announcer.clear_device(device_uid)

        with self._lock:
            # Re-fetched rather than reused: scoring above ran outside the lock,
            # so a concurrent reading for a different device may have evicted
            # this one in the meantime.
            state = self._touch(device_uid)
            state.history.append(reading)
            state.timestamps.append(recorded_at)
            state.readings_seen += 1
            state.forecast_age += 1
            if is_anomaly:
                state.anomalies_seen += 1
            # Refreshed when the cached forecast runs out, not on a fixed
            # reading count. Those are different questions and were previously
            # answered by the same number: a forecast was taken every
            # `forecast_every` readings while covering only `forecast_horizon`
            # of them, so with the shipped 10 and 6 40% of readings were scored
            # with no forecast at all — and the residual detector, which has
            # 100% recall on out_of_range, spike, multivariate and drift, was
            # simply absent for them. The batch worker refreshes on exhaustion
            # and had full coverage, so the same reading was detected
            # differently depending on which path it arrived by.
            #
            # Widening the horizon to 10 would have closed it more cheaply —
            # a batched forecast costs 65-71 ms whether it predicts 1 step or
            # 16 — but it buys coverage by manufacturing false alarms:
            # measured on healthy data, readings exceeding the 3-sigma
            # divergence threshold rise from 0% at step 0 to 3.5% by step 5 and
            # 8.5% by step 10, against a whole-service false-alarm rate of
            # 0.4%. A forecast far enough ahead stops being evidence.
            needs_forecast = (
                (not state.forecast_series or state.forecast_age >= self.forecast_horizon)
                and len(state.history) >= self.min_history_for_forecast
            )
            # Publishing stays on its own cadence, so consumers of
            # prediction.generated see the interval they were built against
            # rather than one derived from the horizon.
            due_publish = state.readings_seen % self.forecast_every == 0
            due_forecast = needs_forecast or due_publish
            due_report = state.readings_seen % self.report_every == 0
            # One snapshot serves both builders below.
            window = list(state.history) if (due_forecast or due_report) else None
            timestamps = list(state.timestamps) if due_report else None
            anomalies_seen = state.anomalies_seen

        if due_forecast:
            prediction = self.build_prediction(device_uid, window)
            with self._lock:
                target = self._devices.get(device_uid)
                if target is not None:
                    target.latest_prediction = prediction
                    # Refresh the cache the next readings will be scored against.
                    target.forecast_series = prediction.get("predictions") or {}
                    target.forecast_age = 0
                    origin = window[-1] if window else {}
                    before = window[-2] if window and len(window) > 1 else {}
                    target.forecast_method = str(prediction.get("method") or "unknown")
                    target.forecast_origin = dict(origin)
                    target.forecast_regimes = {
                        feature: regime_of(feature, origin.get(feature), before.get(feature))
                        for feature in FEATURES
                    }
                    target.forecast_issued_at = str(prediction.get("generatedAt") or "")
                    # The trend baseline is fixed here, from the same window the
                    # model saw, so it cannot learn from what comes after.
                    target.forecast_trends = {
                        feature: slope
                        for feature in FEATURES
                        if (slope := trend_slope([row.get(feature) for row in (window or [])])) is not None
                    }
            if due_publish:
                metrics.STREAM_EVENTS.labels("prediction").inc()
                events.append((RK_PREDICTION, prediction))

        if due_report:
            with self._lock:
                target = self._devices.get(device_uid)
                cached_forecast = dict(target.forecast_series) if target else {}
            report = self._build_report(device_uid, window, timestamps, anomalies_seen, cached_forecast)
            with self._lock:
                target = self._devices.get(device_uid)
                if target is not None:
                    target.latest_report = report
            metrics.STREAM_EVENTS.labels("report").inc()
            events.append((RK_REPORT, report))

        return events

    # ------------------------------------------------------------------
    # Builders
    # ------------------------------------------------------------------

    @staticmethod
    def _anomaly_event(device_uid: str, reading: dict, analysis, recorded_at: str) -> dict:
        sensor = analysis.anomaly.sensor or "environment"
        expected = None
        if analysis.forecast and isinstance(analysis.forecast, dict):
            expected = analysis.forecast.get(sensor)
        return {
            "eventId": str(uuid.uuid4()),
            "deviceUid": device_uid,
            "sensor": sensor,
            "value": reading.get(sensor),
            "expectedValue": expected,
            "score": analysis.anomaly.score,
            "severity": analysis.anomaly.severity,
            "reason": "; ".join(analysis.reasons) if analysis.reasons else "Anomalous reading detected",
            "modelName": MODEL_NAME,
            "modelVersion": MODEL_VERSION,
            "detectedAt": recorded_at,
            "source": "stream",
        }

    def build_prediction(self, device_uid: str, history: List[dict]) -> dict:
        """Multi-step forecast per feature. Never raises."""
        predictions: Dict[str, List[float]] = {}
        reasons: List[str] = []
        method = "unavailable"

        try:
            series = _forecast_horizon(history, self.forecast_horizon)
            if series:
                predictions = series
                method = "chronos-2"
        except Exception as exc:  # pragma: no cover - defensive
            logger.warning("Forecast for %s failed: %s", scrub(device_uid), exc)
            reasons.append(f"Forecast error: {exc}")

        if not predictions:
            # Naive fallback: hold the last observed value across the horizon so
            # the contract still yields usable numbers for the UI.
            last = history[-1] if history else {}
            for feature in FEATURES:
                if feature in last:
                    predictions[feature] = [round(float(last[feature]), 3)] * self.forecast_horizon
            if predictions and method == "unavailable":
                method = "naive-last-value"
                reasons.append("Chronos-2 unavailable; forecast holds the last observed value.")

        return {
            "eventId": str(uuid.uuid4()),
            "deviceUid": device_uid,
            "generatedAt": _now_iso(),
            "horizon": self.forecast_horizon,
            "method": method,
            "modelName": config.CHRONOS_MODEL if method == "chronos-2" else method,
            "modelVersion": MODEL_VERSION,
            "predictions": predictions,
            "reasons": reasons,
            "historyPoints": len(history),
        }

    def _build_report(
        self,
        device_uid: str,
        history: List[dict],
        timestamps: List[str],
        anomalies_seen: int,
        cached_forecast: Optional[Dict[str, List[float]]] = None,
    ) -> dict:
        # Deliberately several passes per feature rather than one accumulating
        # loop. min, max and sum run in C over a list; a single Python-level
        # pass that computes all three by hand touches the window once but does
        # it in bytecode, and measured twice as slow on a 48-sample window.
        # Fewer passes is not the same as less work.
        #
        # (The name is not `metrics`: that is the module this file records to,
        # and shadowing it here is one edit away from a silent bug.)
        feature_metrics: Dict[str, dict] = {}
        for feature in FEATURES:
            values = [value for value in (float(row[feature]) for row in history
                                          if feature in row and row[feature] is not None)
                      if math.isfinite(value)]
            if not values:
                continue
            feature_metrics[feature] = {
                "min": round(min(values), 3),
                "max": round(max(values), 3),
                "avg": round(sum(values) / len(values), 3),
                "last": round(values[-1], 3),
                "samples": len(values),
            }

        # Reuses the forecast the worker already holds. Running the model again
        # just to restate its next step would double this report's cost for a
        # number that has not changed.
        if cached_forecast is None:
            cached_forecast = _forecast_horizon(history, 1)
        forecast_summary = {
            feature: round(float(series[0]), 3)
            for feature, series in cached_forecast.items()
            if series
        }

        window_start = timestamps[0] if timestamps else _now_iso()
        window_end = timestamps[-1] if timestamps else _now_iso()

        return {
            "eventId": str(uuid.uuid4()),
            "deviceUid": device_uid,
            "generatedAt": _now_iso(),
            "windowStart": window_start,
            "windowEnd": window_end,
            "sampleCount": len(history),
            "anomalyCount": anomalies_seen,
            "metrics": feature_metrics,
            "forecastSummary": forecast_summary,
            "narrative": _narrative(device_uid, feature_metrics, anomalies_seen, len(history)),
        }

    # ------------------------------------------------------------------
    # HTTP surface helpers
    # ------------------------------------------------------------------

    def latest_prediction(self, device_uid: str) -> Optional[dict]:
        with self._lock:
            state = self._devices.get(device_uid)
            return state.latest_prediction if state else None

    def latest_report(self, device_uid: str) -> Optional[dict]:
        with self._lock:
            state = self._devices.get(device_uid)
            return state.latest_report if state else None

    def all_predictions(self) -> List[dict]:
        with self._lock:
            return [s.latest_prediction for s in self._devices.values() if s.latest_prediction]

    def all_reports(self) -> List[dict]:
        with self._lock:
            return [s.latest_report for s in self._devices.values() if s.latest_report]

    def generate_report_now(self, device_uid: str) -> Optional[dict]:
        with self._lock:
            state = self._devices.get(device_uid)
            if not state or not state.history:
                return None
            history = list(state.history)
            timestamps = list(state.timestamps)
            anomalies = state.anomalies_seen
            cached_forecast = dict(state.forecast_series)
        report = self._build_report(device_uid, history, timestamps, anomalies, cached_forecast)
        with self._lock:
            if device_uid in self._devices:
                self._devices[device_uid].latest_report = report
        return report

    def tracked_devices(self) -> int:
        with self._lock:
            return len(self._devices)

    def _evict_if_needed(self, device_uid: str) -> None:
        """Drops the device that has been silent longest.

        Previously insertion-ordered, which evicted whichever device had been
        reporting *longest* — so a station streaming continuously since startup
        was dropped the moment one new identifier appeared, losing its rolling
        history, its report cadence and its cached forecast, while a device that
        sent one reading and vanished was kept.
        """
        # Clamped for the same reason the flatline detector's bound is: at zero
        # the table cannot hold the device about to be added, and evicting from
        # an empty table raised KeyError on every reading. A misconfigured bound
        # should cost memory headroom, not take the worker down.
        limit = max(1, MAX_TRACKED_DEVICES)
        if device_uid in self._devices:
            return
        while len(self._devices) >= limit and self._devices:
            oldest, _ = self._devices.popitem(last=False)
            # The flatline detector keys its learned baselines by the same
            # device identifier and has no other signal that a device is gone,
            # so its entry for an evicted device would otherwise sit there until
            # its own table filled up independently.
            flatline_detector.forget(oldest)
            logger.warning("Stream device table full; evicted %s", scrub(oldest))


def _forecast_horizon(history: List[dict], horizon: int) -> Dict[str, List[float]]:
    """Multi-step forecast for every feature, in one batched model call."""
    return chronos_forecaster.predict_features(history, horizon=horizon, features=FEATURES)


def _narrative(device_uid: str, metrics: Dict[str, dict], anomalies: int, samples: int) -> str:
    if not metrics:
        return f"No usable readings for {device_uid} in the current window."
    parts = [f"{device_uid}: {samples} readings analysed"]
    if "temperature" in metrics:
        t = metrics["temperature"]
        parts.append(f"temperature {t['min']}–{t['max']}°C (avg {t['avg']})")
    if "humidity" in metrics:
        h = metrics["humidity"]
        parts.append(f"humidity avg {h['avg']}%")
    if "airQuality" in metrics:
        parts.append(f"air quality last {metrics['airQuality']['last']}")
    parts.append("no anomalies flagged" if anomalies == 0 else f"{anomalies} anomaly reading(s) flagged")
    return "; ".join(parts) + "."


stream_processor = StreamProcessor()
