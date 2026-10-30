-- Baseline schema for alert_db.

-- ---------------------------------------------------------------------------
-- alert_rules — operator-configured thresholds per device and metric
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS alert_rules (
    id             BIGSERIAL PRIMARY KEY,
    device_uid     VARCHAR(100)   NOT NULL,
    metric_type    VARCHAR(20)    NOT NULL,
    condition_type VARCHAR(20)    NOT NULL,
    threshold      NUMERIC(10, 2) NOT NULL,
    is_active      BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMP
);

-- Every incoming reading looks up the active rules for its device: hottest path
-- in the service.
CREATE INDEX IF NOT EXISTS idx_alert_rules_device_active
    ON alert_rules (device_uid, is_active);

-- ---------------------------------------------------------------------------
-- alerts — triggered alert instances
--
-- Deduplicated rather than append-only: a station sitting above a threshold
-- reports every minute, and one row per reading would bury the operator. The
-- dedupe_key identifies "the same problem"; recurrences bump occurrence_count.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS alerts (
    id               BIGSERIAL PRIMARY KEY,
    device_uid       VARCHAR(100)   NOT NULL,
    rule_id          BIGINT         NOT NULL,
    metric_type      VARCHAR(20)    NOT NULL,
    severity         VARCHAR(20)    NOT NULL DEFAULT 'WARNING',
    message          VARCHAR(255)   NOT NULL,
    trigger_value    NUMERIC(10, 2) NOT NULL,
    status           VARCHAR(20)    NOT NULL,

    dedupe_key       VARCHAR(200)   NOT NULL,
    occurrence_count INTEGER        NOT NULL DEFAULT 1,
    last_triggered_at TIMESTAMP,

    -- AI anomaly detail, populated only for metric_type = 'ANOMALY'.
    sensor_type      VARCHAR(50),
    -- Anomaly score in [0,1]. A score, not a calibrated confidence.
    anomaly_score    NUMERIC(5, 4),
    expected_value   NUMERIC(10, 2),
    model_name       VARCHAR(100),
    model_version    VARCHAR(50),

    triggered_at     TIMESTAMP      NOT NULL,
    resolved_at      TIMESTAMP
);

-- The alert centre lists newest-first, filtered by device and/or status.
CREATE INDEX IF NOT EXISTS idx_alerts_device_triggered ON alerts (device_uid, triggered_at DESC);
CREATE INDEX IF NOT EXISTS idx_alerts_status_triggered ON alerts (status, triggered_at DESC);

-- Deduplication asks "is there already an open alert for this exact condition?"
-- on every evaluated reading, so it must be an index lookup, not a scan.
CREATE INDEX IF NOT EXISTS idx_alerts_dedupe ON alerts (dedupe_key, status);
