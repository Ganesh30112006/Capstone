package com.weatherplatform.sensorservice.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SensorHealthLedgerTest {

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private NamedParameterJdbcTemplate named;

    @InjectMocks
    private SensorHealthLedger ledger;

    @Test
    void countsEachEvaluatedSensorInTheColumnForItsOutcome() {
        Map<String, String> statuses = new LinkedHashMap<>();
        statuses.put("temperature", "HEALTHY");
        statuses.put("humidity", "WARNING");
        statuses.put("pressure", "FAILED");

        ledger.record("DEV-1", statuses, LocalDateTime.of(2026, 9, 28, 14, 37, 12));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), args.capture());
        Timestamp hour = Timestamp.valueOf(LocalDateTime.of(2026, 9, 28, 14, 0));
        assertThat(List.of(args.getValue())).containsExactly(
                "DEV-1", "temperature", hour, 1, 0, 0,
                "DEV-1", "humidity", hour, 0, 1, 0,
                "DEV-1", "pressure", hour, 0, 0, 1);
    }

    @Test
    void doesNotCountASensorThatWasNotEvaluatedAsAPass() {
        Map<String, String> statuses = new LinkedHashMap<>();
        statuses.put("temperature", "HEALTHY");
        statuses.put("oxygen", SensorFailureDetector.STATUS_UNKNOWN);
        statuses.put("rain", SensorFailureDetector.STATUS_UNKNOWN);

        ledger.record("DEV-1", statuses, LocalDateTime.of(2026, 9, 28, 14, 0));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), args.capture());
        assertThat(args.getValue()).hasSize(6).contains("temperature").doesNotContain("oxygen", "rain");
    }

    @Test
    void writesNothingWhenNothingWasEvaluated() {
        ledger.record("DEV-1", Map.of("rain", SensorFailureDetector.STATUS_UNKNOWN), LocalDateTime.now());

        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void addsToTheHoursExistingCountsRatherThanReplacingThem() {
        ledger.record("DEV-1", Map.of("temperature", "HEALTHY"), LocalDateTime.of(2026, 9, 28, 14, 0));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("ON CONFLICT (device_uid, sensor_type, bucket_start)")
                .contains("passed = sensor_health_hourly.passed + EXCLUDED.passed");
    }
}
