"""Synthetic fleet data with known labels.

Shared by the detection-quality and performance harnesses so both measure the
same thing. Everything is seeded: a benchmark whose numbers move between runs
cannot be used to judge a change.

The anomaly kinds are the failure modes this platform actually sees on an
environmental sensor, and they are deliberately of different difficulty:

  * ``out_of_range`` — a value outside the instrument's physical envelope. The
    rule engine alone should catch every one of these; if it does not, the
    deterministic floor is broken.
  * ``spike`` — a sudden jump that stays inside physical bounds. Invisible to
    rules. This is what the forecast residual exists for.
  * ``multivariate`` — every feature individually plausible, the combination
    not (hot, humid, low oxygen and low pressure at once). Invisible to both
    rules and a per-feature residual. This is the only thing the Isolation
    Forest can catch that nothing else can, and it is the reason it is here.
  * ``drift`` — a slow shift away from the trained distribution. Hard for
    everything, included because reporting a detector's weak case is the point
    of measuring it.
  * ``stuck`` — an instrument repeating one value. Physically plausible and
    statistically unremarkable per reading; included to show what the current
    stack does *not* catch.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, List, Optional, Sequence, Tuple

import numpy as np

FEATURES = ["temperature", "humidity", "airQuality", "oxygen", "pressure"]

# The distribution a healthy station reports. Loosely modelled on a temperate
# outdoor site, and well inside every rule bound so that "normal" data never
# accidentally trips the deterministic floor.
BASELINE = {
    "temperature": (22.0, 3.0),
    "humidity": (55.0, 8.0),
    "airQuality": (45.0, 12.0),
    "oxygen": (20.9, 0.15),
    "pressure": (1013.0, 6.0),
}

ANOMALY_KINDS = ("out_of_range", "spike", "multivariate", "drift", "stuck")


@dataclass
class Labelled:
    """A reading stream with ground truth alongside it."""

    readings: List[dict]
    labels: List[bool]
    kinds: List[str]  # "" for normal readings

    def __len__(self) -> int:
        return len(self.readings)


def normal_frame(rows: int, seed: int = 11) -> Dict[str, np.ndarray]:
    """Training data: the healthy distribution, nothing else."""
    rng = np.random.default_rng(seed)
    return {
        name: rng.normal(mean, sd, rows)
        for name, (mean, sd) in BASELINE.items()
    }


def _normal_reading(rng: np.random.Generator) -> dict:
    return {name: float(rng.normal(mean, sd)) for name, (mean, sd) in BASELINE.items()}


def _inject(reading: dict, kind: str, rng: np.random.Generator) -> dict:
    out = dict(reading)
    if kind == "out_of_range":
        # Past TEMP_MAX (45) or below OX_MIN (15) — the rule engine's territory.
        if rng.random() < 0.5:
            out["temperature"] = float(rng.uniform(50.0, 80.0))
        else:
            out["oxygen"] = float(rng.uniform(5.0, 14.0))
    elif kind == "spike":
        # A jump of 6-10 baseline sigma that still lands inside rule bounds.
        name = ["temperature", "humidity", "airQuality"][rng.integers(0, 3)]
        mean, sd = BASELINE[name]
        out[name] = float(mean + rng.choice([-1.0, 1.0]) * rng.uniform(6.0, 10.0) * sd)
        out["temperature"] = min(out["temperature"], 44.0)
        out["humidity"] = float(np.clip(out["humidity"], 0.0, 99.0))
        out["airQuality"] = float(np.clip(out["airQuality"], 0.0, 290.0))
    elif kind == "multivariate":
        # Each value is unremarkable on its own; together they are a combination
        # the trained distribution never contains.
        out["temperature"] = float(rng.uniform(33.0, 37.0))
        out["humidity"] = float(rng.uniform(88.0, 96.0))
        out["airQuality"] = float(rng.uniform(110.0, 140.0))
        out["oxygen"] = float(rng.uniform(20.0, 20.3))
        out["pressure"] = float(rng.uniform(975.0, 985.0))
    elif kind == "drift":
        # A shift of 3-4 sigma: outside the healthy distribution, but far less
        # extreme than a spike.
        for name in ("temperature", "humidity"):
            mean, sd = BASELINE[name]
            out[name] = float(mean + rng.uniform(3.0, 4.0) * sd)
    elif kind == "stuck":
        out.update({"temperature": 22.0, "humidity": 55.0, "airQuality": 45.0,
                    "oxygen": 20.9, "pressure": 1013.0})
    return out


def labelled_stream(rows: int, anomaly_rate: float = 0.05, seed: int = 23,
                    kinds: Tuple[str, ...] = ANOMALY_KINDS) -> Labelled:
    """A reading stream with `anomaly_rate` of readings deliberately broken."""
    rng = np.random.default_rng(seed)
    readings: List[dict] = []
    labels: List[bool] = []
    label_kinds: List[str] = []

    for _ in range(rows):
        reading = _normal_reading(rng)
        if rng.random() < anomaly_rate:
            kind = kinds[int(rng.integers(0, len(kinds)))]
            readings.append(_inject(reading, kind, rng))
            labels.append(True)
            label_kinds.append(kind)
        else:
            readings.append(reading)
            labels.append(False)
            label_kinds.append("")

    return Labelled(readings, labels, label_kinds)


def balanced_by_kind(per_kind: int, seed: int = 31) -> Labelled:
    """Equal numbers of each anomaly kind, plus a matching pool of normals.

    Used to report per-kind recall, which a single mixed stream cannot: with a
    5% anomaly rate, five kinds and a few thousand rows, some kinds get too few
    samples for their recall to mean anything.
    """
    rng = np.random.default_rng(seed)
    readings: List[dict] = []
    labels: List[bool] = []
    kinds: List[str] = []

    for kind in ANOMALY_KINDS:
        for _ in range(per_kind):
            readings.append(_inject(_normal_reading(rng), kind, rng))
            labels.append(True)
            kinds.append(kind)

    for _ in range(per_kind * len(ANOMALY_KINDS)):
        readings.append(_normal_reading(rng))
        labels.append(False)
        kinds.append("")

    return Labelled(readings, labels, kinds)


def history_window(rows: int, seed: int = 41) -> List[dict]:
    """A clean context window, for paths that need history to forecast from."""
    rng = np.random.default_rng(seed)
    return [_normal_reading(rng) for _ in range(rows)]


# ---------------------------------------------------------------------------
# Stationary conditions and frozen instruments
# ---------------------------------------------------------------------------
#
# A flat reading trace has two very different causes, and telling them apart is
# the whole difficulty of flatline detection:
#
#   * the environment genuinely is not changing, and the instrument's finite
#     resolution rounds what little movement there is onto one value; or
#   * the instrument has stopped measuring.
#
# The first is ordinary. A DHT22 reports temperature in 0.1 degree steps, so a
# still room at 21.04 degrees emits "21.0" for as long as it stays there. A
# barometer reporting whole hectopascals does the same through settled weather.
# Any detector that equates "repeated value" with "broken" will accuse those
# instruments, and it will do it most often on exactly the calm nights when
# nothing is wrong.
#
# So these generators model resolution explicitly: a continuous true signal,
# quantised the way a real ADC quantises it.

# Reporting resolution of instruments this platform actually uses, and the
# short-term standard deviation of the underlying quantity in calm conditions.
SENSOR_RESOLUTION = {
    "temperature": 0.1,     # DHT22 / SHT31 style
    "humidity": 0.1,
    "airQuality": 1.0,      # integer index
    "oxygen": 0.1,
    "pressure": 1.0,        # whole hPa
}


# How strongly the simulated quantity is pulled back to its baseline each
# reading. Low enough that short windows still look like a random walk,
# high enough that the process stays inside the instrument envelope.
_REVERSION = 0.02


def _quantise(value: float, step: float) -> float:
    return round(round(value / step) * step, 6)


def stationary_stream(rows: int, calm: float = 0.02, drift_per_reading: float = 0.0,
                      seed: int = 101) -> List[dict]:
    """A working instrument in genuinely settled conditions.

    `calm` is the standard deviation of the true quantity between readings, as
    a fraction of each feature's baseline spread; `drift_per_reading` adds a
    slow trend. Both are then quantised to the instrument's resolution, which is
    what produces long runs of identical values without anything being wrong.
    """
    rng = np.random.default_rng(seed)
    true_values = {name: mean for name, (mean, _) in BASELINE.items()}
    out: List[dict] = []
    for _ in range(rows):
        reading = {}
        for name, (mean, spread) in BASELINE.items():
            # Mean-reverting (Ornstein-Uhlenbeck), not a random walk. A plain
            # walk is not stationary: with calm=0.25 it wanders roughly 19
            # degrees over 4,000 readings and leaves the rule engine's physical
            # bounds entirely. Measuring flatline false alarms on that data
            # counted rule breaches caused by the generator instead.
            centre = mean + drift_per_reading * spread * len(out)
            pull = _REVERSION * (centre - true_values[name])
            true_values[name] += pull + rng.normal(0.0, calm * spread)
            reading[name] = _quantise(true_values[name], SENSOR_RESOLUTION[name])
        out.append(reading)
    return out


def frozen_stream(rows: int, features: Optional[Sequence[str]] = None,
                  seed: int = 103) -> List[dict]:
    """An instrument that has stopped measuring.

    With `features` given, only those channels freeze and the rest keep working
    — a failed sensing element rather than a hung board, and the harder case.
    """
    working = stationary_stream(rows, calm=0.25, seed=seed)
    frozen_at = dict(working[0])
    targets = list(features) if features is not None else FEATURES
    return [{name: (frozen_at[name] if name in targets else row[name])
             for name in FEATURES}
            for row in working]


def freezes_partway(rows: int, freeze_after: int, seed: int = 107) -> List[dict]:
    """Works normally, then stops. The transition a detector should catch."""
    working = stationary_stream(rows, calm=0.25, seed=seed)
    frozen_at = dict(working[freeze_after])
    return working[:freeze_after] + [dict(frozen_at) for _ in range(rows - freeze_after)]
