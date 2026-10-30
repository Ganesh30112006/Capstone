"""Can the flatline detector tell a calm night from a dead sensor?

This is the question the rest of the benchmark suite does not answer. The
per-kind table in `detection_quality` uses synthetic flat sequences, and a
detector can score 100% on those while being unusable in production — the first
version of this detector did exactly that.

What makes the problem hard is quantisation. Instruments report in steps, so a
settled environment produces long runs of bit-identical values with nothing
broken. The two causes of a flat trace look identical in a short window:

    environment not changing + finite resolution   ->  flat
    instrument stopped measuring                   ->  flat

So this harness measures the two things that matter operationally:

  1. **False alarms** across a range of genuinely stationary conditions, from
     barely-moving to ordinary. This is where the first detector scored 100%.
  2. **Detection latency** — readings between an instrument freezing and the
     service raising an anomaly, measured through the real scorer rather than
     the detector's own boolean, because a detector that flags internally while
     the service stays silent has not detected anything.

Both are measured against a mean-reverting process, not a random walk. That
matters: a plain walk with calm=0.25 wanders about 19 degrees over 4,000
readings and leaves the rule engine's physical bounds, so an earlier version of
this file was counting rule breaches caused by its own generator and reporting
them as flatline false alarms.

Run with:  python -m benchmarks.flatline_validation
"""

from __future__ import annotations

import logging
import sys
import tempfile
from pathlib import Path
from typing import Optional, Tuple

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import config  # noqa: E402

_SANDBOX = Path(tempfile.mkdtemp(prefix="ai-flatline-"))
config.IF_MODEL_PATH = _SANDBOX / "model.joblib"
config.METADATA_PATH = _SANDBOX / "metadata.json"
config.IF_SIGNATURE_PATH = _SANDBOX / "model.joblib.sig"

from anomaly_scorer import anomaly_scorer  # noqa: E402
from chronos_forecaster import ForecastResult  # noqa: E402
from flatline_detector import FlatlineDetector  # noqa: E402
from isolation_forest_model import IsolationForestResult  # noqa: E402
from rule_engine import rule_engine  # noqa: E402

from benchmarks import workloads  # noqa: E402

ROWS = 4000

# The trap this detector exists for: the forest sees an ordinary reading and
# the residual sees perfect agreement with its own forecast, because a flat
# series predicts itself exactly. Scoring against these two is what proves the
# flatline detector can carry a verdict alone, which it must.
SILENT_FOREST = IsolationForestResult(False, 0.0, [], available=True)
PERFECT_FORECAST = ForecastResult({}, {}, {"temperature": 0.0}, [], available=True)


def alerts(detector: FlatlineDetector, device: str, reading: dict) -> bool:
    """Whether the *service* raises an anomaly, not just whether the detector does."""
    flat = detector.observe(device, reading)
    analysis = anomaly_scorer.combine(
        rule_engine.evaluate(reading), SILENT_FOREST, PERFECT_FORECAST, reading, flat)
    return analysis.is_anomaly


def false_alarm_rate(stream) -> float:
    detector = FlatlineDetector()
    return sum(alerts(detector, "healthy", row) for row in stream) / len(stream)


def detection_latency(freeze_at: int, calm: float, seed: int = 801) -> Optional[int]:
    base = workloads.stationary_stream(ROWS + 1000, calm=calm, seed=seed)
    frozen = dict(base[freeze_at])
    stream = base[:freeze_at] + [dict(frozen) for _ in range(ROWS + 1000 - freeze_at)]

    detector = FlatlineDetector()
    for index, reading in enumerate(stream):
        if alerts(detector, "failing", reading):
            return index - freeze_at
    return None


def channel_latency(feature: str, freeze_at: int, calm: float) -> Optional[int]:
    """One channel freezes while the rest keep working — a failed sensing
    element rather than a hung board, and the harder case."""
    base = workloads.stationary_stream(ROWS + 2000, calm=calm, seed=811)
    frozen = dict(base[freeze_at])
    stream = base[:freeze_at] + [
        {name: (frozen[name] if name == feature else row[name]) for name in workloads.FEATURES}
        for row in base[freeze_at:]
    ]

    detector = FlatlineDetector()
    for index, reading in enumerate(stream):
        if alerts(detector, "failing", reading):
            return index - freeze_at
    return None


CONDITIONS: Tuple[Tuple[str, dict], ...] = (
    ("very calm", {"calm": 0.01}),
    ("calm night", {"calm": 0.05}),
    ("settled", {"calm": 0.10}),
    ("ordinary", {"calm": 0.25}),
    ("calm + slow drift", {"calm": 0.02, "drift_per_reading": 0.0005}),
)


def report_false_alarms() -> None:
    print("\n" + "=" * 78)
    print("1. FALSE ALARMS  (working instrument, genuinely stationary conditions)")
    print("=" * 78)
    print(f"  {'condition':<20} {'alerted':>10}   longest healthy run per channel")

    for label, kwargs in CONDITIONS:
        stream = workloads.stationary_stream(ROWS, seed=801, **kwargs)
        rate = false_alarm_rate(stream)

        runs = []
        for feature in workloads.FEATURES:
            longest = current = 1
            previous = stream[0][feature]
            for row in stream[1:]:
                if abs(row[feature] - previous) <= 1e-9:
                    current += 1
                else:
                    current = 1
                    previous = row[feature]
                longest = max(longest, current)
            runs.append(longest)
        print(f"  {label:<20} {rate:>9.2%}   " + " ".join(f"{r:>5}" for r in runs))

    print("\n  " + " " * 32 + " ".join(f"{f[:5]:>5}" for f in workloads.FEATURES))
    print("\n  Oxygen is the reason a fixed threshold cannot work: its reporting")
    print("  step is large relative to how much ambient oxygen actually moves, so")
    print("  a healthy sensor holds one value for as long as conditions hold.")


def report_detection() -> None:
    print("\n" + "=" * 78)
    print("2. DETECTION LATENCY  (freeze to service alert, whole device)")
    print("=" * 78)
    print(f"  {'freeze at reading':<20} " +
          " ".join(f"{label:>14}" for label, _ in CONDITIONS[:4]))

    for freeze_at in (300, 1000, 2000):
        cells = []
        for _, kwargs in CONDITIONS[:4]:
            latency = detection_latency(freeze_at, kwargs["calm"])
            cells.append(f"+{latency}" if latency is not None else "never")
        print(f"  {freeze_at:<20} " + " ".join(f"{c:>14}" for c in cells))


def report_per_channel() -> None:
    print("\n" + "=" * 78)
    print("3. SINGLE FAILED CHANNEL  (sensing element, not the whole board)")
    print("=" * 78)
    print(f"  {'frozen channel':<20} {'calm night':>14} {'settled':>14}")

    for feature in workloads.FEATURES:
        cells = []
        for calm in (0.05, 0.10):
            latency = channel_latency(feature, 500, calm)
            cells.append(f"+{latency}" if latency is not None else "never")
        print(f"  {feature:<20} " + " ".join(f"{c:>14}" for c in cells))

    print("\n  Latency scales with how variable the channel normally is, which is")
    print("  the intended behaviour rather than a weakness: the alarm point is a")
    print("  multiple of that channel's own longest healthy run, so a channel")
    print("  that legitimately sits still for a long time is given that long.")


def report_limits() -> None:
    print("\n" + "=" * 78)
    print("4. KNOWN BLIND SPOT  (frozen before the service ever saw it change)")
    print("=" * 78)

    stream = workloads.frozen_stream(ROWS, features=["temperature"])
    detector = FlatlineDetector()
    caught = any(alerts(detector, "cold-frozen", row) for row in stream)
    print(f"  temperature frozen from the first reading: "
          f"{'detected' if caught else 'NOT detected'}")

    state = detector._devices["cold-frozen"]["temperature"]
    print(f"  that channel completed {state.runs_seen} runs, so it never established")
    print("  a normal to break.")
    print("\n  This is a real limit, not an oversight. A channel that has never been")
    print("  seen to move is indistinguishable from one whose quantity does not")
    print("  move — ambient oxygen at a 0.1% reporting step is the ordinary case,")
    print("  and an absolute run limit flagged every such sensor after two days.")
    print("  Closing it needs information the readings do not carry: the")
    print("  instrument's declared resolution, or a heartbeat independent of the")
    print("  measurement.")


def main() -> None:
    print("Flatline validation — stationary conditions against frozen instruments")
    ceiling = (f"capped at {config.FLATLINE_ABSOLUTE_RUN}"
               if config.FLATLINE_ABSOLUTE_RUN > 0 else "no ceiling")
    print(f"alarm = {config.FLATLINE_RUN_MULTIPLIER}x the channel's longest completed run, "
          f"minimum {config.FLATLINE_MIN_RUN}, {ceiling}")
    print(f"warm-up: {config.FLATLINE_WARMUP_RUNS} completed runs before a channel is judged")

    report_false_alarms()
    report_detection()
    report_per_channel()
    report_limits()
    print()


if __name__ == "__main__":
    logging.disable(logging.WARNING)
    main()
