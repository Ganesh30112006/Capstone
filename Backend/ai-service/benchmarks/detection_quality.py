"""Does the detector actually detect?

Every other test in this service checks that code runs. This one checks that
the thing the service exists to do works, on labelled data, with numbers that
can be compared across changes.

It reports four things:

  1. **Separation** — the score distribution each detector assigns to normal
     versus anomalous readings. A detector whose two distributions overlap
     cannot be fixed by moving a threshold, and this is where that shows.
  2. **Per-kind recall** — which failure modes are caught, and by which
     detector. An aggregate number hides that a stack catches every
     out-of-range reading and no multivariate ones.
  3. **A threshold sweep** — precision, recall and F1 across the range, so the
     configured threshold is a choice with evidence behind it rather than a
     number someone typed.
  4. **The degraded cases** — what detection looks like with the Isolation
     Forest unavailable, or the forecast unavailable, or both. The service is
     designed to keep running in those states, so their cost should be known.

Run with:  python -m benchmarks.detection_quality
"""

from __future__ import annotations

import logging
import sys
import tempfile
from pathlib import Path
from typing import Dict, Sequence, Tuple

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import config  # noqa: E402

# Redirected before the model module is imported: this harness retrains
# repeatedly and must never touch the deployed artifact.
_SANDBOX = Path(tempfile.mkdtemp(prefix="ai-bench-"))
config.IF_MODEL_PATH = _SANDBOX / "model.joblib"
config.METADATA_PATH = _SANDBOX / "metadata.json"
config.IF_SIGNATURE_PATH = _SANDBOX / "model.joblib.sig"

from anomaly_scorer import anomaly_scorer  # noqa: E402
from chronos_forecaster import ForecastResult  # noqa: E402
from isolation_forest_model import IsolationForestModel  # noqa: E402
from rule_engine import rule_engine  # noqa: E402

from benchmarks import workloads  # noqa: E402
from benchmarks.workloads import FEATURES  # noqa: E402


# ---------------------------------------------------------------------------
# Metrics
# ---------------------------------------------------------------------------

def confusion(predicted: Sequence[bool], actual: Sequence[bool]) -> Tuple[int, int, int, int]:
    tp = sum(1 for p, a in zip(predicted, actual) if p and a)
    fp = sum(1 for p, a in zip(predicted, actual) if p and not a)
    fn = sum(1 for p, a in zip(predicted, actual) if not p and a)
    tn = sum(1 for p, a in zip(predicted, actual) if not p and not a)
    return tp, fp, fn, tn


def prf(predicted: Sequence[bool], actual: Sequence[bool]) -> Dict[str, float]:
    tp, fp, fn, tn = confusion(predicted, actual)
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    # False positives per thousand readings is the number an operator feels:
    # this stack scores every reading from every device, continuously.
    fp_rate = fp / (fp + tn) * 1000 if fp + tn else 0.0
    return {"precision": precision, "recall": recall, "f1": f1,
            "fp_per_1000": fp_rate, "tp": tp, "fp": fp, "fn": fn, "tn": tn}


# ---------------------------------------------------------------------------
# Scoring harness
# ---------------------------------------------------------------------------

def _residual_result(reading: dict, history_stats: Dict[str, Tuple[float, float]],
                     available: bool = True) -> ForecastResult:
    """A stand-in for the Chronos residual detector.

    Chronos is not installed in most environments this runs in, and a benchmark
    that silently measures "no forecast" would report the stack as worse than it
    is. The substitute forecasts each feature as the mean of the clean history —
    a persistence forecast — which is the weakest thing a real forecaster could
    do. Numbers from it are therefore a floor, not a claim about Chronos.
    """
    if not available:
        return ForecastResult({}, {}, {}, ["unavailable"], available=False)

    expected, residuals, residuals_norm = {}, {}, {}
    for name, (mean, sd) in history_stats.items():
        if name not in reading:
            continue
        expected[name] = mean
        residual = float(reading[name]) - mean
        residuals[name] = residual
        residuals_norm[name] = abs(residual) / max(sd, 1e-3)
    return ForecastResult(expected, residuals, residuals_norm, [], available=True)



def _flatline(history, reading):
    """Replays a window through the stateful detector on a throwaway device.

    The detector learns each channel's normal run length over time, so it
    cannot be asked about an isolated reading — it has to be shown the sequence
    that leads to it. See benchmarks/flatline_validation.py, which measures it
    properly on long streams; this is only enough for the per-kind table.
    """
    from flatline_detector import FlatlineDetector

    detector = FlatlineDetector()
    result = None
    for row in list(history) + [reading]:
        result = detector.observe("bench", row)
    return result


class Harness:
    def __init__(self, training_rows: int = 4000):
        frame = pd.DataFrame(workloads.normal_frame(training_rows))
        self.model = IsolationForestModel()
        self.model.train(frame, is_synthetic=False)
        self.stats = {
            name: (float(frame[name].mean()), float(frame[name].std()))
            for name in FEATURES
        }

    def score(self, reading: dict, if_on: bool = True, forecast_on: bool = True,
              history: Sequence[dict] = ()):
        rule = rule_engine.evaluate(reading)
        if if_on:
            forest = self.model.evaluate(reading)
        else:
            from isolation_forest_model import IsolationForestResult
            forest = IsolationForestResult(False, 0.0, [], available=False)
        residual = _residual_result(reading, self.stats, available=forecast_on)
        flat = _flatline(history, reading)
        return anomaly_scorer.combine(rule, forest, residual, reading, flat)

    def components(self, reading: dict, history: Sequence[dict] = ()):
        rule = rule_engine.evaluate(reading)
        forest = self.model.evaluate(reading)
        residual = _residual_result(reading, self.stats)
        max_res = max(residual.residuals_norm.values()) if residual.residuals_norm else 0.0
        flat = _flatline(history, reading)
        return rule.is_anomaly, forest.score, max_res, flat


# ---------------------------------------------------------------------------
# Reports
# ---------------------------------------------------------------------------

def _percentiles(values: Sequence[float]) -> str:
    array = np.asarray(values, dtype=float)
    if not array.size:
        return "n/a"
    p = np.percentile(array, [5, 50, 95])
    return f"p5 {p[0]:5.3f}  p50 {p[1]:5.3f}  p95 {p[2]:5.3f}"


def report_separation(harness: Harness) -> None:
    print("\n" + "=" * 78)
    print("1. DETECTOR SEPARATION  (can a threshold separate these at all?)")
    print("=" * 78)

    data = workloads.balanced_by_kind(400)
    normal_if, normal_res = [], []
    anom_if, anom_res = [], []

    for reading, label in zip(data.readings, data.labels):
        _, if_score, res, _flat = harness.components(reading)
        (anom_if if label else normal_if).append(if_score)
        (anom_res if label else normal_res).append(min(1.0, res / 4.0))

    print(f"  Isolation Forest  normal   {_percentiles(normal_if)}")
    print(f"                    anomaly  {_percentiles(anom_if)}")
    print(f"  Residual (proxy)  normal   {_percentiles(normal_res)}")
    print(f"                    anomaly  {_percentiles(anom_res)}")

    overlap = np.percentile(normal_if, 95) >= np.percentile(anom_if, 5)
    print(f"\n  Isolation Forest distributions overlap at p95/p5: {overlap}")


def report_per_kind(harness: Harness) -> None:
    print("\n" + "=" * 78)
    print("2. RECALL BY FAILURE MODE  (what does each detector actually catch?)")
    print("=" * 78)
    print(f"  {'kind':<14} {'rules':>8} {'forest':>8} {'residual':>9} "
          f"{'flatline':>9} {'combined':>9}")

    per_kind = 400
    data = workloads.balanced_by_kind(per_kind)
    clean_history = workloads.history_window(24)
    # Working readings first, so each channel establishes what its normal
    # runs look like, then the freeze.
    stuck_prologue = workloads.stationary_stream(300, calm=0.25, seed=909)

    for kind in workloads.ANOMALY_KINDS:
        rows = [r for r, k in zip(data.readings, data.kinds) if k == kind]
        rule_hits = forest_hits = res_hits = flat_hits = combined_hits = 0
        for reading in rows:
            # A frozen sensor needs a realistic prologue, not a flat window:
            # the detector learns each channel's normal run length before it can
            # call one abnormal, so a window containing only the frozen value
            # gives it nothing to judge against. Every other failure mode
            # follows an ordinary healthy window.
            if kind == "stuck":
                history = stuck_prologue + [dict(reading)] * 40
            else:
                history = clean_history
            rule_fired, if_score, max_res, flat = harness.components(reading, history)
            rule_hits += rule_fired
            forest_hits += if_score >= config.ANOMALY_THRESHOLD
            res_hits += min(1.0, max_res / 4.0) >= config.ANOMALY_THRESHOLD
            flat_hits += flat.available and flat.is_anomaly
            combined_hits += harness.score(reading, history=history).is_anomaly
        n = len(rows)
        print(f"  {kind:<14} {rule_hits/n:>7.0%} {forest_hits/n:>8.0%} "
              f"{res_hits/n:>9.0%} {flat_hits/n:>9.0%} {combined_hits/n:>9.0%}")

    normals = [r for r, k in zip(data.readings, data.kinds) if k == ""]
    false_alarms = sum(harness.score(r, history=clean_history).is_anomaly for r in normals)
    print(f"  {'(normal)':<14} {'':>8} {'':>8} {'':>9} {'':>9} "
          f"{false_alarms/len(normals):>8.1%} false alarm")


def report_threshold_sweep(harness: Harness) -> None:
    print("\n" + "=" * 78)
    print("3. THRESHOLD SWEEP  (is the configured threshold defensible?)")
    print("=" * 78)

    data = workloads.labelled_stream(4000, anomaly_rate=0.05)
    scores, rule_flags = [], []
    for reading in data.readings:
        analysis = harness.score(reading)
        rule_flags.append(rule_engine.evaluate(reading).is_anomaly)
        scores.append(analysis.score)

    print(f"  {'threshold':>9} {'precision':>10} {'recall':>8} {'F1':>7} {'FP/1000':>9}")
    best = (0.0, None)
    for threshold in [round(t, 2) for t in np.arange(0.30, 1.00, 0.05)]:
        predicted = [s >= threshold or rf for s, rf in zip(scores, rule_flags)]
        stats = prf(predicted, data.labels)
        marker = "  <- configured" if abs(threshold - config.ANOMALY_THRESHOLD) < 1e-9 else ""
        print(f"  {threshold:>9.2f} {stats['precision']:>10.3f} {stats['recall']:>8.3f} "
              f"{stats['f1']:>7.3f} {stats['fp_per_1000']:>9.1f}{marker}")
        if stats["f1"] > best[0]:
            best = (stats["f1"], threshold)
    print(f"\n  best F1 {best[0]:.3f} at threshold {best[1]:.2f} "
          f"(configured {config.ANOMALY_THRESHOLD})")


def report_weight_sweep(harness: Harness) -> None:
    """Sweeps the two trust weights and the threshold together.

    The three interact: under a noisy-OR a detector's weight is the ceiling of
    what it can assert alone, so a threshold above a weight means that detector
    can never fire by itself no matter how certain it is. Picking any one of the
    three in isolation is how the service ended up with a threshold its own
    scorer could not reach.
    """
    print("\n" + "=" * 78)
    print("4. WEIGHT / THRESHOLD SWEEP  (the three settings are not independent)")
    print("=" * 78)

    data = workloads.labelled_stream(4000, anomaly_rate=0.05)
    components = [harness.components(r)[:3] for r in data.readings]

    def evaluate(w_if: float, w_res: float, threshold: float) -> Dict[str, float]:
        predicted = []
        for rule_fired, if_score, max_res in components:
            if rule_fired:
                predicted.append(True)
                continue
            nothing_wrong = (1.0 - w_if * min(1.0, max(0.0, if_score)))
            nothing_wrong *= (1.0 - w_res * min(1.0, max(0.0, max_res / 4.0)))
            predicted.append((1.0 - nothing_wrong) >= threshold)
        return prf(predicted, data.labels)

    rows = []
    for w_if in (0.3, 0.4, 0.5, 0.6, 0.7):
        for w_res in (0.4, 0.5, 0.6, 0.7, 0.8):
            for threshold in (0.40, 0.45, 0.50, 0.55, 0.60, 0.65, 0.70, 0.75):
                stats = evaluate(w_if, w_res, threshold)
                rows.append((stats["f1"], w_if, w_res, threshold, stats))

    rows.sort(key=lambda row: row[0], reverse=True)
    print(f"  {'w_if':>5} {'w_res':>6} {'thresh':>7} {'precision':>10} "
          f"{'recall':>8} {'F1':>7} {'FP/1000':>9}")
    print("  -- best F1 overall --")
    for f1, w_if, w_res, threshold, stats in rows[:5]:
        print(f"  {w_if:>5.1f} {w_res:>6.1f} {threshold:>7.2f} {stats['precision']:>10.3f} "
              f"{stats['recall']:>8.3f} {f1:>7.3f} {stats['fp_per_1000']:>9.1f}")

    # An alerting system is judged on what an operator has to read, so the
    # configuration that matters is the best recall inside a false-alarm budget,
    # not the best F1 in the abstract.
    budget = 5.0
    affordable = [row for row in rows if row[4]["fp_per_1000"] <= budget]
    affordable.sort(key=lambda row: row[4]["recall"], reverse=True)
    print(f"  -- best recall within {budget:.0f} false alarms per 1000 readings --")
    for f1, w_if, w_res, threshold, stats in affordable[:5]:
        print(f"  {w_if:>5.1f} {w_res:>6.1f} {threshold:>7.2f} {stats['precision']:>10.3f} "
              f"{stats['recall']:>8.3f} {f1:>7.3f} {stats['fp_per_1000']:>9.1f}")

    current = evaluate(config.W_IF, config.W_RESIDUAL, config.ANOMALY_THRESHOLD)
    print(f"  -- configured ({config.W_IF}, {config.W_RESIDUAL}, "
          f"{config.ANOMALY_THRESHOLD}) --")
    print(f"  {config.W_IF:>5.1f} {config.W_RESIDUAL:>6.1f} "
          f"{config.ANOMALY_THRESHOLD:>7.2f} {current['precision']:>10.3f} "
          f"{current['recall']:>8.3f} {current['f1']:>7.3f} "
          f"{current['fp_per_1000']:>9.1f}")


def report_degraded(harness: Harness) -> None:
    print("\n" + "=" * 78)
    print("5. DEGRADED OPERATION  (what does losing a detector cost?)")
    print("=" * 78)

    data = workloads.labelled_stream(3000, anomaly_rate=0.05, seed=99)
    modes = [
        ("full stack", True, True),
        ("no Isolation Forest", False, True),
        ("no forecast residual", True, False),
        ("rules only", False, False),
    ]
    print(f"  {'mode':<22} {'precision':>10} {'recall':>8} {'F1':>7} {'FP/1000':>9}")
    for label, if_on, forecast_on in modes:
        predicted = [harness.score(r, if_on, forecast_on).is_anomaly for r in data.readings]
        stats = prf(predicted, data.labels)
        print(f"  {label:<22} {stats['precision']:>10.3f} {stats['recall']:>8.3f} "
              f"{stats['f1']:>7.3f} {stats['fp_per_1000']:>9.1f}")


def report_determinism(harness: Harness) -> None:
    print("\n" + "=" * 78)
    print("6. DETERMINISM  (same reading, same verdict?)")
    print("=" * 78)
    data = workloads.labelled_stream(300, seed=7)
    first = [harness.score(r).score for r in data.readings]
    second = [harness.score(r).score for r in data.readings]
    print(f"  identical scores across two passes: {first == second}")

    retrained = Harness(training_rows=4000)
    third = [retrained.score(r).score for r in data.readings]
    print(f"  identical after retraining on the same seeded data: {first == third}")


def main() -> None:
    print("Detection quality — seeded synthetic fleet data")
    print(f"weights: rule=(override) if={config.W_IF} residual={config.W_RESIDUAL} "
          f"threshold={config.ANOMALY_THRESHOLD}")

    harness = Harness()
    scale = getattr(harness.model.model, "anomaly_scale_", None)
    print(f"model calibration scale: {scale:.4f}" if scale else "model uncalibrated")

    report_separation(harness)
    report_per_kind(harness)
    report_threshold_sweep(harness)
    report_weight_sweep(harness)
    report_degraded(harness)
    report_determinism(harness)
    print()


if __name__ == "__main__":
    # The service logs each device eviction at WARNING, which is right in
    # production and useless here: the churn test deliberately evicts hundreds
    # in a row and would bury the numbers it is printing.
    logging.disable(logging.WARNING)
    main()
