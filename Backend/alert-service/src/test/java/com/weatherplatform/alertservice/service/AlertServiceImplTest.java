package com.weatherplatform.alertservice.service;

import com.weatherplatform.alertservice.dto.AlertRuleRequest;
import com.weatherplatform.alertservice.dto.AlertRuleResponse;
import com.weatherplatform.alertservice.entity.Alert;
import com.weatherplatform.alertservice.entity.AlertRule;
import com.weatherplatform.alertservice.entity.AlertSeverity;
import com.weatherplatform.alertservice.entity.AlertStatus;
import com.weatherplatform.alertservice.entity.ConditionType;
import com.weatherplatform.alertservice.entity.MetricType;
import com.weatherplatform.alertservice.event.AnomalyDetectedEvent;
import com.weatherplatform.alertservice.event.DeviceOfflineEvent;
import com.weatherplatform.alertservice.event.SensorFailureDetectedEvent;
import com.weatherplatform.alertservice.event.SensorReadingEvent;
import com.weatherplatform.alertservice.mapper.AlertMapper;
import com.weatherplatform.alertservice.repository.AlertRepository;
import com.weatherplatform.alertservice.repository.AlertRuleRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertServiceImplTest {

    @Mock
    private AlertRuleRepository ruleRepository;
    @Mock
    private AlertRepository alertRepository;
    @Mock
    private AlertMapper mapper;

    private MeterRegistry meterRegistry;
    private AlertServiceImpl alertService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        alertService = new AlertServiceImpl(ruleRepository, alertRepository, mapper, meterRegistry);
        ReflectionTestUtils.setField(alertService, "cooldownMinutes", 30L);
        ReflectionTestUtils.setField(alertService, "offlineCooldownMinutes", 60L);

        // JPA's save returns the managed entity; echo the argument back so the
        // service can read the persisted alert the way it does in production.
        lenient().when(alertRepository.save(any(Alert.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Alert captureSavedAlert() {
        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(alertRepository).save(captor.capture());
        return captor.getValue();
    }

    private SensorReadingEvent readingWithTemperature(double temperature) {
        SensorReadingEvent event = new SensorReadingEvent();
        event.setDeviceUid("STATION-001");
        event.setTemperature(BigDecimal.valueOf(temperature));
        return event;
    }

    private AlertRule temperatureRule() {
        return AlertRule.builder()
                .id(42L)
                .deviceUid("STATION-001")
                .metricType(MetricType.TEMPERATURE)
                .conditionType(ConditionType.GREATER_THAN)
                .threshold(BigDecimal.valueOf(35.0))
                .active(true)
                .build();
    }

    // ------------------------------------------------------------------
    // Rules
    // ------------------------------------------------------------------

    @Test
    void createsAlertRule() {
        AlertRuleRequest req = AlertRuleRequest.builder()
                .deviceUid("STATION-001")
                .metricType(MetricType.TEMPERATURE)
                .conditionType(ConditionType.GREATER_THAN)
                .threshold(BigDecimal.valueOf(35.0))
                .active(true)
                .build();

        AlertRule entity = temperatureRule();
        AlertRuleResponse expected = AlertRuleResponse.builder()
                .id(42L)
                .deviceUid("STATION-001")
                .metricType(MetricType.TEMPERATURE)
                .conditionType(ConditionType.GREATER_THAN)
                .threshold(BigDecimal.valueOf(35.0))
                .active(true)
                .build();

        when(mapper.toEntity(req)).thenReturn(entity);
        when(ruleRepository.save(entity)).thenReturn(entity);
        when(mapper.toResponse(entity)).thenReturn(expected);

        AlertRuleResponse created = alertService.createRule(req);

        assertNotNull(created);
        assertEquals(42L, created.getId());
        assertEquals("STATION-001", created.getDeviceUid());
    }

    // ------------------------------------------------------------------
    // Threshold evaluation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A reading over the threshold raises an alert")
    void raisesAlertWhenThresholdBreached() {
        when(ruleRepository.findByDeviceUidAndActiveTrue("STATION-001")).thenReturn(List.of(temperatureRule()));
        when(alertRepository.findFirstByDedupeKeyAndStatusOrderByTriggeredAtDesc(anyString(), any()))
                .thenReturn(Optional.empty());

        alertService.processSensorReading(readingWithTemperature(41.0));

        Alert saved = captureSavedAlert();
        assertThat(saved.getDeviceUid()).isEqualTo("STATION-001");
        assertThat(saved.getMetricType()).isEqualTo(MetricType.TEMPERATURE);
        assertThat(saved.getTriggerValue()).isEqualByComparingTo(BigDecimal.valueOf(41.0));
        assertThat(saved.getStatus()).isEqualTo(AlertStatus.ACTIVE);
        assertThat(saved.getOccurrenceCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("A reading within the threshold raises nothing")
    void doesNotRaiseAlertWhenWithinThreshold() {
        when(ruleRepository.findByDeviceUidAndActiveTrue("STATION-001")).thenReturn(List.of(temperatureRule()));

        alertService.processSensorReading(readingWithTemperature(22.0));

        verify(alertRepository, never()).save(any(Alert.class));
    }

    @Test
    void doesNothingWhenDeviceHasNoRules() {
        when(ruleRepository.findByDeviceUidAndActiveTrue("STATION-001")).thenReturn(List.of());

        alertService.processSensorReading(readingWithTemperature(99.0));

        verify(alertRepository, never()).save(any(Alert.class));
    }

    // ------------------------------------------------------------------
    // Deduplication and cooldown
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A repeat breach inside the cooldown updates the open alert instead of creating another")
    void deduplicatesRepeatBreachWithinCooldown() {
        Alert open = Alert.builder()
                .id(7L)
                .deviceUid("STATION-001")
                .ruleId(42L)
                .metricType(MetricType.TEMPERATURE)
                .message("Existing open alert")
                .triggerValue(BigDecimal.valueOf(38.0))
                .status(AlertStatus.ACTIVE)
                .severity(AlertSeverity.WARNING)
                .dedupeKey("STATION-001|TEMPERATURE|42")
                .occurrenceCount(3)
                .triggeredAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5))
                .lastTriggeredAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1))
                .build();

        when(ruleRepository.findByDeviceUidAndActiveTrue("STATION-001")).thenReturn(List.of(temperatureRule()));
        when(alertRepository.findFirstByDedupeKeyAndStatusOrderByTriggeredAtDesc(
                "STATION-001|TEMPERATURE|42", AlertStatus.ACTIVE)).thenReturn(Optional.of(open));

        alertService.processSensorReading(readingWithTemperature(41.0));

        Alert saved = captureSavedAlert();
        // Same row, updated — not a second alert for the same fault.
        assertThat(saved.getId()).isEqualTo(7L);
        assertThat(saved.getOccurrenceCount()).isEqualTo(4);
        assertThat(saved.getMessage()).isEqualTo("Existing open alert");
        assertThat(meterRegistry.counter("alerts.suppressed", "metric", "TEMPERATURE").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("An unresolved alert re-surfaces once the cooldown expires")
    void reNotifiesAfterCooldownExpires() {
        Alert open = Alert.builder()
                .id(7L)
                .deviceUid("STATION-001")
                .ruleId(42L)
                .metricType(MetricType.TEMPERATURE)
                .message("Stale message from hours ago")
                .triggerValue(BigDecimal.valueOf(38.0))
                .status(AlertStatus.ACTIVE)
                .severity(AlertSeverity.WARNING)
                .dedupeKey("STATION-001|TEMPERATURE|42")
                .occurrenceCount(120)
                .triggeredAt(LocalDateTime.now(ZoneOffset.UTC).minusHours(4))
                .lastTriggeredAt(LocalDateTime.now(ZoneOffset.UTC).minusHours(2))
                .build();

        when(ruleRepository.findByDeviceUidAndActiveTrue("STATION-001")).thenReturn(List.of(temperatureRule()));
        when(alertRepository.findFirstByDedupeKeyAndStatusOrderByTriggeredAtDesc(
                "STATION-001|TEMPERATURE|42", AlertStatus.ACTIVE)).thenReturn(Optional.of(open));

        alertService.processSensorReading(readingWithTemperature(45.0));

        Alert saved = captureSavedAlert();
        assertThat(saved.getOccurrenceCount()).isEqualTo(121);
        // Refreshed so the operator sees the current reading, not the old one.
        assertThat(saved.getMessage()).contains("45.0");
        assertThat(meterRegistry.counter("alerts.renotified", "metric", "TEMPERATURE").count()).isEqualTo(1.0);
    }

    // ------------------------------------------------------------------
    // Other alert sources
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A failed sensor is critical; a degraded one is a warning")
    void mapsSensorFailureSeverity() {
        when(alertRepository.findFirstByDedupeKeyAndStatusOrderByTriggeredAtDesc(anyString(), any()))
                .thenReturn(Optional.empty());

        SensorFailureDetectedEvent event = SensorFailureDetectedEvent.builder()
                .deviceUid("STATION-001")
                .sensorType("temperature")
                .status("FAILED")
                .failureReason("Reading outside instrument range")
                .triggerValue("-999")
                .detectedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();

        alertService.processSensorFailure(event);

        Alert saved = captureSavedAlert();
        assertThat(saved.getSeverity()).isEqualTo(AlertSeverity.CRITICAL);
        assertThat(saved.getSensorType()).isEqualTo("temperature");
        assertThat(saved.getMetricType()).isEqualTo(MetricType.SENSOR_FAILURE);
    }

    @Test
    @DisplayName("Anomaly detail is stored in columns, not parsed back out of the message")
    void storesAnomalyDetailStructurally() {
        when(alertRepository.findFirstByDedupeKeyAndStatusOrderByTriggeredAtDesc(anyString(), any()))
                .thenReturn(Optional.empty());

        AnomalyDetectedEvent event = AnomalyDetectedEvent.builder()
                .eventId("evt-1")
                .deviceUid("STATION-001")
                .sensor("temperature")
                .value(46.5)
                .expectedValue(31.2)
                .score(0.87)
                .severity("CRITICAL")
                .reason("Reading is 15.3 above the forecast band")
                .modelName("IsolationForest + Chronos-2")
                .modelVersion("1.0")
                .detectedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();

        alertService.processAnomaly(event);

        Alert saved = captureSavedAlert();
        assertThat(saved.getMetricType()).isEqualTo(MetricType.ANOMALY);
        assertThat(saved.getSeverity()).isEqualTo(AlertSeverity.CRITICAL);
        assertThat(saved.getSensorType()).isEqualTo("temperature");
        assertThat(saved.getAnomalyScore()).isEqualByComparingTo(BigDecimal.valueOf(0.87));
        assertThat(saved.getExpectedValue()).isEqualByComparingTo(BigDecimal.valueOf(31.2));
        assertThat(saved.getModelVersion()).isEqualTo("1.0");
    }

    @Test
    @DisplayName("A malformed anomaly event does not blow up alert creation")
    void toleratesAnomalyEventWithMissingFields() {
        when(alertRepository.findFirstByDedupeKeyAndStatusOrderByTriggeredAtDesc(anyString(), any()))
                .thenReturn(Optional.empty());

        AnomalyDetectedEvent sparse = AnomalyDetectedEvent.builder()
                .deviceUid("STATION-001")
                .build();

        alertService.processAnomaly(sparse);

        Alert saved = captureSavedAlert();
        assertThat(saved.getSeverity()).isEqualTo(AlertSeverity.WARNING);
        assertThat(saved.getTriggerValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(saved.getSensorType()).isEqualTo("environment");
    }

    @Test
    void offlineDeviceRaisesCriticalAlert() {
        when(alertRepository.findFirstByDedupeKeyAndStatusOrderByTriggeredAtDesc(anyString(), any()))
                .thenReturn(Optional.empty());

        DeviceOfflineEvent event = DeviceOfflineEvent.builder()
                .deviceUid("STATION-001")
                .deviceName("North Ridge")
                .lastSeenAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(10))
                .offlineDetectedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();

        alertService.processDeviceOffline(event);

        Alert saved = captureSavedAlert();
        assertThat(saved.getSeverity()).isEqualTo(AlertSeverity.CRITICAL);
        assertThat(saved.getMetricType()).isEqualTo(MetricType.DEVICE_OFFLINE);
    }
}
