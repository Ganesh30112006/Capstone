"""The paper's tables, from forecast receipts.

    python -m benchmarks.forecast_accountability receipts.csv [--out tables.md]

`receipts.csv` is the export from GET /api/ai/forecast-receipts (operators,
through the gateway). Each row is one forecast step scored against the reading
it predicted; see forecast_ledger.py for exactly how.

The live page reports a 95% interval that treats readings as independent. They
are not: consecutive readings of one station are strongly autocorrelated, and
so are the errors of forecasts issued from overlapping histories. This harness
is the version that can be cited. For each (method, metric) and each forecast
step it reports:

  n         receipts scored
  MAE       model, and persistence at issue (the same information the model had)
  skill     1 - MAE(model) / MAE(persistence)
  win       share of non-tied receipts where the model was closer
  DM        Diebold-Mariano statistic on absolute-error loss differentials
            d_t = |e_persistence| - |e_model|, with a Newey-West long-run
            variance estimated per station and summed (stations treated as
            independent of each other, readings within a station not)
  p         two-sided, normal reference

and the same split by regime (calm / changing, fixed when the forecast was
issued). Positive DM means the model's errors were smaller.

What a result here does and does not establish -- the README's distinction
between stability and validity applies in full. On the simulator's telemetry
these tables measure the forecaster against the simulator. Run on receipts from
real stations, they are evidence about the deployment.
"""

from __future__ import annotations

import argparse
import math
import sys
from typing import Iterable, List, Optional, Tuple

import numpy as np
import pandas as pd


def newey_west_lag(n: int) -> int:
    """The usual automatic bandwidth, floor(4 (n/100)^(2/9))."""
    return max(0, int(math.floor(4 * (n / 100.0) ** (2.0 / 9.0)))) if n > 0 else 0


def long_run_variance(d: np.ndarray, lag: Optional[int] = None) -> float:
    """Bartlett-kernel (Newey-West) long-run variance of a series about its mean."""
    n = len(d)
    if n < 2:
        return float("nan")
    lag = newey_west_lag(n) if lag is None else lag
    centred = d - d.mean()
    lrv = float(centred @ centred) / n
    for k in range(1, min(lag, n - 1) + 1):
        weight = 1.0 - k / (lag + 1.0)
        lrv += 2.0 * weight * float(centred[k:] @ centred[:-k]) / n
    return lrv


def diebold_mariano(groups: Iterable[np.ndarray]) -> Tuple[float, float, int]:
    """DM statistic and two-sided p-value, pooling independent groups (stations).

    Each group is one station's loss differentials in time order. The pooled
    mean is the plain mean of all differentials; its variance is the sum of the
    groups' long-run variances, each weighted by the group's size.
    """
    arrays = [np.asarray(g, dtype=float) for g in groups if len(g) > 1]
    n = sum(len(a) for a in arrays)
    if n < 2:
        return float("nan"), float("nan"), n
    mean = sum(float(a.sum()) for a in arrays) / n
    var_of_mean = sum(len(a) * long_run_variance(a) for a in arrays) / (n * n)
    if not var_of_mean > 0:
        return float("nan"), float("nan"), n
    stat = mean / math.sqrt(var_of_mean)
    p = math.erfc(abs(stat) / math.sqrt(2.0))
    return stat, p, n


#: Baseline columns in the export, and how the tables name them.
BASELINES = (
    ("persistenceAtIssue", "persistence (last value at issue)"),
    ("trendAtIssue", "trend (line through recent readings, at issue)"),
    ("dailyGuess", "daily (same time of day, about 24 h earlier)"),
)


def summarise(frame: pd.DataFrame, baseline: str = "persistenceAtIssue") -> dict:
    """Model against one baseline, on the receipts where that baseline had a value (paired)."""
    frame = frame[frame[baseline].notna()]
    if frame.empty:
        return {"n": 0, "mae_model": float("nan"), "mae_persistence": float("nan"), "skill": float("nan"),
                "win": float("nan"), "dm": float("nan"), "p": float("nan")}
    e_model = (frame["actual"] - frame["forecast"]).abs()
    e_base = (frame["actual"] - frame[baseline]).abs()
    d = e_base - e_model
    mae_m, mae_b = float(e_model.mean()), float(e_base.mean())
    decided = int((d != 0).sum())
    groups: List[np.ndarray] = [
        (g["actual"] - g[baseline]).abs().to_numpy() - (g["actual"] - g["forecast"]).abs().to_numpy()
        for _, g in frame.sort_values("recordedAt").groupby("deviceUid")
    ]
    stat, p, _ = diebold_mariano(groups)
    return {
        "n": len(frame),
        "mae_model": mae_m,
        "mae_persistence": mae_b,
        "skill": (1.0 - mae_m / mae_b) if mae_b > 0 else float("nan"),
        "win": (int((d > 0).sum()) / decided) if decided else float("nan"),
        "dm": stat,
        "p": p,
    }


def _fmt(value: float, digits: int = 3) -> str:
    if value is None or (isinstance(value, float) and not math.isfinite(value)):
        return "—"
    return f"{value:.{digits}f}"


def _pct(value: float) -> str:
    text = _fmt(value * 100 if isinstance(value, float) else value, 1)
    return text if text == "—" else f"{text}%"


def _p(value: float) -> str:
    if not isinstance(value, float) or not math.isfinite(value):
        return "—"
    return "<0.001" if value < 0.001 else f"{value:.3f}"


def table(rows: List[Tuple[str, dict]], first: str) -> str:
    lines = [
        f"| {first} | n | MAE model | MAE baseline | skill | win | DM | p |",
        "|---|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for label, s in rows:
        lines.append(
            f"| {label} | {s['n']} | {_fmt(s['mae_model'])} | {_fmt(s['mae_persistence'])} | "
            f"{_pct(s['skill'])} | {_pct(s['win'])} | {_fmt(s['dm'], 2)} | {_p(s['p'])} |"
        )
    return "\n".join(lines)


def report(frame: pd.DataFrame) -> str:
    out: List[str] = ["# Forecast accountability", ""]
    stations = frame["deviceUid"].nunique()
    out.append(f"{len(frame)} receipts from {stations} stations, "
               f"{frame['recordedAt'].min()} to {frame['recordedAt'].max()}.")
    out.append("")
    baselines = [(col, name) for col, name in BASELINES if col in frame.columns]
    for method, by_method in frame.groupby("method"):
        out.append(f"## {method}")
        out.append("")
        for col, name in baselines:
            out.append(f"Against {name}:")
            out.append("")
            out.append(table([(metric, summarise(g, col)) for metric, g in by_method.groupby("metric")], "metric"))
            out.append("")
        for metric, by_metric in by_method.groupby("metric"):
            out.append(f"### {method} · {metric}")
            out.append("")
            out.append("By forecast step (readings ahead):")
            out.append("")
            out.append(table([(str(step), summarise(g)) for step, g in by_metric.groupby("step")], "step"))
            out.append("")
            out.append("By regime at issue:")
            out.append("")
            out.append(table([(regime, summarise(g)) for regime, g in by_metric.groupby("regime")], "regime"))
            out.append("")
    out.append("Each baseline is compared only on receipts where it had a value (paired), so n differs. "
               "Positive DM: the model's absolute errors were smaller than the baseline's. "
               "Variance: Newey-West per station, stations pooled as independent.")
    return "\n".join(out)


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("receipts", help="CSV exported from /api/ai/forecast-receipts")
    parser.add_argument("--out", help="write the markdown here instead of stdout")
    args = parser.parse_args(argv)

    frame = pd.read_csv(args.receipts)
    needed = {"recordedAt", "deviceUid", "method", "metric", "step", "regime", "actual", "forecast", "persistenceAtIssue"}
    missing = needed - set(frame.columns)
    if missing:
        print(f"missing columns: {', '.join(sorted(missing))}", file=sys.stderr)
        return 2
    if frame.empty:
        print("no receipts to analyse", file=sys.stderr)
        return 1

    text = report(frame)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as handle:
            handle.write(text + "\n")
    else:
        print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
