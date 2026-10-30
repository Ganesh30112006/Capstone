"""Settings that are each valid alone and wrong together.

This service's characteristic failure is not a crash. It is a configuration in
which the service starts, answers every request, reports itself healthy, and
silently no longer detects something. That has shipped three times:

  * the original scorer summed a rule weight into its denominator, capping every
    achievable score at 0.500 against a threshold of 0.75 — both models pinned
    at maximum certainty produced no anomaly, and the whole test suite passed;
  * the flatline detector's score floor sat below ANOMALY_THRESHOLD/W_FLATLINE,
    so it identified frozen sensors internally while the service said nothing;
  * FLATLINE_WARMUP_RUNS at 0 was measured raising alarms on 95% of readings
    from a perfectly healthy instrument.

No single setting is out of range in any of those. What is wrong is a
*relationship*, so `audit_detection_settings` checks relationships, and these
tests pin the ones that have actually bitten.

The audit reports rather than raises. Refusing to start would convert a
degraded detector into an outage, and this service is explicitly designed to be
non-essential — storage and rule-based alerting continue without it.
"""

import pytest

import config


@pytest.fixture()
def settings(monkeypatch):
    """Lets a test set one relationship wrong without disturbing the others."""
    def apply(**overrides):
        for name, value in overrides.items():
            assert hasattr(config, name), f"no such setting: {name}"
            monkeypatch.setattr(config, name, value)
        return config.audit_detection_settings()
    return apply


def _messages(findings):
    return " ".join(message for _severity, message in findings)


def test_the_shipped_configuration_is_coherent():
    """The defaults must pass their own audit, or it means nothing."""
    assert config.audit_detection_settings() == []


# ---------------------------------------------------------------------------
# Mute detectors
# ---------------------------------------------------------------------------

def test_a_flatline_weight_below_the_threshold_is_an_error(settings):
    """The most serious incoherence available, because nothing else covers it.

    Rules, the Isolation Forest and the forecast residual each measured 0%
    recall on a frozen sensor — and the residual does worse than miss it, since
    a flat series predicts itself perfectly and reports maximum agreement. So a
    flatline weight under the threshold removes the failure mode from coverage
    entirely rather than merely making it harder to detect.
    """
    findings = settings(W_FLATLINE=0.4, ANOMALY_THRESHOLD=0.75)
    assert any(severity == "error" for severity, _ in findings)
    assert "AI_WEIGHT_FLATLINE" in _messages(findings)


def test_a_residual_weight_below_the_threshold_is_a_warning(settings):
    """Less serious: the forest can still corroborate a residual signal."""
    findings = settings(W_RESIDUAL=0.5, ANOMALY_THRESHOLD=0.75)
    assert any(severity == "warning" and "AI_WEIGHT_RESIDUAL" in message
               for severity, message in findings)


def test_the_forest_weight_below_the_threshold_is_not_reported(settings):
    """It is 0.4 against 0.75 by design, and measured better that way.

    An audit that flags a deliberate decision trains people to ignore it.
    """
    findings = settings(W_IF=0.4, ANOMALY_THRESHOLD=0.75)
    assert "AI_WEIGHT_IF" not in _messages(findings)


def test_raising_the_threshold_past_a_weight_mutes_that_detector(settings):
    """The same incoherence reached from the other side.

    Raising the threshold looks like a local decision about sensitivity, and it
    silently switches detectors off one at a time as it passes their weights.
    """
    assert settings(ANOMALY_THRESHOLD=0.95) != []
    assert "AI_WEIGHT_FLATLINE" in _messages(settings(ANOMALY_THRESHOLD=0.95))


# ---------------------------------------------------------------------------
# Ranges
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("threshold", [0.0, -1.0, 1.5, 2.0])
def test_an_unreachable_threshold_is_an_error(settings, threshold):
    findings = settings(ANOMALY_THRESHOLD=threshold)
    assert any(severity == "error" and "AI_OVERALL_THRESHOLD" in message
               for severity, message in findings)


@pytest.mark.parametrize("weight", [-0.5, 1.5])
def test_a_weight_outside_zero_to_one_is_an_error(settings, weight):
    findings = settings(W_IF=weight)
    assert any(severity == "error" and "AI_WEIGHT_IF" in message
               for severity, message in findings)


def test_zero_warmup_runs_is_an_error(settings):
    """Measured at 95% false alarms on a healthy instrument."""
    findings = settings(FLATLINE_WARMUP_RUNS=0)
    assert any(severity == "error" and "WARMUP" in message
               for severity, message in findings)


def test_an_alarm_point_of_one_repeat_is_an_error(settings):
    """Every quantised instrument repeats a value; that cannot be the alarm."""
    assert any(severity == "error" for severity, _ in
               settings(FLATLINE_MIN_RUN=0, FLATLINE_RUN_MULTIPLIER=0.0))


def test_a_ceiling_below_the_floor_is_reported(settings):
    """The two would then disagree, and the ceiling would win everywhere."""
    findings = settings(FLATLINE_ABSOLUTE_RUN=5, FLATLINE_MIN_RUN=30)
    assert any("ABSOLUTE_RUN" in message for _severity, message in findings)


def test_a_disabled_ceiling_is_not_reported(settings):
    """0 means off, which is the recommended setting, not a misconfiguration."""
    assert settings(FLATLINE_ABSOLUTE_RUN=0) == []


@pytest.mark.parametrize("name", [
    "FLATLINE_MAX_DEVICES", "FORECAST_CACHE_MAX_DEVICES", "MAX_CONCURRENT_INFERENCE",
])
def test_a_table_bound_below_one_is_reported_as_clamped(settings, name):
    """These are clamped at use rather than honoured; say so rather than
    leaving an operator to believe a bound of 0 took effect."""
    findings = settings(**{name: 0})
    assert any(severity == "warning" for severity, _ in findings)


# ---------------------------------------------------------------------------
# Surfaced where someone will see it
# ---------------------------------------------------------------------------

def test_health_reports_the_findings(monkeypatch):
    """A degraded detector is invisible in every other field of /health."""
    import tempfile
    from pathlib import Path
    sandbox = Path(tempfile.mkdtemp(prefix="ai-coherence-"))
    monkeypatch.setattr(config, "IF_MODEL_PATH", sandbox / "model.joblib")
    monkeypatch.setattr(config, "METADATA_PATH", sandbox / "metadata.json")
    monkeypatch.setattr(config, "IF_SIGNATURE_PATH", sandbox / "model.joblib.sig")

    import main
    from fastapi.testclient import TestClient
    client = TestClient(main.app)

    assert client.get("/health").json()["detectionConfigFindings"] == []

    monkeypatch.setattr(config, "W_FLATLINE", 0.4)
    findings = client.get("/health").json()["detectionConfigFindings"]
    assert findings and findings[0]["severity"] == "error"
    assert "AI_WEIGHT_FLATLINE" in findings[0]["message"]
