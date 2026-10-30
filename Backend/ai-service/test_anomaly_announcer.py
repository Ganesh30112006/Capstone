"""The bus carries conditions, not repetitions of them.

A station reporting once a second that drifts for an hour produces 3,600
detections of one condition. Every one of them used to become an event on the
bus, an alert-service evaluation and two log lines, all describing the state
the first event already described. These tests pin the rule that replaced that:
announce what is new, re-assert what is standing, and never let silence be
mistaken for health.
"""
import pytest

from anomaly_announcer import AnomalyAnnouncer


def test_announces_a_new_condition():
    announcer = AnomalyAnnouncer(reassert_seconds=300)

    assert announcer.should_announce("ESP32-CHN-002", "temperature", "HIGH", now=0.0)


def test_suppresses_the_same_condition_on_every_later_reading():
    announcer = AnomalyAnnouncer(reassert_seconds=300)
    announcer.should_announce("ESP32-CHN-002", "temperature", "HIGH", now=0.0)

    # One reading a second for the next minute, all of them anomalous.
    announced = [
        announcer.should_announce("ESP32-CHN-002", "temperature", "HIGH", now=float(t))
        for t in range(1, 61)
    ]

    assert not any(announced)


def test_announces_again_when_the_condition_gets_worse():
    announcer = AnomalyAnnouncer(reassert_seconds=300)
    announcer.should_announce("ESP32-CHN-002", "temperature", "MEDIUM", now=0.0)

    # Getting worse is news, even a second later.
    assert announcer.should_announce("ESP32-CHN-002", "temperature", "HIGH", now=1.0)
    assert announcer.should_announce("ESP32-CHN-002", "temperature", "CRITICAL", now=2.0)


def test_does_not_announce_a_condition_easing_off():
    # The score is continuous and the severity bands are thresholds across it,
    # so a condition sitting on a boundary flaps HIGH, MEDIUM, HIGH between
    # consecutive readings. Announcing each flap re-announced half of them.
    announcer = AnomalyAnnouncer(reassert_seconds=300)
    announcer.should_announce("ESP32-CHN-002", "temperature", "HIGH", now=0.0)

    assert not announcer.should_announce("ESP32-CHN-002", "temperature", "MEDIUM", now=1.0)
    assert not announcer.should_announce("ESP32-CHN-002", "temperature", "LOW", now=2.0)
    # ...and flapping back up to where it already stood is not news either.
    assert not announcer.should_announce("ESP32-CHN-002", "temperature", "HIGH", now=3.0)


def test_an_unrecognised_severity_is_not_ranked_as_mild():
    # Better to announce something unclassifiable than to file it under LOW and
    # suppress it against a severity nobody chose.
    announcer = AnomalyAnnouncer(reassert_seconds=300)
    announcer.should_announce("ESP32-CHN-002", "temperature", "HIGH", now=0.0)

    assert announcer.should_announce("ESP32-CHN-002", "temperature", "MELTDOWN", now=1.0)


def test_re_asserts_a_standing_condition_on_the_interval():
    # Suppression is not silence. A consumer that started late or lost its queue
    # has to be able to learn that the condition exists.
    announcer = AnomalyAnnouncer(reassert_seconds=300)
    announcer.should_announce("ESP32-CHN-002", "temperature", "HIGH", now=0.0)

    assert not announcer.should_announce("ESP32-CHN-002", "temperature", "HIGH", now=299.0)
    assert announcer.should_announce("ESP32-CHN-002", "temperature", "HIGH", now=300.0)


def test_each_sensor_on_a_device_stands_on_its_own():
    announcer = AnomalyAnnouncer(reassert_seconds=300)
    announcer.should_announce("ESP32-CHN-002", "temperature", "HIGH", now=0.0)

    # A second sensor going bad is a second condition, not a repeat of the first.
    assert announcer.should_announce("ESP32-CHN-002", "airQuality", "HIGH", now=1.0)


def test_each_device_stands_on_its_own():
    announcer = AnomalyAnnouncer(reassert_seconds=300)
    announcer.should_announce("ESP32-CHN-002", "temperature", "HIGH", now=0.0)

    assert announcer.should_announce("ESP32-CHN-003", "temperature", "HIGH", now=1.0)


def test_a_clean_reading_clears_the_device():
    announcer = AnomalyAnnouncer(reassert_seconds=300)
    announcer.should_announce("ESP32-CHN-002", "temperature", "HIGH", now=0.0)

    announcer.clear_device("ESP32-CHN-002")

    # Recovered and failed again is a new condition, announced at once rather
    # than waiting out the rest of the interval.
    assert announcer.should_announce("ESP32-CHN-002", "temperature", "HIGH", now=2.0)


def test_clearing_one_device_leaves_the_others_standing():
    announcer = AnomalyAnnouncer(reassert_seconds=300)
    announcer.should_announce("ESP32-CHN-002", "temperature", "HIGH", now=0.0)
    announcer.should_announce("ESP32-CHN-003", "temperature", "HIGH", now=0.0)

    announcer.clear_device("ESP32-CHN-002")

    assert not announcer.should_announce("ESP32-CHN-003", "temperature", "HIGH", now=1.0)


@pytest.mark.parametrize("disabled", [0, -1])
def test_zero_disables_suppression_entirely(disabled):
    # The escape hatch: an operator who wants every detection on the bus sets
    # the interval to zero and gets exactly the old behaviour back.
    announcer = AnomalyAnnouncer(reassert_seconds=disabled)

    assert all(
        announcer.should_announce("ESP32-CHN-002", "temperature", "HIGH", now=float(t))
        for t in range(10)
    )
