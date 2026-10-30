"""Thread-safety and robustness under concurrent load.

This service runs three things in one process against shared mutable state:
the FastAPI thread pool serving HTTP, the batch RabbitMQ consumer, and the
live-stream consumer. They share the per-device tables, the forecast cache and
the model reference, and a retrain can replace that model while requests are
being scored against it.

Nothing exercised any of that. Every existing test is single-threaded, which is
precisely the condition under which a data race cannot be observed — and a race
here does not crash, it produces a wrong score or a lost device, silently and
occasionally.

These tests are deliberately contended: many threads, short work, shared keys.
A passing run is evidence, not proof — races are probabilistic — so they use
enough iterations to make a real race very likely to show rather than merely
possible.
"""

from __future__ import annotations

import threading
from concurrent.futures import ThreadPoolExecutor

import pandas as pd
import pytest

import config
from forecast_cache import ForecastCache
from isolation_forest_model import IsolationForestModel
from stream_processor import StreamProcessor

from benchmarks import workloads

# Enough contention to make a real race very likely to surface, without turning
# the suite into something nobody runs. Every reading here is fully scored, so
# the cost is dominated by the model rather than by the locking being tested.
THREADS = 8
PER_THREAD = 60


def _drive(fn, threads: int = THREADS, per_thread: int = PER_THREAD):
    """Runs `fn(index)` across threads, re-raising the first failure."""
    errors = []

    def worker(thread_index: int):
        try:
            for step in range(per_thread):
                fn(thread_index * per_thread + step)
        except Exception as exc:  # pragma: no cover - only on a real failure
            errors.append(exc)

    with ThreadPoolExecutor(max_workers=threads) as pool:
        list(pool.map(worker, range(threads)))

    if errors:
        raise errors[0]


# ---------------------------------------------------------------------------
# Stream processor
# ---------------------------------------------------------------------------

class TestStreamProcessorUnderLoad:

    def test_concurrent_devices_do_not_lose_readings(self):
        """Each device's counter must equal what was sent to it.

        The per-device table is a defaultdict mutated under a lock, and the
        reading counter is read, incremented and written back. A missed
        increment here is a report built over the wrong window.
        """
        processor = StreamProcessor()
        readings = workloads.history_window(64)

        def send(index: int) -> None:
            device = f"DEV-{index % 16:02d}"
            processor.handle_reading(dict(readings[index % len(readings)], deviceUid=device))

        _drive(send)

        total_sent = THREADS * PER_THREAD
        with processor._lock:
            counted = sum(state.readings_seen for state in processor._devices.values())
        assert counted == total_sent
        assert processor.tracked_devices() == 16

    def test_one_hot_device_stays_consistent(self):
        """All threads on a single key is the worst case for the lock."""
        processor = StreamProcessor()
        readings = workloads.history_window(64)

        def send(index: int) -> None:
            processor.handle_reading(dict(readings[index % len(readings)], deviceUid="DEV-HOT"))

        _drive(send)

        with processor._lock:
            state = processor._devices["DEV-HOT"]
            assert state.readings_seen == THREADS * PER_THREAD
            # The window is a bounded deque; it must never exceed its maxlen
            # regardless of how many writers raced to append.
            assert len(state.history) <= config.AI_HISTORY_WINDOW
            assert len(state.history) == len(state.timestamps)

    def test_device_table_stays_bounded_under_churn(self):
        """A hostile or churning fleet must not grow the table without limit."""
        from stream_processor import MAX_TRACKED_DEVICES

        processor = StreamProcessor()
        reading = workloads.history_window(1)[0]

        def send(index: int) -> None:
            processor.handle_reading(dict(reading, deviceUid=f"CHURN-{index:06d}"))

        _drive(send, threads=8, per_thread=200)
        assert processor.tracked_devices() <= MAX_TRACKED_DEVICES

    def test_reports_can_be_read_while_readings_arrive(self):
        """The HTTP surface reads these tables from a different thread than the
        consumer writing them."""
        processor = StreamProcessor()
        readings = workloads.history_window(64)
        stop = threading.Event()
        seen = []

        def reader():
            while not stop.is_set():
                seen.append(len(processor.all_reports()))
                processor.all_predictions()
                processor.tracked_devices()
                # Yields between passes. A reader spinning on the same lock the
                # writers need starves them, which measures the busy-loop rather
                # than the thing under test.
                stop.wait(0.001)

        thread = threading.Thread(target=reader, daemon=True)
        thread.start()
        try:
            _drive(lambda i: processor.handle_reading(
                dict(readings[i % len(readings)], deviceUid=f"DEV-{i % 8}")))
        finally:
            stop.set()
            thread.join(timeout=5)

        assert seen, "the reader thread never ran"


# ---------------------------------------------------------------------------
# Forecast cache
# ---------------------------------------------------------------------------

class TestForecastCacheUnderLoad:

    def test_age_never_goes_backwards_for_a_device(self):
        """`record_reading` is a read-modify-write returning the new age.

        Two threads seeing the same age would both score against the same
        forecast step, which is a silently wrong residual rather than an error.
        """
        cache = ForecastCache(refresh_every=6)
        cache.store("DEV-1", {"temperature": [1.0] * 6})
        seen = []
        lock = threading.Lock()

        def bump(_index: int) -> None:
            age = cache.record_reading("DEV-1")
            with lock:
                seen.append(age)

        _drive(bump)

        # Every increment must be distinct: a duplicate is a lost update.
        assert len(set(seen)) == len(seen)
        assert max(seen) == THREADS * PER_THREAD

    def test_store_and_read_race_cleanly(self):
        cache = ForecastCache(refresh_every=4)

        def churn(index: int) -> None:
            device = f"DEV-{index % 8}"
            if index % 5 == 0:
                cache.store(device, {"temperature": [float(index)] * 4})
            else:
                expected = cache.expected_for(device)
                # Whatever comes back must be structurally usable: a torn read
                # would surface as a malformed series here.
                assert isinstance(expected, dict)
                for series in expected.values():
                    assert len(series) == 1

        _drive(churn)


# ---------------------------------------------------------------------------
# Model hot-swap
# ---------------------------------------------------------------------------

class TestModelHotSwap:

    def test_scoring_continues_across_a_retrain(self, tmp_path):
        """Retraining rebinds the pipeline while requests are being scored.

        A reader must see either the old fitted model or the new one, never a
        half-fitted mix and never None.
        """
        original = (config.IF_MODEL_PATH, config.METADATA_PATH, config.IF_SIGNATURE_PATH)
        config.IF_MODEL_PATH = tmp_path / "model.joblib"
        config.METADATA_PATH = tmp_path / "metadata.json"
        config.IF_SIGNATURE_PATH = tmp_path / "model.joblib.sig"
        try:
            model = IsolationForestModel()
            frame = pd.DataFrame(workloads.normal_frame(400))
            readings = workloads.history_window(64)
            stop = threading.Event()
            failures = []

            def retrain():
                while not stop.is_set():
                    try:
                        model.train(frame, is_synthetic=False)
                    except Exception as exc:  # pragma: no cover
                        failures.append(exc)
                        return

            trainer = threading.Thread(target=retrain, daemon=True)
            trainer.start()
            try:
                def probe(index: int) -> None:
                    result = model.evaluate(readings[index % len(readings)])
                    # Never a confident-looking 0.0 from an absent model: that
                    # is the failure mode this whole area was rebuilt around.
                    assert result.available is True
                    assert 0.0 <= result.score <= 1.0

                _drive(probe, threads=6, per_thread=100)
            finally:
                stop.set()
                trainer.join(timeout=30)

            assert not failures
        finally:
            config.IF_MODEL_PATH, config.METADATA_PATH, config.IF_SIGNATURE_PATH = original


# ---------------------------------------------------------------------------
# HTTP surface
# ---------------------------------------------------------------------------

class TestHttpUnderLoad:

    def test_concurrent_requests_all_succeed(self):
        """The admission limiter must queue requests, not fail them, at a
        concurrency the service is sized for."""
        from fastapi.testclient import TestClient

        import main

        client = TestClient(main.app)
        payload = {"deviceUid": "DEV-1", "temperature": 25.0,
                   "humidity": 50.0, "pressure": 1013.25}
        statuses = []
        lock = threading.Lock()

        def call(_index: int) -> None:
            response = client.post("/api/ai/predict-anomaly", json=payload)
            with lock:
                statuses.append(response.status_code)

        _drive(call, threads=8, per_thread=12)

        assert statuses
        # 503 is a correct answer under saturation, so it is allowed; anything
        # else is not.
        assert set(statuses) <= {200, 503}
        assert statuses.count(200) > 0

    def test_metrics_endpoint_is_safe_while_requests_are_served(self):
        """Prometheus scrapes on its own thread while the registry is written."""
        from fastapi.testclient import TestClient

        import main

        client = TestClient(main.app)
        payload = {"deviceUid": "DEV-1", "temperature": 25.0,
                   "humidity": 50.0, "pressure": 1013.25}

        def call(index: int) -> None:
            if index % 4 == 0:
                assert client.get("/metrics").status_code == 200
            else:
                client.post("/api/ai/predict-anomaly", json=payload)

        _drive(call, threads=6, per_thread=10)


# ---------------------------------------------------------------------------
# Robustness
# ---------------------------------------------------------------------------

class TestMalformedInputRobustness:
    """The broker path takes whatever a device sent. None of it may raise out
    of the processor: an uncaught exception there dead-letters a message that a
    guard should simply have dropped."""

    @pytest.mark.parametrize("message", [
        {},
        {"deviceUid": "DEV-1"},
        {"deviceUid": "DEV-1", "temperature": None, "humidity": 1, "pressure": 1},
        {"deviceUid": "DEV-1", "temperature": "warm", "humidity": 1, "pressure": 1},
        {"deviceUid": "DEV-1", "temperature": [], "humidity": 1, "pressure": 1},
        {"deviceUid": "DEV-1", "temperature": {"n": 1}, "humidity": 1, "pressure": 1},
        {"deviceUid": "DEV-1", "temperature": 1e308 * 10, "humidity": 1, "pressure": 1},
        {"deviceUid": "DEV-1", "temperature": "NaN", "humidity": 1, "pressure": 1},
        {"deviceUid": 42, "temperature": 1, "humidity": 1, "pressure": 1},
        {"deviceUid": "x" * 5000, "temperature": 1, "humidity": 1, "pressure": 1},
        {"deviceUid": "DEV-1", "temperature": 1, "humidity": 1, "pressure": 1,
         "recordedAt": "not-a-timestamp"},
        {"deviceUid": "DEV-1", "temperature": 1, "humidity": 1, "pressure": 1,
         "recordedAt": None},
    ])
    def test_malformed_messages_never_raise(self, message):
        processor = StreamProcessor()
        assert isinstance(processor.handle_reading(message), list)

    def test_a_very_deep_history_does_not_break_scoring(self):
        """The window is capped, but a caller can still hand in more."""
        from ml_service import orchestrator

        history = workloads.history_window(2000)
        result = orchestrator.analyze_with_forecast(
            "DEV-1", history[-1], history, {"temperature": [22.0]})
        assert result.anomaly.score >= 0.0
