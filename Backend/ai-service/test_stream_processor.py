"""Tests for the live-stream AI processor.

These run without Chronos installed, so forecasting exercises the naive
fallback path — which is exactly what CI and most local runs will hit.
"""

from stream_processor import StreamProcessor, RK_ANOMALY, RK_PREDICTION, RK_REPORT


def _reading(temp=21.0, hum=55.0, press=1013.0, aq=40.0, ox=20.9, ts="2026-09-01T00:00:00Z"):
    return {
        "deviceUid": "esp32-01",
        "temperature": temp,
        "humidity": hum,
        "pressure": press,
        "airQuality": aq,
        "oxygen": ox,
        "recordedAt": ts,
    }


def test_ignores_unusable_readings():
    proc = StreamProcessor()
    assert proc.handle_reading({"deviceUid": "esp32-01"}) == []
    assert proc.handle_reading({"temperature": 1, "humidity": 1, "pressure": 1}) == []  # no deviceUid


def test_emits_prediction_on_cadence():
    proc = StreamProcessor(forecast_every=5, report_every=999, min_history_for_forecast=3)

    events_by_key = []
    for i in range(5):
        events = proc.handle_reading(_reading(temp=20 + i))
        events_by_key.append([k for k, _ in events])

    # First four readings: no forecast. Fifth: a prediction.
    assert all(RK_PREDICTION not in keys for keys in events_by_key[:4])
    assert RK_PREDICTION in events_by_key[4]

    prediction = proc.latest_prediction("esp32-01")
    assert prediction is not None
    assert prediction["horizon"] == 6
    assert prediction["method"] in {"chronos-2", "naive-last-value"}
    assert "temperature" in prediction["predictions"]
    assert len(prediction["predictions"]["temperature"]) == 6


def test_emits_report_on_cadence_with_aggregates():
    proc = StreamProcessor(forecast_every=999, report_every=4)

    last_events = []
    for i in range(4):
        last_events = proc.handle_reading(_reading(temp=10 + i, hum=50 + i))

    keys = [k for k, _ in last_events]
    assert RK_REPORT in keys

    report = proc.latest_report("esp32-01")
    assert report["sampleCount"] == 4
    assert report["metrics"]["temperature"]["min"] == 10.0
    assert report["metrics"]["temperature"]["max"] == 13.0
    assert report["metrics"]["temperature"]["last"] == 13.0
    assert report["anomalyCount"] == 0
    assert report["deviceUid"] in report["narrative"]


def test_out_of_bounds_reading_raises_anomaly_event():
    proc = StreamProcessor(forecast_every=999, report_every=999)

    events = proc.handle_reading(_reading(temp=999.0))
    keys = [k for k, _ in events]

    assert RK_ANOMALY in keys
    anomaly = next(payload for k, payload in events if k == RK_ANOMALY)
    assert anomaly["deviceUid"] == "esp32-01"
    assert anomaly["severity"] in {"LOW", "MEDIUM", "HIGH", "CRITICAL"}
    assert anomaly["source"] == "stream"


def test_generate_report_now_without_history_returns_none():
    proc = StreamProcessor()
    assert proc.generate_report_now("unknown-device") is None


def test_generate_report_now_after_readings():
    proc = StreamProcessor(forecast_every=999, report_every=999)
    for _ in range(3):
        proc.handle_reading(_reading())

    report = proc.generate_report_now("esp32-01")
    assert report is not None
    assert report["sampleCount"] == 3
    assert proc.latest_report("esp32-01") == report


def test_device_table_is_bounded(monkeypatch):
    import stream_processor as sp
    monkeypatch.setattr(sp, "MAX_TRACKED_DEVICES", 2)
    proc = StreamProcessor(forecast_every=999, report_every=999)

    for uid in ("a", "b", "c"):
        r = _reading()
        r["deviceUid"] = uid
        proc.handle_reading(r)

    assert proc.tracked_devices() <= 2


# ---------------------------------------------------------------------------
# Forecast coverage
# ---------------------------------------------------------------------------

def _stub_forecast(proc, horizon):
    """Replaces the transformer call so coverage can be measured without it."""
    features = ["temperature", "humidity", "airQuality", "oxygen", "pressure"]
    proc.build_prediction = lambda uid, window: {
        "deviceUid": uid,
        "predictions": {name: [20.0] * horizon for name in features},
        "method": "stub",
    }


def _coverage(proc, readings=120):
    """How many readings were scored with a forecast available, and how many
    prediction events were published."""
    from ml_service import orchestrator

    counts = {"with": 0, "without": 0}
    original = orchestrator.analyze_with_forecast

    def spy(uid, reading, history, expected):
        counts["with" if expected else "without"] += 1
        return original(uid, reading, history, expected)

    orchestrator.analyze_with_forecast = spy
    published = 0
    try:
        for index in range(readings):
            events = proc.handle_reading(_reading(temp=21.0 + index * 0.01))
            published += sum(1 for key, _ in events if key == RK_PREDICTION)
    finally:
        orchestrator.analyze_with_forecast = original
    return counts, published


def test_every_reading_after_warm_up_is_scored_against_a_forecast():
    """The refresh interval must not outrun the horizon it refreshes.

    These were once the same number used for two different jobs: a forecast was
    taken every `forecast_every` readings but covered only `forecast_horizon`
    of them. With the shipped 10 and 6, 40% of readings were scored with no
    forecast at all — so the residual detector, which carries 100% recall on
    out_of_range, spike, multivariate and drift, was absent for them. The batch
    worker refreshed on exhaustion and had full coverage, so the same reading
    was detected differently depending on which path it arrived by.
    """
    proc = StreamProcessor(forecast_every=10, report_every=10 ** 9,
                           min_history_for_forecast=8, forecast_horizon=6)
    _stub_forecast(proc, horizon=6)

    counts, _ = _coverage(proc, readings=120)

    # Only the readings before there is enough history to forecast at all may
    # go unscored by the residual detector.
    assert counts["without"] <= proc.min_history_for_forecast
    assert counts["with"] == 120 - counts["without"]


def test_publishing_stays_on_its_own_cadence():
    """Refreshing more often must not publish more often.

    `prediction.generated` has downstream consumers, and its interval is a
    contract with them rather than a consequence of how long a forecast stays
    usable.
    """
    proc = StreamProcessor(forecast_every=10, report_every=10 ** 9,
                           min_history_for_forecast=8, forecast_horizon=6)
    _stub_forecast(proc, horizon=6)

    _, published = _coverage(proc, readings=120)

    assert published == 12


def test_coverage_holds_when_the_horizon_exceeds_the_publish_interval():
    """The other direction must not regress either."""
    proc = StreamProcessor(forecast_every=4, report_every=10 ** 9,
                           min_history_for_forecast=8, forecast_horizon=12)
    _stub_forecast(proc, horizon=12)

    counts, published = _coverage(proc, readings=80)

    assert counts["without"] <= proc.min_history_for_forecast
    assert published == 20
