"""Tests for the asynchronous AI worker's message handling.

These cover the pure logic — payload parsing, event construction, and the
forecast-history buffer — without needing a broker. The parts that talk to
RabbitMQ are exercised end-to-end by the compose stack, not here.
"""

from datetime import datetime, timezone

import pytest

import rabbitmq_consumer
from models import AnomalyLevel, AnomalyAnalysisResponse, ModelInfo


# ---------------------------------------------------------------------------
# Payload parsing
# ---------------------------------------------------------------------------

def test_to_reading_extracts_full_payload():
    reading = rabbitmq_consumer._to_reading({
        "deviceUid": "STATION-001",
        "temperature": 31.4,
        "humidity": 67.8,
        "pressure": 1007.6,
        "airQuality": 82,
        "oxygen": 20.8,
    })

    assert reading == {
        "temperature": 31.4,
        "humidity": 67.8,
        "pressure": 1007.6,
        "airQuality": 82.0,
        "oxygen": 20.8,
    }


def test_to_reading_defaults_optional_sensors():
    # A station without an air-quality or oxygen probe still scores on the rest.
    reading = rabbitmq_consumer._to_reading({
        "temperature": 20.0, "humidity": 50.0, "pressure": 1013.0,
    })

    assert reading["airQuality"] == 50.0
    assert reading["oxygen"] == 20.9


def test_to_reading_rejects_payload_missing_required_sensor():
    # Without temperature the model cannot score it; retrying would never help.
    assert rabbitmq_consumer._to_reading({"humidity": 50.0, "pressure": 1013.0}) is None


def test_to_reading_rejects_non_numeric_values():
    assert rabbitmq_consumer._to_reading({
        "temperature": "hot", "humidity": 50.0, "pressure": 1013.0,
    }) is None


def test_to_reading_treats_null_optional_as_default():
    reading = rabbitmq_consumer._to_reading({
        "temperature": 20.0, "humidity": 50.0, "pressure": 1013.0,
        "airQuality": None, "oxygen": None,
    })

    assert reading["airQuality"] == 50.0
    assert reading["oxygen"] == 20.9


# ---------------------------------------------------------------------------
# Timestamps
# ---------------------------------------------------------------------------

def test_parse_timestamp_preserves_utc_instant():
    parsed = rabbitmq_consumer._parse_timestamp("2026-08-31T18:30:00Z")
    assert datetime.fromisoformat(parsed) == datetime(2026, 8, 31, 18, 30, tzinfo=timezone.utc)


def test_parse_timestamp_assumes_utc_for_naive_value():
    # Sensor-service emits UTC without an offset; it must not be read as local.
    parsed = rabbitmq_consumer._parse_timestamp("2026-08-31T18:30:00")
    assert datetime.fromisoformat(parsed) == datetime(2026, 8, 31, 18, 30, tzinfo=timezone.utc)


@pytest.mark.parametrize("value", [None, "", "not-a-timestamp"])
def test_parse_timestamp_falls_back_to_now(value):
    # A bad clock on one device must not stop the anomaly from being reported.
    parsed = rabbitmq_consumer._parse_timestamp(value)
    assert datetime.fromisoformat(parsed).tzinfo is not None


# ---------------------------------------------------------------------------
# Event construction — the contract the alert service consumes
# ---------------------------------------------------------------------------

def _analysis(sensor="temperature", score=0.87, severity="CRITICAL", forecast=None):
    return AnomalyAnalysisResponse(
        deviceUid="STATION-001",
        timestamp="2026-08-31T18:30:00Z",
        forecast=forecast if forecast is not None else {"temperature": 31.2},
        anomaly=AnomalyLevel(detected=True, score=score, severity=severity, sensor=sensor),
        model=ModelInfo(forecastModel="amazon/chronos-2", anomalyModel="IsolationForest", version="1.0"),
        reasons=["Reading is 15.3 above the forecast band"],
    )


def test_build_anomaly_event_matches_alert_service_contract():
    message = {"deviceUid": "STATION-001", "recordedAt": "2026-08-31T18:30:00Z"}
    reading = {"temperature": 46.5, "humidity": 50.0, "pressure": 1013.0,
               "airQuality": 82.0, "oxygen": 20.8}

    event = rabbitmq_consumer.build_anomaly_event(message, reading, _analysis())

    # Field names must match alert-service's AnomalyDetectedEvent exactly —
    # a mismatch here binds every field to null and breaks alert creation.
    assert set(event) == {
        "eventId", "deviceUid", "sensor", "value", "expectedValue",
        "score", "severity", "reason", "modelName", "modelVersion", "detectedAt",
    }
    assert event["deviceUid"] == "STATION-001"
    assert event["sensor"] == "temperature"
    assert event["value"] == 46.5
    assert event["expectedValue"] == 31.2
    assert event["score"] == 0.87
    assert event["severity"] == "CRITICAL"


def test_build_anomaly_event_reports_score_not_confidence():
    # The value is a distance-from-expectation measure, not a calibrated
    # probability. Naming it "confidence" would overstate what the model knows.
    event = rabbitmq_consumer.build_anomaly_event(
        {"deviceUid": "STATION-001"},
        {"temperature": 46.5},
        _analysis(),
    )

    assert "score" in event
    assert "confidence" not in event


def test_build_anomaly_event_handles_absent_forecast():
    # Chronos may be unavailable or the history too short to forecast.
    event = rabbitmq_consumer.build_anomaly_event(
        {"deviceUid": "STATION-001"},
        {"temperature": 46.5},
        _analysis(forecast={}),
    )

    assert event["expectedValue"] is None
    assert event["value"] == 46.5


def test_build_anomaly_event_defaults_unattributed_sensor():
    event = rabbitmq_consumer.build_anomaly_event(
        {"deviceUid": "STATION-001"},
        {"temperature": 46.5},
        _analysis(sensor=None),
    )

    assert event["sensor"] == "environment"


def test_build_anomaly_event_generates_unique_ids():
    args = ({"deviceUid": "STATION-001"}, {"temperature": 46.5}, _analysis())
    first = rabbitmq_consumer.build_anomaly_event(*args)
    second = rabbitmq_consumer.build_anomaly_event(*args)

    assert first["eventId"] != second["eventId"]


# ---------------------------------------------------------------------------
# Forecast history buffer
# ---------------------------------------------------------------------------

def test_history_buffer_is_per_device():
    buffer = rabbitmq_consumer.DeviceHistoryBuffer(window=10)
    buffer.record("STATION-001", {"temperature": 20.0})
    buffer.record("STATION-002", {"temperature": 30.0})

    assert len(buffer.history("STATION-001")) == 1
    assert buffer.history("STATION-001")[0]["temperature"] == 20.0
    assert buffer.history("STATION-002")[0]["temperature"] == 30.0


def test_history_buffer_is_bounded_to_window():
    buffer = rabbitmq_consumer.DeviceHistoryBuffer(window=3)
    for i in range(10):
        buffer.record("STATION-001", {"temperature": float(i)})

    history = buffer.history("STATION-001")
    assert len(history) == 3
    # Keeps the most recent readings, which is what the forecaster needs.
    assert [h["temperature"] for h in history] == [7.0, 8.0, 9.0]


def test_history_buffer_starts_empty_for_unknown_device():
    buffer = rabbitmq_consumer.DeviceHistoryBuffer(window=5)
    assert buffer.history("NEVER-SEEN") == []
