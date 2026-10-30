"""Regression tests for the service's per-device state tables.

Three components keep a map keyed by device identifier: the stream worker's
rolling history, the flatline detector's learned baselines, and the broker
worker's forecast cache. Device identifiers arrive from the fleet rather than
from a fixed list, so every one of these maps has to answer two questions:

  * **Is it bounded?** An unbounded map grows with the number of distinct
    identifiers ever seen — retired devices, mistyped ones, hostile ones.
  * **When full, what does it drop?** This is the question that was answered
    wrongly. All three evicted in *insertion* order, which drops the device
    that has been reporting longest and keeps the one that sent a single
    reading and vanished.

The second is not a tidiness issue. The flatline detector's state is earned: a
channel needs FLATLINE_WARMUP_RUNS completed runs before it can be judged at
all. Evicting an established station therefore blinds the detector for that
station until it warms up again, and before the fix a single new identifier was
enough to do it.

Each test below names the behaviour it pins rather than the implementation, so
they stay meaningful if the eviction policy changes again.
"""

import logging
import random

import pytest

import config
from flatline_detector import FlatlineDetector
from forecast_cache import ForecastCache

logging.disable(logging.CRITICAL)

READING = {
    "temperature": 20.0,
    "humidity": 50.0,
    "airQuality": 40.0,
    "oxygen": 20.9,
    "pressure": 1013.0,
}


def _moving(rng, base=20.0):
    """A reading whose temperature actually changes, so a baseline can form."""
    return {**READING, "temperature": base + rng.randint(0, 9) * 0.1}


def _warm(detector, device, rng, readings=400):
    """Gives a device enough completed runs to be judged."""
    for _ in range(readings):
        detector.observe(device, _moving(rng))
    return detector._devices[device]["temperature"]


# ---------------------------------------------------------------------------
# Flatline detector table
# ---------------------------------------------------------------------------

def test_flatline_table_is_bounded():
    detector = FlatlineDetector()
    for i in range(config.FLATLINE_MAX_DEVICES * 3):
        detector.observe(f"dev-{i}", READING)
    assert detector.tracked_devices() == config.FLATLINE_MAX_DEVICES


def test_flatline_keeps_the_device_that_is_still_reporting():
    """A continuously active station must not be evicted by one new arrival.

    The regression: the table was insertion-ordered, so the station that had
    been reporting since startup was the *first* candidate for eviction. It
    went as soon as the table was full and one unknown identifier appeared.
    """
    detector = FlatlineDetector()
    detector.observe("station-A", READING)
    for i in range(config.FLATLINE_MAX_DEVICES - 1):
        detector.observe(f"filler-{i}", READING)
    for _ in range(50):
        detector.observe("station-A", READING)

    detector.observe("newcomer", READING)

    assert "station-A" in detector._devices
    assert detector.tracked_devices() == config.FLATLINE_MAX_DEVICES


def test_learned_baseline_survives_a_flood_of_unknown_identifiers():
    """Junk traffic must not blind the detector for a station that keeps talking.

    This is the security-relevant shape of the eviction bug: identifiers are
    attacker-influenceable on the broker path, and under insertion order a
    burst of FLATLINE_MAX_DEVICES invented identifiers wiped every learned
    baseline in the table. Each wiped channel then needed a full warm-up before
    it could raise an anomaly again, so the flood bought a window of blindness.
    """
    rng = random.Random(3)
    detector = FlatlineDetector()
    _warm(detector, "station-A", rng)

    for i in range(config.FLATLINE_MAX_DEVICES * 20):
        detector.observe(f"junk-{i}", READING)
        if i % 5 == 0:
            detector.observe("station-A", _moving(rng))

    assert "station-A" in detector._devices
    state = detector._devices["station-A"]["temperature"]
    assert state.runs_seen >= config.FLATLINE_WARMUP_RUNS, (
        "station kept reporting throughout but lost its baseline")


def test_silent_device_is_the_one_evicted():
    """The complement: LRU should drop whoever stopped talking."""
    detector = FlatlineDetector()
    detector.observe("gone-quiet", READING)
    for i in range(config.FLATLINE_MAX_DEVICES - 1):
        detector.observe(f"filler-{i}", READING)
        detector.observe(f"filler-{i}", READING)

    detector.observe("newcomer", READING)

    assert "gone-quiet" not in detector._devices


# ---------------------------------------------------------------------------
# The absolute-run ceiling
# ---------------------------------------------------------------------------

def test_healthy_long_run_channel_is_not_accused(monkeypatch):
    """A quantised-stable channel must not be accused for being stable.

    A remnant of the removed absolute backstop survived as a *cap* on the
    learned alarm point. It looked harmless, but it bit exactly one population:
    channels whose learned threshold had grown past the cap — which are the
    healthy, heavily quantised ones the learned baseline exists to protect.

    Here a channel completes several runs of 1,400 readings (oxygen's measured
    order of magnitude), so its learned alarm point is 5,600. With the old cap
    of 3,000 it was accused after holding steady for 3,000 readings with
    nothing wrong.
    """
    monkeypatch.setattr(config, "FLATLINE_ABSOLUTE_RUN", 0)
    detector = FlatlineDetector()

    for run_index in range(6):
        for _ in range(1400):
            detector.observe("healthy-o2", {**READING, "oxygen": 20.9 + run_index * 0.1})

    state = detector._devices["healthy-o2"]["oxygen"]
    assert state.longest_normal_run == 1400
    assert state.alarm_at() == 5600

    for _ in range(3500):
        result = detector.observe("healthy-o2", {**READING, "oxygen": 21.4})
        assert not result.is_anomaly, "healthy quantised channel accused of freezing"


def test_ceiling_still_applies_when_an_operator_sets_one(monkeypatch):
    """Disabled by default, but not deleted: a site may still opt in."""
    monkeypatch.setattr(config, "FLATLINE_ABSOLUTE_RUN", 100)
    rng = random.Random(5)
    detector = FlatlineDetector()
    _warm(detector, "capped", rng)

    fired = any(detector.observe("capped", READING).is_anomaly for _ in range(400))
    assert fired


def test_a_real_freeze_is_still_caught_without_the_ceiling(monkeypatch):
    """Removing the cap must cost latency, not detection."""
    monkeypatch.setattr(config, "FLATLINE_ABSOLUTE_RUN", 0)
    rng = random.Random(9)
    detector = FlatlineDetector()
    state = _warm(detector, "freezer", rng)
    alarm = state.alarm_at()

    detected_at = None
    for i in range(alarm * 4):
        if detector.observe("freezer", READING).is_anomaly:
            detected_at = i
            break

    assert detected_at is not None, "a genuine freeze went undetected"
    assert detected_at <= alarm + 2


# ---------------------------------------------------------------------------
# Forecast cache
# ---------------------------------------------------------------------------

def test_forecast_cache_is_bounded():
    cache = ForecastCache(refresh_every=6, max_devices=50)
    for i in range(5000):
        cache.store(f"dev-{i}", {"temperature": [1.0] * 6})
    assert cache.tracked_devices() == 50


def test_forecast_cache_age_table_does_not_leak():
    """`record_reading` is called once per reading, for every device.

    Before the fix it created an age entry whether or not a forecast existed
    for that device, which made the age map a second unbounded table alongside
    the series map it indexes into — and the one that grew fastest, because a
    device accumulates readings long before it has enough history to forecast.
    """
    cache = ForecastCache(refresh_every=6, max_devices=50)
    for i in range(5000):
        cache.record_reading(f"never-forecast-{i}")
    assert len(cache._age) == 0

    cache.store("real", {"temperature": [1.0] * 6})
    assert cache.record_reading("real") == 1
    assert len(cache._age) == 1


def test_forecast_cache_evicts_the_least_recently_used():
    cache = ForecastCache(refresh_every=6, max_devices=3)
    for name in ("a", "b", "c"):
        cache.store(name, {"temperature": [1.0] * 6})
    cache.record_reading("a")          # 'a' is active; 'b' is now the stalest
    cache.store("d", {"temperature": [2.0] * 6})

    assert "a" in cache._series
    assert "b" not in cache._series
    assert cache.tracked_devices() == 3


def test_forecast_cache_forget_clears_both_tables():
    cache = ForecastCache(refresh_every=6, max_devices=10)
    cache.store("x", {"temperature": [1.0] * 6})
    cache.record_reading("x")
    cache.forget("x")
    assert "x" not in cache._series and "x" not in cache._age


# ---------------------------------------------------------------------------
# Stream worker table, and its coupling to the detector
# ---------------------------------------------------------------------------

@pytest.fixture()
def processor():
    import stream_processor as sp
    # Forecasting and reporting are transformer calls and report builds; neither
    # is under test here, so they are pushed out of reach.
    return sp.StreamProcessor(forecast_every=10 ** 9, report_every=10 ** 9)


def test_stream_table_keeps_the_busiest_device(processor):
    import stream_processor as sp
    processor.handle_reading({"deviceUid": "station-A", **READING})
    for i in range(sp.MAX_TRACKED_DEVICES - 1):
        processor.handle_reading({"deviceUid": f"filler-{i}", **READING})
    for _ in range(20):
        processor.handle_reading({"deviceUid": "station-A", **READING})

    processor.handle_reading({"deviceUid": "newcomer", **READING})

    assert "station-A" in processor._devices
    assert processor._devices["station-A"].readings_seen == 21


def test_stream_eviction_releases_the_detector_baseline(processor):
    """The two tables are keyed alike and must not drift apart.

    The flatline detector has no other signal that a device is gone — it is fed
    reading by reading — so without this the baselines of evicted devices sat
    in memory until its own table filled independently.
    """
    import stream_processor as sp
    from flatline_detector import flatline_detector

    processor.handle_reading({"deviceUid": "doomed", **READING})
    assert "doomed" in flatline_detector._devices

    for i in range(sp.MAX_TRACKED_DEVICES + 5):
        processor.handle_reading({"deviceUid": f"f-{i}", **READING})

    assert "doomed" not in processor._devices
    assert "doomed" not in flatline_detector._devices


def test_stream_table_is_bounded(processor):
    import stream_processor as sp
    for i in range(sp.MAX_TRACKED_DEVICES * 2):
        processor.handle_reading({"deviceUid": f"dev-{i}", **READING})
    assert processor.tracked_devices() == sp.MAX_TRACKED_DEVICES
