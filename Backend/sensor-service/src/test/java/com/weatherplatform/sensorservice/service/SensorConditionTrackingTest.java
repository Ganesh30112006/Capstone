package com.weatherplatform.sensorservice.service;

import com.weatherplatform.sensorservice.config.SensorThresholdProperties;
import com.weatherplatform.sensorservice.dto.SensorDataRequest;
import com.weatherplatform.sensorservice.entity.SensorData;
import com.weatherplatform.sensorservice.entity.SensorHealth;
import com.weatherplatform.sensorservice.repository.SensorDataRepository;
import com.weatherplatform.sensorservice.repository.SensorHealthRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the detector records about each sensor beyond its status: which check
 * decided it, when it last passed, whether it is frozen -- and that a sensor
 * nothing checked is reported as unknown rather than healthy.
 */
@ExtendWith(MockitoExtension.class)
class SensorConditionTrackingTest {

    @Mock
    private SensorHealthRepository healthRepository;
    @Mock
    private SensorDataRepository dataRepository;
    @Mock
    private RabbitTemplate rabbitTemplate;
    @Mock
    private SensorThresholdProperties props;
    @Mock
    private SensorHealthLedger ledger;

    @InjectMocks
    private SensorFailureDetector detector;

    /** The health rows as the repository would hold them, so a test can read back what was saved. */
    private final Map<String, SensorHealth> stored = new HashMap<>();

    @BeforeEach
    void setUp() {
        lenient().when(props.getTemperature()).thenReturn(new SensorThresholdProperties.Range(-40.0, 75.0, 25.0));
        lenient().when(props.getHumidity()).thenReturn(new SensorThresholdProperties.Range(0.0, 100.0, 45.0));
        lenient().when(props.getAirQuality()).thenReturn(new SensorThresholdProperties.Range(0.0, 500.0, null));
        lenient().when(props.getPressure()).thenReturn(new SensorThresholdProperties.Range(300.0, 1150.0, null));
        lenient().when(props.getOxygen()).thenReturn(new SensorThresholdProperties.Range(5.0, 30.0, null));
        lenient().when(props.getStuckReadingCount()).thenReturn(4);
        lenient().when(props.getStuckWindowSeconds()).thenReturn(240);
        lenient().when(props.getRecoveryHoldSeconds()).thenReturn(60);
        lenient().when(dataRepository.findByDeviceUidOrderByRecordedAtDesc(anyString(), any(Pageable.class)))
                .thenReturn(Page.empty());

        lenient().when(healthRepository.findByDeviceUidAndSensorType(anyString(), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(stored.get(inv.getArgument(1, String.class))));
        lenient().when(healthRepository.save(any(SensorHealth.class))).thenAnswer(inv -> {
            SensorHealth health = inv.getArgument(0);
            stored.put(health.getSensorType(), health);
            return health;
        });
    }

    private static SensorDataRequest reading() {
        SensorDataRequest req = new SensorDataRequest();
        req.setDeviceUid("DEV-1");
        req.setTemperature(BigDecimal.valueOf(25.0));
        req.setHumidity(BigDecimal.valueOf(50.0));
        req.setAirQuality(45);
        req.setPressure(BigDecimal.valueOf(1013.25));
        return req;
    }

    @Test
    void reportsAnOxygenProbeThatSentNothingAsUnknownNotHealthy() {
        Map<String, String> statuses = detector.evaluateAndRecordHealth(reading());

        assertThat(statuses).containsEntry("oxygen", SensorFailureDetector.STATUS_UNKNOWN);
        // And no health record is invented for it.
        assertThat(stored).doesNotContainKey("oxygen");
    }

    @Test
    void reportsRainAsUnknownBecauseNothingChecksIt() {
        assertThat(detector.evaluateAndRecordHealth(reading()))
                .containsEntry("rain", SensorFailureDetector.STATUS_UNKNOWN);
    }

    @Test
    void stillEvaluatesOxygenWhenItIsReported() {
        SensorDataRequest req = reading();
        req.setOxygen(BigDecimal.valueOf(40.0));

        assertThat(detector.evaluateAndRecordHealth(req)).containsEntry("oxygen", "FAILED");
        assertThat(stored.get("oxygen").getCondition()).isEqualTo("OUT_OF_RANGE");
    }

    @Test
    void recordsWhichCheckDecidedEachStatus() {
        SensorDataRequest req = reading();
        req.setTemperature(BigDecimal.valueOf(90.0));
        req.setHumidity(null);

        detector.evaluateAndRecordHealth(req);

        assertThat(stored.get("temperature").getStatus()).isEqualTo("FAILED");
        assertThat(stored.get("temperature").getCondition()).isEqualTo("OUT_OF_RANGE");
        assertThat(stored.get("temperature").getDetails()).contains("90").contains("-40.0 to 75.0");
        assertThat(stored.get("humidity").getCondition()).isEqualTo("MISSING");
        assertThat(stored.get("pressure").getCondition()).isEqualTo("NONE");
    }

    @Test
    void stampsTheLastHealthyReadingOnlyWhenTheSensorPasses() {
        detector.evaluateAndRecordHealth(reading());
        LocalDateTime healthyAt = stored.get("temperature").getLastHealthyAt();
        assertThat(healthyAt).isNotNull();

        SensorDataRequest broken = reading();
        broken.setTemperature(BigDecimal.valueOf(90.0));
        detector.evaluateAndRecordHealth(broken);

        // A failure does not move it: it still says when the sensor last worked.
        assertThat(stored.get("temperature").getLastHealthyAt()).isEqualTo(healthyAt);
    }

    @Test
    void recordsWhenAFlatlineBeganAndClearsItWhenTheValueMoves() {
        when(props.getStuckWindowSeconds()).thenReturn(0);
        for (int i = 0; i < 4; i++) {
            detector.evaluateAndRecordHealth(reading());
        }
        SensorHealth frozen = stored.get("temperature");
        assertThat(frozen.getStatus()).isEqualTo("WARNING");
        assertThat(frozen.getCondition()).isEqualTo("FLATLINE");
        assertThat(frozen.getFlatlineSince()).isNotNull();

        SensorDataRequest moved = reading();
        moved.setTemperature(BigDecimal.valueOf(25.4));
        detector.evaluateAndRecordHealth(moved);

        assertThat(stored.get("temperature").getCondition()).isEqualTo("NONE");
        assertThat(stored.get("temperature").getFlatlineSince()).isNull();
    }

    @Test
    void judgesRateOfChangeOnRawValuesSoACalibrationIsNotAJump() {
        // 2 degrees a minute allowed. The stored reading a minute ago was raw
        // 20.0, corrected to 23.0 by a +3 calibration. The sensor now reports
        // 20.5: half a degree of real change. Against the corrected value it
        // would look like a 2.5 degree drop.
        when(props.getTemperature()).thenReturn(new SensorThresholdProperties.Range(-40.0, 75.0, 2.0));
        SensorData aMinuteAgo = SensorData.builder()
                .rawTemperature(BigDecimal.valueOf(20.0))
                .temperature(BigDecimal.valueOf(23.0))
                .recordedAt(LocalDateTime.now().minusMinutes(1))
                .build();
        when(dataRepository.findByDeviceUidOrderByRecordedAtDesc(eq("DEV-1"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(aMinuteAgo)));

        SensorDataRequest req = reading();
        req.setTemperature(BigDecimal.valueOf(20.5));

        assertThat(detector.evaluateAndRecordHealth(req)).containsEntry("temperature", "HEALTHY");
    }

    @Test
    void stillCatchesARealJumpInTheRawValues() {
        when(props.getTemperature()).thenReturn(new SensorThresholdProperties.Range(-40.0, 75.0, 2.0));
        SensorData aMinuteAgo = SensorData.builder()
                .rawTemperature(BigDecimal.valueOf(20.0))
                .temperature(BigDecimal.valueOf(23.0))
                .recordedAt(LocalDateTime.now().minusMinutes(1))
                .build();
        when(dataRepository.findByDeviceUidOrderByRecordedAtDesc(eq("DEV-1"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(aMinuteAgo)));

        SensorDataRequest req = reading();
        req.setTemperature(BigDecimal.valueOf(23.0)); // raw +3 in a minute

        assertThat(detector.evaluateAndRecordHealth(req)).containsEntry("temperature", "WARNING");
        assertThat(stored.get("temperature").getCondition()).isEqualTo("RAPID_CHANGE");
    }

    @Test
    void countsEveryReadingsOutcomesInTheLedger() {
        detector.evaluateAndRecordHealth(reading());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> statuses = ArgumentCaptor.forClass(Map.class);
        verify(ledger, atLeastOnce()).record(eq("DEV-1"), statuses.capture(), any(LocalDateTime.class));
        assertThat(statuses.getValue())
                .containsEntry("temperature", "HEALTHY")
                .containsEntry("oxygen", SensorFailureDetector.STATUS_UNKNOWN);
    }
}
