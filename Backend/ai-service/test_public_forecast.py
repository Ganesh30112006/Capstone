"""The public forecast is a projection of the operator prediction, and says what
the model actually forecasts: the next N readings, not the next N days."""
from fastapi.testclient import TestClient

import main
from main import app

client = TestClient(app)

PREDICTION = {
    "eventId": "03f9f78a-18e6-4162-8ef9-fb717c849a5f",
    "deviceUid": "ESP32-CHN-001",
    "generatedAt": "2026-10-10T13:38:20.102898+00:00",
    "horizon": 6,
    "method": "chronos-2",
    "modelName": "amazon/chronos-2",
    "modelVersion": "1.0",
    "predictions": {"temperature": [30.29, 30.26, 30.25, 30.24, 30.22, 30.21]},
    "reasons": [],
    "historyPoints": 48,
}


def test_serves_the_latest_forecast_counted_in_readings(monkeypatch):
    monkeypatch.setattr(main.stream_processor, "latest_prediction", lambda uid: PREDICTION)

    response = client.get("/api/public/forecasts/ESP32-CHN-001")

    assert response.status_code == 200
    body = response.json()
    assert body["stationId"] == "ESP32-CHN-001"
    assert body["horizonReadings"] == 6
    assert body["historyPoints"] == 48
    assert body["predictions"]["temperature"][0] == 30.29


def test_leaves_operator_internals_out(monkeypatch):
    monkeypatch.setattr(main.stream_processor, "latest_prediction", lambda uid: PREDICTION)

    body = client.get("/api/public/forecasts/ESP32-CHN-001").json()

    assert "eventId" not in body
    assert "reasons" not in body
    assert "modelVersion" not in body


def test_a_station_with_no_forecast_yet_is_absent_not_empty(monkeypatch):
    monkeypatch.setattr(main.stream_processor, "latest_prediction", lambda uid: None)

    response = client.get("/api/public/forecasts/ESP32-CHN-404")

    assert response.status_code == 404
