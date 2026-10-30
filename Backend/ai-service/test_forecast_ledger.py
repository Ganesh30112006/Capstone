"""Forecast receipts: the scoring is fair, honest about small samples, and safe."""

import math

import pytest

import forecast_ledger as fl
from forecast_ledger import ForecastLedger, regime_of


def _record(ledger, *, actual, forecast, issue, latest, step=0, method="chronos-2", device="ESP32-CHN-001",
            regime="calm", metric="temperature"):
    return ledger.record(
        device_uid=device, method=method, step=step,
        actual={metric: actual}, expected={metric: [forecast]},
        issue_values={metric: issue}, latest_values={metric: latest},
        regimes={metric: regime}, recorded_at="2026-10-29T00:00:00Z", issued_at="2026-10-29T00:00:00Z",
    )


def test_regime_is_judged_from_the_step_before_the_forecast():
    assert regime_of("temperature", 25.0, 24.9) == "calm"
    assert regime_of("temperature", 25.0, 24.7) == "changing"
    assert regime_of("temperature", 25.0, None) == "unknown"


def test_a_perfect_forecaster_has_skill_one_and_wins_every_time():
    ledger = ForecastLedger()
    for i in range(fl.MIN_SCORED):
        _record(ledger, actual=20.0 + i * 0.1, forecast=20.0 + i * 0.1, issue=20.0, latest=20.0)
    stats = ledger.summary()["methods"]["chronos-2"]["metrics"]["temperature"]
    assert stats["skill"] == pytest.approx(1.0)
    assert stats["winRate"] == pytest.approx(1.0)
    assert stats["meanErrorReductionCi95"][0] > 0


def test_holding_the_last_value_scores_exactly_zero_skill():
    # The naive fallback *is* persistence at issue: if this were not 0, the
    # baseline and the fallback would disagree about what "repeat the last
    # value" means, and every skill score would be off by that disagreement.
    ledger = ForecastLedger()
    for i in range(fl.MIN_SCORED):
        _record(ledger, actual=20.0 + (i % 5) * 0.3, forecast=20.0, issue=20.0, latest=20.1, method="naive-last-value")
    stats = ledger.summary()["methods"]["naive-last-value"]["metrics"]["temperature"]
    assert stats["skill"] == pytest.approx(0.0)


def test_a_worse_than_baseline_forecaster_has_negative_skill():
    ledger = ForecastLedger()
    for _ in range(fl.MIN_SCORED):
        _record(ledger, actual=20.0, forecast=22.0, issue=20.5, latest=20.5)
    assert ledger.summary()["methods"]["chronos-2"]["metrics"]["temperature"]["skill"] < 0


def test_skill_is_withheld_until_there_are_enough_receipts():
    ledger = ForecastLedger()
    for _ in range(fl.MIN_SCORED - 1):
        _record(ledger, actual=20.0, forecast=20.0, issue=21.0, latest=21.0)
    stats = ledger.summary()["methods"]["chronos-2"]["metrics"]["temperature"]
    assert stats["enough"] is False
    assert stats["skill"] is None and stats["winRate"] is None and stats["meanErrorReductionCi95"] is None


def test_methods_are_never_pooled():
    # Crediting Chronos with a fallback's receipts would be the paper's central error.
    ledger = ForecastLedger()
    _record(ledger, actual=20.0, forecast=20.0, issue=21.0, latest=21.0, method="chronos-2")
    _record(ledger, actual=20.0, forecast=21.0, issue=21.0, latest=21.0, method="naive-last-value")
    methods = ledger.summary()["methods"]
    assert methods["chronos-2"]["metrics"]["temperature"]["scored"] == 1
    assert methods["naive-last-value"]["metrics"]["temperature"]["scored"] == 1


def test_missing_or_non_finite_values_are_skipped_not_zeroed():
    ledger = ForecastLedger()
    assert _record(ledger, actual=math.nan, forecast=20.0, issue=20.0, latest=20.0) == 0
    assert ledger.record(
        device_uid="ESP32-CHN-001", method="chronos-2", step=0,
        actual={"temperature": 20.0}, expected={}, issue_values={"temperature": 20.0},
        latest_values={"temperature": 20.0}, regimes={}, recorded_at="", issued_at="",
    ) == 0
    assert ledger.summary()["totalScored"] == 0


def test_breakdowns_by_regime_step_and_station():
    ledger = ForecastLedger()
    _record(ledger, actual=20.0, forecast=20.1, issue=20.0, latest=20.0, step=0, regime="calm")
    _record(ledger, actual=21.0, forecast=20.8, issue=20.0, latest=20.5, step=2, regime="changing", device="ESP32-CHN-002")
    block = ledger.summary()["methods"]["chronos-2"]
    assert set(block["byRegime"]["temperature"]) == {"calm", "changing"}
    assert set(block["byStep"]["temperature"]) == {"1", "3"}
    assert set(block["byStation"]) == {"ESP32-CHN-001", "ESP32-CHN-002"}


def test_receipts_are_bounded_and_exportable():
    ledger = ForecastLedger(max_receipts=5)
    for i in range(12):
        _record(ledger, actual=20.0 + i, forecast=20.0, issue=20.0, latest=20.0)
    assert len(ledger.receipts()) == 5
    csv_text = ledger.receipts_csv()
    assert csv_text.splitlines()[0].startswith("recordedAt,issuedAt,deviceUid,method,metric,step")
    assert len(csv_text.strip().splitlines()) == 6
    recent = ledger.recent("ESP32-CHN-001", "temperature", limit=3)
    assert [r["actual"] for r in recent] == [29.0, 30.0, 31.0]


def test_stream_processor_writes_receipts_for_forecast_steps(monkeypatch):
    import stream_processor as sp

    ledger = ForecastLedger()
    monkeypatch.setattr(sp, "forecast_ledger", ledger)
    # A forecaster that always says 25.0, so the outcome is known in advance.
    monkeypatch.setattr(sp, "_forecast_horizon", lambda history, horizon: {"temperature": [25.0] * horizon})

    processor = sp.StreamProcessor(forecast_every=100, min_history_for_forecast=2, forecast_horizon=3)
    for i in range(8):
        processor.handle_reading({
            "deviceUid": "ESP32-CHN-001",
            "temperature": 24.0 + i * 0.1, "humidity": 60.0, "airQuality": 40,
            "pressure": 1010.0, "oxygen": 20.9,
            "recordedAt": f"2026-10-29T00:00:0{i}Z",
        })
    receipts = [r for r in ledger.receipts() if r["metric"] == "temperature"]
    assert receipts, "forecast steps should have been scored"
    assert all(r["forecast"] == 25.0 and r["method"] == "chronos-2" for r in receipts)
    assert all(1 <= r["step"] <= 3 for r in receipts)
    # The baseline is the value the forecast was issued from, never the reading being scored.
    assert all(r["persistenceAtIssue"] != r["actual"] for r in receipts)


def test_diebold_mariano_detects_a_real_difference_and_not_a_fake_one():
    import numpy as np
    from benchmarks.forecast_accountability import diebold_mariano, long_run_variance

    rng = np.random.default_rng(7)
    # Lag 0 is the plain (biased) variance.
    x = rng.normal(size=200)
    assert long_run_variance(x, lag=0) == pytest.approx(float(((x - x.mean()) ** 2).mean()))

    better = [rng.normal(0.3, 1.0, 300) for _ in range(3)]   # model consistently closer
    stat, p, n = diebold_mariano(better)
    assert n == 900 and stat > 3 and p < 0.01

    same = [rng.normal(0.0, 1.0, 300) for _ in range(3)]     # no difference at all
    stat, p, _ = diebold_mariano(same)
    assert abs(stat) < 3 and p > 0.001


# ---------------------------------------------------------------- harder baselines

def test_trend_slope_is_least_squares_and_needs_two_points():
    from forecast_ledger import trend_slope
    assert trend_slope([1.0, 2.0, 3.0, 4.0]) == pytest.approx(1.0)
    assert trend_slope([5.0, 5.0, 5.0]) == pytest.approx(0.0)
    assert trend_slope([7.0]) is None
    assert trend_slope([1.0, float("nan"), 3.0]) == pytest.approx(2.0)


def test_trend_baseline_extends_the_line_by_the_step():
    ledger = ForecastLedger()
    ledger.record(
        device_uid="ESP32-CHN-001", method="chronos-2", step=2,  # third reading after issue
        actual={"temperature": 23.0}, expected={"temperature": [22.0]},
        issue_values={"temperature": 20.0}, latest_values={"temperature": 22.0},
        regimes={"temperature": "changing"}, recorded_at="2026-10-29T00:00:15Z", issued_at="",
        trend_slopes={"temperature": 1.0},
    )
    row = ledger.receipts()[0]
    assert row["trendAtIssue"] == pytest.approx(23.0)  # 20 + 1.0 * 3
    stats = ledger.summary()["methods"]["chronos-2"]["metrics"]["temperature"]["baselines"]
    assert stats["trend"]["scored"] == 1 and stats["trend"]["maeBaseline"] == pytest.approx(0.0)


def _at(hours: float) -> str:
    from datetime import datetime, timedelta, timezone
    base = datetime(2026, 10, 1, 6, 0, 30, tzinfo=timezone.utc)
    return (base + timedelta(hours=hours)).isoformat().replace("+00:00", "Z")


def _daily_record(ledger, hours, actual, forecast=None):
    return ledger.record(
        device_uid="ESP32-CHN-001", method="chronos-2", step=0,
        actual={"temperature": actual},
        expected={"temperature": [forecast]} if forecast is not None else {},
        issue_values={"temperature": actual}, latest_values={"temperature": actual},
        regimes={}, recorded_at=_at(hours), issued_at="",
    )


def test_daily_guess_only_uses_a_reading_from_about_a_day_earlier():
    ledger = ForecastLedger()
    _daily_record(ledger, 0, 25.0)               # seeds today's 06:00 slot, nothing scored
    _daily_record(ledger, 10, 30.0, forecast=30)  # same slot? no: 16:00 -- and anyway 10h is too soon
    _daily_record(ledger, 24, 26.0, forecast=26)  # 06:00 next day: yesterday's 25.0 applies
    rows = ledger.receipts()
    assert rows[0]["dailyGuess"] is None
    assert rows[1]["dailyGuess"] == pytest.approx(25.0)
    pairs = ledger.summary()["methods"]["chronos-2"]["metrics"]["temperature"]["baselines"]
    # Paired: daily was compared once, persistence twice.
    assert pairs["daily"]["scored"] == 1 and pairs["persistence"]["scored"] == 2


def test_daily_guess_is_never_older_than_the_window():
    ledger = ForecastLedger()
    _daily_record(ledger, 0, 25.0)
    _daily_record(ledger, 48, 26.0, forecast=26)  # two days later: too old to be "yesterday"
    assert ledger.receipts()[0]["dailyGuess"] is None


def test_replay_reproduces_the_live_scores_exactly():
    live = ForecastLedger()
    for i in range(40):
        live.record(
            device_uid="ESP32-CHN-00%d" % (1 + i % 2), method="chronos-2", step=i % 3,
            actual={"temperature": 25 + (i % 7) * 0.2, "humidity": 60 + i % 5},
            expected={"temperature": [25.3], "humidity": [62.0]},
            issue_values={"temperature": 25.0, "humidity": 61.0},
            latest_values={"temperature": 25.1, "humidity": 61.0},
            regimes={"temperature": "calm", "humidity": "changing"},
            recorded_at=_at(i / 60), issued_at="", trend_slopes={"temperature": 0.05},
        )
    restored = ForecastLedger()
    restored.replay(live.receipts())
    a, b = live.summary(), restored.summary()
    a.pop("storage"); b.pop("storage")
    assert a == b


# ---------------------------------------------------------------- the database store

def _postgres_url(db: str):
    import config
    base = config.AI_DB_URL.rpartition("/")[0]
    return f"{base}/{db}"


def _postgres_reachable() -> bool:
    try:
        import psycopg
        with psycopg.connect(_postgres_url("postgres"), connect_timeout=2):
            return True
    except Exception:
        return False


def test_store_degrades_to_memory_when_the_database_is_unreachable():
    from receipt_store import ReceiptStore
    store = ReceiptStore(url="postgresql://nobody:x@127.0.0.1:1/ai_db", queue_max=3)
    assert store.start() == []
    store.enqueue([{"n": i} for i in range(5)])  # must not raise, must count what it lost
    status = store.status()
    store.stop(timeout=0.1)
    assert status["state"] == "failed" and status["lastError"]
    assert "x@" not in status["lastError"]       # never the connection string
    assert status["dropped"] == 2 and status["pending"] == 3


@pytest.mark.skipif(not _postgres_reachable(), reason="local PostgreSQL not running")
def test_receipts_survive_a_restart_through_postgres():
    import psycopg
    from receipt_store import ReceiptStore

    db = "ai_db_test_receipts"
    admin = _postgres_url("postgres")
    with psycopg.connect(admin, autocommit=True) as conn:
        conn.execute(f"DROP DATABASE IF EXISTS {db}")
    try:
        first = ForecastLedger()
        store = ReceiptStore(url=_postgres_url(db))
        first.attach_store(store)
        assert store.start() == []                     # creates the database and the table
        for i in range(35):
            _record(first, actual=20.0 + i * 0.1, forecast=20.0 + i * 0.1, issue=20.0, latest=20.0)
        store.stop()                                   # flushes what is queued
        assert store.status()["written"] == 35

        # A new process: an empty ledger, rebuilt from what the database kept.
        second = ForecastLedger()
        again = ReceiptStore(url=_postgres_url(db))
        second.replay(again.start())
        again.stop()
        restored = second.summary()["methods"]["chronos-2"]["metrics"]["temperature"]
        assert restored["scored"] == 35 and restored["skill"] == pytest.approx(1.0)
        assert len(again.export(days=1, limit=100)) == 35
    finally:
        with psycopg.connect(admin, autocommit=True) as conn:
            conn.execute(f"DROP DATABASE IF EXISTS {db} WITH (FORCE)")
