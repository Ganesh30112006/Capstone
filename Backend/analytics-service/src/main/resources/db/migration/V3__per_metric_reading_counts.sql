-- Per-metric reading counts for daily_statistics.
--
-- The averages here divide a metric's sum by reading_count, which counts every
-- reading a station sent that day regardless of which metrics were in it. Two
-- separate errors follow from that.
--
-- A station whose air-quality sensor is absent or broken contributes nothing to
-- sum_air_quality but its full reading_count to the divisor, so the fleet
-- average is pulled toward zero by stations that never measured the thing being
-- averaged.
--
-- Worse, "never measured" and "measured zero" become the same row. The read
-- path tried to tell them apart by looking for a NULL sum, but DailyAggregateWriter
-- has always written the sums through COALESCE(..., 0) and since
-- V2__daily_statistics_aggregate_invariants the columns are NOT NULL anyway --
-- so the NULL it was looking for could not occur. A fleet with no air-quality
-- data at all reported an average AQI of 0: perfect air, from no measurements.
--
-- These columns record how many readings actually carried each metric, so the
-- divisor matches the numerator and a count of zero is distinguishable from a
-- sum of zero.
--
-- They are nullable on purpose. NULL means "this row predates per-metric
-- counting and the true count is unknowable" -- it is not a zero, and the read
-- path reports unknown rather than inventing a denominator. The backfill below
-- fills in only what can be established with certainty.

ALTER TABLE daily_statistics ADD COLUMN IF NOT EXISTS temperature_count INTEGER;
ALTER TABLE daily_statistics ADD COLUMN IF NOT EXISTS humidity_count    INTEGER;
ALTER TABLE daily_statistics ADD COLUMN IF NOT EXISTS air_quality_count INTEGER;
ALTER TABLE daily_statistics ADD COLUMN IF NOT EXISTS pressure_count    INTEGER;
ALTER TABLE daily_statistics ADD COLUMN IF NOT EXISTS oxygen_count      INTEGER;

-- Backfill only the certain case.
--
-- min_<metric> is written straight from the reading and is left NULL until a
-- value actually arrives, so min IS NULL proves the metric was never present in
-- that bucket and the count is exactly 0. When min IS NOT NULL the metric was
-- present at least once, but how many of that day's readings carried it is not
-- recoverable from what was stored -- so it stays NULL, meaning unknown.
--
-- Guessing reading_count here would have been the tempting move and would have
-- been wrong: it asserts every reading carried the metric, which is precisely
-- the assumption that produced the depressed averages in the first place.
UPDATE daily_statistics SET
    temperature_count = CASE WHEN min_temperature IS NULL THEN 0 ELSE NULL END,
    humidity_count    = CASE WHEN min_humidity    IS NULL THEN 0 ELSE NULL END,
    air_quality_count = CASE WHEN min_air_quality IS NULL THEN 0 ELSE NULL END,
    pressure_count    = CASE WHEN min_pressure    IS NULL THEN 0 ELSE NULL END,
    oxygen_count      = CASE WHEN min_oxygen      IS NULL THEN 0 ELSE NULL END;
