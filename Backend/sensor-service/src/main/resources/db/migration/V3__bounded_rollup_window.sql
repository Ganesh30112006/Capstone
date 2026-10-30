-- Bounds the work a roll-up run does.
--
-- rollup_sensor_data_hourly() had no lower bound: every run re-aggregated every
-- raw reading ever stored and re-wrote every hourly bucket. At one reading per
-- station per minute that was survivable. At one per second it is ~500k rows a
-- day, so the nightly run would re-read the entire table — a cost that grows
-- with the history it has already summarised, until the run no longer finishes
-- inside the window it was scheduled for.
--
-- The roll-up is unchanged in what it produces. It now starts from the earlier
-- of: the newest hour already summarised, or p_since_hours ago.
--
--   * Steady state — the newest summary is recent, so a run reads the last
--     p_since_hours of raw rows and no more.
--   * First ever run — no summaries exist, so it reads everything once and
--     establishes the baseline. That is the only full pass.
--   * After an outage — the newest summary is old, so the run reaches back to
--     it and closes the gap rather than leaving hours unsummarised. Unsummarised
--     hours are never pruned, so a gap costs disk, never data.
--
-- Re-reading the newest summarised hour on every run is deliberate: a reading
-- that arrives late, for an hour already summarised, is folded in on the next
-- run instead of being silently missing from the summary.

-- Adding a parameter creates an overload rather than replacing the function,
-- and a one-argument call would then match both and fail as ambiguous. The
-- single-argument version goes first.
DROP FUNCTION IF EXISTS rollup_sensor_data_hourly(INTEGER);

CREATE OR REPLACE FUNCTION rollup_sensor_data_hourly(
    p_older_than_hours INTEGER DEFAULT 2,
    p_since_hours      INTEGER DEFAULT 48
)
RETURNS INTEGER AS $$
DECLARE
    affected  INTEGER;
    v_from    TIMESTAMP;
    v_cutoff  TIMESTAMP;
BEGIN
    v_cutoff := date_trunc('hour', NOW() - (p_older_than_hours || ' hours')::INTERVAL);

    -- '-infinity' when nothing has been summarised yet, which makes LEAST pick
    -- it and the first run cover the whole table.
    SELECT LEAST(
               date_trunc('hour', NOW() - (p_since_hours || ' hours')::INTERVAL),
               COALESCE((SELECT MAX(bucket_start) FROM sensor_data_hourly), '-infinity'::TIMESTAMP)
           )
      INTO v_from;

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
    WHERE recorded_at >= v_from
      AND recorded_at <  v_cutoff
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
