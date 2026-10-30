"""How many trees does the Isolation Forest actually need?

The forest is the slowest thing in the scoring path by an order of magnitude,
and this service scores every reading from every device one at a time, so the
tree count is the main lever on throughput.

How much of a lever it is has changed. Scoring one reading used to cost about
as much as scoring a hundred, because scikit-learn re-validates every tree on
every call and that fixed overhead swamped the arithmetic — which meant this
table barely responded to the number it was varying. The service now bypasses
that revalidation (see _FastScorer in isolation_forest_model), and cost is
roughly linear in tree count, so the trade the table describes is a real one.

Trading it away is only safe if detection survives, which is a question for
measurement rather than intuition. This report puts latency and quality side by
side at several sizes so the choice is made on evidence.

Run with:  python -m benchmarks.model_sizing
"""

from __future__ import annotations

import logging
import sys
import tempfile
import timeit
from pathlib import Path
from typing import Dict

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import config  # noqa: E402

_SANDBOX = Path(tempfile.mkdtemp(prefix="ai-sizing-"))
config.IF_MODEL_PATH = _SANDBOX / "model.joblib"
config.METADATA_PATH = _SANDBOX / "metadata.json"
config.IF_SIGNATURE_PATH = _SANDBOX / "model.joblib.sig"

from isolation_forest_model import IsolationForestModel  # noqa: E402

from benchmarks import workloads  # noqa: E402

SIZES = (10, 25, 50, 100, 200)

# The size that ships, for the relative comparison at the end.
SHIPPED = 50


def _quality(model: IsolationForestModel) -> Dict[str, float]:
    """Recall on the failure modes the forest is responsible for, and the rate
    at which it accuses healthy readings."""
    data = workloads.balanced_by_kind(300)

    scores = {kind: [] for kind in workloads.ANOMALY_KINDS}
    normal_scores = []
    for reading, kind in zip(data.readings, data.kinds):
        score = model.evaluate(reading).score
        if kind:
            scores[kind].append(score)
        else:
            normal_scores.append(score)

    normal = np.asarray(normal_scores)
    multivariate = np.asarray(scores["multivariate"])
    drift = np.asarray(scores["drift"])

    return {
        # The forest exists for readings whose values are individually plausible
        # and whose combination is not. Nothing else in the stack catches those.
        "multivariate_recall": float((multivariate >= config.ANOMALY_THRESHOLD).mean()),
        "drift_recall": float((drift >= config.ANOMALY_THRESHOLD).mean()),
        "false_alarm": float((normal >= config.ANOMALY_THRESHOLD).mean()),
        # Separation is the property a threshold depends on. If the healthy p99
        # sits above the anomalous p1, no threshold choice can fix it.
        "normal_p99": float(np.percentile(normal, 99)),
        "multivariate_p1": float(np.percentile(multivariate, 1)),
    }


def main() -> None:
    print("Isolation Forest sizing — latency against detection, seeded data")
    print(f"threshold {config.ANOMALY_THRESHOLD}, contamination {config.IF_CONTAMINATION}\n")

    frame = pd.DataFrame(workloads.normal_frame(4000))
    probe = workloads.history_window(1)[0]
    original = config.IF_CONTAMINATION

    print(f"  {'trees':>6} {'score us':>9} {'per sec':>9} {'multivar':>9} {'drift':>7} "
          f"{'false':>7} {'norm p99':>9} {'mv p1':>7} {'artifact':>9}")

    results = []
    for trees in SIZES:
        model = IsolationForestModel()
        # n_estimators is not a constructor argument on the model wrapper; the
        # forest is rebuilt here so sizing stays a benchmark concern rather than
        # leaking a knob into production code that nobody would tune.
        from sklearn.ensemble import IsolationForest
        from sklearn.pipeline import Pipeline
        from sklearn.preprocessing import StandardScaler

        matrix = frame[model.features].to_numpy(dtype=float)
        pipeline = Pipeline([
            ("scaler", StandardScaler()),
            ("forest", IsolationForest(n_estimators=trees,
                                       contamination=original, random_state=42)),
        ])
        pipeline.fit(matrix)
        # Calibrated exactly as production does, or this would measure a
        # different model from the one that ships.
        decisions = pipeline.decision_function(matrix)
        spread = float(np.median(decisions) - np.percentile(decisions, 1))
        pipeline.anomaly_scale_ = max(config.IF_CALIBRATION_FACTOR * spread, 1e-3)
        # And given the same fast scoring path, for the same reason. Without
        # this the table measures scikit-learn's per-tree validation overhead
        # rather than the service's actual cost, and that overhead has a
        # different shape: it is dominated by a fixed cost per tree that the
        # fast path removes, which is precisely what changes the answer to
        # "how many trees should ship".
        model._attach_fast_scorer(pipeline, matrix)
        model._pipeline = pipeline

        model.evaluate(probe)
        latency = min(timeit.repeat(lambda: model.evaluate(probe),
                                    number=100, repeat=5)) / 100 * 1e6
        quality = _quality(model)

        import joblib
        path = _SANDBOX / f"size-{trees}.joblib"
        # Stripped first, because production attaches the fast path *after*
        # writing the artifact and this column is meant to report what would
        # actually be written. Left on, it reported a 50-tree model as 640K
        # when the file that ships is 586K.
        pipeline._fast_scorer = None
        joblib.dump(pipeline, path)
        size_kb = path.stat().st_size / 1024

        results.append((trees, latency, quality))
        print(f"  {trees:>6} {latency:>9.0f} {1e6/latency:>9,.0f} "
              f"{quality['multivariate_recall']:>8.0%} {quality['drift_recall']:>6.0%} "
              f"{quality['false_alarm']:>6.1%} {quality['normal_p99']:>9.3f} "
              f"{quality['multivariate_p1']:>7.3f} {size_kb:>8.0f}K")

    baseline = next(r for r in results if r[0] == SHIPPED)
    print(f"\n  Relative to the shipped {SHIPPED}-tree model ({baseline[1]:.0f} us):")
    for trees, latency, quality in results:
        if trees == SHIPPED:
            continue
        delta_mv = quality["multivariate_recall"] - baseline[2]["multivariate_recall"]
        print(f"    {trees:>3} trees: {baseline[1]/latency:>5.2f}x faster, "
              f"multivariate recall {delta_mv:+.1%}, "
              f"false alarms {quality['false_alarm'] - baseline[2]['false_alarm']:+.1%}")

    print("\n  Scoring is one reading at a time on both worker paths, so the")
    print("  per-call cost above is the per-reading cost.")
    print("\n  These are measured through the shipped scoring path, which skips")
    print("  scikit-learn's per-tree revalidation. That matters for reading the")
    print("  table: on the library path the cost sat near 1,000 us at almost any")
    print("  tree count, because fixed overhead swamped the arithmetic, and the")
    print("  latency column barely responded to the dial it is measuring. Tree")
    print("  count is now roughly linear in cost, so this is a real trade-off.")


if __name__ == "__main__":
    # The service logs each device eviction at WARNING, which is right in
    # production and useless here: the churn test deliberately evicts hundreds
    # in a row and would bury the numbers it is printing.
    logging.disable(logging.WARNING)
    main()
