"""Reuse of a multi-step forecast across the readings it covers.

A Chronos forecast for horizon H already predicts the next H readings. Asking
the model again for each of those readings recomputes something it has already
said — and a transformer forward pass is the most expensive thing this service
does, by orders of magnitude over the arithmetic that consumes it.

So: forecast once per interval, then score each arriving reading against the
step of that forecast which corresponds to it. Past the horizon the forecast is
discarded rather than stretched, because a forecast says nothing about readings
beyond the window it covered, and holding its last value would invent a
baseline instead of measuring against one.

Both workers share this, so the cost model is the same on the durable path and
the live-stream path.
"""

from __future__ import annotations

import threading
from collections import OrderedDict
from typing import Dict, List, Optional

import config


def forecast_step(series: Dict[str, List[float]], step: int) -> Dict[str, List[float]]:
    """The forecast for `step` readings after it was made, as a one-step series.

    Empty once `step` runs past the horizon.
    """
    if not series or step < 0:
        return {}
    out: Dict[str, List[float]] = {}
    for feature, values in series.items():
        if values and step < len(values):
            out[feature] = [values[step]]
    return out


class ForecastCache:
    """Per-device forecast with an age counter, safe for concurrent readers.

    Bounded, and least-recently-used first. Device identifiers arrive from the
    fleet rather than from a fixed list, so an unbounded table here grows with
    the number of distinct identifiers ever seen — including mistyped and
    retired ones — and never shrinks. Its siblings (the stream worker's device
    table and the flatline detector's baselines) are both bounded; this one was
    not, and held every identifier it was ever given.
    """

    def __init__(self, refresh_every: int, max_devices: Optional[int] = None):
        self.refresh_every = max(1, refresh_every)
        self.max_devices = max(
            1, int(max_devices if max_devices is not None
                   else getattr(config, "FORECAST_CACHE_MAX_DEVICES", 500)))
        self._series: "OrderedDict[str, Dict[str, List[float]]]" = OrderedDict()
        self._age: Dict[str, int] = {}
        self._lock = threading.Lock()

    def _evict_if_needed(self, device_uid: str) -> None:
        """Caller must hold the lock."""
        if device_uid in self._series or len(self._series) < self.max_devices:
            return
        oldest, _ = self._series.popitem(last=False)
        self._age.pop(oldest, None)

    def expected_for(self, device_uid: str) -> Dict[str, List[float]]:
        """The forecast step matching this device's next reading."""
        with self._lock:
            series = self._series.get(device_uid)
            age = self._age.get(device_uid, 0)
        return forecast_step(series or {}, age)

    def age(self, device_uid: str) -> int:
        with self._lock:
            return self._age.get(device_uid, 0)

    def record_reading(self, device_uid: str) -> int:
        """Advances the cache age. Returns the new age.

        A device with no stored forecast is ignored rather than given an age
        entry. An age is only meaningful as an offset into a stored horizon, so
        an entry without one is dead weight — and this is called once per
        reading, which made the age table a second unbounded map alongside the
        series it indexes into.
        """
        with self._lock:
            if device_uid not in self._series:
                return 0
            age = self._age.get(device_uid, 0) + 1
            self._age[device_uid] = age
            self._series.move_to_end(device_uid)
            return age

    def due_for_refresh(self, device_uid: str) -> bool:
        with self._lock:
            if device_uid not in self._series:
                return True
            return self._age.get(device_uid, 0) >= self.refresh_every

    def store(self, device_uid: str, series: Dict[str, List[float]]) -> None:
        with self._lock:
            self._evict_if_needed(device_uid)
            self._series[device_uid] = series or {}
            self._series.move_to_end(device_uid)
            self._age[device_uid] = 0

    def tracked_devices(self) -> int:
        with self._lock:
            return len(self._series)

    def forget(self, device_uid: str) -> None:
        with self._lock:
            self._series.pop(device_uid, None)
            self._age.pop(device_uid, None)
