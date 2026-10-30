-- Aggregate columns on daily_statistics are counters, and a counter is never
-- unknown -- it is zero until something increments it.
--
-- The baseline gave these columns a DEFAULT but left them nullable, so the
-- invariant held only for rows this service inserted through the JPA builder.
-- Any row reaching the table another way -- a backfill, a manual correction, a
-- future writer -- could carry NULL, and both sides of the application then
-- broke on it:
--
--   read  path: reduce(ZERO, BigDecimal::add) over a null sum -> NPE -> HTTP 500
--                on the device report and the fleet report;
--   write path: stats.getSumTemperature().add(reading) -> NPE, turning valid
--                telemetry into a failed message.
--
-- Enforcing it here rather than guarding every call site gives one invariant
-- that both paths inherit, and lets the aggregation be expressed as a single
-- atomic UPDATE (see V2's companion change in AnalyticsServiceImpl).
--
-- min_* and max_* are deliberately left nullable: unlike a sum, an extreme over
-- zero readings genuinely has no value, and NULL is the honest representation.

-- 1. Backfill anything already NULL. Zero is correct here: these columns are
--    running totals, so a NULL means "nothing has been added yet".
UPDATE daily_statistics SET sum_temperature   = 0 WHERE sum_temperature   IS NULL;
UPDATE daily_statistics SET sum_humidity      = 0 WHERE sum_humidity      IS NULL;
UPDATE daily_statistics SET sum_air_quality   = 0 WHERE sum_air_quality   IS NULL;
UPDATE daily_statistics SET sum_pressure      = 0 WHERE sum_pressure      IS NULL;
UPDATE daily_statistics SET sum_oxygen        = 0 WHERE sum_oxygen        IS NULL;
UPDATE daily_statistics SET rain_events_count = 0 WHERE rain_events_count IS NULL;

-- 2. Restate the defaults explicitly, so an INSERT that omits the column lands
--    on zero rather than NULL.
ALTER TABLE daily_statistics ALTER COLUMN sum_temperature   SET DEFAULT 0;
ALTER TABLE daily_statistics ALTER COLUMN sum_humidity      SET DEFAULT 0;
ALTER TABLE daily_statistics ALTER COLUMN sum_air_quality   SET DEFAULT 0;
ALTER TABLE daily_statistics ALTER COLUMN sum_pressure      SET DEFAULT 0;
ALTER TABLE daily_statistics ALTER COLUMN sum_oxygen        SET DEFAULT 0;
ALTER TABLE daily_statistics ALTER COLUMN rain_events_count SET DEFAULT 0;

-- 3. Close the hole: an explicit NULL is now rejected by the database, which is
--    the only place the rule cannot be bypassed.
ALTER TABLE daily_statistics ALTER COLUMN sum_temperature   SET NOT NULL;
ALTER TABLE daily_statistics ALTER COLUMN sum_humidity      SET NOT NULL;
ALTER TABLE daily_statistics ALTER COLUMN sum_air_quality   SET NOT NULL;
ALTER TABLE daily_statistics ALTER COLUMN sum_pressure      SET NOT NULL;
ALTER TABLE daily_statistics ALTER COLUMN sum_oxygen        SET NOT NULL;
ALTER TABLE daily_statistics ALTER COLUMN rain_events_count SET NOT NULL;
