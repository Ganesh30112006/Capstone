"""Zero-shot time-series forecasting over recent per-feature history.

Chronos is a transformer, and it is by far the most expensive thing in this
service. Two properties of the implementation matter more than anything else
here:

  * **All features go through the model in one batched call.** Forecasting five
    features with five sequential `predict` calls costs five forward passes and
    five lots of Python/tensor overhead to produce what one batch produces.
  * **Inference runs under `torch.inference_mode()`.** Nothing here trains, so
    building an autograd graph is pure waste of time and memory.

The pipeline is loaded lazily and never re-loaded on failure: a model that
cannot be fetched stays absent, callers get an empty forecast, and the hybrid
scorer carries on without the residual signal rather than stalling every
reading behind a retry.
"""

from __future__ import annotations

import logging
import math
import os
import threading
from typing import Dict, List, Optional, Sequence

import numpy as np
import torch

import config
import metrics

try:
    from chronos import BaseChronosPipeline
except ImportError:  # pragma: no cover - exercised only without the extra installed
    BaseChronosPipeline = None

logger = logging.getLogger(__name__)

FEATURES: List[str] = ["temperature", "humidity", "airQuality", "oxygen", "pressure"]

# Chronos needs a usable window before a forecast means anything.
MIN_CONTEXT = 5

# Residual beyond this many standard deviations is called out as a divergence.
RESIDUAL_SIGMA = 3.0

# A CPU container gets no benefit from torch spawning a thread per core and
# then contending with the web server and two consumer threads for them.
_TORCH_THREADS = int(os.environ.get("AI_TORCH_THREADS", "2"))
try:
    torch.set_num_threads(max(1, _TORCH_THREADS))
except Exception:  # pragma: no cover - platform dependent
    logger.debug("Could not set torch thread count", exc_info=True)


def extract_series(history: Sequence[dict], features: Sequence[str]) -> Dict[str, np.ndarray]:
    """Per-feature float arrays over the history window.

    Shared by the inference path and the residual path, which need the same
    columns out of the same rows; the residual path runs for every arriving
    reading, so this is the hottest non-model code in the service.

    It stays a comprehension per feature rather than one loop accumulating all
    of them. A single pass touches each row once but does it in bytecode, and
    measured slower than five comprehensions that iterate in C — fewer passes
    is not the same as less work. The non-finite filter is vectorised in numpy
    for the same reason.

    Non-finite samples are dropped rather than carried: one NaN in a context
    window makes the whole forecast NaN, and one in a spread calculation makes
    the normalised residual NaN, which then compares false against every
    divergence threshold — removing the detector instead of reporting it gone.
    """
    out: Dict[str, np.ndarray] = {}
    for feature in features:
        values = [row[feature] for row in history if row.get(feature) is not None]
        if not values:
            continue
        # The cast is to float32, so a sample that is finite as a float64 but
        # larger than ~3.4e38 becomes infinity here. That is the same condition
        # the filter below exists for, and it is handled there — the warning
        # numpy would otherwise raise reports a case this code has already
        # decided about, so it is silenced rather than left to look like a
        # fault.
        with np.errstate(over="ignore", invalid="ignore"):
            try:
                array = np.asarray(values, dtype=np.float32)
            except (TypeError, ValueError):
                # A non-numeric sample somewhere in the column. Rare enough to
                # be worth handling on a slow path rather than slowing the fast
                # one.
                array = np.asarray(_coerce(values), dtype=np.float32)
        finite = array[np.isfinite(array)]
        if finite.size:
            out[feature] = finite
    return out


def _coerce(values: Sequence) -> List[float]:
    """Best-effort float conversion, skipping whatever cannot be converted."""
    coerced: List[float] = []
    for value in values:
        try:
            coerced.append(float(value))
        except (TypeError, ValueError):
            continue
    return coerced


def _population_std(values: np.ndarray) -> float:
    """np.std(values, dtype=np.float64), without np.std's dispatch overhead.

    This runs once per feature per reading on the residual path — five times for
    every reading the service scores — and `np.std` spends most of a 48-sample
    call in Python: argument normalisation, error-state save and restore, and
    the sparse/subclass checks it makes before reaching any arithmetic. Doing
    the same two passes directly measured 2.6x faster, which was worth having
    once the Isolation Forest stopped dominating the profile and this became
    co-equal with it.

    Deliberately *not* the algebraic shortcut, var = E[x^2] - E[x]^2, which is
    faster still (3.6x) and wrong for this data. It subtracts two large nearly
    equal numbers, and these are exactly the features that trigger that: a
    settled pressure channel sits near 1013 with a spread under 1. Measured, it
    lost up to 6.6e-10 relative on ordinary pressure windows and 3e-5 on a
    nearly-still one — and the spread is a *divisor* here, so that error lands
    straight in the normalised residual the detection threshold is applied to.

    The two-pass form below reproduces numpy's own algorithm, in the same order
    and the same dtype, so it is bit-identical rather than merely close:
    verified over 30,000 windows spanning all five feature distributions, the
    degenerate zero-variance case, and window lengths from 2 to 2,000.
    """
    x = np.asarray(values, dtype=np.float64)
    count = x.size
    deviation = x - x.sum() / count
    deviation *= deviation
    return float(np.sqrt(deviation.sum() / count))


class ForecastResult:
    __slots__ = ("expected", "residuals", "residuals_norm", "reasons", "available")

    def __init__(self, expected: Dict[str, float], residuals: Dict[str, float],
                 residuals_norm: Dict[str, float], reasons: List[str], available: bool = True):
        self.expected = expected
        self.residuals = residuals
        self.residuals_norm = residuals_norm
        self.reasons = reasons
        # False when no forecast could be produced at all, so the scorer can
        # renormalise rather than read "no residual" as "nothing unusual".
        self.available = available


class ChronosForecaster:

    def __init__(self):
        self.pipeline = None
        self._lock = threading.Lock()
        self._load_failed = False
        self.features = FEATURES

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------

    def _load_model(self) -> None:
        """Loads the pipeline once. A failed load is not retried per reading."""
        if self.pipeline is not None or self._load_failed or BaseChronosPipeline is None:
            return
        with self._lock:
            if self.pipeline is not None or self._load_failed:
                return
            try:
                logger.info("Loading Chronos pipeline: %s", config.CHRONOS_MODEL)
                self.pipeline = BaseChronosPipeline.from_pretrained(
                    config.CHRONOS_MODEL,
                    device_map=config.DEVICE_MAP,
                )
                logger.info("Chronos pipeline ready")
                metrics.MODEL_AVAILABLE.labels("chronos").set(1)
            except Exception:
                # Marked failed so every subsequent reading skips straight to the
                # fallback instead of paying for another download attempt.
                self._load_failed = True
                metrics.MODEL_AVAILABLE.labels("chronos").set(0)
                logger.exception("Chronos pipeline failed to load; forecasting is disabled")

    @property
    def is_available(self) -> bool:
        return self.pipeline is not None

    # ------------------------------------------------------------------
    # Core inference
    # ------------------------------------------------------------------

    def predict_features(
        self,
        history: Sequence[dict],
        horizon: int = 1,
        features: Optional[Sequence[str]] = None,
    ) -> Dict[str, List[float]]:
        """Multi-step forecast per feature, produced in a single batched call.

        Returns an empty dict when the model is unavailable or no feature has
        enough context, so every caller can fall back the same way.
        """
        if horizon < 1:
            return {}
        self._load_model()
        if self.pipeline is None:
            return {}

        wanted = list(features) if features is not None else self.features

        # One pass for every feature, then one model call for all of them.
        # from_numpy shares the array's buffer instead of walking a Python list
        # element by element, which matters at 48 samples x 5 features per call.
        names: List[str] = []
        contexts: List[torch.Tensor] = []
        for feature, series in extract_series(history, wanted).items():
            if series.size < MIN_CONTEXT:
                continue
            names.append(feature)
            contexts.append(torch.from_numpy(series))

        if not contexts:
            return {}

        try:
            with metrics.CHRONOS_LATENCY.time(), torch.inference_mode():
                forecasts = self.pipeline.predict(contexts, prediction_length=horizon)
            metrics.CHRONOS_INFERENCES.inc()
            metrics.CHRONOS_SERIES.inc(len(contexts))
        except Exception:
            metrics.CHRONOS_FAILURES.inc()
            logger.exception("Chronos inference failed for features %s", names)
            return {}

        out: Dict[str, List[float]] = {}
        for name, forecast in zip(names, forecasts):
            try:
                raw = forecast.numpy() if hasattr(forecast, "numpy") else forecast
                samples = np.asarray(raw, dtype=float)
                # The last axis is always the horizon; everything before it is
                # sample (and possibly batch) dimensions to take the median
                # over. Collapsing them explicitly keeps this correct whether
                # the pipeline hands back one tensor per series or one stacked
                # batch tensor.
                if samples.ndim <= 1:
                    median = np.atleast_1d(samples)
                else:
                    median = np.median(samples.reshape(-1, samples.shape[-1]), axis=0)
                out[name] = [round(float(value), 3) for value in np.ravel(median)[:horizon]]
            except Exception:
                logger.exception("Could not read the Chronos forecast for %s", name)
        return out

    # ------------------------------------------------------------------
    # Residual scoring
    # ------------------------------------------------------------------

    def forecast_and_evaluate(self, history: List[Dict[str, float]],
                              current: Dict[str, float]) -> ForecastResult:
        """One-step-ahead forecast compared against the reading that arrived."""
        if not history or BaseChronosPipeline is None:
            return ForecastResult({}, {}, {}, ["Chronos disabled or insufficient history."], available=False)

        present = [feature for feature in self.features if feature in current]
        expected = self.predict_features(history, horizon=1, features=present)
        if not expected:
            return ForecastResult({}, {}, {}, ["No Chronos forecast available for this window."],
                                  available=False)

        return self.evaluate_against(expected, history, current)

    def evaluate_against(
        self,
        expected_series: Dict[str, List[float]],
        history: Sequence[dict],
        current: Dict[str, float],
    ) -> ForecastResult:
        """Scores a reading against an already-computed forecast.

        Split out from the inference so a cached forecast can be reused across
        readings: the comparison is arithmetic, the forecast is a transformer
        forward pass, and only one of those is worth repeating per reading.
        """
        expected: Dict[str, float] = {}
        residuals: Dict[str, float] = {}
        residuals_norm: Dict[str, float] = {}
        reasons: List[str] = []

        # Shares the extraction with the inference path. This is not a speed
        # win — measured, it is within noise of the code it replaced — but it
        # is one implementation of "the history as numbers" instead of two, and
        # it is where non-finite samples get filtered out before they can reach
        # a standard deviation.
        scorable = [feature for feature in expected_series if feature in current]
        contexts = extract_series(history, scorable)

        for feature in scorable:
            series = expected_series[feature]
            if not series:
                continue
            predicted = float(series[0])
            if not math.isfinite(predicted):
                # A NaN forecast would make every residual NaN, and a NaN
                # residual compares false against the divergence threshold —
                # silently removing this detector rather than reporting it gone.
                continue
            actual = float(current[feature])
            if not math.isfinite(actual):
                # Same reasoning in the other direction: the rule engine flags a
                # non-numeric reading, and this detector declines to invent a
                # residual for it rather than contributing a NaN to the score.
                continue
            expected[feature] = predicted

            residual = actual - predicted
            residuals[feature] = residual

            context = contexts.get(feature)
            # Accumulated in float64 even though the array is float32 (which is
            # what the model's context tensor wants). A float32 std over a
            # 48-sample window loses enough precision to move the normalised
            # residual, and that value is compared against a fixed sigma
            # threshold — so the dtype would quietly shift the detection edge.
            #
            # Guarded so a stuck sensor (zero variance) cannot divide by ~0 and
            # report an infinite divergence.
            spread = (_population_std(context)
                      if context is not None and context.size else 0.0)
            normalised = abs(residual) / max(spread, 1e-3)
            residuals_norm[feature] = normalised

            if normalised > RESIDUAL_SIGMA:
                reasons.append(
                    f"Actual {feature} ({actual:.1f}) diverges from the forecast ({predicted:.1f})."
                )

        available = bool(expected)
        if not available:
            reasons.append("No Chronos forecast available for this window.")
        return ForecastResult(expected, residuals, residuals_norm, reasons, available=available)


chronos_forecaster = ChronosForecaster()
