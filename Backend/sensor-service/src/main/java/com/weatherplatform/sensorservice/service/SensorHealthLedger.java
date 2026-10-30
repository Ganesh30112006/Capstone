package com.weatherplatform.sensorservice.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Counts every sensor evaluation by outcome, per hour.
 *
 * This is what a health score is computed from. sensor_health keeps only the
 * latest verdict, and a percentage computed from one verdict would be either
 * 0% or 100%. One row per sensor per hour keeps the counts exact at a fixed
 * size, and writes once per reading -- all of that reading's sensors in a
 * single statement -- rather than once per sensor.
 */
@Component
@RequiredArgsConstructor
public class SensorHealthLedger {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    /** Passed, warned and failed evaluations of one sensor over a window. */
    public record Tally(int passed, int warned, int failed, LocalDateTime windowStart) {
        public int total() {
            return passed + warned + failed;
        }
    }

    /**
     * Adds one reading's outcomes to the current hour.
     *
     * Only HEALTHY, WARNING and FAILED are counted. A sensor that was not
     * evaluated contributes nothing, rather than a pass it never earned.
     */
    public void record(String deviceUid, Map<String, String> statuses, LocalDateTime at) {
        Timestamp bucket = Timestamp.valueOf(at.truncatedTo(ChronoUnit.HOURS));
        List<Object[]> rows = new ArrayList<>();
        for (Map.Entry<String, String> entry : statuses.entrySet()) {
            String status = entry.getValue();
            int passed = "HEALTHY".equals(status) ? 1 : 0;
            int warned = "WARNING".equals(status) ? 1 : 0;
            int failed = "FAILED".equals(status) ? 1 : 0;
            if (passed + warned + failed == 0) continue;
            rows.add(new Object[]{deviceUid, entry.getKey(), bucket, passed, warned, failed});
        }
        if (rows.isEmpty()) return;

        StringBuilder sql = new StringBuilder(
                "INSERT INTO sensor_health_hourly (device_uid, sensor_type, bucket_start, passed, warned, failed) VALUES ");
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            sql.append(i == 0 ? "" : ", ").append("(?, ?, ?, ?, ?, ?)");
            args.addAll(List.of(rows.get(i)));
        }
        sql.append(" ON CONFLICT (device_uid, sensor_type, bucket_start) DO UPDATE SET"
                + " passed = sensor_health_hourly.passed + EXCLUDED.passed,"
                + " warned = sensor_health_hourly.warned + EXCLUDED.warned,"
                + " failed = sensor_health_hourly.failed + EXCLUDED.failed");
        jdbc.update(sql.toString(), args.toArray());
    }

    /**
     * Each sensor's tally since {@code since}, keyed by device then sensor type.
     *
     * A sensor with no evaluations in the window is absent, never a zero tally:
     * 0 of 0 is not a health score of anything.
     */
    public Map<String, Map<String, Tally>> window(Collection<String> deviceUids, LocalDateTime since) {
        Map<String, Map<String, Tally>> tallies = new LinkedHashMap<>();
        if (deviceUids.isEmpty()) return tallies;

        String sql = """
                SELECT device_uid, sensor_type,
                       SUM(passed) AS passed, SUM(warned) AS warned, SUM(failed) AS failed,
                       MIN(bucket_start) AS window_start
                  FROM sensor_health_hourly
                 WHERE device_uid IN (:uids) AND bucket_start >= :since
                 GROUP BY device_uid, sensor_type
                """;
        named.query(sql, new MapSqlParameterSource()
                        .addValue("uids", deviceUids)
                        .addValue("since", Timestamp.valueOf(since.truncatedTo(ChronoUnit.HOURS))),
                rs -> {
                    tallies.computeIfAbsent(rs.getString("device_uid"), k -> new LinkedHashMap<>())
                            .put(rs.getString("sensor_type"), new Tally(
                                    rs.getInt("passed"), rs.getInt("warned"), rs.getInt("failed"),
                                    rs.getTimestamp("window_start").toLocalDateTime()));
                });
        return tallies;
    }

    /** Drops hours older than the cutoff. Returns how many rows went. */
    public int pruneBefore(LocalDateTime cutoff) {
        return jdbc.update("DELETE FROM sensor_health_hourly WHERE bucket_start < ?", Timestamp.valueOf(cutoff));
    }
}
