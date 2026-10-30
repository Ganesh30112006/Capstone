-- Baseline schema for analytics_db.
--
-- Analytics aggregates on the way in rather than on the way out: each telemetry
-- event folds into a running daily bucket, so a 30-day report is a handful of
-- row reads instead of a scan over millions of raw readings.

-- ---------------------------------------------------------------------------
-- daily_statistics — running per-device, per-day aggregates
--
-- Sums (not averages) are stored so a new reading can be folded in with a
-- single update; the mean is derived at read time as sum / reading_count.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS daily_statistics (
    id                BIGSERIAL PRIMARY KEY,
    device_uid        VARCHAR(100)   NOT NULL,
    stat_date         DATE           NOT NULL,
    sum_temperature   NUMERIC(12, 2) DEFAULT 0,
    min_temperature   NUMERIC(5, 2),
    max_temperature   NUMERIC(5, 2),
    sum_humidity      NUMERIC(12, 2) DEFAULT 0,
    min_humidity      NUMERIC(5, 2),
    max_humidity      NUMERIC(5, 2),
    sum_air_quality   BIGINT         DEFAULT 0,
    min_air_quality   INTEGER,
    max_air_quality   INTEGER,
    sum_pressure      NUMERIC(14, 2) DEFAULT 0,
    min_pressure      NUMERIC(7, 2),
    max_pressure      NUMERIC(7, 2),
    sum_oxygen        NUMERIC(12, 2) DEFAULT 0,
    min_oxygen        NUMERIC(5, 2),
    max_oxygen        NUMERIC(5, 2),
    rain_events_count INTEGER        DEFAULT 0,
    reading_count     INTEGER        NOT NULL DEFAULT 0,
    created_at        TIMESTAMP,
    updated_at        TIMESTAMP,
    CONSTRAINT uk_daily_stats_device_date UNIQUE (device_uid, stat_date)
);

-- Report ranges are always "one device, a span of days", newest first.
CREATE INDEX IF NOT EXISTS idx_daily_stats_device_date ON daily_statistics (device_uid, stat_date DESC);
-- Fleet reports slice by date across every device.
CREATE INDEX IF NOT EXISTS idx_daily_stats_date        ON daily_statistics (stat_date);

-- ---------------------------------------------------------------------------
-- device_location_snapshots — latest position + reading per device
--
-- One row per device, overwritten on each reading. This is what backs the
-- heatmap: rendering it never touches the raw telemetry table.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS device_location_snapshots (
    id                BIGSERIAL PRIMARY KEY,
    device_uid        VARCHAR(100)     NOT NULL UNIQUE,
    latitude          DOUBLE PRECISION NOT NULL,
    longitude         DOUBLE PRECISION NOT NULL,
    latest_temperature NUMERIC(5, 2),
    latest_humidity    NUMERIC(5, 2),
    latest_air_quality INTEGER,
    latest_pressure    NUMERIC(7, 2),
    latest_oxygen      NUMERIC(5, 2),
    last_updated_at    TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_location_snapshots_coords
    ON device_location_snapshots (latitude, longitude);
