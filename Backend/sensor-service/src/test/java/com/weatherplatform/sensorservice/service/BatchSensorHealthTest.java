package com.weatherplatform.sensorservice.service;

import com.weatherplatform.sensorservice.config.SensorThresholdProperties;
import com.weatherplatform.sensorservice.dto.SensorHealthResponse;
import com.weatherplatform.sensorservice.entity.SensorHealth;
import com.weatherplatform.sensorservice.repository.SensorDataRepository;
import com.weatherplatform.sensorservice.repository.SensorHealthRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The companion to {@link BatchLatestReadingTest} for diagnostics.
 *
 * Two things are pinned here. The first is the same batching property: the
 * admin sensor page asked for health one station at a time, so its cost grew
 * with the fleet.
 *
 * The second matters more. A station with no diagnostics on file used to come
 * back as an empty report with {@code overallStatus: HEALTHY} and a
 * {@code lastCheckedAt} stamped with the time of the request -- a passing
 * verdict and a freshness claim for a check that had never run. Both are
 * asserted against below.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BatchSensorHealthTest {

    @Mock
    private SensorHealthRepository healthRepository;
    @Mock
    private SensorDataRepository dataRepository;
    @Mock
    private RabbitTemplate rabbitTemplate;
    @Mock
    private SensorThresholdProperties props;

    @InjectMocks
    private SensorFailureDetector detector;

    private static final LocalDateTime CHECKED = LocalDateTime.of(2026, 9, 1, 10, 0);

    private static SensorHealth record(String uid, String sensorType, String status, LocalDateTime checkedAt) {
        SensorHealth health = new SensorHealth();
        health.setDeviceUid(uid);
        health.setSensorType(sensorType);
        health.setStatus(status);
        health.setDetails(status + " on " + sensorType);
        health.setLastCheckedAt(checkedAt);
        return health;
    }

    // ------------------------------------------------- provenance

    @Test
    @DisplayName("a station with no diagnostics on file is UNKNOWN, not HEALTHY")
    void neverEvaluatedIsNotHealthy() {
        when(healthRepository.findByDeviceUid("ESP32-NEW")).thenReturn(List.of());

        SensorHealthResponse report = detector.getDeviceSensorHealth("ESP32-NEW");

        // HEALTHY here would be a verdict on checks that have not run. The
        // absence of a recorded failure is not the presence of a passed check.
        assertThat(report.getOverallStatus()).isEqualTo("UNKNOWN");
        assertThat(report.getSensors()).isEmpty();
    }

    @Test
    @DisplayName("lastCheckedAt is null when nothing has been checked, not the time of the request")
    void doesNotStampNowOnACheckThatNeverRan() {
        when(healthRepository.findByDeviceUid("ESP32-NEW")).thenReturn(List.of());

        SensorHealthResponse report = detector.getDeviceSensorHealth("ESP32-NEW");

        // Stamping now() would date the report to the moment it was read and
        // make a station that has never been evaluated look freshly checked.
        assertThat(report.getLastCheckedAt()).isNull();
    }

    @Test
    @DisplayName("lastCheckedAt is the newest recorded check, not whichever row came last")
    void reportsTheNewestCheckTime() {
        when(healthRepository.findByDeviceUid("ESP32-001")).thenReturn(List.of(
                record("ESP32-001", "temperature", "HEALTHY", CHECKED.plusMinutes(30)),
                record("ESP32-001", "humidity", "HEALTHY", CHECKED)));

        SensorHealthResponse report = detector.getDeviceSensorHealth("ESP32-001");

        assertThat(report.getLastCheckedAt()).isEqualTo(CHECKED.plusMinutes(30));
    }

    @Test
    @DisplayName("a device with no diagnostics is absent from the batch, not an empty report")
    void omitsNeverEvaluatedDevicesFromTheBatch() {
        when(healthRepository.findByDeviceUidIn(any())).thenReturn(List.of(
                record("ESP32-001", "temperature", "HEALTHY", CHECKED)));

        Map<String, SensorHealthResponse> reports =
                detector.getSensorHealthForEach(List.of("ESP32-001", "ESP32-002"));

        assertThat(reports).containsOnlyKeys("ESP32-001");
        assertThat(reports).doesNotContainKey("ESP32-002");
    }

    // ------------------------------------------------- roll-up

    @Test
    @DisplayName("groups every probe under the device that reported it")
    void groupsRecordsByDevice() {
        when(healthRepository.findByDeviceUidIn(any())).thenReturn(List.of(
                record("ESP32-001", "temperature", "HEALTHY", CHECKED),
                record("ESP32-002", "humidity", "WARNING", CHECKED),
                record("ESP32-001", "pressure", "FAILED", CHECKED)));

        Map<String, SensorHealthResponse> reports =
                detector.getSensorHealthForEach(List.of("ESP32-001", "ESP32-002"));

        assertThat(reports.get("ESP32-001").getSensors()).containsOnlyKeys("temperature", "pressure");
        assertThat(reports.get("ESP32-002").getSensors()).containsOnlyKeys("humidity");
    }

    @Test
    @DisplayName("the device verdict is its worst probe, regardless of row order")
    void overallStatusIsTheWorstProbe() {
        when(healthRepository.findByDeviceUidIn(any())).thenReturn(List.of(
                record("ESP32-001", "temperature", "FAILED", CHECKED),
                record("ESP32-001", "humidity", "HEALTHY", CHECKED),
                record("ESP32-002", "humidity", "HEALTHY", CHECKED),
                record("ESP32-002", "pressure", "WARNING", CHECKED),
                record("ESP32-003", "oxygen", "HEALTHY", CHECKED)));

        Map<String, SensorHealthResponse> reports =
                detector.getSensorHealthForEach(List.of("ESP32-001", "ESP32-002", "ESP32-003"));

        // A FAILED probe must not be downgraded by a later HEALTHY one.
        assertThat(reports.get("ESP32-001").getOverallStatus()).isEqualTo("FAILED");
        assertThat(reports.get("ESP32-002").getOverallStatus()).isEqualTo("WARNING");
        assertThat(reports.get("ESP32-003").getOverallStatus()).isEqualTo("HEALTHY");
    }

    @Test
    @DisplayName("a WARNING does not overwrite an already FAILED verdict")
    void warningDoesNotMaskAFailure() {
        when(healthRepository.findByDeviceUidIn(any())).thenReturn(List.of(
                record("ESP32-001", "temperature", "FAILED", CHECKED),
                record("ESP32-001", "humidity", "WARNING", CHECKED)));

        Map<String, SensorHealthResponse> reports = detector.getSensorHealthForEach(List.of("ESP32-001"));

        assertThat(reports.get("ESP32-001").getOverallStatus()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("carries each probe's own status and detail through untouched")
    void preservesPerProbeDetail() {
        when(healthRepository.findByDeviceUidIn(any())).thenReturn(List.of(
                record("ESP32-001", "humidity", "WARNING", CHECKED)));

        Map<String, SensorHealthResponse> reports = detector.getSensorHealthForEach(List.of("ESP32-001"));

        SensorHealthResponse.SensorStatusDetail humidity =
                reports.get("ESP32-001").getSensors().get("humidity");
        assertThat(humidity.getStatus()).isEqualTo("WARNING");
        assertThat(humidity.getDetails()).isEqualTo("WARNING on humidity");
        assertThat(humidity.getLastUpdated()).isEqualTo(CHECKED);
    }

    // ------------------------------------------------- batching

    @Test
    @DisplayName("asks the database once for the whole batch")
    void issuesASingleQuery() {
        when(healthRepository.findByDeviceUidIn(any())).thenReturn(List.of());

        detector.getSensorHealthForEach(List.of("A", "B", "C", "D"));

        verify(healthRepository).findByDeviceUidIn(any());
        // The per-device path is what this endpoint exists to replace.
        verify(healthRepository, never()).findByDeviceUid(anyString());
    }

    @Test
    @DisplayName("passes the caller's UID set straight through to the query")
    void queriesForExactlyTheRequestedDevices() {
        when(healthRepository.findByDeviceUidIn(any())).thenReturn(List.of());

        detector.getSensorHealthForEach(List.of("ESP32-001", "ESP32-002"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(healthRepository).findByDeviceUidIn(captor.capture());
        assertThat(captor.getValue()).containsExactly("ESP32-001", "ESP32-002");
    }

    @Test
    @DisplayName("a batch where nothing has ever been evaluated is empty, not a healthy fleet")
    void anUnevaluatedFleetIsEmptyNotHealthy() {
        when(healthRepository.findByDeviceUidIn(any())).thenReturn(List.of());

        Map<String, SensorHealthResponse> reports =
                detector.getSensorHealthForEach(List.of("ESP32-001", "ESP32-002"));

        // Two stations' worth of HEALTHY reports would have been the old
        // behaviour: a fleet-wide all-clear derived from an empty table.
        assertThat(reports).isEmpty();
    }
}
