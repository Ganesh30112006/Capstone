"""Reading timestamps off the message bus, in every shape the bus carries them.

The platform is polyglot and the Java services publish `LocalDateTime` values.
Jackson's default encoding for those is a numeric array --
`[2026, 9, 16, 8, 6, 32, 695122000]` -- not a string. Both consumers here
assumed a string, and each failed differently on the array:
`rabbitmq_consumer` called `.replace()` on a list and crashed, sending every
reading it scored to the dead-letter queue, while `stream_processor` stringified
it, failed to parse, and quietly substituted the current time -- giving readings
a fabricated age that nothing downstream could tell from a real one.

The Java side now emits ISO-8601 strings, but the array form is still accepted
here: messages published before that change are sitting in the dead-letter
queues, and a consumer that can only read the newest wire format cannot be used
to drain them.

`to_utc_iso` returns None when it genuinely cannot tell the time, so the caller
decides what to do about it. That decision belongs at the call site -- it is not
this function's place to invent one.
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Any, Optional


def to_utc_iso(raw: Any) -> Optional[str]:
    """Normalises a bus timestamp to a UTC ISO-8601 string, or None."""
    if raw is None or raw == "":
        return None

    parsed = _to_datetime(raw)
    if parsed is None:
        return None
    if parsed.tzinfo is None:
        # Naive values on this bus are UTC by platform convention.
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc).isoformat()


def _to_datetime(raw: Any) -> Optional[datetime]:
    if isinstance(raw, datetime):
        return raw

    # Jackson's LocalDateTime/LocalDate array form.
    if isinstance(raw, (list, tuple)):
        return _from_java_array(raw)

    if isinstance(raw, (int, float)):
        # Seconds or milliseconds since the epoch.
        seconds = raw / 1000.0 if raw > 100_000_000_000 else float(raw)
        try:
            return datetime.fromtimestamp(seconds, tz=timezone.utc)
        except (OverflowError, OSError, ValueError):
            return None

    if isinstance(raw, str):
        text = raw.strip()
        if not text:
            return None
        try:
            return datetime.fromisoformat(text.replace("Z", "+00:00"))
        except ValueError:
            return None

    return None


def _from_java_array(parts: Any) -> Optional[datetime]:
    """[year, month, day, hour, minute, second, nanosecond] -- trailing parts optional."""
    try:
        values = [int(p) for p in parts]
    except (TypeError, ValueError):
        return None
    if len(values) < 3:
        return None

    values = (values + [0] * 7)[:7]
    year, month, day, hour, minute, second, nanosecond = values
    try:
        base = datetime(year, month, day, hour, minute, tzinfo=timezone.utc)
    except ValueError:
        return None
    # Seconds and nanoseconds are added rather than passed in, so a leap second
    # (second == 60) widens the value instead of raising.
    return base + timedelta(seconds=second, microseconds=nanosecond // 1000)
