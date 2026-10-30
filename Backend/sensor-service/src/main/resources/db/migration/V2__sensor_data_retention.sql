-- Retention and roll-up support for sensor_data.
--
-- Telemetry grows without bound: 5 stations reporting every 60s is ~2.6M rows
-- per year, and the fleet is meant to grow. Rather than let the hot table grow
-- forever, raw readings are aggregated into hourly buckets and then pruned.
--
-- Analytics keeps its own daily_statistics in analytics_db; this roll-up exists
-- so sensor-service can still answer long-range history queries cheaply after
-- the raw rows have aged out.

-- ---------------------------------------------------------------------------
-- sensor_data_hourly — one row per device per hour
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sensor_data_hourly (
    id               BIGSERIAL PRIMARY KEY,
    device_uid       VARCHAR(100) NOT NULL,
    bucket_start     TIMESTAMP    NOT NULL,
    reading_count    INTEGER      NOT NULL,
    avg_temperature  NUMERIC(6, 2),
    min_temperature  NUMERIC(6, 2),
    max_temperature  NUMERIC(6, 2),
    avg_humidity     NUMERIC(6, 2),
    min_humidity     NUMERIC(6, 2),
    max_humidity     NUMERIC(6, 2),
    avg_air_quality  NUMERIC(8, 2),
    min_air_quality  INTEGER,
    max_air_quality  INTEGER,
    avg_pressure     NUMERIC(8, 2),
    min_pressure     NUMERIC(8, 2),
    max_pressure     NUMERIC(8, 2),
    avg_oxygen       NUMERIC(6, 2),
    rain_minutes     INTEGER,
    CONSTRAINT uk_sensor_hourly_device_bucket UNIQUE (device_uid, bucket_start)
);

CREATE INDEX IF NOT EXISTS idx_sensor_hourly_device_bucket
    ON sensor_data_hourly (device_uid, bucket_start DESC);

-- ---------------------------------------------------------------------------
-- Roll-up: fold completed hours into the summary table.
--
-- Idempotent — re-running for the same window updates rather than duplicates,
-- so a retried or overlapping schedule is harmless.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION rollup_sensor_data_hourly(p_older_than_hours INTEGER DEFAULT 2)
RETURNS INTEGER AS $$
DECLARE
    affected INTEGER;
BEGIN
    INSERT INTO sensor_data_hourly (
        device_uid, bucket_start, reading_count,
        avg_temperature, min_temperature, max_temperature,
        avg_humidity, min_humidity, max_humidity,
        avg_air_quality, min_air_quality, max_air_quality,
        avg_pressure, min_pressure, max_pressure,
        avg_oxygen, rain_minutes
    )
    SELECT
        device_uid,
        date_trunc('hour', recorded_at)          AS bucket_start,
        COUNT(*)                                  AS reading_count,
        AVG(temperature), MIN(temperature), MAX(temperature),
        AVG(humidity),    MIN(humidity),    MAX(humidity),
        AVG(air_quality), MIN(air_quality), MAX(air_quality),
        AVG(pressure),    MIN(pressure),    MAX(pressure),
        AVG(oxygen),
        COUNT(*) FILTER (WHERE rain)              AS rain_minutes
    FROM sensor_data
    WHERE recorded_at < date_trunc('hour', NOW() - (p_older_than_hours || ' hours')::INTERVAL)
    GROUP BY device_uid, date_trunc('hour', recorded_at)
    ON CONFLICT (device_uid, bucket_start) DO UPDATE SET
        reading_count   = EXCLUDED.reading_count,
        avg_temperature = EXCLUDED.avg_temperature,
        min_temperature = EXCLUDED.min_temperature,
        max_temperature = EXCLUDED.max_temperature,
        avg_humidity    = EXCLUDED.avg_humidity,
        min_humidity    = EXCLUDED.min_humidity,
        max_humidity    = EXCLUDED.max_humidity,
        avg_air_quality = EXCLUDED.avg_air_quality,
        min_air_quality = EXCLUDED.min_air_quality,
        max_air_quality = EXCLUDED.max_air_quality,
        avg_pressure    = EXCLUDED.avg_pressure,
        min_pressure    = EXCLUDED.min_pressure,
        max_pressure    = EXCLUDED.max_pressure,
        avg_oxygen      = EXCLUDED.avg_oxygen,
        rain_minutes    = EXCLUDED.rain_minutes;

    GET DIAGNOSTICS affected = ROW_COUNT;
    RETURN affected;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- Prune: drop raw readings that have already been rolled up.
--
-- Refuses to delete an hour that has no summary row, so a failed roll-up can
-- never silently destroy data.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION prune_sensor_data(p_retain_days INTEGER DEFAULT 90)
RETURNS INTEGER AS $$
DECLARE
    deleted INTEGER;
BEGIN
    DELETE FROM sensor_data sd
    WHERE sd.recorded_at < NOW() - (p_retain_days || ' days')::INTERVAL
      AND EXISTS (
          SELECT 1 FROM sensor_data_hourly h
          WHERE h.device_uid = sd.device_uid
            AND h.bucket_start = date_trunc('hour', sd.recorded_at)
      );

    GET DIAGNOSTICS deleted = ROW_COUNT;
    RETURN deleted;
END;
$$ LANGUAGE plpgsql;
