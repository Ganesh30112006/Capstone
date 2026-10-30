package com.weatherplatform.analyticsservice.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the aggregate invariant on a database that was migrated, not on one
 * that was created already correct.
 *
 * <p>{@code V2__daily_statistics_aggregate_invariants} exists because every
 * {@code sum_*} column on {@code daily_statistics} was nullable and only the
 * Java builder defaulted them to zero. A row written with a NULL -- by a
 * backfill, or by any writer that was not this service -- loaded as null and
 * went into {@code reduce(ZERO, BigDecimal::add)}, which throws on a null
 * operand, returning 500 from both the device report and the fleet report.
 *
 * <p>The application carried {@code nullSafeSum} as a compatibility guard for
 * exactly that. A guard is only safe to remove once the invariant it stands in
 * for is enforced somewhere else <em>and that has been demonstrated against
 * data that predates it</em> -- which is what this test does: it builds the
 * schema at V1, writes the legacy NULL rows that used to break the reports,
 * then migrates and checks what became of them.
 *
 * <p>A test that started at V2 could not show this. It would only ever see rows
 * that could not have been NULL in the first place.
 */
@Testcontainers(disabledWithoutDocker = true)
class DailyStatisticsInvariantIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("analytics_db")
                    .withUsername("analytics")
                    .withPassword("analytics");

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .target(target)
                .load();
    }

    /** Schema at V1 only, holding the legacy rows the reports used to choke on. */
    private void seedLegacyRowsAtV1() throws SQLException {
        Flyway v1 = flyway("1");
        v1.clean();
        v1.migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            // Explicit NULLs: what a backfill or a foreign writer left behind.
            st.executeUpdate("""
                INSERT INTO daily_statistics
                    (device_uid, stat_date, sum_temperature, sum_humidity,
                     sum_air_quality, sum_pressure, sum_oxygen, reading_count)
                VALUES
                    ('ESP32-LEGACY-1', DATE '2026-09-01', NULL, NULL, NULL, NULL, NULL, 12),
                    ('ESP32-LEGACY-2', DATE '2026-09-02', 41.5, NULL, 300,  NULL, 20.9, 7)
                """);

            try (ResultSet rs = st.executeQuery(
                    "SELECT count(*) FROM daily_statistics WHERE sum_temperature IS NULL")) {
                rs.next();
                assertThat(rs.getInt(1))
                        .as("the legacy rows must really be NULL before the migration, "
                                + "otherwise this test proves nothing")
                        .isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("migrating a database that already holds NULL sums backfills them to zero")
    void migrationBackfillsLegacyNulls() throws SQLException {
        seedLegacyRowsAtV1();

        flyway("2").migrate();

        try (Connection c = connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("""
                 SELECT count(*) FROM daily_statistics
                 WHERE sum_temperature IS NULL OR sum_humidity IS NULL
                    OR sum_air_quality IS NULL OR sum_pressure IS NULL
                    OR sum_oxygen IS NULL
                 """)) {
            rs.next();
            assertThat(rs.getInt(1)).as("no NULL sum may survive the migration").isZero();
        }

        // The value that was present is preserved; only the absent ones become 0.
        try (Connection c = connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT sum_temperature, sum_humidity FROM daily_statistics "
                             + "WHERE device_uid = 'ESP32-LEGACY-2'")) {
            rs.next();
            assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("41.5");
            assertThat(rs.getBigDecimal(2)).isEqualByComparingTo("0");
        }
    }

    @Test
    @DisplayName("after migrating, the database itself refuses a NULL sum")
    void theDatabaseEnforcesTheInvariantAfterwards() throws SQLException {
        seedLegacyRowsAtV1();
        flyway("2").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertThatThrownBy(() -> st.executeUpdate("""
                    INSERT INTO daily_statistics
                        (device_uid, stat_date, sum_temperature, reading_count)
                    VALUES ('ESP32-NEW', DATE '2026-09-03', NULL, 1)
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("sum_temperature");
        }
    }

    @Test
    @DisplayName("a writer that omits the sums gets zero from the column default, not NULL")
    void omittingASumYieldsZeroNotNull() throws SQLException {
        seedLegacyRowsAtV1();
        flyway("2").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate("""
                    INSERT INTO daily_statistics (device_uid, stat_date, reading_count)
                    VALUES ('ESP32-SPARSE', DATE '2026-09-04', 0)
                    """);

            try (ResultSet rs = st.executeQuery(
                    "SELECT sum_temperature, sum_humidity, sum_air_quality, sum_pressure, sum_oxygen "
                            + "FROM daily_statistics WHERE device_uid = 'ESP32-SPARSE'")) {
                rs.next();
                for (int col = 1; col <= 5; col++) {
                    assertThat(rs.getBigDecimal(col))
                            .as("column %d must default to 0, so a partial writer cannot "
                                    + "reintroduce the NULL the application used to guard against", col)
                            .isEqualByComparingTo("0");
                }
            }
        }
    }

    // ------------------------------------------------- V3: per-metric counts

    @Test
    @DisplayName("V3 backfills a count of zero only where the metric was provably never present")
    void v3BackfillsOnlyWhatItCanKnow() throws SQLException {
        Flyway v1 = flyway("1");
        v1.clean();
        v1.migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate("""
                INSERT INTO daily_statistics
                    (device_uid, stat_date, sum_temperature, min_temperature, max_temperature,
                     sum_air_quality, reading_count)
                VALUES
                    -- Temperature was seen (min is set); air quality never was.
                    ('ESP32-PARTIAL', DATE '2026-09-01', 200.0, 18.0, 25.0, 0, 10)
                """);
        }

        flyway("3").migrate();

        try (Connection c = connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT temperature_count, air_quality_count FROM daily_statistics "
                             + "WHERE device_uid = 'ESP32-PARTIAL'")) {
            rs.next();

            rs.getInt(1);
            assertThat(rs.wasNull())
                    .as("temperature was present at least once, but how many of the day's "
                            + "readings carried it is not recoverable — it must stay unknown "
                            + "rather than be guessed at reading_count")
                    .isTrue();

            int airQuality = rs.getInt(2);
            assertThat(rs.wasNull())
                    .as("min_air_quality is NULL, which proves the metric was never present, "
                            + "so the count is known to be exactly zero")
                    .isFalse();
            assertThat(airQuality).isZero();
        }
    }

    @Test
    @DisplayName("an unknown count stays unknown as more readings arrive")
    void anUnknownCountIsNotQuietlyRepaired() throws SQLException {
        Flyway v1 = flyway("1");
        v1.clean();
        v1.migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate("""
                INSERT INTO daily_statistics
                    (device_uid, stat_date, sum_temperature, min_temperature, max_temperature,
                     reading_count)
                VALUES ('ESP32-OLD', DATE '2026-09-01', 200.0, 18.0, 25.0, 10)
                """);
        }
        flyway("3").migrate();

        // The writer's conflict path adds to the existing count; NULL + 1 is
        // NULL, so the row does not acquire a partial count that would read as
        // exact once more readings land on the same day.
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate("""
                UPDATE daily_statistics
                   SET temperature_count = temperature_count + 1, reading_count = reading_count + 1
                 WHERE device_uid = 'ESP32-OLD'
                """);

            try (ResultSet rs = st.executeQuery(
                    "SELECT temperature_count, reading_count FROM daily_statistics "
                            + "WHERE device_uid = 'ESP32-OLD'")) {
                rs.next();
                rs.getInt(1);
                assertThat(rs.wasNull()).as("still unknown").isTrue();
                assertThat(rs.getInt(2)).isEqualTo(11);
            }
        }
    }
}
