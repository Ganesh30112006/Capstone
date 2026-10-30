package com.weatherplatform.analyticsservice.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The write side of the daily aggregates: folds one reading into its bucket.
 *
 * This exists as its own component, separate from the read repository, because
 * the operation has to be a single atomic statement.
 *
 * The previous shape was read-modify-write: SELECT the day's row, add the
 * reading to it in Java, save. Two readings for the same device and day
 * arriving concurrently each read the same starting totals and each wrote back
 * their own version, so whichever committed second discarded the other's
 * contribution -- the reading vanished from both the sum and reading_count.
 * The unique constraint on (device_uid, stat_date) prevented duplicate rows but
 * said nothing about a lost update, and no error was raised, so nothing
 * retried. A retry could not have helped in any case: the arithmetic had
 * already been done against stale values.
 *
 * Doing the arithmetic inside INSERT ... ON CONFLICT DO UPDATE holds the row
 * lock across the whole read-add-write, so concurrent readings serialise.
 */
@Component
public class DailyAggregateWriter {

    /*
     * Notes on the SQL:
     *  - COALESCE on the sums means a reading that omits a metric adds zero to
     *    that running total rather than nulling it.
     *  - PostgreSQL's LEAST and GREATEST ignore NULL operands, which is exactly
     *    what the min and max columns need: the first real value beats the
     *    initial NULL, and a reading missing that metric leaves the
     *    extreme untouched.
     *  - reading_count increments once per reading regardless of which metrics
     *    are present, matching the behaviour this replaced.
     */
    private static final String UPSERT = """
            INSERT INTO daily_statistics (
                device_uid, stat_date,
                sum_temperature, min_temperature, max_temperature,
                sum_humidity,    min_humidity,    max_humidity,
                sum_air_quality, min_air_quality, max_air_quality,
                sum_pressure,    min_pressure,    max_pressure,
                sum_oxygen,      min_oxygen,      max_oxygen,
                rain_events_count, reading_count,
                temperature_count, humidity_count, air_quality_count,
                pressure_count, oxygen_count,
                created_at, updated_at)
            VALUES (
                :deviceUid, :statDate,
                COALESCE(CAST(:temperature AS NUMERIC), 0), CAST(:temperature AS NUMERIC), CAST(:temperature AS NUMERIC),
                COALESCE(CAST(:humidity    AS NUMERIC), 0), CAST(:humidity    AS NUMERIC), CAST(:humidity    AS NUMERIC),
                COALESCE(CAST(:airQuality  AS BIGINT),  0), CAST(:airQuality  AS INTEGER), CAST(:airQuality  AS INTEGER),
                COALESCE(CAST(:pressure    AS NUMERIC), 0), CAST(:pressure    AS NUMERIC), CAST(:pressure    AS NUMERIC),
                COALESCE(CAST(:oxygen      AS NUMERIC), 0), CAST(:oxygen      AS NUMERIC), CAST(:oxygen      AS NUMERIC),
                :rainEvent, 1,
                -- One per metric actually present in this reading, so each
                -- average divides by the readings that carried it rather than
                -- by every reading the station sent.
                CASE WHEN :temperature IS NULL THEN 0 ELSE 1 END,
                CASE WHEN :humidity    IS NULL THEN 0 ELSE 1 END,
                CASE WHEN :airQuality  IS NULL THEN 0 ELSE 1 END,
                CASE WHEN :pressure    IS NULL THEN 0 ELSE 1 END,
                CASE WHEN :oxygen      IS NULL THEN 0 ELSE 1 END,
                now(), now())
            ON CONFLICT (device_uid, stat_date) DO UPDATE SET
                sum_temperature   = daily_statistics.sum_temperature + EXCLUDED.sum_temperature,
                min_temperature   = LEAST(daily_statistics.min_temperature, EXCLUDED.min_temperature),
                max_temperature   = GREATEST(daily_statistics.max_temperature, EXCLUDED.max_temperature),
                sum_humidity      = daily_statistics.sum_humidity + EXCLUDED.sum_humidity,
                min_humidity      = LEAST(daily_statistics.min_humidity, EXCLUDED.min_humidity),
                max_humidity      = GREATEST(daily_statistics.max_humidity, EXCLUDED.max_humidity),
                sum_air_quality   = daily_statistics.sum_air_quality + EXCLUDED.sum_air_quality,
                min_air_quality   = LEAST(daily_statistics.min_air_quality, EXCLUDED.min_air_quality),
                max_air_quality   = GREATEST(daily_statistics.max_air_quality, EXCLUDED.max_air_quality),
                sum_pressure      = daily_statistics.sum_pressure + EXCLUDED.sum_pressure,
                min_pressure      = LEAST(daily_statistics.min_pressure, EXCLUDED.min_pressure),
                max_pressure      = GREATEST(daily_statistics.max_pressure, EXCLUDED.max_pressure),
                sum_oxygen        = daily_statistics.sum_oxygen + EXCLUDED.sum_oxygen,
                min_oxygen        = LEAST(daily_statistics.min_oxygen, EXCLUDED.min_oxygen),
                max_oxygen        = GREATEST(daily_statistics.max_oxygen, EXCLUDED.max_oxygen),
                rain_events_count = daily_statistics.rain_events_count + EXCLUDED.rain_events_count,
                reading_count     = daily_statistics.reading_count + 1,

                -- NULL + 1 is NULL, and that is deliberate: a row whose count was
                -- unknowable at V3 stays unknown rather than quietly becoming a
                -- partial count that would read as exact.
                temperature_count = daily_statistics.temperature_count + EXCLUDED.temperature_count,
                humidity_count    = daily_statistics.humidity_count    + EXCLUDED.humidity_count,
                air_quality_count = daily_statistics.air_quality_count + EXCLUDED.air_quality_count,
                pressure_count    = daily_statistics.pressure_count    + EXCLUDED.pressure_count,
                oxygen_count      = daily_statistics.oxygen_count      + EXCLUDED.oxygen_count,
                updated_at        = now()
            """;

    @PersistenceContext
    private EntityManager entityManager;

    public void foldReadingIntoDailyBucket(
            String deviceUid,
            LocalDate statDate,
            BigDecimal temperature,
            BigDecimal humidity,
            Integer airQuality,
            BigDecimal pressure,
            BigDecimal oxygen,
            int rainEvent) {

        Query query = entityManager.createNativeQuery(UPSERT);
        query.setParameter("deviceUid", deviceUid);
        query.setParameter("statDate", statDate);
        query.setParameter("temperature", temperature);
        query.setParameter("humidity", humidity);
        query.setParameter("airQuality", airQuality);
        query.setParameter("pressure", pressure);
        query.setParameter("oxygen", oxygen);
        query.setParameter("rainEvent", rainEvent);
        query.executeUpdate();
    }
}
