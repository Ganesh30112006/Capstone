import pytest
from fastapi.testclient import TestClient

import config
from main import app

client = TestClient(app)


@pytest.fixture
def isolated_artifact(tmp_path, monkeypatch):
    """Keeps training tests off the deployed model artifact.

    Without this, running the suite retrains and overwrites the real
    models/ directory — which is how a model fitted on a single test reading
    ended up on disk, reporting itself as non-synthetic.
    """
    monkeypatch.setattr(config, "IF_MODEL_PATH", tmp_path / "model.joblib")
    monkeypatch.setattr(config, "METADATA_PATH", tmp_path / "metadata.json")
    return tmp_path


def _readings(count: int) -> list:
    return [
        {
            "temperature": 20.0 + (i % 10) * 0.5,
            "humidity": 50.0 + (i % 7),
            "airQuality": 40.0 + (i % 13),
            "oxygen": 20.9,
            "pressure": 1010.0 + (i % 5),
        }
        for i in range(count)
    ]

def test_health_check():
    response = client.get("/health")
    assert response.status_code == 200
    data = response.json()
    # Not asserted as UP. Nothing in this test process connects to a broker, so
    # both consumers are disconnected and DEGRADED is the truthful answer -- the
    # previous unconditional assertion held only because the endpoint returned
    # UP whatever was happening, which is the defect test_health_probes covers.
    assert data["status"] in {"UP", "DEGRADED", "DOWN"}
    assert data["status"] == ("UP" if data["messaging"]["scoringWorkerConnected"] else "DEGRADED")
    assert "models" in data

def test_model_info():
    response = client.get("/api/ai/model-info")
    assert response.status_code == 200
    data = response.json()
    assert "Integration" not in data.get("anomaly_model", "")
    assert data["forecast_model"] == "amazon/chronos-2"

def test_train_endpoint_empty():
    response = client.post("/api/ai/train", json={"readings": []})
    assert response.status_code == 400

def test_train_endpoint_rejects_a_dataset_too_small_to_fit():
    """One reading cannot fit a scaler or a forest. Accepting it would return
    SUCCESS for a model that detects nothing."""
    response = client.post("/api/ai/train", json={"readings": _readings(1)})
    assert response.status_code == 422
    assert "at least" in response.json()["detail"].lower()


def test_train_endpoint_valid(isolated_artifact):
    count = config.MIN_TRAINING_SAMPLES + 10
    response = client.post("/api/ai/train", json={"readings": _readings(count)})
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "SUCCESS"
    assert data["samples_trained"] == count

def test_legacy_predict_normal():
    payload = {
        "deviceUid": "DEV-TEST-01",
        "temperature": 25.0,
        "humidity": 50.0,
        "pressure": 1013.25
    }
    response = client.post("/api/ai/predict-anomaly", json=payload)
    assert response.status_code == 200
    data = response.json()
    # Should not be a hard anomaly
    # If untrained model thinks it is, anomaly boolean will depend on threshold
    assert "anomaly" in data

def test_legacy_predict_critical_anomaly():
    payload = {
        "deviceUid": "DEV-TEST-01",
        "temperature": 100.0, # Rule Engine violation
        "humidity": 50.0,
        "pressure": 1013.25
    }
    response = client.post("/api/ai/predict-anomaly", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert data["anomaly"] is True

def test_anomaly_analysis_endpoint():
    payload = {
        "deviceUid": "DEV-TEST-02",
        "current": {
            "temperature": 22.1,
            "humidity": 45.0,
            "pressure": 1015.0
        },
        "history": [
            {"temperature": 22.0, "humidity": 45.0, "pressure": 1015.0},
            {"temperature": 22.2, "humidity": 44.9, "pressure": 1015.1}
        ]
    }
    response = client.post("/api/ai/anomaly-analysis", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert "severity" in data["anomaly"]
    assert "forecast" in data
    assert "reasons" in data

def test_forecast_missing_history():
    payload = {
        "deviceUid": "DEV-TEST-03",
        "history": []
    }
    response = client.post("/api/ai/forecast", json=payload)
    assert response.status_code == 400

def test_forecast_success_graceful_missing_chronos():
    payload = {
        "deviceUid": "DEV-TEST-03",
        "history": [
            {"temperature": 22.0, "humidity": 45.0, "pressure": 1015.0},
            {"temperature": 22.2, "humidity": 44.9, "pressure": 1015.1},
            {"temperature": 22.3, "humidity": 45.2, "pressure": 1014.9},
            {"temperature": 22.1, "humidity": 45.1, "pressure": 1015.2},
            {"temperature": 22.5, "humidity": 45.0, "pressure": 1015.0},
            {"temperature": 22.4, "humidity": 45.3, "pressure": 1015.3}
        ]
    }
    response = client.post("/api/ai/forecast", json=payload)
    # Test will succeed but expected values will be empty if env lacking torch/chronos libs
    assert response.status_code == 200
    data = response.json()
    assert "reasons" in data
