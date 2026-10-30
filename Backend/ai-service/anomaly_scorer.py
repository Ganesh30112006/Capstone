"""Combines the detectors into one score.

Four detectors, answering different questions and failing differently, so the
combination has to be explicit about both:

  * **Rules** encode physical limits. Deterministic, always available, and a
    breach is an anomaly regardless of what anything else thinks — a reading
    outside an instrument's operating envelope is a broken sensor, not a subtle
    pattern. They are a hard override, not a vote.
  * **Isolation Forest** finds readings whose feature *combination* is unlike
    the training distribution. Needs a fitted model.
  * **Chronos residuals** measure divergence from what the recent series
    predicted. Needs a loaded model and enough history.
  * **Flatline** finds an instrument that has stopped measuring. Needs a window.

The three graded detectors are combined as independent evidence — a noisy-OR —
rather than averaged, and each one's weight is the ceiling of what it can
assert on its own.

Both of those properties exist because their absence was measured, not
theorised:

  * Rules used to be a weighted term here, carrying the largest weight. A
    reading that broke no physical bound therefore contributed that weight to
    the denominator and nothing to the numerator, capping every score at
    (W_IF + W_RESIDUAL) / (W_RULE + W_IF + W_RESIDUAL) = 0.5 against a
    threshold of 0.75. Both models pinned at maximum scored 0.500 and never
    fired: neither could report an anomaly at any input.
  * Averaging let a detector that saw nothing pull down one that was certain,
    on precisely the cases the certain one exists to catch. The full stack
    scored recall 0.557 while the same stack with the Isolation Forest switched
    off scored 0.800 — adding a working detector made detection worse.

A noisy-OR fixes the second and subsumes the hand-rolled renormalisation this
scorer used to need: a detector with nothing to say contributes a factor of 1,
so "no signal" is no longer read as "nothing wrong", and the combined score can
never fall below what any single detector reports.

Weights and threshold are therefore one decision, not four. See
benchmarks/detection_quality.py, which sweeps them together.
"""

from __future__ import annotations

from typing import Dict, List, Optional

import config


class AnomalyAnalysis:
    __slots__ = ("is_anomaly", "score", "severity", "reasons", "max_sensor", "components")

    def __init__(self, is_anomaly: bool, score: float, severity: str, reasons: List[str],
                 max_sensor: Optional[str], components: Optional[Dict[str, bool]] = None):
        self.is_anomaly = is_anomaly
        self.score = score
        self.severity = severity
        self.reasons = reasons
        self.max_sensor = max_sensor
        # Which detectors contributed, so a caller can tell a confident "normal"
        # from one reached with half the stack unavailable.
        self.components = components or {}


def _clamp(value: float) -> float:
    """Keeps a detector's contribution inside [0, 1].

    A score above 1 would make its noisy-OR factor negative and flip the sign
    of the combined evidence, so this is load-bearing rather than defensive.
    """
    if value != value:  # NaN
        return 0.0
    return 0.0 if value < 0.0 else (1.0 if value > 1.0 else value)


def _dedupe(reasons: List[str]) -> List[str]:
    """Order-preserving dedupe. A set would reorder the explanation per call."""
    seen = set()
    out = []
    for reason in reasons:
        if reason not in seen:
            seen.add(reason)
            out.append(reason)
    return out


class AnomalyScorer:

    def combine(self, rule_result, if_result, forecast_result,
                current_readings: Dict[str, float],
                flatline_result=None) -> AnomalyAnalysis:

        reasons: List[str] = []
        reasons.extend(rule_result.reasons)
        reasons.extend(if_result.reasons)
        reasons.extend(forecast_result.reasons)
        if flatline_result is not None:
            reasons.extend(flatline_result.reasons)

        if_available = getattr(if_result, "available", True)
        forecast_available = getattr(forecast_result, "available", True)
        flatline_available = (flatline_result is not None
                              and getattr(flatline_result, "available", False))

        # Two things changed here, and both were load-bearing.
        #
        # First, rules left the weighted sum. They used to sit in it carrying
        # the largest weight of the three, so a reading that broke no physical
        # bound contributed that weight to the denominator and nothing to the
        # numerator, capping every score at
        #
        #     (W_IF + W_RESIDUAL) / (W_RULE + W_IF + W_RESIDUAL) = 0.5
        #
        # against a threshold of 0.75. Both models pinned at maximum scored
        # 0.500 and did not fire: the Isolation Forest and the Chronos residual
        # could not report an anomaly at any input, and the two models were
        # decoration on a rule engine. Rules are a hard override now, which is
        # what they already were for the boolean below — a reading outside an
        # instrument's physical envelope is a certainty, not a probability, and
        # does not belong in a weighted vote against detectors dealing in
        # likelihoods.
        #
        # Second, the models are combined as independent evidence (a noisy-OR)
        # rather than averaged. They answer different questions: the forest
        # finds readings whose feature *combination* is unlike the training
        # distribution; the residual finds readings that diverge from what the
        # recent series predicted. A spike in one feature is a real anomaly the
        # forest has no reason to object to, and a plausible-looking
        # combination is one the residual has no reason to object to — so an
        # average makes each detector dilute the other on exactly the cases the
        # other exists to catch. Measured on labelled data that was not subtle:
        # the full stack scored recall 0.557 while the same stack with the
        # Isolation Forest *switched off* scored 0.800. Adding a working
        # detector made detection worse.
        #
        # Under a noisy-OR evidence only accumulates. Each detector multiplies
        # the probability that nothing is wrong, a detector with nothing to say
        # contributes a factor of 1, and the combined score can never fall below
        # what any single detector reports. That monotonicity is what was
        # missing, and it also subsumes the renormalisation this scorer used to
        # do by hand: an unavailable detector contributes no evidence, which is
        # the correct reading of "no signal" — rather than needing its weight
        # lifted out of a denominator so it does not count as "nothing wrong".
        #
        # A consequence worth stating: a detector's weight is the ceiling of
        # what it can assert alone, so a weight below the threshold means that
        # detector can never fire by itself. Weights and threshold are one
        # decision; see config and benchmarks/detection_quality.py.
        nothing_wrong = 1.0

        # 1. Isolation Forest. The weight is how far one detector alone is
        #    trusted to carry a verdict.
        if if_available:
            nothing_wrong *= 1.0 - config.W_IF * _clamp(if_result.score)

        # 2. Forecast residual, taken from the most divergent feature.
        max_res_sensor = None
        if forecast_available and forecast_result.residuals_norm:
            max_res_sensor = max(forecast_result.residuals_norm,
                                 key=forecast_result.residuals_norm.get)
            max_res_val = forecast_result.residuals_norm[max_res_sensor]
            # 4 sigma or beyond saturates the residual contribution.
            nothing_wrong *= 1.0 - config.W_RESIDUAL * _clamp(max_res_val / 4.0)

        # 3. Flatline. Weighted above the threshold so it can fire alone, which
        #    it must: a frozen instrument drives the residual detector to
        #    perfect agreement, so the rest of the stack reads it as healthier
        #    than a working sensor rather than merely unremarkable.
        if flatline_available and flatline_result.is_anomaly:
            nothing_wrong *= 1.0 - config.W_FLATLINE * _clamp(flatline_result.score)

        model_score = 1.0 - nothing_wrong

        if rule_result.is_anomaly:
            # Certainty, not agreement. The models can only add detail to a
            # physical-bounds breach, never argue it down.
            final_score = 1.0
            is_anomaly = True
        else:
            final_score = model_score
            is_anomaly = final_score >= config.ANOMALY_THRESHOLD

        flatline_sensor = getattr(flatline_result, "sensor", None) if flatline_available else None
        max_sensor = rule_result.max_sensor or flatline_sensor or max_res_sensor or "multiple"

        severity = "NORMAL"
        if is_anomaly:
            if final_score > 0.9 or rule_result.severity == "CRITICAL":
                severity = "CRITICAL"
            elif final_score > 0.8:
                severity = "HIGH"
            elif final_score > 0.6:
                severity = "MEDIUM"
            else:
                severity = "LOW"

        if not (if_available and forecast_available):
            missing = []
            if not if_available:
                missing.append("Isolation Forest")
            if not forecast_available:
                missing.append("forecast residuals")
            reasons.append("Scored without " + " and ".join(missing) + ".")

        return AnomalyAnalysis(
            is_anomaly=is_anomaly,
            score=round(final_score, 2),
            severity=severity,
            reasons=_dedupe(reasons),
            max_sensor=max_sensor,
            components={"rules": True, "isolationForest": if_available,
                        "forecast": forecast_available, "flatline": flatline_available},
        )


anomaly_scorer = AnomalyScorer()
