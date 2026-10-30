"""Regression tests for the AI service's failure modes and its cost model.

Each test here corresponds to a defect that shipped, or to an efficiency
property that is easy to lose in a refactor and impossible to notice by reading
the output — a silently disabled detector and a working one both return 200
with a plausible-looking score.
"""

from __future__ import annotations

import numpy as np
import pandas as pd
import pytest
from sklearn.ensemble import IsolationForest

import config
from anomaly_scorer import anomaly_scorer
from chronos_forecaster import ForecastResult
from isolation_forest_model import IsolationForestModel, IsolationForestResult
from rule_engine import rule_engine
from stream_processor import StreamProcessor, _forecast_step

EXTREME = {"temperature": 500.0, "humidity": 5.0, "airQuality": 999.0, "oxygen": 1.0, "pressure": 1.0}
NORMAL = {"temperature": 25.0, "humidity": 55.0, "airQuality": 50.0, "oxygen": 20.9, "pressure": 1013.0}


@pytest.fixture
def artifact_paths(tmp_path, monkeypatch):
    """Redirects model persistence at a temp dir so tests never touch the real artifact."""
    model_path = tmp_path / "model.joblib"
    metadata_path = tmp_path / "metadata.json"
    monkeypatch.setattr(config, "IF_MODEL_PATH", model_path)
    monkeypatch.setattr(config, "METADATA_PATH", metadata_path)
    return model_path, metadata_path


class TestIsolationForestPersistence:
    """The scaler used to be fitted in memory and never saved. Every restarted
    container then reloaded the forest, raised NotFittedError inside evaluate(),
    swallowed it into a reasons string, and scored every reading 0.0 — so a
    reading of 500 degrees was reported as perfectly normal."""

    def test_detects_an_extreme_reading_after_a_restart(self, artifact_paths):
        model_path, _ = artifact_paths

        first = IsolationForestModel()
        assert first.is_available
        assert model_path.exists(), "training must persist an artifact"

        # A second instance reads the artifact from disk, exactly as a restarted
        # container does.
        reloaded = IsolationForestModel()
        result = reloaded.evaluate(EXTREME)

        assert result.available, "the reloaded model must be able to score"
        assert result.is_anomaly, "an extreme reading must be flagged after a reload"
        assert result.score > 0.0

    def test_reload_scores_identically_to_the_freshly_trained_model(self, artifact_paths):
        trained = IsolationForestModel()
        reloaded = IsolationForestModel()
        assert reloaded.evaluate(EXTREME).score == pytest.approx(trained.evaluate(EXTREME).score)
        assert reloaded.evaluate(NORMAL).score == pytest.approx(trained.evaluate(NORMAL).score)

    def test_refuses_an_artifact_that_predates_the_bundled_scaler(self, artifact_paths):
        """A bare forest cannot be scored correctly, because the scaling it was
        fitted under is gone. It must be refused, not half-loaded."""
        import joblib

        model_path, _ = artifact_paths
        model_path.parent.mkdir(parents=True, exist_ok=True)
        legacy = IsolationForest(random_state=42).fit(np.random.default_rng(0).normal(size=(50, 5)))
        joblib.dump(legacy, model_path)

        model = IsolationForestModel()

        assert model.is_available, "it must retrain rather than be left with no detector"
        assert model.evaluate(EXTREME).is_anomaly

    def test_an_unusable_model_reports_itself_unavailable(self, artifact_paths):
        model = IsolationForestModel()
        model._pipeline = None

        result = model.evaluate(EXTREME)

        # Not "score 0.0, nothing wrong" — that is indistinguishable from a
        # confident normal, which is how the original bug hid.
        assert result.available is False
        assert result.score == 0.0

    def test_training_marks_synthetic_data_as_degraded(self, artifact_paths):
        model = IsolationForestModel()
        assert model.degraded_reason == "trained on synthetic data"

        real = pd.DataFrame({
            "temperature": np.random.default_rng(1).normal(22, 2, 200),
            "humidity": np.random.default_rng(2).normal(55, 5, 200),
            "airQuality": np.random.default_rng(3).normal(45, 10, 200),
            "oxygen": np.random.default_rng(4).normal(20.9, 0.2, 200),
            "pressure": np.random.default_rng(5).normal(1013, 5, 200),
        })
        model.train(real, is_synthetic=False)
        assert model.degraded_reason is None


class TestScorerRenormalisation:
    """An unavailable detector must not drag the score toward zero. Its weight
    stays in the denominator only if it actually contributed."""

    @staticmethod
    def _forecast(available: bool) -> ForecastResult:
        if available:
            return ForecastResult({"temperature": 22.0}, {"temperature": 0.1},
                                  {"temperature": 0.1}, [], available=True)
        return ForecastResult({}, {}, {}, ["unavailable"], available=False)

    def test_isolation_forest_score_is_not_diluted_when_forecast_is_missing(self):
        """A detector with nothing to say must not reduce what another asserts.

        Under the noisy-OR combiner an unavailable detector contributes no
        evidence at all, so a maxed Isolation Forest asserts exactly its own
        weight. The earlier weighted-mean version had to lift the missing
        detector's weight out of a denominator to approximate this; now it
        falls out of the combiner.
        """
        clean_rules = rule_engine.evaluate(NORMAL)
        if_hit = IsolationForestResult(True, 1.0, ["outlier"], available=True)

        without_forecast = anomaly_scorer.combine(clean_rules, if_hit, self._forecast(False), NORMAL)

        assert without_forecast.score == pytest.approx(config.W_IF)
        assert without_forecast.components["forecast"] is False

    def test_a_quiet_forecast_never_lowers_the_combined_score(self):
        """Evidence only accumulates.

        A weighted mean let a detector that saw nothing pull the score down,
        which is how the full stack came to score *worse* than the same stack
        with the Isolation Forest switched off. The combiner must be monotonic.
        """
        clean_rules = rule_engine.evaluate(NORMAL)
        if_hit = IsolationForestResult(True, 1.0, ["outlier"], available=True)

        alone = anomaly_scorer.combine(clean_rules, if_hit, self._forecast(False), NORMAL)
        with_quiet_forecast = anomaly_scorer.combine(clean_rules, if_hit, self._forecast(True), NORMAL)

        assert with_quiet_forecast.score >= alone.score

    def test_a_rule_breach_still_fires_with_every_model_unavailable(self):
        breach = rule_engine.evaluate(EXTREME)
        dead_if = IsolationForestResult(False, 0.0, [], available=False)

        analysis = anomaly_scorer.combine(breach, dead_if, self._forecast(False), EXTREME)

        assert analysis.is_anomaly
        assert analysis.severity == "CRITICAL"
        assert analysis.components == {"rules": True, "isolationForest": False,
                                       "forecast": False, "flatline": False}

    def test_degraded_scoring_is_stated_in_the_reasons(self):
        analysis = anomaly_scorer.combine(
            rule_engine.evaluate(NORMAL),
            IsolationForestResult(False, 0.0, [], available=False),
            self._forecast(False),
            NORMAL,
        )
        assert any("Scored without" in reason for reason in analysis.reasons)

    def test_reasons_keep_a_stable_order(self):
        args = (
            rule_engine.evaluate(EXTREME),
            IsolationForestResult(True, 0.9, ["outlier"], available=True),
            self._forecast(True),
            EXTREME,
        )
        assert anomaly_scorer.combine(*args).reasons == anomaly_scorer.combine(*args).reasons


class TestForecastStepSelection:

    def test_selects_the_step_matching_the_readings_elapsed(self):
        series = {"temperature": [20.0, 21.0, 22.0]}
        assert _forecast_step(series, 0) == {"temperature": [20.0]}
        assert _forecast_step(series, 2) == {"temperature": [22.0]}

    def test_returns_nothing_once_past_the_horizon(self):
        # Holding the last value here would invent a baseline rather than
        # measure against one.
        assert _forecast_step({"temperature": [20.0, 21.0]}, 2) == {}

    def test_handles_an_absent_forecast(self):
        assert _forecast_step({}, 0) == {}
        assert _forecast_step({"temperature": []}, 0) == {}


class TestStreamForecastReuse:
    """The forecast behind residual scoring is a transformer forward pass; the
    comparison against it is arithmetic. Running the model per reading, as the
    original did, is the single largest cost in this service."""

    def test_runs_one_batched_inference_per_forecast_interval(self, monkeypatch):
        calls = {"count": 0, "series": 0}

        def counting_predict(history, horizon=1, features=None):
            calls["count"] += 1
            names = list(features) if features else ["temperature"]
            calls["series"] += len(names)
            return {name: [20.0] * horizon for name in names}

        monkeypatch.setattr("chronos_forecaster.chronos_forecaster.predict_features", counting_predict)
        monkeypatch.setattr("stream_processor.chronos_forecaster.predict_features", counting_predict)

        processor = StreamProcessor(forecast_every=10, report_every=30, min_history_for_forecast=5)
        for i in range(60):
            processor.handle_reading({
                "deviceUid": "REUSE-1",
                "temperature": 22.0 + (i % 5) * 0.2,
                "humidity": 55.0,
                "pressure": 1013.0,
                "airQuality": 40.0,
                "oxygen": 20.9,
                "recordedAt": f"2026-01-01T00:{i:02d}:00Z",
            })

        # Scoring each reading with its own inference would be ~60. The bound
        # is one inference per horizon-worth of readings (the cached forecast
        # is refreshed when it runs out) plus one per publish interval.
        #
        # This asserted <= 6 when the refresh interval *was* the publish
        # interval. That coupling was the bug: a forecast taken every 10
        # readings covered only the 6 the horizon predicted, so 40% of readings
        # were scored with no residual detector at all. Refreshing on
        # exhaustion costs more inferences and is the point — the number here
        # went from 6 to 12, and residual coverage went from 60% to 100%.
        ceiling = 60 // processor.forecast_horizon + 60 // processor.forecast_every
        assert calls["count"] <= ceiling, (
            f"expected at most {ceiling} inferences, got {calls['count']}")
        assert calls["count"] < 20, "the forecast is no longer being reused across readings"

    def test_scores_readings_against_the_cached_forecast(self, monkeypatch):
        monkeypatch.setattr(
            "chronos_forecaster.chronos_forecaster.predict_features",
            lambda history, horizon=1, features=None: {"temperature": [99.0] * horizon},
        )
        monkeypatch.setattr(
            "stream_processor.chronos_forecaster.predict_features",
            lambda history, horizon=1, features=None: {"temperature": [99.0] * horizon},
        )

        processor = StreamProcessor(forecast_every=5, report_every=100, min_history_for_forecast=3)
        for i in range(6):
            processor.handle_reading({
                "deviceUid": "CACHE-1", "temperature": 22.0, "humidity": 55.0,
                "pressure": 1013.0, "airQuality": 40.0, "oxygen": 20.9,
                "recordedAt": f"2026-01-01T00:{i:02d}:00Z",
            })

        state = processor._devices["CACHE-1"]
        assert state.forecast_series, "a forecast must be cached for the next readings"
        assert state.forecast_age >= 1, "the cache must age as readings arrive"
