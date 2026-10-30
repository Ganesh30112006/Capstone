"""Regression tests for whether the detector actually detects.

Every other test in this service checks that code runs. These check the thing
the service exists to do, and they exist because it did not.

The defect they pin: rules were a weighted term in the same sum as the two
models, carrying the largest weight of the three. A reading that broke no
physical bound therefore contributed W_RULE to the denominator and nothing to
the numerator, capping every score at

    (W_IF + W_RESIDUAL) / (W_RULE + W_IF + W_RESIDUAL) = 0.5

against a threshold of 0.75. Both models pinned at maximum scored 0.500 and did
not fire. The Isolation Forest and the Chronos residual detector could not
report an anomaly at any input — the "hybrid" detector was a rule engine with
two expensive decorations, and nothing in the test suite noticed.

The thresholds asserted below are floors taken from
`benchmarks/detection_quality.py`, set well under the measured values so they
fail on a real regression rather than on sampling noise.
"""

from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

import config
from anomaly_scorer import anomaly_scorer
from chronos_forecaster import ForecastResult
from flatline_detector import FlatlineDetector
from isolation_forest_model import IsolationForestModel, IsolationForestResult
from rule_engine import rule_engine

from benchmarks import workloads
from benchmarks.workloads import FEATURES


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

@pytest.fixture(scope="module")
def model(tmp_path_factory):
    """A forest trained on the clean distribution, in a throwaway directory."""
    directory = tmp_path_factory.mktemp("detection")
    original = (config.IF_MODEL_PATH, config.METADATA_PATH, config.IF_SIGNATURE_PATH)
    config.IF_MODEL_PATH = directory / "model.joblib"
    config.METADATA_PATH = directory / "metadata.json"
    config.IF_SIGNATURE_PATH = directory / "model.joblib.sig"
    try:
        trained = IsolationForestModel()
        trained.train(pd.DataFrame(workloads.normal_frame(2000)), is_synthetic=False)
        yield trained
    finally:
        config.IF_MODEL_PATH, config.METADATA_PATH, config.IF_SIGNATURE_PATH = original


def _residual(reading: dict, stats: dict, available: bool = True) -> ForecastResult:
    if not available:
        return ForecastResult({}, {}, {}, ["unavailable"], available=False)
    norm = {name: abs(float(reading[name]) - mean) / max(sd, 1e-3)
            for name, (mean, sd) in stats.items() if name in reading}
    return ForecastResult({n: stats[n][0] for n in norm}, {}, norm, [], available=True)


@pytest.fixture(scope="module")
def stats():
    frame = pd.DataFrame(workloads.normal_frame(2000))
    return {name: (float(frame[name].mean()), float(frame[name].std())) for name in FEATURES}


def score(model, stats, reading, history=(), if_on=True, forecast_on=True):
    forest = (model.evaluate(reading) if if_on
              else IsolationForestResult(False, 0.0, [], available=False))
    return anomaly_scorer.combine(
        rule_engine.evaluate(reading),
        forest,
        _residual(reading, stats, forecast_on),
        reading,
        _replay_flatline(history, reading),
    )



def _replay_flatline(history, reading):
    """Replays a sequence through the stateful detector on a fresh device.

    It learns each channel's normal run length over time, so it cannot be asked
    about an isolated reading — it has to be shown the sequence leading to one.
    """
    detector = FlatlineDetector()
    result = None
    for row in list(history) + [reading]:
        result = detector.observe("test", row)
    return result


# ---------------------------------------------------------------------------
# The bug itself
# ---------------------------------------------------------------------------

class TestModelsCanFireAtAll:

    def test_the_models_can_reach_the_threshold_without_a_rule_breach(self):
        """The regression that mattered: they could not, at any input.

        Both detectors pinned at maximum used to score 0.500 against a
        threshold of 0.75.
        """
        clean = rule_engine.evaluate({"temperature": 22.0, "humidity": 50.0,
                                      "pressure": 1013.0, "airQuality": 40.0,
                                      "oxygen": 20.9})
        assert not clean.is_anomaly, "this fixture must not trip a physical bound"

        maxed = anomaly_scorer.combine(
            clean,
            IsolationForestResult(True, 1.0, ["outlier"], available=True),
            ForecastResult({}, {}, {"temperature": 1e6}, [], available=True),
            {"temperature": 22.0},
        )
        assert maxed.score >= config.ANOMALY_THRESHOLD
        assert maxed.is_anomaly

    def test_a_detector_weight_is_the_ceiling_it_can_assert_alone(self):
        """Under the noisy-OR, weight and threshold are one decision.

        A detector whose weight is below the threshold can never fire by
        itself, however certain it is. That is a deliberate configuration
        choice, and it must stay visible rather than becoming an accident.
        """
        clean = rule_engine.evaluate({"temperature": 22.0, "humidity": 50.0,
                                      "pressure": 1013.0})
        residual_only = anomaly_scorer.combine(
            clean,
            IsolationForestResult(False, 0.0, [], available=False),
            ForecastResult({}, {}, {"temperature": 1e6}, [], available=True),
            {"temperature": 22.0},
        )
        assert residual_only.score == pytest.approx(config.W_RESIDUAL)

        forest_only = anomaly_scorer.combine(
            clean,
            IsolationForestResult(True, 1.0, [], available=True),
            ForecastResult({}, {}, {}, [], available=False),
            {"temperature": 22.0},
        )
        assert forest_only.score == pytest.approx(config.W_IF)

    def test_evidence_only_accumulates(self):
        """Monotonicity.

        A weighted mean let a quiet detector pull the score down, which is how
        the full stack came to score worse than the same stack with the
        Isolation Forest switched off. Adding evidence must never subtract.
        """
        clean = rule_engine.evaluate({"temperature": 22.0, "humidity": 50.0,
                                      "pressure": 1013.0})
        forest = IsolationForestResult(True, 0.9, [], available=True)

        alone = anomaly_scorer.combine(
            clean, forest, ForecastResult({}, {}, {}, [], available=False), {})
        for residual in (0.0, 0.5, 1.0, 4.0, 100.0):
            combined = anomaly_scorer.combine(
                clean, forest,
                ForecastResult({}, {}, {"temperature": residual}, [], available=True), {})
            assert combined.score >= alone.score - 1e-9, (
                f"a residual of {residual} sigma reduced the score")


# ---------------------------------------------------------------------------
# Measured quality floors
# ---------------------------------------------------------------------------

class TestDetectionQuality:
    """Floors taken from benchmarks/detection_quality.py, set well below the
    measured values so noise does not fail the build."""

    def test_every_failure_mode_is_detected(self, model, stats):
        data = workloads.balanced_by_kind(60)
        clean_history = workloads.history_window(24)
        stuck_prologue = workloads.stationary_stream(300, calm=0.25, seed=909)

        recalls = {}
        for kind in workloads.ANOMALY_KINDS:
            rows = [r for r, k in zip(data.readings, data.kinds) if k == kind]
            history = None
            hits = 0
            for reading in rows:
                # A frozen sensor needs a working prologue, not a flat window:
                # the detector learns each channel's normal run length before it
                # can call one abnormal.
                if kind == "stuck":
                    history = stuck_prologue + [dict(reading)] * 60
                else:
                    history = clean_history
                hits += score(model, stats, reading, history).is_anomaly
            recalls[kind] = hits / len(rows)

        # Each of these was 0% at some point: multivariate and spike because the
        # models could not reach the threshold, stuck because nothing looked at
        # sequences at all.
        assert recalls["out_of_range"] == 1.0
        assert recalls["spike"] >= 0.85
        assert recalls["multivariate"] >= 0.85
        assert recalls["drift"] >= 0.70
        assert recalls["stuck"] >= 0.95

    def test_normal_readings_rarely_raise_an_alarm(self, model, stats):
        """Precision matters as much as recall: this scores every reading from
        every device continuously, so a 5% false-alarm rate is an operator
        ignoring the system by the end of the first day."""
        history = workloads.history_window(24)
        normals = workloads.history_window(400)
        false_alarms = sum(score(model, stats, r, history).is_anomaly for r in normals)
        assert false_alarms / len(normals) <= 0.03

    def test_the_full_stack_beats_every_degraded_mode(self, model, stats):
        """The property the weighted mean violated.

        Turning a working detector off must not improve detection.
        """
        data = workloads.labelled_stream(600, anomaly_rate=0.08, seed=77)
        history = workloads.history_window(24)

        def recall(if_on: bool, forecast_on: bool) -> float:
            hits = sum(
                score(model, stats, r, history, if_on, forecast_on).is_anomaly
                for r, label in zip(data.readings, data.labels) if label
            )
            return hits / sum(data.labels)

        full = recall(True, True)
        assert full >= recall(False, True)
        assert full >= recall(True, False)
        assert full >= recall(False, False)


# ---------------------------------------------------------------------------
# Calibration
# ---------------------------------------------------------------------------

class TestScoreCalibration:

    def test_normal_readings_score_near_zero(self, model):
        """v2 mapped the decision boundary onto 0.5, so an ordinary reading
        scored ~0.39 and a clear outlier ~0.69. Nothing reached either end of
        the scale, and "0.5" silently meant "normal"."""
        normals = workloads.history_window(300)
        scores = np.array([model.evaluate(r).score for r in normals])
        assert scores.mean() < 0.15
        assert float(np.percentile(scores, 95)) < 0.5

    def test_clear_outliers_score_near_one(self, model):
        rng = np.random.default_rng(3)
        outliers = [{
            "temperature": float(rng.uniform(34, 37)),
            "humidity": float(rng.uniform(90, 96)),
            "airQuality": float(rng.uniform(115, 140)),
            "oxygen": float(rng.uniform(20.0, 20.2)),
            "pressure": float(rng.uniform(975, 985)),
        } for _ in range(200)]
        scores = np.array([model.evaluate(r).score for r in outliers])
        assert scores.mean() > 0.8

    def test_the_calibration_constant_is_persisted(self, model):
        """It lives inside the signed artifact, not beside it: a calibration an
        attacker could edit separately would let them scale every score to zero
        without touching the model."""
        assert getattr(model.model, "anomaly_scale_", None) is not None
        assert model.model.anomaly_scale_ > 0


# ---------------------------------------------------------------------------
# Flatline
# ---------------------------------------------------------------------------

class TestFlatlineDetector:
    """A frozen sensor drives the residual detector to perfect agreement, so
    every other detector in the stack reads it as *healthier* than a working
    one. Measured against real Chronos its residual is exactly 0.00 sigma.

    The tests below pin the behaviour that made the first version unusable: it
    flagged a working instrument in very calm conditions 100% of the time,
    because instruments quantise and a settled environment produces long runs
    of bit-identical values with nothing broken.
    """

    @staticmethod
    def _run(stream, device="d"):
        detector = FlatlineDetector()
        return detector, [detector.observe(device, row) for row in stream]

    def test_a_sensor_that_freezes_is_flagged(self):
        working = workloads.stationary_stream(400, calm=0.25, seed=21)
        frozen = dict(working[-1])
        _, results = self._run(working + [dict(frozen)] * 120)

        assert results[-1].is_anomaly
        assert results[-1].sensor is not None
        assert "frozen" in " ".join(results[-1].reasons)

    def test_a_working_sensor_in_calm_conditions_is_not_flagged(self):
        """The regression that matters. A quantised instrument in settled
        conditions repeats values for a long time while working perfectly."""
        for calm in (0.01, 0.05, 0.10, 0.25):
            stream = workloads.stationary_stream(1500, calm=calm, seed=23)
            _, results = self._run(stream, device=f"calm-{calm}")
            flagged = sum(r.is_anomaly for r in results)
            assert flagged == 0, f"calm={calm} produced {flagged} false alarms"

    def test_a_channel_that_never_moves_is_never_accused(self):
        """Ambient oxygen at a 0.1% reporting step genuinely never changes.

        An absolute run limit used to flag exactly this after two days. A
        channel with no established normal has nothing to break.
        """
        stream = workloads.frozen_stream(2000, features=["oxygen"])
        _, results = self._run(stream, device="steady-oxygen")
        assert not any(r.is_anomaly and r.sensor == "oxygen" for r in results)

    def test_a_short_run_is_not_yet_evidence(self):
        working = workloads.stationary_stream(300, calm=0.25, seed=27)
        frozen = dict(working[-1])
        _, results = self._run(working + [dict(frozen)] * 5)
        assert not results[-1].is_anomaly

    def test_no_baseline_reports_unavailable_not_normal(self):
        """"No evidence" and "nothing wrong" must stay distinguishable."""
        reading = {"temperature": 22.0, "humidity": 55.0, "pressure": 1013.0,
                   "airQuality": 45.0, "oxygen": 20.9}
        detector = FlatlineDetector()
        assert detector.observe("fresh", reading).available is False

    def test_a_frozen_sensor_fires_on_its_own(self):
        """It has to. Nothing else in the stack will corroborate it — the
        residual detector actively reports agreement on a flat series — so the
        score must clear threshold/weight without help."""
        working = workloads.stationary_stream(400, calm=0.25, seed=29)
        frozen = dict(working[-1])
        _, results = self._run(working + [dict(frozen)] * 120)

        analysis = anomaly_scorer.combine(
            rule_engine.evaluate(frozen),
            IsolationForestResult(False, 0.0, [], available=True),
            # Perfect agreement with its own forecast: exactly the trap.
            ForecastResult({}, {}, {"temperature": 0.0}, [], available=True),
            frozen,
            results[-1],
        )
        assert analysis.is_anomaly
        assert analysis.score >= config.ANOMALY_THRESHOLD

    def test_the_device_table_is_bounded(self):
        detector = FlatlineDetector()
        reading = {"temperature": 22.0, "humidity": 55.0, "pressure": 1013.0,
                   "airQuality": 45.0, "oxygen": 20.9}
        for index in range(config.FLATLINE_MAX_DEVICES + 120):
            detector.observe(f"DEV-{index}", reading)
        assert detector.tracked_devices() <= config.FLATLINE_MAX_DEVICES


# ---------------------------------------------------------------------------
# Determinism
# ---------------------------------------------------------------------------

def test_scoring_is_deterministic(model, stats):
    """An alert that appears and disappears for the same reading is
    untriageable."""
    readings = workloads.history_window(100)
    history = workloads.history_window(24)
    first = [score(model, stats, r, history).score for r in readings]
    second = [score(model, stats, r, history).score for r in readings]
    assert first == second


def test_training_is_reproducible(tmp_path):
    """Two models fitted on the same data must score identically, or a redeploy
    silently changes which readings alert."""
    original = (config.IF_MODEL_PATH, config.METADATA_PATH, config.IF_SIGNATURE_PATH)
    config.IF_MODEL_PATH = tmp_path / "model.joblib"
    config.METADATA_PATH = tmp_path / "metadata.json"
    config.IF_SIGNATURE_PATH = tmp_path / "model.joblib.sig"
    try:
        frame = pd.DataFrame(workloads.normal_frame(800))
        first = IsolationForestModel()
        first.train(frame, is_synthetic=False)
        second = IsolationForestModel()
        second.train(frame, is_synthetic=False)

        probes = workloads.history_window(50)
        assert ([first.evaluate(r).score for r in probes]
                == [second.evaluate(r).score for r in probes])
        assert first.model.anomaly_scale_ == second.model.anomaly_scale_
    finally:
        config.IF_MODEL_PATH, config.METADATA_PATH, config.IF_SIGNATURE_PATH = original
