"""Forecast receipts: every forecast scored against the reading it predicted.

The stream worker forecasts each station's next few readings, and for a long
time those forecasts were used and forgotten: nothing ever checked whether they
came true. A forecaster that is never scored can be worse than doing nothing
and nobody would know. This module keeps the receipts.

How a forecast is scored
------------------------
A forecast is issued from the readings available at that moment. When the k-th
reading after it arrives, the forecast's step k is compared with what actually
arrived, and so are three simple forecasters that need no model at all:

  persistence  the last value known when the forecast was issued
  trend        a least-squares line through the last few readings at issue,
               extended k readings ahead
  daily        the reading in the same time-of-day slot roughly a day earlier
               (between DAILY_MIN_HOURS and DAILY_MAX_HOURS ago)

Each baseline only uses what was knowable before the reading it is scored on --
the same information the model had, or older. Each is compared with the model
only on the receipts where that baseline had a value (a paired comparison), so
"daily" is never charged for its first day, when it has nothing to say.

The headline skill score is against persistence:

    skill = 1 - MAE(model) / MAE(persistence)

0 for a forecaster no better than repeating the last value, negative for one
that is worse. `latestValue` (the reading immediately before the scored one)
is kept as a harder operational reference, never in place of persistence.

Regime is decided when the forecast is issued, from the last change the station
showed before it -- never from the outcome being scored, which would sort the
evidence by the answer.

What the numbers do and do not establish
----------------------------------------
The confidence interval on the mean error difference treats scored readings as
independent. Consecutive readings of one station are not, so the interval is
optimistic; benchmarks/forecast_accountability.py re-runs the comparison with a
Diebold-Mariano test and a Newey-West variance. And on simulated telemetry these
numbers measure the forecaster against the simulator, not against the
atmosphere.
"""

from __future__ import annotations

import csv
import io
import math
import threading
from collections import deque
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Deque, Dict, Iterable, List, Optional, Sequence, Tuple

import config

METRICS: Tuple[str, ...] = ("temperature", "humidity", "airQuality", "pressure", "oxygen")
BASELINES: Tuple[str, ...] = ("persistence", "trend", "daily")

#: The smallest last step that counts as "changing", per metric, in its own unit.
REGIME_STEP: Dict[str, float] = {
    "temperature": 0.2,
    "humidity": 1.0,
    "airQuality": 3.0,
    "pressure": 0.3,
    "oxygen": 0.05,
}

MIN_SCORED = int(getattr(config, "AI_LEDGER_MIN_SCORED", 30))
MAX_RECEIPTS = int(getattr(config, "AI_LEDGER_MAX_RECEIPTS", 20_000))
TREND_POINTS = max(2, int(getattr(config, "AI_LEDGER_TREND_POINTS", 8)))
DAILY_BUCKET_MINUTES = max(1, int(getattr(config, "AI_LEDGER_DAILY_BUCKET_MINUTES", 5)))
DAILY_MIN_HOURS = float(getattr(config, "AI_LEDGER_DAILY_MIN_HOURS", 20))
DAILY_MAX_HOURS = float(getattr(config, "AI_LEDGER_DAILY_MAX_HOURS", 28))

CSV_FIELDS = ["recordedAt", "issuedAt", "deviceUid", "method", "metric", "step", "regime",
              "actual", "forecast", "persistenceAtIssue", "latestValue", "trendAtIssue", "dailyGuess"]


def regime_of(metric: str, previous: Optional[float], before_previous: Optional[float]) -> str:
    """'calm', 'changing', or 'unknown' when there was no earlier step to judge by."""
    if previous is None or before_previous is None:
        return "unknown"
    return "changing" if abs(previous - before_previous) >= REGIME_STEP.get(metric, math.inf) else "calm"


def trend_slope(values: Sequence[float]) -> Optional[float]:
    """Least-squares slope per reading through the last TREND_POINTS values, or None."""
    ys = [v for v in values if v is not None and math.isfinite(v)][-TREND_POINTS:]
    n = len(ys)
    if n < 2:
        return None
    mean_x = (n - 1) / 2.0
    mean_y = sum(ys) / n
    sxx = sum((i - mean_x) ** 2 for i in range(n))
    sxy = sum((i - mean_x) * (y - mean_y) for i, y in enumerate(ys))
    return sxy / sxx if sxx > 0 else None


def _finite(value) -> Optional[float]:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


def _parse_time(value: str) -> Optional[datetime]:
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def _bucket(moment: datetime) -> int:
    utc = moment.astimezone(timezone.utc)
    return (utc.hour * 60 + utc.minute) // DAILY_BUCKET_MINUTES


@dataclass
class _Pair:
    """Running comparison of the model against one baseline, on paired receipts."""
    n: int = 0
    abs_model: float = 0.0
    abs_base: float = 0.0
    sq_model: float = 0.0
    sq_base: float = 0.0
    wins: int = 0
    ties: int = 0
    # Welford running mean and M2 of d = |e_base| - |e_model| (positive: model better).
    d_mean: float = 0.0
    d_m2: float = 0.0

    def add(self, e_model: float, e_base: float) -> None:
        self.n += 1
        a_m, a_b = abs(e_model), abs(e_base)
        self.abs_model += a_m
        self.abs_base += a_b
        self.sq_model += e_model * e_model
        self.sq_base += e_base * e_base
        if math.isclose(a_m, a_b, rel_tol=1e-9, abs_tol=1e-12):
            self.ties += 1
        elif a_m < a_b:
            self.wins += 1
        d = a_b - a_m
        delta = d - self.d_mean
        self.d_mean += delta / self.n
        self.d_m2 += delta * (d - self.d_mean)

    def summary(self) -> dict:
        n = self.n
        if n == 0:
            return {"scored": 0, "enough": False}
        mae_m, mae_b = self.abs_model / n, self.abs_base / n
        enough = n >= MIN_SCORED
        ci = None
        if enough and n > 1:
            half = 1.96 * math.sqrt(self.d_m2 / (n - 1)) / math.sqrt(n)
            ci = [self.d_mean - half, self.d_mean + half]
        decided = n - self.ties
        return {
            "scored": n,
            "enough": enough,
            "maeModel": mae_m,
            "maeBaseline": mae_b,
            "rmseModel": math.sqrt(self.sq_model / n),
            "rmseBaseline": math.sqrt(self.sq_base / n),
            # Null until there are enough receipts: a skill score from five
            # readings is a coin toss reported to three decimals.
            "skill": (1.0 - mae_m / mae_b) if enough and mae_b > 0 else None,
            "winRate": (self.wins / decided) if enough and decided else None,
            "meanErrorReduction": self.d_mean if enough else None,
            "meanErrorReductionCi95": ci,
        }


class _Group:
    """All baselines for one grouping key, plus the latest-value reference."""

    def __init__(self) -> None:
        self.pairs: Dict[str, _Pair] = {name: _Pair() for name in BASELINES}
        self.abs_latest = 0.0
        self.n_latest = 0

    def summary(self) -> dict:
        base = self.pairs["persistence"].summary()
        # The persistence comparison stays at the top level, under its original
        # names, so existing readers keep working.
        out = {k: v for k, v in base.items() if k not in ("maeBaseline", "rmseBaseline")}
        if base.get("scored"):
            out["maePersistence"] = base["maeBaseline"]
            out["rmsePersistence"] = base["rmseBaseline"]
        if self.n_latest:
            out["maeLatestValue"] = self.abs_latest / self.n_latest
        out["baselines"] = {name: pair.summary() for name, pair in self.pairs.items()}
        return out


class ForecastLedger:
    """Thread-safe store of forecast receipts and their running aggregates.

    Optionally backed by a ReceiptStore (receipt_store.py): new receipts are
    handed to it to write, and `replay` rebuilds the aggregates from what it
    kept, so the scores outlive a restart.
    """

    def __init__(self, max_receipts: int = MAX_RECEIPTS):
        self._lock = threading.Lock()
        self._groups: Dict[Tuple, _Group] = {}
        self._receipts: Deque[dict] = deque(maxlen=max(1, max_receipts))
        # (device, metric, time-of-day slot) -> (when, value): the last reading seen in that slot.
        self._daily: Dict[Tuple[str, str, int], Tuple[datetime, float]] = {}
        self._total = 0
        self._since: Optional[str] = None
        self._store = None

    def attach_store(self, store) -> None:
        self._store = store

    # ------------------------------------------------------------------ scoring

    def _group(self, key: Tuple) -> _Group:
        group = self._groups.get(key)
        if group is None:
            group = self._groups[key] = _Group()
        return group

    def _daily_guess(self, device_uid: str, metric: str, moment: Optional[datetime]) -> Optional[float]:
        if moment is None:
            return None
        held = self._daily.get((device_uid, metric, _bucket(moment)))
        if held is None:
            return None
        age_hours = (moment - held[0]).total_seconds() / 3600.0
        return held[1] if DAILY_MIN_HOURS <= age_hours <= DAILY_MAX_HOURS else None

    def _apply(self, row: dict) -> None:
        """Adds one complete receipt to the aggregates. Callers hold the lock."""
        real, forecast = row["actual"], row["forecast"]
        e_model = real - forecast
        keys = (
            ("all", row["method"], row["metric"]),
            ("regime", row["method"], row["metric"], row["regime"]),
            ("step", row["method"], row["metric"], row["step"]),
            ("device", row["method"], row["deviceUid"], row["metric"]),
        )
        guesses = {"persistence": row["persistenceAtIssue"], "trend": row.get("trendAtIssue"), "daily": row.get("dailyGuess")}
        latest = row.get("latestValue")
        for key in keys:
            group = self._group(key)
            for name, guess in guesses.items():
                if guess is not None:
                    group.pairs[name].add(e_model, real - guess)
            if latest is not None:
                group.abs_latest += abs(real - latest)
                group.n_latest += 1
        self._receipts.append(row)
        self._total += 1
        if self._since is None or row["recordedAt"] < self._since:
            self._since = row["recordedAt"]

    def _remember_daily(self, device_uid: str, metric: str, moment: Optional[datetime], value: float) -> None:
        if moment is None:
            return
        key = (device_uid, metric, _bucket(moment))
        held = self._daily.get(key)
        # Newest wins, so a replayed old receipt never overwrites a fresher slot.
        if held is None or moment >= held[0]:
            self._daily[key] = (moment, value)

    def record(
        self,
        *,
        device_uid: str,
        method: str,
        step: int,
        actual: Dict[str, float],
        expected: Dict[str, List[float]],
        issue_values: Dict[str, float],
        latest_values: Dict[str, float],
        regimes: Dict[str, str],
        recorded_at: str,
        issued_at: str,
        trend_slopes: Optional[Dict[str, float]] = None,
    ) -> int:
        """Scores one arriving reading against the forecast step that predicted it.

        Returns how many metrics were scored. A metric is skipped, not zeroed,
        when the reading, the forecast or the persistence value is missing; the
        trend and daily baselines may be missing on their own and are then
        simply not compared on this receipt.
        """
        moment = _parse_time(recorded_at)
        horizon_step = step + 1  # 1 = the first reading after the forecast
        new_rows: List[dict] = []
        with self._lock:
            for metric in METRICS:
                real = _finite(actual.get(metric))
                if real is None:
                    continue
                forecast = _finite((expected.get(metric) or [None])[0])
                issue = _finite(issue_values.get(metric))
                # Yesterday's slot is read before today's reading is written into it.
                daily = self._daily_guess(device_uid, metric, moment)
                self._remember_daily(device_uid, metric, moment, real)
                if forecast is None or issue is None:
                    continue
                slope = _finite((trend_slopes or {}).get(metric))
                row = {
                    "recordedAt": recorded_at,
                    "issuedAt": issued_at,
                    "deviceUid": device_uid,
                    "method": method,
                    "metric": metric,
                    "step": horizon_step,
                    "regime": regimes.get(metric, "unknown"),
                    "actual": real,
                    "forecast": forecast,
                    "persistenceAtIssue": issue,
                    "latestValue": _finite(latest_values.get(metric)),
                    "trendAtIssue": (issue + slope * horizon_step) if slope is not None else None,
                    "dailyGuess": daily,
                }
                self._apply(row)
                new_rows.append(row)
        if self._store is not None and new_rows:
            self._store.enqueue(new_rows)
        return len(new_rows)

    def replay(self, rows: Iterable[dict]) -> int:
        """Rebuilds the aggregates from stored receipts, oldest first. Writes nothing back."""
        count = 0
        with self._lock:
            for row in rows:
                moment = _parse_time(row["recordedAt"])
                self._remember_daily(row["deviceUid"], row["metric"], moment, row["actual"])
                self._apply(row)
                count += 1
        return count

    # ------------------------------------------------------------------ reading

    def summary(self) -> dict:
        """Aggregates for every method seen, grouped the way a paper's tables are."""
        with self._lock:
            items = [(key, group.summary()) for key, group in self._groups.items()]
            total, since = self._total, self._since
        out: Dict[str, dict] = {}
        for key, stats in items:
            kind, method = key[0], key[1]
            block = out.setdefault(method, {"metrics": {}, "byRegime": {}, "byStep": {}, "byStation": {}})
            if kind == "all":
                block["metrics"][key[2]] = stats
            elif kind == "regime":
                block["byRegime"].setdefault(key[2], {})[key[3]] = stats
            elif kind == "step":
                block["byStep"].setdefault(key[2], {})[str(key[3])] = stats
            elif kind == "device":
                block["byStation"].setdefault(key[2], {})[key[3]] = stats
        store = self._store.status() if self._store is not None else {"enabled": False, "state": "off"}
        return {
            "totalScored": total,
            "since": since,
            "minScoredForSkill": MIN_SCORED,
            "baseline": "persistence: the last value known when the forecast was issued",
            "baselines": {
                "persistence": "the last value known when the forecast was issued",
                "trend": f"a straight line through the last {TREND_POINTS} readings at issue, extended ahead",
                "daily": f"the reading in the same {DAILY_BUCKET_MINUTES}-minute slot "
                         f"{DAILY_MIN_HOURS:g}-{DAILY_MAX_HOURS:g} hours earlier",
            },
            "ciNote": "95% interval on mean |error| reduction assuming independent readings; "
                      "consecutive readings are autocorrelated, so it is optimistic.",
            "storage": store,
            "methods": out,
        }

    def recent(self, device_uid: str, metric: str, limit: int = 60) -> List[dict]:
        """The newest receipts for one station and metric, oldest first."""
        limit = max(1, min(int(limit), 500))
        picked: List[dict] = []
        with self._lock:
            for row in reversed(self._receipts):
                if row["deviceUid"] == device_uid and row["metric"] == metric:
                    picked.append(dict(row))
                    if len(picked) >= limit:
                        break
        picked.reverse()
        return picked

    def receipts(self) -> List[dict]:
        with self._lock:
            return list(self._receipts)

    def receipts_csv(self, rows: Optional[Iterable[dict]] = None) -> str:
        buffer = io.StringIO()
        writer = csv.DictWriter(buffer, fieldnames=CSV_FIELDS, extrasaction="ignore")
        writer.writeheader()
        for row in (self.receipts() if rows is None else rows):
            writer.writerow(row)
        return buffer.getvalue()

    def reset(self) -> None:
        with self._lock:
            self._groups.clear()
            self._receipts.clear()
            self._daily.clear()
            self._total = 0
            self._since = None


forecast_ledger = ForecastLedger()
