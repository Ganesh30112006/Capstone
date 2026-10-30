"""Forecast receipts on disk, so the AI's scorecard outlives a restart.

The ledger (forecast_ledger.py) keeps its running scores in memory. Without
this, every restart of the AI service wiped the evidence and the public page
started again from "0 forecasts checked" -- a scorecard that resets whenever
it is inconvenient is not a scorecard.

Three properties matter more than throughput here:

* The stream never waits on the database. Receipts go into a bounded queue
  and a background thread writes them in batches. If the database is slow or
  down, receipts wait; past the queue's bound the oldest are dropped and
  counted, never silently.
* Order is kept. A failed batch goes back to the front of the queue.
* The page can tell the difference. `status()` says whether receipts are
  being kept, how many are waiting and how many were lost, so the public page
  can say "since the service last started" instead of implying a durable
  record it does not have.

The service owns its own database (ai_db), like every other service in the
platform. It is created on first start if the role may create databases.
"""

from __future__ import annotations

import logging
import threading
import time
from collections import deque
from datetime import datetime, timedelta, timezone
from typing import Deque, List, Optional

import config

logger = logging.getLogger(__name__)

try:  # The driver is optional: without it the ledger simply stays in memory.
    import psycopg
    from psycopg import sql
except ImportError:  # pragma: no cover - depends on the environment
    psycopg = None
    sql = None

SCHEMA = """
CREATE TABLE IF NOT EXISTS forecast_receipts (
    id                   BIGSERIAL PRIMARY KEY,
    recorded_at          TIMESTAMPTZ      NOT NULL,
    issued_at            TIMESTAMPTZ,
    device_uid           TEXT             NOT NULL,
    method               TEXT             NOT NULL,
    metric               TEXT             NOT NULL,
    step                 SMALLINT         NOT NULL,
    regime               TEXT             NOT NULL,
    actual               DOUBLE PRECISION NOT NULL,
    forecast             DOUBLE PRECISION NOT NULL,
    persistence_at_issue DOUBLE PRECISION NOT NULL,
    latest_value         DOUBLE PRECISION,
    trend_at_issue       DOUBLE PRECISION,
    daily_guess          DOUBLE PRECISION,
    stored_at            TIMESTAMPTZ      NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS forecast_receipts_recorded_at ON forecast_receipts (recorded_at);
CREATE INDEX IF NOT EXISTS forecast_receipts_device_metric ON forecast_receipts (device_uid, metric, recorded_at);
"""

INSERT = """
INSERT INTO forecast_receipts (
    recorded_at, issued_at, device_uid, method, metric, step, regime,
    actual, forecast, persistence_at_issue, latest_value, trend_at_issue, daily_guess
) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
"""

SELECT_SINCE = """
SELECT recorded_at, issued_at, device_uid, method, metric, step, regime,
       actual, forecast, persistence_at_issue, latest_value, trend_at_issue, daily_guess
FROM forecast_receipts
WHERE recorded_at >= %s
ORDER BY recorded_at, id
LIMIT %s
"""

BATCH = 2000
FLUSH_SECONDS = 2.0


def _when(value: str) -> Optional[datetime]:
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def _iso(value: Optional[datetime]) -> str:
    if value is None:
        return ""
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def _describe(exc: Exception) -> str:
    """One line, no connection string: driver messages are safe, URLs are not."""
    return (str(exc).strip().splitlines() or [type(exc).__name__])[0][:200]


def _to_params(row: dict) -> tuple:
    return (
        _when(row["recordedAt"]), _when(row.get("issuedAt") or ""), row["deviceUid"], row["method"],
        row["metric"], int(row["step"]), row["regime"], row["actual"], row["forecast"],
        row["persistenceAtIssue"], row.get("latestValue"), row.get("trendAtIssue"), row.get("dailyGuess"),
    )


def _from_record(record: tuple) -> dict:
    (recorded_at, issued_at, device, method, metric, step, regime,
     actual, forecast, issue, latest, trend, daily) = record
    return {
        "recordedAt": _iso(recorded_at), "issuedAt": _iso(issued_at), "deviceUid": device,
        "method": method, "metric": metric, "step": int(step), "regime": regime,
        "actual": actual, "forecast": forecast, "persistenceAtIssue": issue,
        "latestValue": latest, "trendAtIssue": trend, "dailyGuess": daily,
    }


class ReceiptStore:
    def __init__(self, url: str = config.AI_DB_URL, queue_max: int = config.AI_LEDGER_WRITE_QUEUE):
        self._url = url
        self._queue: Deque[dict] = deque()
        self._queue_max = max(1, int(queue_max))
        self._lock = threading.Lock()
        self._wake = threading.Event()
        self._stop = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._state = "off" if not url or psycopg is None else "connecting"
        self._last_error: Optional[str] = None if psycopg is not None else "database driver (psycopg) not installed"
        self._written = 0
        self._dropped = 0

    @property
    def enabled(self) -> bool:
        return bool(self._url) and psycopg is not None

    # ------------------------------------------------------------- lifecycle

    def _ensure_database(self) -> None:
        """Creates ai_db on first run, when the role is allowed to."""
        base, _, name = self._url.rpartition("/")
        name = name.split("?")[0]
        try:
            with psycopg.connect(self._url, connect_timeout=5):
                return
        except psycopg.OperationalError as exc:
            if "does not exist" not in str(exc):
                raise
        with psycopg.connect(f"{base}/postgres", connect_timeout=5, autocommit=True) as conn:
            conn.execute(sql.SQL("CREATE DATABASE {}").format(sql.Identifier(name)))
        logger.info("Created database %s for forecast receipts", name)

    def start(self, replay_days: float = config.AI_LEDGER_REPLAY_DAYS, replay_limit: int = 2_000_000) -> List[dict]:
        """Prepares the table, returns the receipts to replay, and starts writing.

        Never raises: a database that cannot be reached leaves the store in the
        'failed' state, the writer keeps retrying, and the ledger runs from memory.
        """
        if not self.enabled:
            return []
        rows: List[dict] = []
        try:
            self._ensure_database()
            with psycopg.connect(self._url, connect_timeout=5) as conn:
                conn.execute(SCHEMA)
                since = datetime.now(timezone.utc) - timedelta(days=replay_days)
                rows = [_from_record(r) for r in conn.execute(SELECT_SINCE, (since, replay_limit)).fetchall()]
            self._state, self._last_error = "ok", None
        except Exception as exc:
            self._state, self._last_error = "failed", _describe(exc)
            logger.warning("Forecast receipts will not be kept across restarts: %s", self._last_error)
        self._thread = threading.Thread(target=self._run, name="receipt-writer", daemon=True)
        self._thread.start()
        return rows

    def stop(self, timeout: float = 5.0) -> None:
        self._stop.set()
        self._wake.set()
        if self._thread is not None:
            self._thread.join(timeout)

    # ------------------------------------------------------------- writing

    def enqueue(self, rows: List[dict]) -> None:
        if not self.enabled:
            return
        with self._lock:
            self._queue.extend(rows)
            while len(self._queue) > self._queue_max:
                self._queue.popleft()
                self._dropped += 1
        if len(self._queue) >= BATCH:
            self._wake.set()

    def _take(self) -> List[dict]:
        with self._lock:
            return [self._queue.popleft() for _ in range(min(BATCH, len(self._queue)))]

    def _put_back(self, batch: List[dict]) -> None:
        with self._lock:
            self._queue.extendleft(reversed(batch))
            while len(self._queue) > self._queue_max:
                self._queue.pop()  # the newest go when the backlog is full of older evidence
                self._dropped += 1

    def flush(self, conn) -> int:
        written = 0
        while True:
            batch = self._take()
            if not batch:
                return written
            try:
                with conn.cursor() as cur:
                    cur.executemany(INSERT, [_to_params(r) for r in batch])
                conn.commit()
            except Exception:
                self._put_back(batch)
                raise
            written += len(batch)
            self._written += len(batch)

    def _run(self) -> None:
        conn = None
        backoff = 1.0
        while not self._stop.is_set() or self._queue:
            self._wake.wait(FLUSH_SECONDS)
            self._wake.clear()
            if not self._queue:
                continue
            try:
                if conn is None or conn.closed:
                    conn = psycopg.connect(self._url, connect_timeout=5)
                    conn.execute(SCHEMA)
                    conn.commit()
                self.flush(conn)
                self._state, self._last_error = "ok", None
                backoff = 1.0
            except Exception as exc:
                self._state, self._last_error = "failed", _describe(exc)
                if conn is not None:
                    try:
                        conn.close()
                    except Exception:
                        pass
                conn = None
                if self._stop.is_set():
                    break
                time.sleep(min(backoff, 30.0))
                backoff *= 2
        if conn is not None:
            conn.close()

    # ------------------------------------------------------------- reading

    def export(self, days: float, limit: int) -> Optional[List[dict]]:
        """Stored receipts from the last `days`, or None when the store cannot answer."""
        if not self.enabled:
            return None
        try:
            with psycopg.connect(self._url, connect_timeout=5) as conn:
                since = datetime.now(timezone.utc) - timedelta(days=days)
                return [_from_record(r) for r in conn.execute(SELECT_SINCE, (since, limit)).fetchall()]
        except Exception as exc:
            logger.warning("Receipt export from the database failed: %s", _describe(exc))
            return None

    def status(self) -> dict:
        with self._lock:
            pending = len(self._queue)
        return {
            "enabled": self.enabled,
            "state": self._state,
            "written": self._written,
            "pending": pending,
            "dropped": self._dropped,
            "lastError": self._last_error,
        }


receipt_store = ReceiptStore()
