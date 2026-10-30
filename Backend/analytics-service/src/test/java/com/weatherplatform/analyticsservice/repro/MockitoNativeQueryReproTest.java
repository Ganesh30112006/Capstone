package com.weatherplatform.analyticsservice.repro;

import com.weatherplatform.analyticsservice.entity.DailyStatistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Narrowing an unexplained test-infrastructure failure, not a product defect.
 *
 * Adding the daily-aggregate upsert to {@code DailyStatisticsRepository} made
 * Mockito unable to mock that interface at all, with
 * {@code InternalError: class redefinition failed: invalid class} raised from
 * ByteBuddy's retransformation -- which broke unrelated test files that had
 * only ever mocked the repository. Moving the statement to a component using
 * {@code EntityManager} resolved it, but the cause was never established.
 *
 * Each interface below isolates one difference between a repository method that
 * mocks cleanly and the one that did not: the JPQL/native switch, the
 * {@code @Modifying} annotation, and the size of the annotation's string value.
 *
 * <h2>Outcome</h2>
 *
 * None of them reproduce it. Neither does putting the method back on the real
 * {@code DailyStatisticsRepository} and running the module's whole suite: that
 * passes too. So the failure is <strong>not</strong> a property of a native or
 * {@code @Modifying} repository query, and nothing here argues against writing
 * one -- which is worth stating plainly, because the workaround could otherwise
 * be read as evidence that they are unsafe.
 *
 * What is left is the build state at the time. {@code invalid class} is raised
 * when the class file the agent retransforms with disagrees with the class
 * already loaded, and an earlier step of that same bisect was confounded by
 * Maven reporting BUILD SUCCESS over stale output. A stale
 * {@code target/classes} entry for the edited interface fits the evidence and
 * fits why it cannot be reproduced now. That is a plausible cause rather than a
 * confirmed one, and it is recorded as such.
 *
 * These cases are kept as the harness: if it recurs, the variant that fails
 * says which difference matters, and a run after {@code mvn clean} says whether
 * the build state was the cause after all.
 */
class MockitoNativeQueryReproTest {

    interface PlainRepository extends JpaRepository<DailyStatistics, Long> {
        Optional<DailyStatistics> findByDeviceUidAndStatDate(String deviceUid, LocalDate statDate);
    }

    interface JpqlRepository extends JpaRepository<DailyStatistics, Long> {
        @Query("SELECT d FROM DailyStatistics d WHERE d.deviceUid = :deviceUid")
        List<DailyStatistics> findForDevice(@Param("deviceUid") String deviceUid);
    }

    interface ShortNativeRepository extends JpaRepository<DailyStatistics, Long> {
        @Query(value = "SELECT * FROM daily_statistics WHERE device_uid = :deviceUid", nativeQuery = true)
        List<DailyStatistics> findForDevice(@Param("deviceUid") String deviceUid);
    }

    interface ModifyingNativeRepository extends JpaRepository<DailyStatistics, Long> {
        @Modifying
        @Query(value = "UPDATE daily_statistics SET reading_count = reading_count + 1 WHERE id = :id",
                nativeQuery = true)
        void bump(@Param("id") Long id);
    }

    /**
     * The shape that actually failed: a {@code @Modifying} native upsert whose
     * SQL is a multi-kilobyte text block, declared on the same interface that
     * other tests mock.
     */
    interface LargeUpsertRepository extends JpaRepository<DailyStatistics, Long> {
        @Modifying
        @Query(value = """
                INSERT INTO daily_statistics (
                    device_uid, stat_date,
                    sum_temperature, min_temperature, max_temperature,
                    sum_humidity,    min_humidity,    max_humidity,
                    sum_air_quality, min_air_quality, max_air_quality,
                    sum_pressure,    min_pressure,    max_pressure,
                    sum_oxygen,      min_oxygen,      max_oxygen,
                    rain_events_count, reading_count, created_at, updated_at)
                VALUES (
                    :deviceUid, :statDate,
                    COALESCE(CAST(:temperature AS NUMERIC), 0), CAST(:temperature AS NUMERIC), CAST(:temperature AS NUMERIC),
                    COALESCE(CAST(:humidity    AS NUMERIC), 0), CAST(:humidity    AS NUMERIC), CAST(:humidity    AS NUMERIC),
                    COALESCE(CAST(:airQuality  AS BIGINT),  0), CAST(:airQuality  AS INTEGER), CAST(:airQuality  AS INTEGER),
                    COALESCE(CAST(:pressure    AS NUMERIC), 0), CAST(:pressure    AS NUMERIC), CAST(:pressure    AS NUMERIC),
                    COALESCE(CAST(:oxygen      AS NUMERIC), 0), CAST(:oxygen      AS NUMERIC), CAST(:oxygen      AS NUMERIC),
                    :rainEvent, 1, now(), now())
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
                    updated_at        = now()
                """, nativeQuery = true)
        void foldReadingIntoDailyBucket(
                @Param("deviceUid") String deviceUid,
                @Param("statDate") LocalDate statDate,
                @Param("temperature") BigDecimal temperature,
                @Param("humidity") BigDecimal humidity,
                @Param("airQuality") Integer airQuality,
                @Param("pressure") BigDecimal pressure,
                @Param("oxygen") BigDecimal oxygen,
                @Param("rainEvent") int rainEvent);
    }

    @Test
    @DisplayName("a repository with only derived queries mocks cleanly")
    void plainRepositoryMocks() {
        assertThat(mock(PlainRepository.class)).isNotNull();
    }

    @Test
    @DisplayName("a JPQL @Query does not prevent mocking")
    void jpqlRepositoryMocks() {
        assertThat(mock(JpqlRepository.class)).isNotNull();
    }

    @Test
    @DisplayName("a short native @Query does not prevent mocking")
    void shortNativeRepositoryMocks() {
        assertThat(mock(ShortNativeRepository.class)).isNotNull();
    }

    @Test
    @DisplayName("a @Modifying native @Query does not prevent mocking")
    void modifyingNativeRepositoryMocks() {
        assertThat(mock(ModifyingNativeRepository.class)).isNotNull();
    }

    @Test
    @DisplayName("the exact shape that failed: a @Modifying native upsert in a large text block")
    void largeUpsertRepositoryMocks() {
        assertThat(mock(LargeUpsertRepository.class)).isNotNull();
    }
}
