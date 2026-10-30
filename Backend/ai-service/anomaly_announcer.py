"""Decides whether a detected anomaly is news.

Shared by both scoring paths — the live stream worker and the batch worker —
because the question is about the device, not about which worker noticed. An
announcement by one suppresses the repeat from the other.
"""
import threading
import time

import config

ANOMALY_REASSERT_SECONDS = int(getattr(config, "AI_ANOMALY_REASSERT_SECONDS", 300))


class AnomalyAnnouncer:
    """Decides whether a detected anomaly is news.

    The detector runs on every reading and should: each reading either is or is
    not anomalous, and the counter in `metrics` records all of them. The bus is
    a different question. A station reporting once a second that is drifting for
    an hour produces 3,600 detections of one condition, and every one of them
    became an event, an alert-service evaluation and two log lines — describing
    the state that the first event already described.

    So an anomaly is announced when it is new, when its severity changes, and
    then on a slow re-assert. The re-assert matters: a consumer that started
    late, restarted, or lost its queue must still learn that the condition
    exists, and must never infer that a station is well from the absence of
    events.

    A reading that scores clean clears the device, so the next anomaly after a
    recovery is announced immediately rather than waiting out the interval.

    Escalation is announced; de-escalation is not. The score is continuous and
    the severity bands are thresholds across it, so a condition sitting near a
    boundary flaps — HIGH, MEDIUM, HIGH — on consecutive readings. Treating
    every change as news announced half of them again. Getting worse is a fact a
    consumer has to act on; getting slightly better, while the condition still
    stands, is carried by the next re-assert.
    """

    #: Ordered worst-last, so a severity's position is its rank.
    SEVERITY_ORDER = ("LOW", "MEDIUM", "HIGH", "CRITICAL")

    def __init__(self, reassert_seconds: int = ANOMALY_REASSERT_SECONDS):
        self._reassert = max(0, reassert_seconds)
        self._standing: dict = {}
        self._lock = threading.Lock()

    @classmethod
    def _rank(cls, severity) -> int:
        try:
            return cls.SEVERITY_ORDER.index(str(severity).upper())
        except ValueError:
            # An unrecognised severity is never quietly ranked as mild.
            return len(cls.SEVERITY_ORDER)

    def should_announce(self, device_uid, sensor, severity, now=None) -> bool:
        if self._reassert == 0:
            return True
        moment = now if now is not None else time.monotonic()
        key = (device_uid, sensor)
        rank = self._rank(severity)
        with self._lock:
            standing = self._standing.get(key)
            if standing is not None:
                standing_rank, announced_at = standing
                worse = rank > standing_rank
                due = (moment - announced_at) >= self._reassert
                if not worse and not due:
                    return False
                # A de-escalation that is only being carried by the re-assert
                # must not raise the standing rank back up.
                self._standing[key] = (max(rank, standing_rank) if due and not worse else rank, moment)
                return True
            self._standing[key] = (rank, moment)
            return True

    def clear_device(self, device_uid) -> None:
        """A clean reading: nothing is standing for this device any more."""
        with self._lock:
            for key in [k for k in self._standing if k[0] == device_uid]:
                del self._standing[key]

    def forget(self, device_uid) -> None:
        """Same as clearing, for a device that is no longer being tracked."""
        self.clear_device(device_uid)


anomaly_announcer = AnomalyAnnouncer()
