"""Evaluates the detector against real station telemetry.

Everything else in this directory runs on seeded synthetic streams. That
establishes one thing — that the detector behaves consistently and separates
the cases it is shown — and it is routinely mistaken for a second thing it does
not establish at all: that those streams resemble a real station.

Those are different questions and should not be reported as one number:

    stability    "does it behave the same across seeds?"        measured: +/-3.4%
    validity     "does our reference data resemble deployment?"  needs field data

This harness answers the second. It takes telemetry a real fleet produced and
reports the same measures the synthetic suite reports, broken down by channel
and by operating regime, so a detector tuned on modelled quantisation can be
checked against observed quantisation.

## Input

A CSV or JSON-lines file with a device column, a timestamp column, and one
column per channel:

    deviceUid,recordedAt,temperature,humidity,airQuality,oxygen,pressure
    esp32-01,2026-03-01T00:00:00Z,21.3,55.2,44,20.9,1013

Optionally a second file marking known faults, used to measure detection delay:

    deviceUid,channel,startedAt
    esp32-07,temperature,2026-03-04T09:15:00Z

Without it the harness reports flag *rates* and lists the episodes it raised,
for someone who knows the fleet to adjudicate. It does not assume unlabelled
data is healthy — an unlabelled flag is a candidate, not a false positive, and
it is reported as such.

## Usage

    python -m benchmarks.field_evaluation telemetry.csv
    python -m benchmarks.field_evaluation telemetry.csv --faults faults.csv
    python -m benchmarks.field_evaluation telemetry.csv --json report.json
"""

from __future__ import annotations

import argparse
import csv
import json
import logging
import sys
from collections import defaultdict
from datetime import datetime
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import config  # noqa: E402

from anomaly_scorer import anomaly_scorer  # noqa: E402
from chronos_forecaster import ForecastResult  # noqa: E402
from flatline_detector import FlatlineDetector  # noqa: E402
from isolation_forest_model import IsolationForestResult  # noqa: E402
from rule_engine import rule_engine  # noqa: E402

FEATURES = ["temperature", "humidity", "airQuality", "oxygen", "pressure"]

# Scored against a silent forest and a forecast reporting perfect agreement, so
# what is measured is what the flatline detector contributes on its own. That is
# the situation it exists for: a flat series predicts itself exactly, so nothing
# else in the stack will corroborate it.
SILENT_FOREST = IsolationForestResult(False, 0.0, [], available=True)
PERFECT_FORECAST = ForecastResult({}, {}, {"temperature": 0.0}, [], available=True)


# ---------------------------------------------------------------------------
# Loading
# ---------------------------------------------------------------------------

def _parse_time(raw: str) -> Optional[datetime]:
    if not raw:
        return None
    try:
        return datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError:
        return None


def load_readings(path: Path) -> List[dict]:
    """Rows as dicts, in file order. JSON lines or CSV, detected by extension."""
    rows: List[dict] = []
    if path.suffix.lower() in {".jsonl", ".ndjson", ".json"}:
        with open(path, "r", encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if line:
                    rows.append(json.loads(line))
    else:
        with open(path, "r", encoding="utf-8", newline="") as handle:
            rows.extend(csv.DictReader(handle))

    cleaned: List[dict] = []
    for row in rows:
        device = row.get("deviceUid") or row.get("device_uid") or row.get("device")
        if not device:
            continue
        reading = {"deviceUid": str(device),
                   "recordedAt": _parse_time(str(row.get("recordedAt")
                                                 or row.get("timestamp") or ""))}
        for name in FEATURES:
            raw = row.get(name)
            if raw in (None, ""):
                continue
            try:
                reading[name] = float(raw)
            except (TypeError, ValueError):
                continue
        if any(name in reading for name in FEATURES):
            cleaned.append(reading)
    return cleaned


def load_faults(path: Path) -> Dict[Tuple[str, str], datetime]:
    faults: Dict[Tuple[str, str], datetime] = {}
    with open(path, "r", encoding="utf-8", newline="") as handle:
        for row in csv.DictReader(handle):
            started = _parse_time(str(row.get("startedAt") or row.get("started_at") or ""))
            device = row.get("deviceUid") or row.get("device_uid")
            channel = row.get("channel") or row.get("sensor")
            if device and channel and started:
                faults[(str(device), str(channel))] = started
    return faults


# ---------------------------------------------------------------------------
# Observed instrument behaviour
# ---------------------------------------------------------------------------

def observed_resolution(values: Iterable[float]) -> Optional[float]:
    """Smallest non-zero step the instrument was seen to take.

    This is the assumption the synthetic suite hard-codes. Reading it back off
    real data is the single most useful thing in this report: if a channel's
    observed step differs from the modelled one, every run-length number tuned
    against the model is suspect for that channel.
    """
    array = np.asarray(list(values), dtype=float)
    array = array[np.isfinite(array)]
    if array.size < 2:
        return None
    steps = np.abs(np.diff(array))
    steps = steps[steps > 1e-12]
    if steps.size == 0:
        return None
    return float(np.percentile(steps, 1))


def run_lengths(values: Iterable[float]) -> List[int]:
    runs: List[int] = []
    current = 0
    previous = None
    for value in values:
        if previous is not None and abs(value - previous) <= config.FLATLINE_EPSILON:
            current += 1
        else:
            if current:
                runs.append(current)
            current = 1
            previous = value
        previous = value
    if current:
        runs.append(current)
    return runs


def device_regime(windows: Dict[str, List[float]]) -> str:
    """The device's operating regime, from all channels rather than one.

    Taken as the median of each channel's relative spread, so one quantised
    channel sitting still does not make an otherwise busy station look quiet.
    """
    relatives = []
    for values in windows.values():
        array = np.asarray(values, dtype=float)
        array = array[np.isfinite(array)]
        if array.size < 2:
            continue
        centre = abs(float(np.mean(array))) or 1.0
        relatives.append(float(np.std(array)) / centre)
    if not relatives:
        return "unknown"
    return _band(float(np.median(relatives)))


def _band(relative: float) -> str:
    if relative < 1e-4:
        return "static"
    if relative < 1e-3:
        return "quiet"
    if relative < 1e-2:
        return "typical"
    return "active"


def regime_of(values: List[float]) -> str:
    """Coarse operating regime for a window, by relative variability.

    Reported separately because a false-positive rate averaged over a whole
    deployment hides the thing that matters: this detector's failure mode is
    concentrated in quiet conditions, which is exactly where an average buries
    it.
    """
    array = np.asarray(values, dtype=float)
    array = array[np.isfinite(array)]
    if array.size < 2:
        return "unknown"
    centre = abs(float(np.mean(array))) or 1.0
    return _band(float(np.std(array)) / centre)


# ---------------------------------------------------------------------------
# Evaluation
# ---------------------------------------------------------------------------

class Episode:
    __slots__ = ("device", "channel", "started_at", "run_length", "alarm_at")

    def __init__(self, device, channel, started_at, run_length, alarm_at):
        self.device = device
        self.channel = channel
        self.started_at = started_at
        self.run_length = run_length
        self.alarm_at = alarm_at


def evaluate(readings: List[dict], faults: Dict[Tuple[str, str], datetime]) -> dict:
    detector = FlatlineDetector()
    by_device: Dict[str, List[dict]] = defaultdict(list)
    for reading in readings:
        by_device[reading["deviceUid"]].append(reading)

    # Devices with a known fault are kept apart from the rest. Mixing them
    # makes any "false positive rate" meaningless: one sustained freeze
    # contributes hundreds of correctly flagged readings, which is not the same
    # thing as hundreds of false alarms.
    faulted_devices = {device for device, _ in faults}

    episodes: List[Episode] = []
    flagged_by_regime: Dict[str, List[int]] = defaultdict(list)
    flagged_by_regime_faulted: Dict[str, List[int]] = defaultdict(list)
    scores: List[float] = []
    open_episode: Dict[Tuple[str, str], Episode] = {}

    window: Dict[str, List[float]] = defaultdict(list)

    for device, rows in by_device.items():
        # Cleared per device: one station's trailing window must not leak into
        # the next station's regime classification.
        window.clear()
        for index, reading in enumerate(rows):
            measurement = {name: reading[name] for name in FEATURES if name in reading}
            result = detector.observe(device, measurement)

            analysis = anomaly_scorer.combine(
                rule_engine.evaluate(measurement), SILENT_FOREST, PERFECT_FORECAST,
                measurement, result)
            scores.append(analysis.score)

            # Regime is judged across every channel, never on the one the
            # detector happened to flag. Using result.sensor makes the
            # breakdown circular: a frozen channel is static by definition, so
            # every true positive lands in the quietest bucket and the table
            # stops saying anything about false positives.
            for name, value in measurement.items():
                series = window[name]
                series.append(value)
                if len(series) > 48:
                    series.pop(0)
            regime = device_regime(window)
            bucket = (flagged_by_regime_faulted if device in faulted_devices
                      else flagged_by_regime)
            bucket[regime].append(1 if analysis.is_anomaly else 0)

            key = (device, result.sensor or "")
            if result.is_anomaly and result.sensor:
                if key not in open_episode:
                    episode = Episode(device, result.sensor, reading.get("recordedAt")
                                      or index, result.run_length, 0)
                    open_episode[key] = episode
                    episodes.append(episode)
            elif key in open_episode:
                del open_episode[key]

    return {
        "devices": len(by_device),
        "readings": len(readings),
        "episodes": episodes,
        "flagged_by_regime": dict(flagged_by_regime),
        "flagged_by_regime_faulted": dict(flagged_by_regime_faulted),
        "faulted_devices": faulted_devices,
        "scores": scores,
        "faults": faults,
        "detector": detector,
        "by_device": by_device,
    }


# ---------------------------------------------------------------------------
# Reporting
# ---------------------------------------------------------------------------

def report(result: dict) -> dict:
    summary: dict = {}
    by_device = result["by_device"]

    print("\n" + "=" * 78)
    print("1. DATASET")
    print("=" * 78)
    print(f"  devices {result['devices']}    readings {result['readings']:,}")

    print("\n" + "=" * 78)
    print("2. OBSERVED INSTRUMENT BEHAVIOUR  (does the modelled assumption hold?)")
    print("=" * 78)
    print(f"  {'channel':<14} {'step':>10} {'modelled':>10} {'median run':>12} {'longest run':>12}")

    modelled = {"temperature": 0.1, "humidity": 0.1, "airQuality": 1.0,
                "oxygen": 0.1, "pressure": 1.0}
    channel_stats = {}
    for name in FEATURES:
        values: List[float] = []
        runs: List[int] = []
        for rows in by_device.values():
            series = [row[name] for row in rows if name in row]
            if len(series) < 2:
                continue
            values.extend(series)
            runs.extend(run_lengths(series))
        if not values:
            print(f"  {name:<14} {'absent':>10}")
            continue
        step = observed_resolution(values)
        median_run = float(np.median(runs)) if runs else 0.0
        longest = max(runs) if runs else 0
        channel_stats[name] = {"step": step, "modelled_step": modelled[name],
                               "median_run": median_run, "longest_run": longest}
        print(f"  {name:<14} {step if step else float('nan'):>10.4g} "
              f"{modelled[name]:>10.4g} {median_run:>12.1f} {longest:>12}")
    summary["channels"] = channel_stats

    print("\n  A step materially different from the modelled one invalidates the")
    print("  run-length tuning for that channel: the alarm multiplier was chosen")
    print("  against the modelled quantisation, not this one.")

    print("\n" + "=" * 78)
    print("3. FLAG RATE BY OPERATING REGIME")
    print("=" * 78)
    faulted = result.get("faulted_devices") or set()
    label = "devices with no known fault" if faulted else "all devices"
    print(f"  {label}")
    print(f"  {'regime':<14} {'readings':>10} {'flagged':>10}")
    regimes = {}
    for regime in ("static", "quiet", "typical", "active", "unknown"):
        flags = result["flagged_by_regime"].get(regime)
        if not flags:
            continue
        rate = sum(flags) / len(flags)
        regimes[regime] = {"readings": len(flags), "flag_rate": rate}
        print(f"  {regime:<14} {len(flags):>10,} {rate:>9.2%}")
    summary["regimes"] = regimes

    if faulted:
        faulted_regimes = {}
        print(f"\n  devices with a known fault ({len(faulted)})")
        print(f"  {'regime':<14} {'readings':>10} {'flagged':>10}")
        for regime in ("static", "quiet", "typical", "active", "unknown"):
            flags = result["flagged_by_regime_faulted"].get(regime)
            if not flags:
                continue
            rate = sum(flags) / len(flags)
            faulted_regimes[regime] = {"readings": len(flags), "flag_rate": rate}
            print(f"  {regime:<14} {len(flags):>10,} {rate:>9.2%}")
        summary["regimes_faulted"] = faulted_regimes

    print("\n  Split because one sustained freeze contributes hundreds of")
    print("  correctly flagged readings, which is not hundreds of false alarms.")
    print("  Only the first table is a false-positive rate, and only as far as")
    print("  the fault list is complete.")
    print("\n  Broken out by regime because this detector's failure mode is")
    print("  concentrated in quiet conditions, where an average would bury it.")

    print("\n" + "=" * 78)
    print("4. EPISODES RAISED")
    print("=" * 78)
    episodes = result["episodes"]
    faults = result["faults"]
    if not episodes:
        print("  none")
    else:
        by_channel: Dict[str, int] = defaultdict(int)
        for episode in episodes:
            by_channel[episode.channel] += 1
        for channel, count in sorted(by_channel.items(), key=lambda kv: -kv[1]):
            print(f"  {channel:<14} {count:>5} episode(s)")

    if faults:
        print("\n  Detection delay against the supplied fault list:")
        matched = 0
        for (device, channel), started in sorted(faults.items()):
            hit = next((e for e in episodes
                        if e.device == device and e.channel == channel
                        and isinstance(e.started_at, datetime)
                        and e.started_at >= started), None)
            if hit is None:
                print(f"    {device}/{channel:<12} not detected")
                continue
            matched += 1
            delay = hit.started_at - started
            print(f"    {device}/{channel:<12} +{delay}")
        print(f"    detected {matched}/{len(faults)}")
        summary["faults_detected"] = matched
        summary["faults_total"] = len(faults)
    else:
        print("\n  No fault list supplied, so these are candidates, not false")
        print("  positives. Unlabelled data is not evidence of health; someone who")
        print("  knows the fleet has to adjudicate these before the flag rate above")
        print("  can be read as a false-positive rate.")

    summary["episodes"] = len(episodes)
    summary["devices"] = result["devices"]
    summary["readings"] = result["readings"]

    scores = np.asarray(result["scores"], dtype=float)
    if scores.size:
        print("\n" + "=" * 78)
        print("5. SCORE CALIBRATION")
        print("=" * 78)
        percentiles = np.percentile(scores, [50, 90, 99, 99.9])
        print(f"  p50 {percentiles[0]:.3f}   p90 {percentiles[1]:.3f}   "
              f"p99 {percentiles[2]:.3f}   p99.9 {percentiles[3]:.3f}")
        print(f"  above threshold ({config.ANOMALY_THRESHOLD}): "
              f"{float((scores >= config.ANOMALY_THRESHOLD).mean()):.2%}")
        summary["score_percentiles"] = [float(v) for v in percentiles]

    return summary


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("telemetry", type=Path, help="CSV or JSON-lines readings")
    parser.add_argument("--faults", type=Path, default=None,
                        help="CSV of known faults, for detection delay")
    parser.add_argument("--json", type=Path, default=None,
                        help="write a machine-readable summary here")
    args = parser.parse_args()

    readings = load_readings(args.telemetry)
    if not readings:
        print("No usable readings found.")
        return
    faults = load_faults(args.faults) if args.faults else {}

    print("Field evaluation — real telemetry, not synthetic streams")
    print(f"source: {args.telemetry}")
    print(f"alarm = {config.FLATLINE_RUN_MULTIPLIER}x each channel's longest completed run, "
          f"minimum {config.FLATLINE_MIN_RUN}")

    summary = report(evaluate(readings, faults))

    if args.json:
        args.json.write_text(json.dumps(summary, indent=2, default=str), encoding="utf-8")
        print(f"\n  summary written to {args.json}")
    print()


if __name__ == "__main__":
    logging.disable(logging.WARNING)
    main()
