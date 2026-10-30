-- Calibration records and the sensor-health history they are judged against.
--
-- Until now the platform could say whether a sensor's latest reading passed its
-- checks, and nothing else. It could not say when a sensor was last calibrated,
-- by how much it was corrected, how often it has been failing, or whether it
-- has frozen -- so any "calibration: valid" or "health: 98%" shown anywhere
-- would have been a claim with no row behind it.

-- ---------------------------------------------------------------------------
-- 1. Calibration history
--
-- One row per calibration, never updated. The active calibration for a sensor
-- is simply its newest row, and the older rows are what drift is estimated
-- from, so history is the point rather than an audit afterthought.
--
-- The offset is not an input. It is the difference between two observations
-- taken together: a reference instrument's reading and the sensor's own raw
-- reading. Storing both, and constraining the offset to be exactly their
-- difference, means every correction applied to telemetry can be traced back
-- to what was actually measured -- nobody can type in "+3" and have it
-- silently shift a week of readings.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sensor_calibration (
    id               BIGSERIAL    PRIMARY KEY,
    device_uid       VARCHAR(100) NOT NULL,
    sensor_type      VARCHAR(50)  NOT NULL,
    reference_value  NUMERIC(9,3) NOT NULL,
    sensor_value     NUMERIC(9,3) NOT NULL,
    offset_value     NUMERIC(9,3) NOT NULL,
    calibrated_at    TIMESTAMP    NOT NULL,
    valid_until      TIMESTAMP    NOT NULL,
    performed_by     VARCHAR(255),
    notes            VARCHAR(500),
    created_at       TIMESTAMP    NOT NULL DEFAULT (NOW() AT TIME ZONE 'UTC'),
    -- Rain is a yes/no and GPS is not a measurement; neither has an offset.
    CONSTRAINT ck_sensor_calibration_type
        CHECK (sensor_type IN ('temperature', 'humidity', 'airQuality', 'pressure', 'oxygen')),
    CONSTRAINT ck_sensor_calibration_offset
        CHECK (offset_value = reference_value - sensor_value),
    CONSTRAINT ck_sensor_calibration_validity
        CHECK (valid_until > calibrated_at)
);

-- Every read is "this sensor, newest first": the active calibration at ingest,
-- and the last two for the drift estimate.
CREATE INDEX IF NOT EXISTS idx_sensor_calibration_lookup
    ON sensor_calibration (device_uid, sensor_type, calibrated_at DESC);

-- ---------------------------------------------------------------------------
-- 2. Raw values alongside corrected ones
--
-- The existing columns go on meaning what every reader already takes them to
-- mean: the platform's best value for the quantity, which is now the corrected
-- one. The device's own figures are kept beside them, so that a calibration
-- later found to be wrong costs a recomputation rather than the history.
--
-- NULL on rows written before this migration. Those rows predate calibration,
-- so the value already in the main column is the raw one.
-- ---------------------------------------------------------------------------
ALTER TABLE sensor_data ADD COLUMN IF NOT EXISTS raw_temperature NUMERIC(5,2);
ALTER TABLE sensor_data ADD COLUMN IF NOT EXISTS raw_humidity    NUMERIC(5,2);
ALTER TABLE sensor_data ADD COLUMN IF NOT EXISTS raw_air_quality INTEGER;
ALTER TABLE sensor_data ADD COLUMN IF NOT EXISTS raw_pressure    NUMERIC(7,2);
ALTER TABLE sensor_data ADD COLUMN IF NOT EXISTS raw_oxygen      NUMERIC(5,2);

-- ---------------------------------------------------------------------------
-- 3. Why a sensor is in the state it is in
--
-- status says HEALTHY, WARNING or FAILED. condition says which check decided
-- it, because a frozen sensor and one swinging wildly are both WARNING and
-- call for different fixes.
--
-- NULL condition on rows last written before this migration: the reason was
-- never recorded, which is not the same as there being none.
-- ---------------------------------------------------------------------------
ALTER TABLE sensor_health ADD COLUMN IF NOT EXISTS condition       VARCHAR(20);
ALTER TABLE sensor_health ADD COLUMN IF NOT EXISTS last_healthy_at TIMESTAMP;
ALTER TABLE sensor_health ADD COLUMN IF NOT EXISTS flatline_since  TIMESTAMP;

ALTER TABLE sensor_health ADD CONSTRAINT ck_sensor_health_condition
    CHECK (condition IS NULL
           OR condition IN ('NONE', 'MISSING', 'OUT_OF_RANGE', 'RAPID_CHANGE', 'FLATLINE'));

-- ---------------------------------------------------------------------------
-- 4. Evaluation outcomes, counted by the hour
--
-- A health score is only as honest as the counts under it. sensor_health keeps
-- the latest verdict alone, so "98% of readings passed" had nothing to be
-- computed from. One row per sensor per hour keeps it exact at a fixed size --
-- a few thousand rows a month -- where one row per evaluation would add five
-- writes to every reading on the largest table in the service.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sensor_health_hourly (
    device_uid   VARCHAR(100) NOT NULL,
    sensor_type  VARCHAR(50)  NOT NULL,
    bucket_start TIMESTAMP    NOT NULL,
    passed       INTEGER      NOT NULL DEFAULT 0,
    warned       INTEGER      NOT NULL DEFAULT 0,
    failed       INTEGER      NOT NULL DEFAULT 0,
    PRIMARY KEY (device_uid, sensor_type, bucket_start),
    CONSTRAINT ck_sensor_health_hourly_counts CHECK (passed >= 0 AND warned >= 0 AND failed >= 0)
);

CREATE INDEX IF NOT EXISTS idx_sensor_health_hourly_bucket
    ON sensor_health_hourly (bucket_start);
