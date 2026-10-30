-- Baseline schema for sensor_db.
--
-- This is the platform's highest-volume table: one row per station per
-- reporting interval, forever. Every index here exists because a specific read
-- path needs it, and the retention policy below keeps the hot table bounded.

-- ---------------------------------------------------------------------------
-- sensor_data — raw environmental telemetry
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sensor_data (
    id          BIGSERIAL PRIMARY KEY,
    -- Producer-assigned ID from the MQTT payload. UNIQUE is the idempotency
    -- guarantee: an SQS redelivery or RabbitMQ retry cannot double-insert.
    message_id  VARCHAR(100) UNIQUE,
    device_uid  VARCHAR(100) NOT NULL,
    temperature NUMERIC(5, 2) NOT NULL,
    humidity    NUMERIC(5, 2) NOT NULL,
    air_quality INTEGER       NOT NULL,
    pressure    NUMERIC(7, 2) NOT NULL,
    rain        BOOLEAN       NOT NULL,
    oxygen      NUMERIC(5, 2),
    latitude    DOUBLE PRECISION,
    longitude   DOUBLE PRECISION,
    -- Always UTC. Ingestion normalises the device's offset timestamp before publishing.
    recorded_at TIMESTAMP     NOT NULL,
    created_at  TIMESTAMP     NOT NULL
);

-- Serves latest-reading, paged history, and the failure detector's
-- recent-window lookup — all of which are "one device, newest first".
CREATE INDEX IF NOT EXISTS idx_sensor_data_device_recorded
    ON sensor_data (device_uid, recorded_at DESC);

-- Serves fleet-wide time-range scans (network trend, heatmap, retention sweep).
CREATE INDEX IF NOT EXISTS idx_sensor_data_recorded_at
    ON sensor_data (recorded_at);

-- ---------------------------------------------------------------------------
-- sensor_health — latest deterministic health verdict per sensor per device
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sensor_health (
    id              BIGSERIAL PRIMARY KEY,
    device_uid      VARCHAR(100) NOT NULL,
    sensor_type     VARCHAR(50)  NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    details         VARCHAR(255),
    last_checked_at TIMESTAMP,
    CONSTRAINT uk_sensor_health_device_type UNIQUE (device_uid, sensor_type)
);

CREATE INDEX IF NOT EXISTS idx_sensor_health_status ON sensor_health (status);
