"""The fast Isolation Forest scoring path must be indistinguishable from sklearn.

Profiling the service found the shape of its cost to be lopsided: the rule
engine, the flatline detector and the scorer each run in about 2 microseconds,
and Isolation Forest scoring took roughly 1,100 — so one detector was 99% of the
per-reading budget. Almost none of that was tree traversal. Scoring one reading
asks each of 50 estimators for a leaf, and `tree.apply` re-runs `check_is_fitted`
and a feature-count check *per tree, per reading*: fifty rounds of Python
bookkeeping around fifty sub-microsecond Cython calls.

`_FastScorer` does that arithmetic directly, which measured 8-9x faster
end-to-end through `evaluate()`. The risk it introduces is not slowness but
silent divergence: it depends on scikit-learn internals, and a version that
reorganised them could leave a detector that still returns plausible numbers
while no longer being the model that was trained and calibrated.

So these tests hold it to *exact equality* rather than a tolerance. Both paths
run the same operations in the same order on the same dtypes; any difference at
all means an assumption has stopped holding, and a difference nobody can explain
is not one to tolerate in a detector. The production code takes the same
position — it verifies at build time and declines to install itself on any
mismatch, so a future sklearn costs speed rather than correctness.
"""

import logging
import tempfile
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

import config

_SANDBOX = Path(tempfile.mkdtemp(prefix="ai-fastscore-"))
config.IF_MODEL_PATH = _SANDBOX / "model.joblib"
config.METADATA_PATH = _SANDBOX / "metadata.json"
config.IF_SIGNATURE_PATH = _SANDBOX / "model.joblib.sig"

from isolation_forest_model import (  # noqa: E402
    IsolationForestModel, UnrepresentableReading, _FastScorer,
)

from benchmarks import workloads  # noqa: E402

logging.disable(logging.CRITICAL)


@pytest.fixture(scope="module")
def model():
    trained = IsolationForestModel()
    trained.train(pd.DataFrame(workloads.normal_frame(4000, seed=1)), is_synthetic=False)
    return trained


def _row(model, reading):
    return np.asarray([[float(reading.get(f, 0.0)) for f in model.features]], dtype=float)


def test_fast_path_is_installed(model):
    assert getattr(model._pipeline, "_fast_scorer", None) is not None, (
        "the fast path declined to install; scoring is correct but ~8x slower")


def test_agrees_exactly_on_the_healthy_distribution(model):
    rng = np.random.default_rng(5)
    pipeline = model._pipeline
    frame = pd.DataFrame(workloads.normal_frame(2000, seed=rng.integers(10_000)))

    for reading in frame.to_dict("records"):
        row = _row(model, reading)
        assert pipeline._fast_scorer.decision(row) == float(pipeline.decision_function(row)[0])


def test_agrees_exactly_on_every_labelled_anomaly_kind(model):
    """Normal readings are the easy case — they land in well-travelled leaves.

    The anomalies are what reach the sparse edges of the trees, where a
    disagreement would actually change a verdict.
    """
    pipeline = model._pipeline
    stream = workloads.labelled_stream(2000, seed=2)

    mismatches = [
        reading for reading in stream.readings
        if pipeline._fast_scorer.decision(_row(model, reading))
        != float(pipeline.decision_function(_row(model, reading))[0])
    ]
    assert not mismatches


def test_agrees_on_extreme_and_degenerate_rows(model):
    pipeline = model._pipeline
    for value in (-1e9, -1e3, 0.0, 1e-15, 1e3, 1e9):
        row = np.full((1, len(model.features)), value, dtype=float)
        assert pipeline._fast_scorer.decision(row) == float(pipeline.decision_function(row)[0])


def test_anomaly_verdicts_are_unchanged(model):
    """The sign of the decision is the model's own verdict, and it must not move."""
    pipeline = model._pipeline
    stream = workloads.labelled_stream(1500, seed=7)
    for reading in stream.readings:
        row = _row(model, reading)
        assert (pipeline._fast_scorer.decision(row) < 0) == (pipeline.decision_function(row)[0] < 0)


def test_build_refuses_when_verification_fails(model):
    """A probe that disagrees must disable the fast path, not install it."""
    class Disagreeing:
        """Stands in for a pipeline whose internals no longer match."""

        def __init__(self, real):
            self.named_steps = real.named_steps

        def decision_function(self, rows):
            return np.full(len(rows), 12345.0)

    built = _FastScorer.build(Disagreeing(model._pipeline),
                              workloads.normal_frame(64, seed=3)["temperature"].reshape(-1, 1))
    assert built is None


def test_build_declines_rather_than_raising_on_an_unexpected_pipeline():
    assert _FastScorer.build(object(), np.zeros((4, 5))) is None


def test_runtime_failure_falls_back_instead_of_losing_the_detector(model):
    """A fast path that breaks must cost speed, not the detector.

    Reporting the reading unscored would be the worse failure: the hybrid
    scorer would renormalise around a missing signal for a reading the library
    path could have scored perfectly well.
    """
    class Broken:
        def decision(self, row):
            raise RuntimeError("internals moved")

    pipeline = model._pipeline
    original = pipeline._fast_scorer
    try:
        pipeline._fast_scorer = Broken()
        reading = dict(zip(model.features, [20.0, 55.0, 45.0, 20.9, 1013.0]))
        result = model.evaluate(reading)
        assert result.available, "a broken fast path took the detector down with it"
        assert pipeline._fast_scorer is None, "the broken path should be uninstalled"
    finally:
        pipeline._fast_scorer = original


def test_scorer_is_not_written_into_the_artifact(model):
    """The lookup tables are derived from the trees; persisting them would
    duplicate the model inside its own artifact."""
    import joblib
    reloaded = joblib.load(config.IF_MODEL_PATH)
    assert getattr(reloaded, "_fast_scorer", None) is None


def test_a_loaded_model_rebuilds_and_verifies_its_own_fast_path():
    """A restarted container has no training data, only the artifact.

    The scaler carries the training distribution's mean and spread, which is
    enough to generate a probe that lands in the same region of the trees.
    """
    restarted = IsolationForestModel()
    restarted._load_or_init()
    pipeline = restarted._pipeline
    assert getattr(pipeline, "_fast_scorer", None) is not None

    rng = np.random.default_rng(11)
    for _ in range(500):
        row = rng.normal([20, 55, 45, 20.9, 1013], [6, 18, 22, 0.5, 9]).reshape(1, -1)
        assert pipeline._fast_scorer.decision(row) == float(pipeline.decision_function(row)[0])


def test_evaluate_matches_with_and_without_the_fast_path(model):
    """The public result, not just the internal decision, must be identical."""
    pipeline = model._pipeline
    original = pipeline._fast_scorer
    stream = workloads.labelled_stream(600, seed=13)
    try:
        for reading in stream.readings:
            pipeline._fast_scorer = original
            quick = model.evaluate(reading)
            pipeline._fast_scorer = None
            slow = model.evaluate(reading)
            assert quick.score == slow.score
            assert quick.is_anomaly == slow.is_anomaly
            assert quick.available == slow.available
    finally:
        pipeline._fast_scorer = original


# ---------------------------------------------------------------------------
# The float32 boundary — found by fuzzing, see test_fuzz_boundaries.py
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("magnitude", [3.5e38, 1e39, 1e100, 1e308, -1e308, 1.7e308])
def test_readings_past_the_float32_range_are_refused_by_both_paths(model, magnitude):
    """The trees are float32; a float64-finite reading can still overflow them.

    scikit-learn rejects such a reading. Before this was handled, the fast path
    scored it 1.0 instead — so the service's answer depended on whether the
    optimisation happened to be installed, which is exactly the silent
    divergence the whole verification scheme exists to prevent.
    """
    pipeline = model._pipeline
    row = np.full((1, len(model.features)), magnitude, dtype=float)

    with pytest.raises(UnrepresentableReading):
        pipeline._fast_scorer.decision(row)
    with pytest.raises(ValueError):
        pipeline.decision_function(row)


@pytest.mark.parametrize("magnitude", [1e30, 1e37])
def test_large_but_representable_readings_are_still_scored(model, magnitude):
    """The refusal must be the float32 limit, not merely 'a big number'."""
    pipeline = model._pipeline
    row = np.full((1, len(model.features)), magnitude, dtype=float)
    assert pipeline._fast_scorer.decision(row) == float(pipeline.decision_function(row)[0])


def test_evaluate_reports_an_unrepresentable_reading_as_unscored(model):
    """And identically with the fast path on or off."""
    pipeline = model._pipeline
    original = pipeline._fast_scorer
    reading = {name: 1e308 for name in model.features}
    try:
        pipeline._fast_scorer = original
        quick = model.evaluate(reading)
        pipeline._fast_scorer = None
        slow = model.evaluate(reading)
    finally:
        pipeline._fast_scorer = original

    assert (quick.available, quick.score, quick.is_anomaly) ==            (slow.available, slow.score, slow.is_anomaly)
    assert not quick.available


def test_an_unrepresentable_reading_does_not_disable_the_fast_path(model):
    """Otherwise one hostile reading costs the service its speed permanently.

    The fallback that uninstalls a broken fast path must not treat 'the library
    would refuse this too' as evidence that the optimisation is broken.
    """
    pipeline = model._pipeline
    assert pipeline._fast_scorer is not None
    for _ in range(5):
        model.evaluate({name: 1e308 for name in model.features})
    assert pipeline._fast_scorer is not None, (
        "hostile input switched off the fast path")


def test_scoring_does_not_emit_numpy_overflow_warnings(model, recwarn):
    """The overflow is expected and handled; warning about it is misleading."""
    import warnings
    with warnings.catch_warnings():
        warnings.simplefilter("error", RuntimeWarning)
        model.evaluate({name: 1e308 for name in model.features})
        model.evaluate(dict(zip(model.features, [20.0, 55.0, 45.0, 20.9, 1013.0])))
