"""Detects an instrument that has stopped measuring.

A frozen sensor is the failure mode none of the other detectors can see, and
measuring that was the point of building a labelled benchmark: rules, Isolation
Forest and forecast residual all scored 0% recall on it. Each is blind for a
structural reason:

  * **Rules** check a value against the instrument's physical envelope. A stuck
    reading is an ordinary value; it is wrong only because it never changes.
  * **The Isolation Forest** scores one reading at a time. A repeated normal
    reading sits in the densest part of the training distribution — the least
    anomalous thing the forest can be shown.
  * **The forecast residual** compares a reading against what the recent series
    predicted. A flat series predicts itself perfectly, so the residual goes to
    zero. Measured against real Chronos, a frozen sensor produces a residual of
    exactly 0.00 sigma: it does not merely evade detection, it reports maximum
    agreement precisely when the instrument has died.

## Why this is not "the same value N times in a row"

That detector was built first, and measured against realistic data it was
unusable. It flagged a working instrument in very calm conditions **100%** of
the time and a calm night 85% of the time — it would have fired continuously on
exactly the quiet nights when nothing was wrong.

The cause is quantisation. Instruments report in steps — 0.1 degrees, whole
hectopascals — so a settled environment produces long runs of bit-identical
values with nothing broken. Over 4,000 readings from a *healthy* station in
calm conditions, the longest identical run per channel measured:

    temperature 33    humidity 11    airQuality 82    pressure 405    oxygen 4000

Oxygen is the difficulty in one number: it never changed at all. Its reporting
step is larger than how much ambient oxygen actually moves, so a working sensor
holds one value for as long as conditions hold. A fixed threshold is therefore
either above that, and blind to real freezes on every other channel, or below
it, and permanently accusing a healthy oxygen sensor.

Cross-channel comparison fails for the same reason: "this channel is flat while
the others move" describes a frozen thermometer and a healthy oxygen sensor
equally well.

## What actually separates them

A frozen channel's run never ends; a quantised-stable channel's runs are
bounded by the physics of the site. So this learns, per device and per channel,
how long that channel's runs normally get, and reports a break in the channel's
own established behaviour rather than against a global constant.

That makes it stateful, which is why it takes a device identifier rather than
just a window: the evidence needed is longer than any window the forecaster
retains.

## What it still cannot do

If a channel is frozen before this process ever sees it change, it is not
detected at all. There is no baseline to break, and "frozen since startup" is
genuinely indistinguishable from "extremely stable since startup" on the
readings alone.

An absolute run limit was tried for exactly that case and removed, because it
was wrong in the ordinary direction: measured over 4,000 readings, a healthy
oxygen channel in calm conditions never changed once. Its reporting step is
larger than the quantity's real movement, so the limit flagged a working sensor
every time. Inventing a timeout where there is no evidence produces false
certainty, not coverage.

A remnant of that limit survived the removal as a *cap* on the learned alarm
point, which reintroduced the same false positive more quietly: it bit only
channels whose learned threshold had grown past the cap, and those are the
healthy quantised ones. A channel with a natural run of 1,400 readings was
measured being accused after holding steady for 3,000. It is now off by
default (FLATLINE_ABSOLUTE_RUN=0).

Closing this properly needs information the readings do not carry — the
instrument's declared resolution, or a heartbeat independent of the
measurement. It is reported by benchmarks/flatline_validation.py rather than
hidden, so it stays a known limit instead of becoming a surprise.

## What has and has not been established

The numbers quoted here come from seeded synthetic streams, and they answer one
question only: whether the detector behaves consistently and separates the
cases it is shown. They do not establish that those streams resemble a real
station. Quantisation steps, reversion rates and calm periods here are modelled,
not observed, and the alarm multiplier was chosen against them.

So this is calibrated, not validated. Field data is what would turn the second
into a claim; see benchmarks/field_evaluation.py, which takes real telemetry and
reports the same measures per sensor and per operating regime.
"""

from __future__ import annotations

import math
import threading
from collections import OrderedDict
from typing import Dict, List, Optional

import config

FEATURES = ["temperature", "humidity", "airQuality", "oxygen", "pressure"]


class FlatlineResult:
    __slots__ = ("is_anomaly", "score", "reasons", "available", "sensor", "run_length")

    def __init__(self, is_anomaly: bool, score: float, reasons: List[str],
                 available: bool, sensor: Optional[str] = None, run_length: int = 0):
        self.is_anomaly = is_anomaly
        self.score = score
        self.reasons = reasons
        # False while no channel has yet shown enough normal behaviour to be
        # judged against, so the scorer treats it as "no evidence" rather than
        # "nothing wrong".
        self.available = available
        self.sensor = sensor
        self.run_length = run_length


class _ChannelState:
    """One device, one feature: the current run and what normal looks like."""

    __slots__ = ("value", "run", "longest_normal_run", "runs_seen")

    def __init__(self):
        self.value: Optional[float] = None
        self.run = 0
        # The longest run this channel has *completed* — started and then ended
        # by actually changing. A run still in progress is deliberately
        # excluded: a frozen channel's current run is always the longest it has
        # ever had, so including it would let the channel raise its own alarm
        # threshold indefinitely and never trip.
        self.longest_normal_run = 0
        self.runs_seen = 0

    def observe(self, value: float) -> None:
        if self.value is not None and abs(value - self.value) <= config.FLATLINE_EPSILON:
            self.run += 1
            return
        if self.value is not None:
            self.longest_normal_run = max(self.longest_normal_run, self.run)
            self.runs_seen += 1
        self.value = value
        self.run = 1

    def alarm_at(self) -> int:
        """The run length at which this channel is considered frozen."""
        learned = config.FLATLINE_RUN_MULTIPLIER * self.longest_normal_run
        return int(max(config.FLATLINE_MIN_RUN, learned))


class FlatlineDetector:

    def __init__(self):
        # Ordered by how recently each device reported, so the table sheds the
        # device that stopped talking rather than the one that has been talking
        # longest. See _evict_if_needed.
        self._devices: "OrderedDict[str, Dict[str, _ChannelState]]" = OrderedDict()
        self._lock = threading.Lock()

    def observe(self, device_uid: str, reading: Dict[str, float]) -> FlatlineResult:
        """Records a reading and reports whether any channel has frozen."""
        with self._lock:
            channels = self._devices.get(device_uid)
            if channels is None:
                self._evict_if_needed()
                channels = {name: _ChannelState() for name in FEATURES}
                self._devices[device_uid] = channels
            else:
                self._devices.move_to_end(device_uid)

            worst_sensor = None
            worst_run = 0
            worst_alarm = 1
            worst_ratio = 1.0
            warmed = False

            for name in FEATURES:
                raw = reading.get(name)
                if raw is None:
                    continue
                try:
                    value = float(raw)
                except (TypeError, ValueError):
                    continue
                if not math.isfinite(value):
                    continue

                state = channels[name]
                state.observe(value)

                # Only a channel that has demonstrated it *can* change is judged.
                #
                # An earlier version also alarmed on any channel exceeding an
                # absolute run, to cover a sensor frozen before startup. That
                # accused working instruments: where a quantity's natural
                # variation is smaller than the instrument's reporting step, the
                # channel legitimately never changes. Ambient oxygen is the
                # standard case — it sits at 20.9% and a 0.1% sensor reports
                # exactly that indefinitely — and the backstop flagged it after
                # two days, every time. A channel that has never been seen to
                # move has no established behaviour to break, so there is
                # nothing here to detect.
                if state.runs_seen < config.FLATLINE_WARMUP_RUNS:
                    continue
                warmed = True

                # No absolute cap on the learned threshold unless an operator
                # sets one. A cap looks like a safety net and is actually the
                # removed backstop wearing a different hat: it re-imposes a
                # fixed run limit on exactly the channels whose normal runs are
                # longest, which are the healthy quantised ones. Measured with
                # the old default of 3000, a channel with a natural run of 1400
                # — oxygen's observed order of magnitude — was accused after
                # holding steady for 3000 readings with nothing wrong.
                #
                # The cost of removing it is detection *latency* on such a
                # channel, not detection failure: the learned alarm point is
                # still finite. Trading a slower true positive for no false
                # positive is the right way round for a detector whose job is
                # to be trusted on a quiet night.
                alarm = state.alarm_at()
                if config.FLATLINE_ABSOLUTE_RUN > 0:
                    alarm = min(alarm, config.FLATLINE_ABSOLUTE_RUN)
                if state.run <= alarm:
                    continue
                # Ranked by how far past its own threshold each channel is, so
                # the reported sensor is the most clearly frozen one rather than
                # whichever simply has the longest run.
                ratio = state.run / max(alarm, 1)
                if ratio > worst_ratio:
                    worst_sensor, worst_run, worst_alarm, worst_ratio = \
                        name, state.run, alarm, ratio

        if worst_sensor is None:
            return FlatlineResult(False, 0.0, [], available=warmed)

        # Crossing the alarm point is itself the finding: that point is already
        # four times the longest run this channel has ever completed, learned
        # from its own behaviour. So the score starts high and ramps to 1 by
        # twice the alarm, rather than starting low and delaying the verdict.
        #
        # The floor is not arbitrary. This detector has to be able to raise an
        # anomaly alone — a frozen sensor drives the residual detector to
        # perfect agreement, so nothing else in the stack will corroborate it —
        # and under the noisy-OR that needs score * W_FLATLINE >= the threshold.
        # A floor below that would leave the detector flagging internally while
        # the service stayed silent, which is what it did on first measurement.
        floor = min(0.99, config.ANOMALY_THRESHOLD / max(config.W_FLATLINE, 1e-6) * 1.02)
        overshoot = (worst_run - worst_alarm) / max(worst_alarm, 1)
        score = max(floor, min(1.0, floor + (1.0 - floor) * overshoot))
        return FlatlineResult(
            is_anomaly=True,
            score=score,
            reasons=[
                f"{worst_sensor} has reported an identical value for {worst_run} consecutive "
                f"readings, against {worst_alarm} for this channel's own normal behaviour; "
                "the sensor is most likely frozen rather than the environment stable."
            ],
            available=True,
            sensor=worst_sensor,
            run_length=worst_run,
        )

    def forget(self, device_uid: str) -> None:
        with self._lock:
            self._devices.pop(device_uid, None)

    def tracked_devices(self) -> int:
        with self._lock:
            return len(self._devices)

    def _evict_if_needed(self) -> None:
        """Drops the device that has gone quiet longest.

        This was insertion-ordered, which evicted the device that had been
        reporting *longest* rather than the one that had stopped. That is the
        worst possible choice here, because this detector's state is earned:
        a channel needs FLATLINE_WARMUP_RUNS completed runs before it can be
        judged at all, so discarding an established station blinds the detector
        for that station until it warms up again. Measured before the fix, a
        station reporting continuously since startup was evicted by the arrival
        of a single new device, and FLATLINE_MAX_DEVICES junk identifiers wiped
        every learned baseline in the table.
        """
        # Clamped: a table of zero devices cannot hold the one being added, and
        # the unclamped version raised KeyError on every reading because it went
        # on to evict from an empty table. A misconfigured bound should cost
        # memory headroom, not take the detector down.
        limit = max(1, config.FLATLINE_MAX_DEVICES)
        if len(self._devices) < limit:
            return
        while len(self._devices) >= limit and self._devices:
            self._devices.popitem(last=False)


flatline_detector = FlatlineDetector()
