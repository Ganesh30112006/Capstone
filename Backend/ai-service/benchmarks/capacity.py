"""How much fleet can one instance of this service carry, and what stops it?

`performance.py` measures components. This measures the thing an operator
actually has to decide: how many stations one container can take, at what
reporting interval, before something saturates — and which thing saturates
first, since that determines what to buy more of.

The answer is not one number, because the two worker paths have different cost
models and the service is deliberately not on the critical path for either.
Three regimes matter and they are reported separately:

  * **Scoring only** — the per-reading arithmetic. Fast, and never the limit.
  * **Scoring plus forecasting** — what the workers actually do. Dominated by
    the transformer, and therefore by how often the forecast is refreshed.
  * **The HTTP surface** — bounded by admission control rather than by CPU,
    on purpose.

Read the bottleneck line before the capacity line. A capacity figure whose
bottleneck is the forecast interval is a configuration decision; one whose
bottleneck is memory is a sizing decision; they are not fixed by the same
action.

Run with:  python -m benchmarks.capacity
"""

from __future__ import annotations

import gc
import logging
import sys
import tempfile
import time
import tracemalloc
from pathlib import Path
from typing import Callable, Dict

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import config  # noqa: E402

_SANDBOX = Path(tempfile.mkdtemp(prefix="ai-capacity-"))
config.IF_MODEL_PATH = _SANDBOX / "model.joblib"
config.METADATA_PATH = _SANDBOX / "metadata.json"
config.IF_SIGNATURE_PATH = _SANDBOX / "model.joblib.sig"

import stream_processor as sp  # noqa: E402
from chronos_forecaster import chronos_forecaster  # noqa: E402
from isolation_forest_model import isolation_forest  # noqa: E402
from ml_service import orchestrator  # noqa: E402

from benchmarks import workloads  # noqa: E402

SECONDS_PER_DAY = 86_400


def measure(fn: Callable[[], object], calls: int, repeats: int = 7) -> float:
    """Microseconds per call, min of `repeats` runs around a warmed path.

    Min rather than mean: the distribution is one-sided. Nothing makes a run
    faster than the work it does, so the fastest run is the one least polluted
    by whatever else the machine was doing.
    """
    fn()
    best = float("inf")
    for _ in range(repeats):
        start = time.perf_counter()
        for _ in range(calls):
            fn()
        best = min(best, (time.perf_counter() - start) / calls)
    return best * 1e6


def _train() -> None:
    isolation_forest.train(pd.DataFrame(workloads.normal_frame(3000, seed=1)),
                           is_synthetic=False)


# ---------------------------------------------------------------------------
# 1. Per-reading cost
# ---------------------------------------------------------------------------

def report_per_reading() -> Dict[str, float]:
    print("\n" + "=" * 78)
    print("1. PER-READING COST  (what every reading pays, on both worker paths)")
    print("=" * 78)

    history = workloads.history_window(48)
    current = history[-1]
    expected = {name: [current[name]] for name in workloads.FEATURES}

    costs = {
        "rules": measure(lambda: __import__("rule_engine").rule_engine.evaluate(current), 2000),
        "isolation forest": measure(lambda: isolation_forest.evaluate(current), 1000),
        "flatline": measure(
            lambda: __import__("flatline_detector").flatline_detector.observe("cap", current), 2000),
        "forecast residual": measure(
            lambda: chronos_forecaster.evaluate_against(expected, history, current), 1000),
    }
    total = measure(
        lambda: orchestrator.analyze_with_forecast("cap", current, history, expected), 500)

    print(f"  {'component':<28}{'us':>10}{'share':>9}")
    for name, cost in sorted(costs.items(), key=lambda item: -item[1]):
        print(f"  {name:<28}{cost:>10.1f}{cost / total:>8.0%}")
    print(f"  {'-' * 28}{'-' * 10}{'-' * 9}")
    print(f"  {'full hybrid scoring':<28}{total:>10.1f}{1.0:>8.0%}")
    print(f"\n  {1e6 / total:,.0f} readings/second of pure scoring, single-threaded.")
    return {"scoring_us": total, **costs}


# ---------------------------------------------------------------------------
# 2. The transformer, and what the refresh interval does to it
# ---------------------------------------------------------------------------

def report_forecast_cost() -> Dict[str, float]:
    print("\n" + "=" * 78)
    print("2. FORECAST COST  (the transformer, and how far one call is spread)")
    print("=" * 78)

    history = workloads.history_window(48)
    horizon = int(getattr(config, "AI_STREAM_FORECAST_HORIZON", 6))

    # The pipeline loads lazily on first use, so `is_available` is False until
    # something has asked for a forecast. Checking it cold reports a perfectly
    # working Chronos as missing — and then attributes none of the capacity
    # limit to the transformer that is in fact setting it.
    chronos_forecaster.predict_features(history, horizon=horizon,
                                        features=workloads.FEATURES)
    if not chronos_forecaster.is_available:
        print("  Chronos is not installed; the workers publish an unavailable")
        print("  forecast and this section has nothing to measure. Everything")
        print("  below therefore reports the service's floor cost, not its")
        print("  real one.")
        return {"forecast_ms": 0.0, "horizon": horizon}

    per_call = measure(
        lambda: chronos_forecaster.predict_features(history, horizon=horizon,
                                                    features=workloads.FEATURES),
        calls=3, repeats=3) / 1000.0

    print(f"  one batched forecast, horizon {horizon}: {per_call:.1f} ms")
    print(f"  that is one forward pass for all {len(workloads.FEATURES)} features, not "
          f"{len(workloads.FEATURES)} separate ones.")
    print()
    print(f"  {'refresh every':<16}{'amortised/reading':>20}{'residual coverage':>20}")
    for interval in (1, 3, 6, 10, 20):
        amortised = per_call / interval
        coverage = min(1.0, horizon / interval)
        flag = "  <- shipped" if interval == horizon else ""
        print(f"  {interval:<16}{amortised:>17.1f} ms{coverage:>19.0%}{flag}")

    print()
    print("  Coverage is the share of readings that reach the residual detector")
    print("  at all: one forecast predicts `horizon` readings, so refreshing")
    print("  less often than that leaves the rest with no forecast to compare")
    print("  against. Refreshing more often than the horizon buys nothing.")
    return {"forecast_ms": per_call, "horizon": horizon}


# ---------------------------------------------------------------------------
# 3. Sustained worker throughput
# ---------------------------------------------------------------------------

def report_worker_throughput() -> Dict[str, float]:
    print("\n" + "=" * 78)
    print("3. WORKER THROUGHPUT  (one thread, end to end, forecasting included)")
    print("=" * 78)

    processor = sp.StreamProcessor()
    stream = workloads.stationary_stream(600, calm=0.25, seed=5)

    # Warm the model and the per-device state before timing.
    for reading in stream[:60]:
        processor.handle_reading({"deviceUid": "warm", **reading})

    start = time.perf_counter()
    for index, reading in enumerate(stream):
        processor.handle_reading({"deviceUid": f"dev-{index % 20}", **reading})
    elapsed = time.perf_counter() - start

    per_reading_ms = elapsed / len(stream) * 1000
    per_second = len(stream) / elapsed

    print(f"  {len(stream)} readings across 20 devices: {elapsed:.1f}s")
    print(f"  {per_reading_ms:.1f} ms/reading -> {per_second:.0f} readings/second/thread")
    print(f"  {per_second * SECONDS_PER_DAY / 1e6:.1f}M readings/day on one worker thread")
    return {"per_reading_ms": per_reading_ms, "per_second": per_second}


# ---------------------------------------------------------------------------
# 4. Fleet capacity
# ---------------------------------------------------------------------------

def report_fleet_capacity(throughput: Dict[str, float], forecast: Dict[str, float],
                          scoring_us: float) -> None:
    print("\n" + "=" * 78)
    print("4. FLEET CAPACITY  (devices per worker thread, by reporting interval)")
    print("=" * 78)

    per_second = throughput["per_second"]
    print(f"  {'reporting interval':<22}{'devices/thread':>18}{'limited by':>22}")

    horizon = forecast.get("horizon", 6)
    forecast_share = 0.0
    if forecast.get("forecast_ms"):
        forecast_share = (forecast["forecast_ms"] / horizon) / throughput["per_reading_ms"]

    for label, seconds in (("every 10 seconds", 10), ("every 30 seconds", 30),
                           ("every minute", 60), ("every 5 minutes", 300)):
        devices = per_second * seconds
        capped = min(devices, sp.MAX_TRACKED_DEVICES)
        limit = ("device table cap" if capped < devices
                 else ("forecast interval" if forecast_share > 0.5 else "scoring CPU"))
        shown = f"{capped:,.0f}"
        if capped < devices:
            shown = f"{capped:,.0f} of {devices:,.0f}"
        print(f"  {label:<22}{shown:>18}{limit:>22}")

    print()
    if forecast_share > 0.5:
        print(f"  The transformer is {forecast_share:.0%} of per-reading cost, so capacity is")
        print("  set by AI_STREAM_FORECAST_HORIZON far more than by CPU: one call")
        print(f"  covers {horizon} readings, and the cost divides by that number.")
    print(f"  Pure scoring alone would allow {1e6 / scoring_us * 30:,.0f} devices at 30s;")
    print("  the gap between that and the table above is entirely forecasting.")
    print()
    print(f"  Beyond {sp.MAX_TRACKED_DEVICES} devices per process the tables evict, and an")
    print("  evicted device loses its rolling history and its learned flatline")
    print("  baseline — it is not wrong afterwards, but it is blind until it")
    print("  warms up again. Raise AI_STREAM_MAX_DEVICES with memory to match,")
    print("  or run more instances; the workers are independent consumers.")


# ---------------------------------------------------------------------------
# 5. Memory
# ---------------------------------------------------------------------------

def report_memory() -> None:
    print("\n" + "=" * 78)
    print("5. MEMORY  (per-device state, and the ceiling it implies)")
    print("=" * 78)

    processor = sp.StreamProcessor(forecast_every=10 ** 9, report_every=10 ** 9)
    reading = {name: value for name, value in
               zip(workloads.FEATURES, [20.0, 55.0, 45.0, 20.9, 1013.0])}

    gc.collect()
    tracemalloc.start()
    before = tracemalloc.get_traced_memory()[0]
    devices, per_device = 200, 60
    for index in range(devices):
        for _ in range(per_device):
            processor.handle_reading({"deviceUid": f"mem-{index}", **reading})
    after = tracemalloc.get_traced_memory()[0]
    tracemalloc.stop()

    retained = (after - before) / 1024 / 1024
    each = retained * 1024 / devices

    print(f"  {devices} devices x {per_device} readings: {retained:.2f} MiB retained")
    print(f"  {each:.1f} KiB per device (window capped at "
          f"{int(getattr(config, 'AI_HISTORY_WINDOW', 48))} readings)")
    print()
    for cap in (500, 2000, 10000):
        print(f"  at {cap:,} tracked devices: ~{each * cap / 1024:.0f} MiB of per-device state")
    print()
    print("  Three tables are keyed by device and all are bounded and")
    print("  least-recently-used: the stream worker's history, the flatline")
    print("  detector's baselines, and the broker worker's forecast cache.")
    print("  Each sheds the device that has gone quiet longest, never the one")
    print("  that has been reporting longest.")


# ---------------------------------------------------------------------------
# 6. Saturation and shedding
# ---------------------------------------------------------------------------

def report_saturation() -> None:
    print("\n" + "=" * 78)
    print("6. SATURATION  (what happens past capacity)")
    print("=" * 78)
    print(f"  HTTP inference slots        : {config.MAX_CONCURRENT_INFERENCE} concurrent")
    print(f"  queue wait before shedding  : {config.INFERENCE_QUEUE_TIMEOUT}s, then 503 + Retry-After")
    print(f"  device tables               : {sp.MAX_TRACKED_DEVICES} per process, LRU")
    print(f"  request body cap            : {config.MAX_REQUEST_BYTES:,} bytes")
    print(f"  broker message cap          : {config.MAX_MESSAGE_BYTES:,} bytes")
    print()
    print("  The two worker paths shed differently from the HTTP path, and")
    print("  neither can lose data: readings are persisted by sensor-service")
    print("  before either worker sees them, and deterministic rule alerting")
    print("  runs independently. A saturated AI service delays anomaly events;")
    print("  it does not drop telemetry and does not block ingestion.")
    print()
    print("  Requests beyond the inference slots are refused rather than")
    print("  queued indefinitely, because inference is CPU-bound: admitting a")
    print("  burst makes every request in it slower instead of serving more.")


def main() -> None:
    print("AI service capacity — seeded workloads, min-of-N around warmed paths")
    print(f"python {sys.version.split()[0]}  numpy {np.__version__}")
    _train()

    per_reading = report_per_reading()
    forecast = report_forecast_cost()
    throughput = report_worker_throughput()
    report_fleet_capacity(throughput, forecast, per_reading["scoring_us"])
    report_memory()
    report_saturation()
    print()


if __name__ == "__main__":
    logging.disable(logging.WARNING)
    main()
