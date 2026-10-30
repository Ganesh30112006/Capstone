"""Validates the real Chronos residual detector, not the stand-in.

`detection_quality` substitutes a persistence forecast for Chronos, because it
has to run where the model is not installed. That substitute is deliberately
weak, so the weights tuned against it are a conservative floor — but a floor is
only useful if someone eventually checks the real thing against it.

This harness does that. It is separate rather than a section of the quality
report because every evaluation here is a transformer forward pass: it is slow
enough that folding it into the default run would stop people running the
default run.

It answers three questions:

  1. Does the real residual detector separate anomalous readings from normal
     ones at all, per failure mode?
  2. Is it stronger or weaker than the stand-in the weights were tuned against?
     If weaker, the tuned weights are not conservative and need revisiting.
  3. What does a forecast actually cost, and how much does the horizon cache
     save in practice?

Run with:  python -m benchmarks.chronos_validation
"""

from __future__ import annotations

import logging
import sys
import tempfile
import time
from pathlib import Path
from typing import Dict, List

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import config  # noqa: E402

_SANDBOX = Path(tempfile.mkdtemp(prefix="ai-chronos-"))
config.IF_MODEL_PATH = _SANDBOX / "model.joblib"
config.METADATA_PATH = _SANDBOX / "metadata.json"
config.IF_SIGNATURE_PATH = _SANDBOX / "model.joblib.sig"

from chronos_forecaster import chronos_forecaster  # noqa: E402

from benchmarks import workloads  # noqa: E402
from benchmarks.workloads import FEATURES  # noqa: E402

SAMPLES_PER_KIND = 25
CONTEXT = 32


def _normalised_residual(history: List[dict], reading: dict) -> float:
    """The value the scorer actually consumes: the largest normalised residual."""
    expected = chronos_forecaster.predict_features(history, horizon=1, features=FEATURES)
    if not expected:
        return float("nan")
    result = chronos_forecaster.evaluate_against(expected, history, reading)
    if not result.available or not result.residuals_norm:
        return float("nan")
    return max(result.residuals_norm.values())


def _proxy_residual(stats: Dict[str, tuple], reading: dict) -> float:
    """The stand-in detection_quality uses, for a like-for-like comparison."""
    return max(abs(float(reading[name]) - mean) / max(sd, 1e-3)
               for name, (mean, sd) in stats.items() if name in reading)


def main() -> None:
    print("Chronos residual validation — the real detector, not the stand-in")

    chronos_forecaster._load_model()
    if not chronos_forecaster.is_available:
        print("\n  Chronos is not installed or failed to load.")
        print("  Nothing to validate; detection_quality's stand-in stands.")
        return
    print(f"  model: {config.CHRONOS_MODEL}  context: {CONTEXT} readings\n")

    history = workloads.history_window(CONTEXT, seed=61)
    stats = {name: (float(np.mean([r[name] for r in history])),
                    float(np.std([r[name] for r in history])))
             for name in FEATURES}
    data = workloads.balanced_by_kind(SAMPLES_PER_KIND, seed=71)

    def _time(horizon_steps: int, repeats: int = 3) -> float:
        """Min of several warmed calls.

        The first call after loading is several times slower than steady state,
        so timing it once made a horizon-1 forecast look more expensive than a
        horizon-6 one — which would have inverted the conclusion this section
        exists to draw.
        """
        best = float("inf")
        for _ in range(repeats):
            start = time.perf_counter()
            chronos_forecaster.predict_features(history, horizon=horizon_steps,
                                                features=FEATURES)
            best = min(best, time.perf_counter() - start)
        return best

    # Warm the graph before anything is timed.
    chronos_forecaster.predict_features(history, horizon=1, features=FEATURES)
    single = _time(1)
    horizon = _time(config.AI_STREAM_FORECAST_HORIZON)
    print(f"  one batched forecast, horizon 1:  {single*1000:7.1f} ms")
    print(f"  one batched forecast, horizon {config.AI_STREAM_FORECAST_HORIZON}:  "
          f"{horizon*1000:7.1f} ms")
    print(f"  -> the horizon cache spreads {horizon*1000:.0f} ms over "
          f"{config.AI_STREAM_FORECAST_HORIZON} readings "
          f"({horizon*1000/config.AI_STREAM_FORECAST_HORIZON:.1f} ms each) instead of "
          f"paying {single*1000:.0f} ms per reading\n")

    threshold_sigma = 4.0 * config.ANOMALY_THRESHOLD / config.W_RESIDUAL
    print(f"  {'kind':<14} {'chronos sigma':>16} {'proxy sigma':>14} {'chronos fires':>15}")

    for kind in ("", ) + workloads.ANOMALY_KINDS:
        rows = [r for r, k in zip(data.readings, data.kinds) if k == kind]
        if not rows:
            continue
        real, proxy = [], []
        for reading in rows:
            # A stuck sensor's context is its own repeated value; everything
            # else follows a healthy window.
            window = [dict(reading)] * CONTEXT if kind == "stuck" else history
            value = _normalised_residual(window, reading)
            if value == value:
                real.append(value)
            proxy.append(_proxy_residual(stats, reading))

        if not real:
            continue
        real_arr, proxy_arr = np.asarray(real), np.asarray(proxy)
        fires = float((np.minimum(1.0, real_arr / 4.0) * config.W_RESIDUAL
                       >= config.ANOMALY_THRESHOLD).mean())
        label = kind or "(normal)"
        print(f"  {label:<14} {real_arr.mean():>9.2f} (p50 {np.median(real_arr):>4.1f}) "
              f"{proxy_arr.mean():>13.2f} {fires:>14.0%}")

    print(f"\n  A residual fires alone at {threshold_sigma:.1f} sigma "
          f"(threshold {config.ANOMALY_THRESHOLD} / weight {config.W_RESIDUAL}, "
          "saturating at 4 sigma).")
    print("  Where the chronos column is well above the proxy column, the weights")
    print("  tuned in detection_quality are conservative, as intended. Where it is")
    print("  below, they are not, and that sweep should be re-run here.")


if __name__ == "__main__":
    # The service logs each device eviction at WARNING, which is right in
    # production and useless here: the churn test deliberately evicts hundreds
    # in a row and would bury the numbers it is printing.
    logging.disable(logging.WARNING)
    main()
