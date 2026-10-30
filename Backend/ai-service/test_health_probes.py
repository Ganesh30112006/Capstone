"""Health must describe what the service can actually do.

`/health` reported UP unconditionally. The reasoning was that a disconnected
worker should not make an orchestrator cycle a process that is serving requests
fine -- which is correct, and is why /health/live exists and ignores the broker.
What the unconditional UP also did, though, was tell the operator console that
AI Inference was operational while the consumer was dead and not one reading was
being scored. The detail block directly beneath it said "reconnecting to the
broker" at the same time.

There were two consumers when these tests were written, and the service reported
UP while either one held. There is one now — scoring the backend branch — so a
disconnected worker has nothing to hide behind, which is what most of these
assertions are checking.
"""
import pytest
from fastapi.testclient import TestClient

import main
from main import app

client = TestClient(app)


@pytest.fixture
def bus(monkeypatch):
    """Control what the scoring worker reports about its connection."""
    state = {"worker": True, "detector": True, "degraded": None}
    monkeypatch.setattr(main.stream_consumer, "is_connected", lambda: state["worker"])
    monkeypatch.setattr(type(main.isolation_forest), "is_available",
                        property(lambda self: state["detector"]))
    monkeypatch.setattr(main.isolation_forest, "degraded_reason", state["degraded"],
                        raising=False)
    return state


def test_healthy_when_consuming(bus):
    body = client.get("/health").json()
    assert body["status"] == "UP"
    assert body["messaging"]["scoringWorkerConnected"] is True


def test_degraded_when_the_broker_is_unreachable(bus):
    bus["worker"] = False

    body = client.get("/health").json()

    # The failure this test exists for: UP here put a green badge on the
    # console for a service scoring nothing at all.
    assert body["status"] == "DEGRADED"
    assert body["messaging"]["detail"] == "reconnecting to the broker"


def test_a_disconnected_worker_is_not_hidden(bus):
    # This used to read "still UP while one consumer holds": with two workers a
    # dead one was masked by its twin. One worker scores now, so nothing is
    # being scored when it is down, and the status has to say so.
    bus["worker"] = False
    body = client.get("/health").json()
    assert body["status"] == "DEGRADED"


def test_down_only_when_nothing_can_be_scored(bus):
    bus["detector"] = False
    body = client.get("/health").json()
    # No route can score now, HTTP included -- a stronger claim than DEGRADED.
    assert body["status"] == "DOWN"


def test_liveness_ignores_the_broker(bus):
    bus["worker"] = False

    response = client.get("/health/live")

    # Restarting cannot reconnect a broker that is down, and cycling the
    # service would discard the flatline detector's learned per-device history.
    assert response.status_code == 200
    assert response.json()["status"] == "UP"


def test_readiness_refuses_when_not_consuming(bus):
    bus["worker"] = False

    response = client.get("/health/ready")

    # 503 so that anything reading only the status code cannot mistake
    # "not consuming telemetry" for "consuming it fine".
    assert response.status_code == 503
    body = response.json()
    assert body["status"] == "DEGRADED"
    assert body["scoringWorkerConnected"] is False


def test_readiness_passes_when_consuming(bus):
    response = client.get("/health/ready")
    assert response.status_code == 200
    assert response.json()["status"] == "UP"


def test_the_gateway_maps_a_degraded_status_to_a_degraded_badge(bus):
    """SystemHealthController maps UP to operational and anything else to degraded.

    Recorded here because the console's correctness depends on this service
    returning a status that is not the string "UP" when it is not well -- the
    mapping on the gateway side is already right.
    """
    bus["worker"] = False
    assert client.get("/health").json()["status"] != "UP"


def test_the_probes_are_reachable_without_going_through_the_gateway():
    """An orchestrator probes these directly on the private network.

    They were 403 on first deployment: the origin-enforcement middleware matches
    infrastructure paths exactly, by design, so that a route mounted under
    /health cannot become unauthenticated merely by where it sits. That design is
    right, and the two probes are exempt because it was decided, not inherited.
    """
    import security

    assert "/health/live" in security.INFRASTRUCTURE_PATHS
    assert "/health/ready" in security.INFRASTRUCTURE_PATHS
    # Still not a subtree exemption: anything else under /health stays protected.
    assert "/health" not in security.INFRASTRUCTURE_PREFIXES
