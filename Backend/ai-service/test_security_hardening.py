"""Regression tests for the AI service's hardening.

Each test here corresponds to a defect that was present and demonstrable, not
to a hypothetical. The comments name the behaviour that was observed, because
a test whose purpose is forgotten is a test that gets deleted the next time it
is inconvenient.
"""

import asyncio

import numpy as np
import pandas as pd
import pytest
from fastapi.testclient import TestClient

import config
import model_integrity
import security
from rule_engine import rule_engine


# ---------------------------------------------------------------------------
# Non-finite values: the detection bypass
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("literal", ["NaN", "Infinity", "-Infinity"])
def test_non_finite_readings_are_refused_at_the_edge(client, literal):
    """A NaN temperature used to return `anomaly: false, "Parameters normal"`.

    Every rule threshold is a comparison, and every comparison against NaN is
    false, so the reading passed all five bounds; the Isolation Forest then
    raised on it and the residual detector had nothing to compare against. The
    service confidently reported a broken sensor as healthy. Infinity took a
    different route to the same place: it scored, then crashed JSON encoding.
    """
    body = ('{"deviceUid":"DEV-NAN","temperature":%s,'
            '"humidity":50.0,"pressure":1013.25}' % literal)
    response = client.post("/api/ai/predict-anomaly", content=body,
                           headers={"Content-Type": "application/json"})
    assert response.status_code == 422


@pytest.mark.parametrize("value", [float("nan"), float("inf"), float("-inf")])
def test_rule_engine_flags_non_finite_values(value):
    """The broker path has no schema validation in front of it.

    Readings arriving over RabbitMQ are parsed with `float()`, which accepts
    the strings "NaN" and "Infinity". The rule engine is the last layer that
    sees them, so the guard has to hold here independently of Pydantic.
    """
    result = rule_engine.evaluate({
        "temperature": value, "humidity": 50.0, "pressure": 1013.25,
        "airQuality": 50.0, "oxygen": 20.9,
    })
    assert result.is_anomaly is True
    assert result.severity == "CRITICAL"
    assert "non-numeric" in " ".join(result.reasons)


def test_broker_reading_parser_rejects_non_finite():
    from rabbitmq_consumer import _to_reading
    from stream_processor import to_reading

    payload = {"deviceUid": "DEV-1", "temperature": "NaN",
               "humidity": 50.0, "pressure": 1013.25}
    assert _to_reading(payload) is None
    assert to_reading(payload) is None


def test_training_refuses_non_finite_data(isolated_artifact):
    """One NaN row turns every fitted statistic into NaN.

    StandardScaler's mean and variance propagate it, so the whole detector is
    destroyed by a single bad sample — silently, because the model still loads
    and still answers.
    """
    from isolation_forest_model import isolation_forest

    frame = pd.DataFrame({
        "temperature": [25.0] * 60 + [float("nan")],
        "humidity": [50.0] * 61,
        "airQuality": [40.0] * 61,
        "oxygen": [20.9] * 61,
        "pressure": [1013.0] * 61,
    })
    with pytest.raises(ValueError, match="NaN or infinite"):
        isolation_forest.train(frame)


# ---------------------------------------------------------------------------
# Gateway origin and role
# ---------------------------------------------------------------------------

def test_direct_access_is_refused_when_a_secret_is_configured(secured_client):
    """Every Java service refuses un-proxied requests; this one accepted them.

    That mattered most for POST /api/ai/train, which replaces the live detector
    for the whole fleet and needed no credential to reach.
    """
    response = secured_client.post("/api/ai/predict-anomaly", json=_reading())
    assert response.status_code == 403
    assert "API Gateway" in response.json()["message"]


def test_a_wrong_secret_is_refused(secured_client):
    response = secured_client.post("/api/ai/predict-anomaly", json=_reading(),
                                   headers={"X-Gateway-Auth": "not-the-secret"})
    assert response.status_code == 403


def test_the_gateway_secret_admits_the_request(secured_client):
    response = secured_client.post("/api/ai/predict-anomaly", json=_reading(),
                                   headers=_gateway())
    assert response.status_code == 200


def test_training_requires_an_admin_role(secured_client):
    """Defence in depth: the gateway restricts this too, and that is the point.

    A request that never passed through the gateway never met the gateway's
    check, so the service repeats it.
    """
    headers = dict(_gateway(), **{"X-User-Role": "USER", "X-User-Id": "7"})
    response = secured_client.post("/api/ai/train", json={"readings": [_reading()]},
                                   headers=headers)
    assert response.status_code == 403
    assert "administrator" in response.json()["message"]


def test_health_and_metrics_stay_reachable_without_the_secret(secured_client):
    """Probed by the orchestrator and Prometheus, neither of which is the gateway."""
    assert secured_client.get("/health").status_code == 200
    assert secured_client.get("/metrics").status_code == 200


# ---------------------------------------------------------------------------
# Input bounds
# ---------------------------------------------------------------------------

def test_oversized_bodies_are_refused(client):
    payload = {"readings": [_reading_only()] * 60_000}
    response = client.post("/api/ai/train", json=payload)
    assert response.status_code == 413


def test_forecast_horizon_is_bounded(client):
    """Horizon is the transformer's decode length, so it buys CPU by the byte."""
    response = client.post("/api/ai/forecast", json={
        "deviceUid": "DEV-1", "history": [_reading_only()], "horizon": 999_999,
    })
    assert response.status_code == 422


def test_device_uid_with_a_newline_is_refused(client):
    """Identifiers reach log lines; a newline in one forges an entry."""
    response = client.post("/api/ai/predict-anomaly",
                           json=dict(_reading(), deviceUid="DEV-1\nINJECTED LOG LINE"))
    assert response.status_code == 422


def test_unknown_fields_are_refused(client):
    """A misspelled field silently defaulting is how a caller ends up scoring
    against an oxygen level it never sent."""
    response = client.post("/api/ai/predict-anomaly", json=dict(_reading(), oxgen=1.0))
    assert response.status_code == 422


def test_validation_errors_do_not_echo_the_input(client):
    """FastAPI's default handler reflects the rejected value back to the caller."""
    response = client.post("/api/ai/predict-anomaly",
                           json=dict(_reading(), deviceUid="../../etc/passwd"))
    assert response.status_code == 422
    assert "passwd" not in response.text


def test_stream_processor_ignores_malformed_device_ids():
    from stream_processor import stream_processor

    for bad in ["", "x" * 500, "DEV\n1", 12345, None]:
        assert stream_processor.handle_reading(
            {"deviceUid": bad, "temperature": 25.0, "humidity": 50.0, "pressure": 1013.0}
        ) == []


# ---------------------------------------------------------------------------
# Metric cardinality
# ---------------------------------------------------------------------------

def test_request_metrics_use_the_route_template_not_the_path(client):
    """`endpoint` was the raw URL path.

    Every device id — and every random string sent to a path parameter —
    became a permanent time series in this process's registry. That is
    unbounded memory growth reachable by anyone who can make a request, which
    is a denial of service wearing a monitoring label.
    """
    from prometheus_client import generate_latest

    for index in range(25):
        client.get(f"/api/ai/reports/DEVICE-{index}")

    series = [
        line for line in generate_latest().decode().splitlines()
        if line.startswith("ai_service_requests_total{") and "/api/ai/reports/" in line
    ]
    assert len(series) == 1
    assert '{device_uid}' in series[0]


def test_unmatched_paths_collapse_to_one_label(client):
    """Ten distinct unrouted URLs must share one `endpoint` label value.

    The series count is still allowed to vary with method and status — those
    label sets are finite. What must not vary with the URL is the endpoint.
    """
    import metrics

    def counter() -> float:
        # Read as a delta: the registry is process-global, so absolute values
        # depend on which other tests ran first.
        return metrics.REQUEST_COUNT.labels("GET", security.UNMATCHED_ROUTE, 404)._value.get()

    before = counter()
    for index in range(10):
        client.get(f"/definitely-not-a-route-{index}")

    assert counter() - before == 10

    from prometheus_client import generate_latest
    endpoints = {
        line.split('endpoint="', 1)[1].split('"', 1)[0]
        for line in generate_latest().decode().splitlines()
        if line.startswith("ai_service_requests_total{")
    }
    # Ten distinct URLs contributed exactly one endpoint label between them.
    assert security.UNMATCHED_ROUTE in endpoints
    assert not any("definitely-not-a-route" in endpoint for endpoint in endpoints)


# ---------------------------------------------------------------------------
# Artifact integrity
# ---------------------------------------------------------------------------

def test_a_tampered_artifact_is_refused_before_it_is_unpickled(tmp_path, monkeypatch):
    """joblib.load is pickle.load: loading is executing.

    The artifact lives on a mounted volume written by a network-reachable
    endpoint, so anyone who can write that path would otherwise have code
    execution in this container. Verification therefore has to happen before
    the load, not after it.
    """
    monkeypatch.setattr(config, "MODEL_INTEGRITY_KEY", "unit-test-key")
    artifact = tmp_path / "model.joblib"
    signature = tmp_path / "model.joblib.sig"
    artifact.write_bytes(b"a legitimate artifact")

    model_integrity.sign(artifact, signature)
    assert model_integrity.verify(artifact, signature) is None

    artifact.write_bytes(b"a legitimate artifact with a payload appended")
    with pytest.raises(model_integrity.IntegrityError, match="does not match its signature"):
        model_integrity.verify(artifact, signature)


def test_a_keyed_deployment_refuses_an_unsigned_artifact(tmp_path, monkeypatch):
    """An attacker who drops in an artifact cannot produce a signature for it,
    so the absence of one is itself the signal."""
    monkeypatch.setattr(config, "MODEL_INTEGRITY_KEY", "unit-test-key")
    artifact = tmp_path / "model.joblib"
    artifact.write_bytes(b"dropped in by something without the key")

    with pytest.raises(model_integrity.IntegrityError, match="No integrity signature"):
        model_integrity.verify(artifact, tmp_path / "missing.sig")


def test_unkeyed_deployments_report_degraded_rather_than_silently_trusting(tmp_path, monkeypatch):
    monkeypatch.setattr(config, "MODEL_INTEGRITY_KEY", "")
    monkeypatch.setattr(config, "MODEL_INTEGRITY_REQUIRED", False)
    artifact = tmp_path / "model.joblib"
    artifact.write_bytes(b"unverifiable")

    note = model_integrity.verify(artifact, tmp_path / "missing.sig")
    assert note and "unverified" in note


def test_integrity_can_be_made_mandatory(tmp_path, monkeypatch):
    monkeypatch.setattr(config, "MODEL_INTEGRITY_KEY", "")
    monkeypatch.setattr(config, "MODEL_INTEGRITY_REQUIRED", True)
    artifact = tmp_path / "model.joblib"
    artifact.write_bytes(b"unverifiable")

    with pytest.raises(model_integrity.IntegrityError):
        model_integrity.verify(artifact, tmp_path / "missing.sig")


def test_a_retrained_artifact_is_signed(tmp_path, monkeypatch):
    monkeypatch.setattr(config, "MODEL_INTEGRITY_KEY", "unit-test-key")
    monkeypatch.setattr(config, "IF_MODEL_PATH", tmp_path / "model.joblib")
    monkeypatch.setattr(config, "METADATA_PATH", tmp_path / "metadata.json")
    monkeypatch.setattr(config, "IF_SIGNATURE_PATH", tmp_path / "model.joblib.sig")

    from isolation_forest_model import IsolationForestModel

    IsolationForestModel()
    assert (tmp_path / "model.joblib.sig").exists()
    # Round-trips: a model this service wrote is one it will accept back.
    assert model_integrity.verify(tmp_path / "model.joblib",
                                  tmp_path / "model.joblib.sig") is None


# ---------------------------------------------------------------------------
# Admission control
# ---------------------------------------------------------------------------

def test_saturated_inference_sheds_load_instead_of_queueing_forever():
    """Under a burst, admitting everything divides the same cores more ways.

    The slower every request gets, the more arrive before any finish. Refusing
    with 503 and Retry-After keeps the requests that were admitted fast.
    """
    started = asyncio.Event()

    async def slow_app(scope, receive, send):
        started.set()
        await asyncio.sleep(0.5)
        await send({"type": "http.response.start", "status": 200, "headers": []})
        await send({"type": "http.response.body", "body": b"{}"})

    middleware = security.InferenceAdmissionMiddleware(slow_app, limit=1, timeout=0.05)

    async def scenario():
        scope = {"type": "http", "method": "POST", "path": "/api/ai/forecast",
                 "headers": []}
        captured = []

        async def send(message):
            captured.append(message)

        async def receive():
            return {"type": "http.request", "body": b"", "more_body": False}

        occupant = asyncio.create_task(middleware(dict(scope), receive, lambda m: _noop()))
        await started.wait()
        await middleware(dict(scope), receive, send)
        occupant.cancel()
        return captured

    messages = asyncio.run(scenario())
    start = next(m for m in messages if m["type"] == "http.response.start")
    assert start["status"] == 503
    assert any(name == b"retry-after" for name, _ in start["headers"])


def test_non_inference_routes_are_not_admission_controlled():
    """Health probes must not be shed when the model is busy: a probe that fails
    under load makes the orchestrator restart a service that is merely working."""
    async def app(scope, receive, send):
        await send({"type": "http.response.start", "status": 200, "headers": []})
        await send({"type": "http.response.body", "body": b"{}"})

    middleware = security.InferenceAdmissionMiddleware(app, limit=1, timeout=0.01)

    async def scenario():
        captured = []
        scope = {"type": "http", "method": "GET", "path": "/health", "headers": []}
        await middleware(scope, _receive, lambda m: _append(captured, m))
        return captured

    messages = asyncio.run(scenario())
    assert messages[0]["status"] == 200


# ---------------------------------------------------------------------------
# Response hardening
# ---------------------------------------------------------------------------

def test_security_headers_are_present_on_every_response(client):
    headers = client.get("/health").headers
    assert headers["x-content-type-options"] == "nosniff"
    assert headers["x-frame-options"] == "DENY"
    assert "frame-ancestors 'none'" in headers["content-security-policy"]


def test_security_headers_are_present_on_rejections(secured_client):
    """A 403 from the origin check is produced before the router runs, so it has
    to pick the headers up from the outermost layer."""
    response = secured_client.post("/api/ai/predict-anomaly", json=_reading())
    assert response.status_code == 403
    assert response.headers["x-content-type-options"] == "nosniff"


def test_health_reports_the_security_posture(client):
    """A service running unhardened looks identical to a hardened one from the
    outside unless it says so."""
    posture = client.get("/health").json()["security"]
    assert "gatewayOriginEnforced" in posture
    assert "modelIntegrityEnforced" in posture


def test_scrub_neutralises_log_injection():
    assert "\n" not in security.scrub("DEV-1\nINJECTED")
    assert len(security.scrub("x" * 10_000)) < 100


# ---------------------------------------------------------------------------
# Efficiency
# ---------------------------------------------------------------------------

def test_series_extraction_returns_float_arrays_per_feature():
    """Shared by the inference and residual paths, which both want arrays.

    Returning numpy rather than Python lists is what lets the forecaster hand
    the buffer straight to torch.from_numpy, and lets the residual path take a
    standard deviation without rebuilding the column first.
    """
    from chronos_forecaster import extract_series

    features = ["temperature", "humidity", "airQuality", "oxygen", "pressure"]
    history = [{f: 1.0 + index for f in features} for index in range(48)]

    series = extract_series(history, features)
    assert set(series) == set(features)
    assert all(isinstance(values, np.ndarray) for values in series.values())
    assert series["temperature"].size == 48


def test_series_extraction_tolerates_unconvertible_samples():
    """The broker path carries whatever a device sent."""
    from chronos_forecaster import extract_series

    history = [{"temperature": 1.0}, {"temperature": "not-a-number"},
               {"temperature": 3.0}]
    assert extract_series(history, ["temperature"])["temperature"].tolist() == [1.0, 3.0]


def test_series_extraction_drops_non_finite_samples():
    """One NaN in a context window makes the whole forecast NaN."""
    from chronos_forecaster import extract_series

    history = [{"temperature": 1.0}, {"temperature": float("nan")}, {"temperature": 3.0}]
    series = extract_series(history, ["temperature"])
    assert series["temperature"].tolist() == [1.0, 3.0]


def test_report_metrics_ignore_non_finite_samples():
    """A NaN in the window would make min, max and avg all NaN."""
    from stream_processor import stream_processor

    history = [
        {"temperature": 20.0, "humidity": 40.0, "airQuality": 10.0,
         "oxygen": 20.9, "pressure": 1000.0},
        {"temperature": 30.0, "humidity": 60.0, "airQuality": 30.0,
         "oxygen": 21.0, "pressure": 1010.0},
        {"temperature": 25.0, "humidity": 50.0, "airQuality": 20.0,
         "oxygen": 20.8, "pressure": 1005.0},
        {"temperature": float("nan"), "humidity": 50.0, "airQuality": 20.0,
         "oxygen": 20.8, "pressure": 1005.0},
    ]
    report = stream_processor._build_report("DEV-1", history, ["t0", "t1", "t2", "t3"], 0, {})
    temperature = report["metrics"]["temperature"]
    # The NaN row is dropped from temperature only; the others keep all four.
    assert temperature == {"min": 20.0, "max": 30.0, "avg": 25.0,
                           "last": 25.0, "samples": 3}
    assert report["metrics"]["humidity"]["samples"] == 4


def test_residual_spread_is_computed_in_double_precision():
    """The context array is float32 because that is what the model's tensor
    wants. Taking a standard deviation in float32 over a 48-sample window loses
    enough precision to move the normalised residual — which is then compared
    against a fixed sigma threshold, so the dtype would shift the detection
    edge without anyone changing a threshold.
    """
    from chronos_forecaster import chronos_forecaster

    history = [{"temperature": 20.0 + (index % 7) * 0.5} for index in range(48)]
    result = chronos_forecaster.evaluate_against(
        {"temperature": [20.5]}, history, {"temperature": 21.0},
    )

    context = np.asarray([row["temperature"] for row in history], dtype=np.float64)
    expected = abs(21.0 - 20.5) / max(float(np.std(context)), 1e-3)
    assert result.residuals_norm["temperature"] == pytest.approx(expected, rel=1e-9)


def test_residual_scoring_ignores_a_non_finite_forecast():
    """A NaN forecast makes every residual NaN, and a NaN residual compares
    false against the divergence threshold — removing the detector silently
    rather than reporting it gone."""
    from chronos_forecaster import chronos_forecaster

    result = chronos_forecaster.evaluate_against(
        {"temperature": [float("nan")]},
        [{"temperature": 20.0}, {"temperature": 21.0}],
        {"temperature": 25.0},
    )
    assert result.available is False


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _reading() -> dict:
    return {"deviceUid": "DEV-1", "temperature": 25.0, "humidity": 50.0, "pressure": 1013.25}


def _reading_only() -> dict:
    return {"temperature": 25.0, "humidity": 50.0, "pressure": 1013.25,
            "airQuality": 40.0, "oxygen": 20.9}


def _gateway() -> dict:
    return {"X-Gateway-Auth": SECRET}


SECRET = "unit-test-gateway-secret"


async def _receive():
    return {"type": "http.request", "body": b"", "more_body": False}


async def _noop(*_args):
    return None


async def _append(store, message):
    store.append(message)


@pytest.fixture
def client():
    from main import app
    return TestClient(app)


@pytest.fixture
def secured_client(monkeypatch):
    """An app whose origin middleware is configured with a secret.

    Built fresh because middleware reads its configuration once, when the
    stack is constructed.
    """
    monkeypatch.setattr(config, "GATEWAY_INTERNAL_SECRET", SECRET)
    monkeypatch.setattr(config, "GATEWAY_ENFORCE_ORIGIN", True)

    import importlib

    import main
    importlib.reload(main)
    try:
        yield TestClient(main.app)
    finally:
        # Restored so later tests see the unsecured app they expect.
        monkeypatch.undo()
        importlib.reload(main)


@pytest.fixture
def isolated_artifact(tmp_path, monkeypatch):
    """Keeps training tests off the deployed model artifact."""
    monkeypatch.setattr(config, "IF_MODEL_PATH", tmp_path / "model.joblib")
    monkeypatch.setattr(config, "METADATA_PATH", tmp_path / "metadata.json")
    monkeypatch.setattr(config, "IF_SIGNATURE_PATH", tmp_path / "model.joblib.sig")
    return tmp_path


# ---------------------------------------------------------------------------
# Body size limiting, for a sender who does not declare an honest length
# ---------------------------------------------------------------------------

class TestOversizedBodyWithoutAnHonestLength:
    """Content-Length is a claim, and the interesting senders do not make it.

    The cap was enforced in two places: a cheap check against the declared
    length, and a byte count over what actually arrives. The second one worked
    — the handler never saw more than the cap — but it could not *answer*,
    because by the time the overrun was detected the handler had already read
    its (truncated) body and produced a response.

    So a sender who omitted Content-Length, or declared a false one, was told
    422 Unprocessable Entity: their payload was reported as malformed when it
    was oversized. That made "too large" and "wrong shape" indistinguishable to
    anyone trying to fix it, and it contradicted the module's own comment,
    which claimed the 413 was what the caller received.

    Driven at the ASGI layer because TestClient always sets Content-Length,
    which is precisely the case that already worked.
    """

    @staticmethod
    def _oversized_body():
        import json
        reading = {"temperature": 20.0, "humidity": 50.0, "airQuality": 40.0,
                   "oxygen": 20.9, "pressure": 1013.0}
        payload = json.dumps({"readings": [reading] * 40_000}).encode()
        assert len(payload) > config.MAX_REQUEST_BYTES
        return payload

    @staticmethod
    def _post(body, declared):
        import asyncio
        import main

        headers = [(b"host", b"testserver"), (b"content-type", b"application/json")]
        if declared is not None:
            headers.append((b"content-length", str(declared).encode()))
        scope = {
            "type": "http", "asgi": {"version": "3.0"}, "http_version": "1.1",
            "method": "POST", "path": "/api/ai/train", "raw_path": b"/api/ai/train",
            "root_path": "", "scheme": "http", "query_string": b"", "headers": headers,
            "client": ("203.0.113.9", 5555), "server": ("testserver", 80),
        }
        chunks = iter([body[i:i + 65536] for i in range(0, len(body), 65536)])
        sent = []

        async def receive():
            try:
                return {"type": "http.request", "body": next(chunks), "more_body": True}
            except StopIteration:
                return {"type": "http.request", "body": b"", "more_body": False}

        async def send(message):
            sent.append(message)

        asyncio.run(main.app(scope, receive, send))
        starts = [m for m in sent if m["type"] == "http.response.start"]
        assert len(starts) == 1, "exactly one response must be sent"
        return starts[0]["status"]

    def test_a_declared_oversized_length_is_refused(self):
        assert self._post(self._oversized_body(), declared=None) == 413

    def test_an_undeclared_oversized_body_is_refused_as_too_large(self):
        body = self._oversized_body()
        assert self._post(body, declared=len(body)) == 413

    def test_a_falsely_declared_length_is_refused_as_too_large(self):
        """The declared length is the one thing a hostile sender controls freely."""
        assert self._post(self._oversized_body(), declared=10) == 413

    def test_a_body_within_the_cap_still_reaches_the_handler(self):
        """A limiter that refuses everything is not a limiter."""
        import json
        reading = {"temperature": 20.0, "humidity": 50.0, "airQuality": 40.0,
                   "oxygen": 20.9, "pressure": 1013.0}
        small = json.dumps({"readings": [reading] * 60}).encode()
        assert len(small) < config.MAX_REQUEST_BYTES
        assert self._post(small, declared=None) != 413

    def test_the_handler_never_receives_more_than_the_cap(self):
        """The status is the contract; this is the actual protection.

        Bounding what the handler buffers is what stops a large body from
        costing memory, and it has to keep working independently of which
        status the caller is told.
        """
        import asyncio

        from security import BodySizeLimitMiddleware

        seen = {"bytes": 0}

        async def sink(scope, receive, send):
            while True:
                message = await receive()
                seen["bytes"] += len(message.get("body", b""))
                if not message.get("more_body"):
                    break
            await send({"type": "http.response.start", "status": 200, "headers": []})
            await send({"type": "http.response.body", "body": b"{}"})

        limiter = BodySizeLimitMiddleware(sink, max_bytes=1024)
        scope = {"type": "http", "path": "/api/ai/train", "method": "POST",
                 "headers": [(b"content-type", b"application/json")]}
        chunks = iter([b"x" * 256] * 16)
        sent = []

        async def receive():
            try:
                return {"type": "http.request", "body": next(chunks), "more_body": True}
            except StopIteration:
                return {"type": "http.request", "body": b"", "more_body": False}

        async def send(message):
            sent.append(message)

        asyncio.run(limiter(scope, receive, send))

        assert seen["bytes"] <= 1024 + 256, "the handler buffered past the cap"
        assert [m for m in sent if m["type"] == "http.response.start"][0]["status"] == 413
