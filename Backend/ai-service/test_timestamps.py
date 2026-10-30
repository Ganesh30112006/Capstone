"""Timestamps arriving from a polyglot bus.

The Java services publish `LocalDateTime` values, and Jackson's default wire
form for those is a numeric array rather than a string. Both consumers in this
service assumed a string. `rabbitmq_consumer` called `.replace()` on a list and
raised AttributeError, dead-lettering every reading it scored; `stream_processor`
stringified the list, failed to parse it, and silently substituted the current
time -- which is worse, because it gave stale readings a fresh-looking age that
nothing downstream could distinguish from a real one.
"""
from datetime import datetime, timezone

import pytest

from timestamps import to_utc_iso


def test_reads_the_java_localdatetime_array_form():
    # [year, month, day, hour, minute, second, nanosecond] -- what Jackson emits
    # for a LocalDateTime by default, and what crashed the scorer.
    assert to_utc_iso([2026, 9, 16, 8, 6, 32, 695122000]) == "2026-09-16T08:06:32.695122+00:00"


def test_reads_a_short_array_without_time_parts():
    assert to_utc_iso([2026, 9, 16]) == "2026-09-16T00:00:00+00:00"


def test_reads_an_offset_bearing_iso_string():
    assert to_utc_iso("2026-09-16T08:07:21.897832+00:00") == "2026-09-16T08:07:21.897832+00:00"


def test_converts_a_non_zero_offset_rather_than_trimming_it():
    # 13:37+05:30 is 08:07 UTC. Trimming would misfile it by the offset.
    assert to_utc_iso("2026-09-16T13:37:21+05:30") == "2026-09-16T08:07:21+00:00"


def test_treats_a_naive_string_as_utc():
    # The platform's convention: a bare timestamp on this bus is already UTC.
    assert to_utc_iso("2026-09-16T08:07:21") == "2026-09-16T08:07:21+00:00"


def test_reads_zulu_time():
    assert to_utc_iso("2026-09-16T08:07:21Z") == "2026-09-16T08:07:21+00:00"


def test_reads_epoch_seconds_and_milliseconds():
    assert to_utc_iso(1789516800) == "2026-09-16T00:00:00+00:00"
    assert to_utc_iso(1789516800000) == "2026-09-16T00:00:00+00:00"


def test_passes_a_datetime_through_as_utc():
    naive = datetime(2026, 9, 16, 8, 7, 21)
    assert to_utc_iso(naive) == "2026-09-16T08:07:21+00:00"
    aware = datetime(2026, 9, 16, 8, 7, 21, tzinfo=timezone.utc)
    assert to_utc_iso(aware) == "2026-09-16T08:07:21+00:00"


@pytest.mark.parametrize("raw", [None, "", "   ", "yesterday", [], [2026], {"year": 2026}, object()])
def test_returns_none_when_the_time_is_not_knowable(raw):
    # None, never a substituted now(): the caller has to decide what an unknown
    # timestamp means, and it must be able to tell that it is unknown.
    assert to_utc_iso(raw) is None


def test_does_not_raise_on_a_malformed_array():
    assert to_utc_iso([2026, 99, 99, 99, 99]) is None
    assert to_utc_iso(["a", "b", "c"]) is None
